// Fold a draft's local images into the HTML as data URIs.
//
//   node design/scripts/inline-images.mjs design/demos/dir3-hara.html "design/skillmaster-首页演示.html"
//
// A file that opens by double-clicking cannot depend on a sibling folder: move it, mail it, or drop
// it on a designer's desktop and every relative path breaks at once. The drafts keep their paths
// while they are being compared; the delivered file carries its images inside it.

import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

const [src, out] = process.argv.slice(2)
if (!src || !out) {
  console.error('usage: inline-images.mjs <draft.html> <standalone.html>')
  process.exit(1)
}

const from = resolve(src)
const base = dirname(from)

const TYPES = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
}

let inlined = 0
const html = readFileSync(from, 'utf8').replace(
  /(src|href)="([^"]+)"/g,
  (whole, attribute, url) => {
    if (/^(https?:|data:|mailto:|#|\/)/.test(url)) return whole
    const extension = url.slice(url.lastIndexOf('.'))
    const type = TYPES[extension]
    if (!type) return whole
    const bytes = readFileSync(resolve(base, url))
    inlined += 1
    return `${attribute}="data:${type};base64,${bytes.toString('base64')}"`
  },
)

writeFileSync(out, html)
console.log(JSON.stringify({ from, out, inlined }, null, 2))
