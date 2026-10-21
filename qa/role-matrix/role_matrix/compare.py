"""The comparison bench: one address, two implementations, three lists.

During v12 the browser client is rewritten in React while everything it looks like must stay put.
The assertions in `tests/test_ui_matrix.py` already prove the interaction did not move — they read
the page by class name and button text and do not care which framework drew it. What they cannot
prove is that the page still *looks* the same, because the stylesheet is one global file keyed on
class names, tags and attribute state.

So this module reduces a rendered page to the three things that decide its appearance:

1. **The skeleton** — tag, classes, `id`, `name`, `role`, `aria-*`, and the state attributes the
   stylesheet switches on (`hidden`, `disabled`, `checked`, `selected`, `value`, `type`), nested.
2. **The text** — every text node, whitespace-normalised.
3. **The attribute copy** — `placeholder`, `title`, `alt`, `aria-label`: words a person reads that
   are not text nodes.

Diff all three and you have compared what a person sees, without a screenshot. It is not a
replacement for looking (`design/` review is still by eye); it is what makes "nothing changed"
checkable on every page instead of on the two the console suite happens to drive.
"""

from __future__ import annotations

import difflib
from dataclasses import dataclass

# Classes are sorted and nodes are indented by depth, so the output is a stable, line-diffable
# rendering of the tree rather than a serialisation that would differ on class order alone.
CAPTURE = """
() => {
  const STATE = ['id', 'name', 'role', 'hidden', 'disabled', 'checked', 'selected', 'type']
  const COPY = ['placeholder', 'title', 'alt', 'aria-label']
  const SKIP = new Set(['SCRIPT', 'STYLE', 'LINK', 'META', 'NOSCRIPT'])
  const lines = []
  const text = []
  const attributes = []

  const walk = (node, depth) => {
    if (node.nodeType !== 1) return
    if (SKIP.has(node.tagName)) return

    // **An element's own text, joined before it is compared.** One phrase is one text node in a Vue
    // template and two in JSX (`所选版本重新上线，{title} 变成历史版本` versus
    // `所选版本重新上线，{`${title}`}…`), and the boundary between them is invisible: a browser lays
    // adjacent text nodes out as one run. Elements still separate their own runs, so this cannot
    // merge two spans into one sentence. A `<textarea>` is skipped: its text is its `value`, which
    // Vue sets as a property and React also seeds as a child node on mount.
    if (node.tagName !== 'TEXTAREA') {
      const own = Array.from(node.childNodes)
        .filter((child) => child.nodeType === 3)
        .map((child) => child.textContent || '')
        .join('')
        .replace(/\\s+/g, ' ')
        .trim()
      if (own !== '') text.push('  '.repeat(depth) + own)
    }

    let line = '  '.repeat(depth) + node.tagName.toLowerCase()
    const classes = Array.from(node.classList).sort()
    if (classes.length > 0) line += '.' + classes.join('.')
    for (const name of STATE) {
      if (node.hasAttribute(name)) line += '[' + name + '=' + node.getAttribute(name) + ']'
    }
    // `v-show` is an inline `display: none`, and it is the one hiding mechanism the attribute list
    // above cannot see. Only `display` is compared — not the rest of the style, which is what the
    // stylesheet is for.
    if (node.style && node.style.display !== '') {
      line += '[display=' + node.style.display + ']'
    }
    for (const attribute of Array.from(node.attributes)) {
      if (attribute.name.startsWith('aria-')) {
        line += '[' + attribute.name + '=' + attribute.value + ']'
      }
    }
    lines.push(line)

    for (const name of COPY) {
      if (node.hasAttribute(name)) {
        attributes.push('  '.repeat(depth) + name + '=' + node.getAttribute(name))
      }
    }

    for (const child of Array.from(node.childNodes)) walk(child, depth + 1)
  }

  walk(document.body, 0)
  return { skeleton: lines, text, attributes }
}
"""


@dataclass(frozen=True)
class Snapshot:
    """One rendered page, reduced to what decides how it looks."""

    skeleton: tuple[str, ...]
    text: tuple[str, ...]
    attributes: tuple[str, ...]

    def lists(self) -> dict[str, tuple[str, ...]]:
        return {"skeleton": self.skeleton, "text": self.text, "attributes": self.attributes}


def capture(page) -> Snapshot:
    """Read the page the browser is already on. No navigation: the caller decides the address."""
    seen = page.evaluate(CAPTURE)
    return Snapshot(
        skeleton=tuple(seen["skeleton"]),
        text=tuple(seen["text"]),
        attributes=tuple(seen["attributes"]),
    )


def compare(before: Snapshot, after: Snapshot) -> dict[str, list[str]]:
    """The three diffs, keyed by list. Empty lists mean the page did not change."""
    differences: dict[str, list[str]] = {}
    left, right = before.lists(), after.lists()
    for key in left:
        diff = list(
            difflib.unified_diff(
                left[key],
                right[key],
                fromfile=f"{key} · before",
                tofile=f"{key} · after",
                lineterm="",
            )
        )
        if diff:
            differences[key] = diff
    return differences
