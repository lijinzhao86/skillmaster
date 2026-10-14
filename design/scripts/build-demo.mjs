// Compose a demo's deliverable: splice in the shared fragments it asks for, then fold its local
// images into the HTML as data URIs.
//
//   node design/scripts/build-demo.mjs design/demos/dir3-hara.html "design/skillmaster-首页演示.html"
//
// Two things make a delivered demo a *single file*: the fragments it shares with the other demo are
// already text in it, and its images are already bytes in it. What it opens by double-clicking has
// nothing beside it — move it, mail it, drop it on a designer's desktop, and it still works.
//
// A source asks for a fragment with a marker on a line of its own:
//
//   <!-- @partial auth-overlay -->
//
// which is replaced by the whole of `design/partials/auth-overlay.html`. The substitution is
// verbatim — the fragment's own indentation is kept, so what is written in one file is what is read
// in the other.
//
// One pass, one level: a fragment is not itself searched for markers. That is also why a fragment
// must not *contain* a line that looks like a marker — it would be copied through as text, and if
// it sat inside an HTML comment it would close that comment early. Which is not hypothetical: the
// overlay's own header did exactly that, and the page it was spliced into grew a hundred pixels of
// prose nobody had written.
//
// The order matters: fragments first, then images, because a fragment may carry images of its own.

import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

const [src, out] = process.argv.slice(2)
if (!src || !out) {
  console.error('usage: build-demo.mjs <source.html> <deliverable.html>')
  process.exit(1)
}

const from = resolve(src)
const base = dirname(from)
const partialsDir = resolve(base, '..', 'partials')

const TYPES = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
}

const MARKER = /^[ \t]*<!--\s*@partial\s+([a-z0-9][a-z0-9-]*)\s*-->[ \t]*$/gm

const used = []

function splice(html) {
  return html.replace(MARKER, (_whole, name) => {
    const file = resolve(partialsDir, name + '.html')
    if (!existsSync(file)) {
      // A missing fragment is a broken build, not a demo that is missing a section: the delivered
      // file would open with a hole in it and nothing would say so.
      console.error(`build-demo: no such partial: ${file}`)
      process.exit(1)
    }
    used.push(name)
    return readFileSync(file, 'utf8').replace(/\n+$/, '');
  })
}

let inlined = 0

function inline(html) {
  return html.replace(/(src|href)="([^"]+)"/g, (whole, attribute, url) => {
    if (/^(https?:|data:|mailto:|#|\/|\?)/.test(url)) return whole
    const type = TYPES[url.slice(url.lastIndexOf('.'))]
    if (!type) return whole
    inlined += 1
    return `${attribute}="data:${type};base64,${readFileSync(resolve(base, url)).toString('base64')}"`
  })
}

writeFileSync(out, inline(splice(readFileSync(from, 'utf8'))))
console.log(JSON.stringify({ from, out, partials: used, inlined }, null, 2))
