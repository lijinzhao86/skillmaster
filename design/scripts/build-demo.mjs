// Compose a demo's deliverable: bundle its React source, then fold its local images into the HTML
// as data URIs.
//
//   node design/scripts/build-demo.mjs design/demos/dir3-hara.html "design/skillmaster-首页演示.html"
//
// Two things make a delivered demo a *single file*: its React source is already one script in it,
// and its images are already bytes in it. What it opens by double-clicking has nothing beside it —
// move it, mail it, drop it on a designer's desktop, and it still works. The sign-in overlay is
// part of that React source (`src/shared/auth/`), which is how both demos carry the same one.
//
// A source asks for its React build with two markers:
//
//   <!-- @styles console -->    → <style>…</style>   (in <head>)
//   <!-- @bundle console -->    → <script>…</script> (at the end of <body>)
//
// The name is a directory under `design/src/`, and its entry is `main.tsx`. esbuild bundles it with
// React inlined, so the delivered file keeps working from `file://` with no module loader, no CDN
// and no network. The CSS it imports is bundled with it and put where the marker is.
//
// Order: images first, on the template alone, then the bundle. The inliner rewrites `src=`/`href=`
// attributes, and a minified script can contain such a string; running it before anything is spliced
// keeps it looking only at markup that a person wrote.
//
// **A marker nobody consumed is a broken build.** The marker must stand alone on its line, so
// `<!-- @bundle home --> <!-- later -->` matches nothing; before 2026-10-22 that built a blank page
// and reported success. The check at the end turns it into a failure before the output is written.

import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { build } from 'esbuild'

const [src, out] = process.argv.slice(2)
if (!src || !out) {
  console.error('usage: build-demo.mjs <source.html> <deliverable.html>')
  process.exit(1)
}

const from = resolve(src)
const base = dirname(from)
const root = resolve(base, '..')

const TYPES = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
}

const STYLES = /^[ \t]*<!--\s*@styles\s+([a-z0-9][a-z0-9-]*)\s*-->[ \t]*$/gm
const BUNDLE = /^[ \t]*<!--\s*@bundle\s+([a-z0-9][a-z0-9-]*)\s*-->[ \t]*$/gm

const bundles = []

/**
 * One React build, shared by its two markers.
 *
 * The output is not written to disk: it is on its way into the HTML. `minify` is on because this
 * file is a deliverable rather than a place anybody edits; the source is.
 */
async function bundle(name) {
  const found = bundles.find((entry) => entry.name === name)
  if (found) return found
  const entry = resolve(root, 'src', name, 'main.tsx')
  if (!existsSync(entry)) {
    console.error(`build-demo: no such demo source: ${entry}`)
    process.exit(1)
  }
  const result = await build({
    entryPoints: [entry],
    bundle: true,
    write: false,
    format: 'iife',
    target: ['chrome120'],
    jsx: 'automatic',
    minify: true,
    legalComments: 'none',
    /* An imported image becomes a `data:` URI inside the bundle, which is what keeps the delivered
       demo one file: a path in a JSX `src` would be a path beside the file, and there is nothing
       beside the file. */
    loader: {
      '.png': 'dataurl',
      '.jpg': 'dataurl',
      '.jpeg': 'dataurl',
      '.svg': 'dataurl',
      '.webp': 'dataurl',
      '.gif': 'dataurl',
    },
    define: { 'process.env.NODE_ENV': '"production"' },
    outdir: resolve(root, '.build', name),
    logLevel: 'silent',
  })
  const js = result.outputFiles.find((file) => file.path.endsWith('.js'))
  const css = result.outputFiles.find((file) => file.path.endsWith('.css'))
  const entryOut = { name, js: js ? js.text : '', css: css ? css.text : '' }
  bundles.push(entryOut)
  return entryOut
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

/* Line endings are normalised on the way in: the markers are matched at the end of a line, and a
   CRLF checkout would leave a `\r` between the marker and the `$` — every legitimate marker would
   read as "not alone on its line". */
let html = inline(readFileSync(from, 'utf8').replace(/\r\n?/g, '\n'))

/* **A marker the build will not consume is a broken build, not a page missing a section.** Both
   markers must stand alone on their line; `<!-- @bundle home --> <!-- later -->` matches nothing,
   and before 2026-10-22 that shipped a blank deliverable with a zero exit code. Caught here, before
   anything is spliced, so the output is never written. */
const MENTIONS = /@(?:styles|bundle)\s+/
const ALONE = /^[ \t]*<!--\s*@(?:styles|bundle)\s+[a-z0-9][a-z0-9-]*\s*-->[ \t]*$/
html.split('\n').forEach((line, index) => {
  if (!MENTIONS.test(line) || ALONE.test(line)) return
  console.error(`build-demo: ${from}:${index + 1}: a marker must stand alone on its line: ${line.trim()}`)
  process.exit(1)
})

html = await spliceStyles(html)
html = await spliceBundle(html)
writeFileSync(out, html)

async function spliceStyles(text) {
  const names = [...text.matchAll(STYLES)].map((match) => match[1])
  for (const name of names) {
    const built = await bundle(name)
    text = text.replace(new RegExp(`^[ \\t]*<!--\\s*@styles\\s+${name}\\s*-->[ \\t]*$`, 'm'),
      () => `<style>\n${built.css}\n</style>`)
  }
  return text
}

async function spliceBundle(text) {
  const names = [...text.matchAll(BUNDLE)].map((match) => match[1])
  for (const name of names) {
    const built = await bundle(name)
    text = text.replace(new RegExp(`^[ \\t]*<!--\\s*@bundle\\s+${name}\\s*-->[ \\t]*$`, 'm'),
      () => `<script>\n${built.js}\n</script>`)
  }
  return text
}

console.log(JSON.stringify({
  from,
  out,
  bundles: bundles.map((entry) => ({ name: entry.name, js: entry.js.length, css: entry.css.length })),
  inlined,
}, null, 2))
