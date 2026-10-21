import MarkdownIt from 'markdown-it'

/**
 * A skill's `SKILL.md`, rendered.
 *
 * **`html: false` is a security decision, not a formatting one.** With raw HTML off, markdown-it
 * escapes it, so a `SKILL.md` containing `<script>alert(1)</script>` renders as the text of that
 * script rather than as a script. Its own `validateLink` refuses the dangerous URL schemes for the
 * same reason. Both are the library's defaults, both are pinned by tests in
 * `tests/markdown.test.ts`, and **neither is decoration**: the page puts the result into the DOM
 * with `v-html`, which is only safe because of them.
 *
 * **What makes that acceptable today is who reads it.** A skill is private, so the person looking at
 * this document is the person who wrote it, and the only content a person can attack with it is
 * their own browser. **That stops being true when discovery lands** — the day a skill can be read by
 * somebody other than its author, this file needs a sanitiser on its output (DOMPurify or
 * equivalent), not a smaller change here.
 *
 * So: **if you are here to turn `html: true` on** — for `<details>`, for an attribute on a table,
 * for anything — that is the same commit as the sanitiser. It is written down because that is the
 * moment nobody will remember it, and the failure is silent in exactly the wrong direction.
 *
 * The options that are on are on for the reader's sake: `linkify` turns a bare URL into a link,
 * which a skill's references are full of. `breaks` stays off, so a single newline is not a line
 * break — that is CommonMark and what a GitHub README does, and a document read here should look
 * like the same document read there.
 */
const renderer = new MarkdownIt({ html: false, linkify: true, breaks: false })

/**
 * A leading YAML frontmatter block, which is not Markdown and must not be drawn as any.
 *
 * A `SKILL.md` opens with `---` / keys / `---`, and the server serves those bytes unaltered — hosting
 * keeps the document faithful (TD §4.2). Read as CommonMark, those three lines are a thematic break,
 * then a paragraph, and then — because a paragraph followed by `---` is a setext heading — an `<h2>`
 * made of the raw YAML. A faithful renderer therefore draws a rule, the metadata in heading type,
 * another rule, and only then the document. That is not a rendering bug to work around; it is what
 * those bytes mean, and the reason the block is removed before rendering rather than styled after.
 *
 * **Nothing that matters is lost by dropping it.** `name` and `description` — the two a skill is
 * addressed and listed by — are on the page above the document, read from the version's own
 * frontmatter rather than from its text. A key that appears nowhere else is therefore shown nowhere:
 * the browser has no raw view of a `SKILL.md`, only this rendered one. That is the gap this rule
 * opens, and it is worth knowing before adding a key to a skill that a reader is meant to see.
 *
 * The lookahead is not decoration. Without it the pattern is "`---`, anything, `---`", which is also
 * what a document that simply *opens* with a horizontal rule looks like — and that document would
 * lose everything above its second rule. Requiring the first line after the opening fence to hold a
 * `key:` is how frontmatter is told from prose; the cost is that a block whose first line is blank
 * is not recognised, which is the error that fails safe.
 */
const FRONTMATTER = /^---\r?\n(?=[^\s:][^\r\n]*:)[\s\S]*?\r?\n---[ \t]*(?:\r?\n|$)/

export function renderMarkdown(source: string): string {
  return renderer.render(source.replace(FRONTMATTER, ''))
}
