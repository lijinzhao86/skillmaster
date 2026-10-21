// Compare two builds of the console demo, state by state, on what a person actually sees.
//
//   node design/scripts/dom-compare.mjs <before.html> <after.html> [--only <prefix>[,<...>]] [--list]
//
// The demo is being reimplemented in React. "Nothing changed" is the whole acceptance, and a
// screenshot is not a diff: it hides a moved word behind anti-aliasing and hides a missing attribute
// completely. So this reduces each rendered state to the three things that decide what is on screen —
// the element skeleton (tag, classes, ids, state attributes, aria), the text, and the copy carried by
// attributes — and diffs them.
//
// **It walks the visible page, not the document.** An element that is `hidden` contributes its own
// line and nothing under it: a demo keeps every page in the document and shows one, so the five that
// are not up are not evidence either way. That is also why this can compare two implementations that
// disagree about how many pages they mount.
//
// The sign-in overlay is skipped: it is the same shared component (`src/shared/auth/`) in both
// demos, and `console-states.mjs` / `interaction-check.mjs` drive it.

import { execSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { SHOTS } from './console-shots.mjs'

const rawArgs = process.argv.slice(2)
const list = rawArgs.includes('--list')
/** Compare the frame and the strip only — the parts that are not a page. Useful while a page is
 *  still being ported: it proves the shell before there is anything to put in it. */
const shellOnly = rawArgs.includes('--shell')
/** The marketing page has one address and no states of its own; the overlay's states are reached by
 *  clicking and are covered by `interaction-check.mjs`. This compares the page at rest. */
const homeMode = rawArgs.includes('--home')
const onlyAt = rawArgs.indexOf('--only')
const only = onlyAt < 0 ? [] : (rawArgs[onlyAt + 1] || '').split(',').filter(Boolean)
/* An `--only` with nothing after it would otherwise mean "no filter", which is the one reading
   nobody asking for it meant. */
if (onlyAt >= 0 && only.length === 0) {
  console.error('dom-compare: --only wants a state prefix, e.g. --only editor')
  process.exit(1)
}

const states = homeMode
  ? [{ name: 'home', query: '' }]
  : SHOTS.filter((shot) => only.length === 0 || only.some((p) => shot.name.startsWith(p)))

/* `--list` answers without needing two files; it is read before the positionals are checked. */
if (list) {
  states.forEach((shot) => console.log(shot.name))
  process.exit(0)
}

/* **Matching nothing is not the same as finding nothing wrong.** A prefix that names no state —
   `auth`, say: the three login screens are shot by `console-states.mjs` but are not in `SHOTS`, and
   the console's own states are prefixed `skills-`, `skill-`, `version-`, … — would compare zero
   pages and report zero differences, which reads exactly like a pass. */
if (states.length === 0) {
  console.error(
    `dom-compare: --only ${only.join(',')} matched no state. --list shows the names.`,
  )
  process.exit(1)
}

const [before, after] = rawArgs.filter(
  (arg, i) => !arg.startsWith('--') && !(onlyAt >= 0 && i === onlyAt + 1),
)
if (!before || !after) {
  console.error('usage: dom-compare.mjs <before.html> <after.html> [--only <prefix>[,<...>]] [--list]')
  process.exit(1)
}

const require = createRequire(execSync('npm root -g', { encoding: 'utf8' }).trim() + '/')
const puppeteer = require('puppeteer-core')

const CAPTURE = (shellOnly) => {
  const STATE = ['id', 'name', 'role', 'hidden', 'disabled', 'type']
  const COPY = ['placeholder', 'title', 'alt', 'aria-label']
  // A `<template>` is an instruction, not something on screen: the old demo cloned its content into
  // the list, and the React version renders the same rows directly. Neither tree draws it.
  const SKIP = new Set(['SCRIPT', 'STYLE', 'LINK', 'META', 'NOSCRIPT', 'TEMPLATE'])
  const lines = []
  const text = []
  const attributes = []
  const seen = []

  /* An inlined image is a data URI megabytes long and an icon is a path in the markup: both decide
     what a person sees, and neither is text, so the skeleton records a short digest of each. */
  const digest = (value) => {
    let hash = 2166136261
    for (let i = 0; i < value.length; i += 1) {
      hash ^= value.charCodeAt(i)
      hash = Math.imul(hash, 16777619)
    }
    return (hash >>> 0).toString(16)
  }

  /* What a control currently holds. A value is a *property*: the old demo wrote `value` and the
     React version renders it, and only the property says what is in the box either way. Checkboxes
     and radios are state, already recorded above; `date` is included because the key form pre-fills
     one, and it is as visible as a text box. */
  const VALUELESS = new Set(['checkbox', 'radio', 'file', 'hidden', 'submit', 'reset', 'button', 'image'])
  const holdsText = (node) =>
    node.tagName === 'TEXTAREA' ||
    node.tagName === 'SELECT' ||
    (node.tagName === 'INPUT' && !VALUELESS.has((node.getAttribute('type') || 'text').toLowerCase()))

  const walk = (node, depth) => {
    if (node.nodeType !== 1) return
    if (SKIP.has(node.tagName)) return
    // The sign-in sheet is a shared component, identical in both builds; its own suite drives it.
    if (node.id === 'overlay') return

    let line = '  '.repeat(depth) + node.tagName.toLowerCase()
    const classes = Array.from(node.classList).sort()
    if (classes.length > 0) line += '.' + classes.join('.')
    for (const name of STATE) {
      if (node.hasAttribute(name)) line += '[' + name + '=' + node.getAttribute(name) + ']'
    }
    // `checked` and `selected` are state a person sees, and they live on the *property*: assigning
    // `radio.checked = true` does not write the attribute at all, while React writes it on mount.
    // Compare what the control is, not how it got there.
    if (node.checked === true) line += '[checked]'
    if ('selected' in node && node.selected === true) line += '[selected]'
    if (node.style && node.style.display !== '') line += '[display=' + node.style.display + ']'
    for (const attribute of Array.from(node.attributes)) {
      if (attribute.name.startsWith('aria-')) line += '[' + attribute.name + '=' + attribute.value + ']'
    }
    /* An icon is drawn by its path, and a changed path is a different picture even though nothing
       about the element's tag or class changes. */
    for (const name of ['d', 'points', 'viewBox']) {
      if (node.hasAttribute(name)) line += '[' + name + '=' + node.getAttribute(name) + ']'
    }
    if (node.tagName === 'IMG' && node.currentSrc) line += '[src=' + digest(node.currentSrc) + ']'
    if (node.tagName === 'USE' && node.getAttribute('href')) {
      line += '[href=' + node.getAttribute('href') + ']'
    }
    if (holdsText(node) && node.value !== '') {
      // Same rule as the credential text below: minted per load, so only its shape is comparable.
      line += '[value=' + (node.id === 'keySecret' ? 'skm_<secret>' : node.value) + ']'
    }
    lines.push(line)

    // **An element that is hidden contributes its own line and nothing else** — not its text, not
    // its placeholder, not its subtree. The two builds need not agree about what they keep in a
    // hidden node (the old demo left the value empty; React renders it), and what a person sees is
    // the same either way. The element itself still is evidence: a page that should be hidden and is
    // not is exactly what this comparison is for.
    if (node.hasAttribute('hidden')) return

    if (node.tagName !== 'TEXTAREA') {
      const own = Array.from(node.childNodes)
        .filter((child) => child.nodeType === 3)
        .map((child) => child.textContent || '')
        .join('')
        .replace(/\s+/g, ' ')
        .trim()
      // The credential string is minted at random on every load, on both sides — the demo may invent
      // a credential, it may not invent the same one twice. Its *shape* is what this can compare.
      if (own !== '' && node.id === 'keySecret') {
        text.push('  '.repeat(depth) + 'skm_<secret>')
      } else if (own !== '') {
        text.push('  '.repeat(depth) + own)
      }
    }

    for (const name of COPY) {
      if (node.hasAttribute(name)) {
        attributes.push('  '.repeat(depth) + name + '=' + node.getAttribute(name))
      }
    }

    for (const child of Array.from(node.childNodes)) walk(child, depth + 1)
  }

  if (shellOnly) {
    const frame = []
    for (const selector of ['.topbar', '.sidenav', '.demo-bar']) {
      const node = document.querySelector(selector)
      if (node) frame.push(node)
    }
    frame.forEach((root) => walk(root, 0))
    return { skeleton: lines, text, attributes }
  }

  const roots = []
  for (const child of Array.from(document.body.children)) {
    if (child.tagName === 'SCRIPT' || child.tagName === 'STYLE') continue
    // The sign-in overlay is a shared fragment, identical in both builds.
    if (child.id === 'overlay') continue
    // React's mount point is a wrapper, not part of the page: compare what is inside it.
    if (child.id === 'app' && child.tagName === 'DIV') {
      for (const inner of Array.from(child.children)) roots.push(inner)
      continue
    }
    roots.push(child)
  }
  roots.forEach((root) => walk(root, 0))
  return { skeleton: lines, text, attributes }
}

const browser = await puppeteer.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: true,
  args: ['--no-sandbox', '--force-color-profile=srgb', '--window-size=1440,1000'],
  defaultViewport: { width: 1440, height: 900, deviceScaleFactor: 1 },
})
const page = await browser.newPage()

/* **A dirty editor asks before it leaves.** The browser's own prompt is not something Puppeteer
   dismisses on its own, so without this the next state's navigation waits out the timeout. Accepting
   is the answer a person gives when they are only here to look. */
page.on('dialog', (dialog) => { void dialog.accept().catch(() => {}) })

/* **The state carries its own width.** Two states are photographed at 400px (`…-narrow`), and what
   a person sees there is a different layout, not the same one smaller. Rendering both builds at
   1440 and calling them equal would compare the wrong picture. */
const capture = async (file, shot) => {
  await page.setViewport({
    width: shot.width || 1440,
    height: shot.height || 900,
    deviceScaleFactor: 1,
  })
  await page.goto('file://' + resolve(file) + shot.query, { waitUntil: 'load' })
  await new Promise((done) => setTimeout(done, 200))
  return page.evaluate(CAPTURE, shellOnly)
}

const diffLines = (a, b) => {
  const out = []
  const n = Math.max(a.length, b.length)
  for (let i = 0; i < n; i += 1) {
    if (a[i] !== b[i]) out.push(`  ${i + 1}: ${JSON.stringify(a[i])} → ${JSON.stringify(b[i])}`)
  }
  return out
}

let failed = 0
const report = []
for (const shot of states) {
  const left = await capture(before, shot)
  const right = await capture(after, shot)
  const differences = {}
  for (const key of ['skeleton', 'text', 'attributes']) {
    const d = diffLines(left[key], right[key])
    if (d.length > 0) differences[key] = d
  }
  const ok = Object.keys(differences).length === 0
  if (!ok) failed += 1
  report.push({ name: shot.name, query: shot.query, ok, differences })
  process.stdout.write(`${ok ? '一致' : '不同'} · ${shot.name}\n`)
}

await browser.close()

console.log(
  JSON.stringify(
    {
      before: resolve(before),
      after: resolve(after),
      states: states.length,
      failed,
      report,
      sizes: {
        before: readFileSync(resolve(before)).length,
        after: readFileSync(resolve(after)).length,
      },
    },
    null,
    1,
  ),
)
process.exit(failed === 0 ? 0 : 1)
