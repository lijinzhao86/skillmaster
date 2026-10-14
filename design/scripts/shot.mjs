// Screenshot a design draft at 1440 wide, full page, and report any console/page errors.
//
//   node design/scripts/shot.mjs design/demos/foo.html design/shots/foo.png
//
// Drives the Chrome already installed on this machine through the globally installed
// puppeteer-core, so nothing needs to be downloaded. The page height is measured rather than
// guessed — a fixed window size would either clip the page or leave a band of empty pixels.

import { execSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

const [src, out] = process.argv.slice(2)
if (!src || !out) {
  console.error('usage: shot.mjs <input.html> <output.png>')
  process.exit(1)
}

const globalRoot = execSync('npm root -g', { encoding: 'utf8' }).trim()
const require = createRequire(globalRoot + '/')
const puppeteer = require('puppeteer-core')

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

// Headful on purpose. Headless Chrome has no GPU compositor, so it silently skips
// `backdrop-filter` — the login scrim is a blur, and in headless the screenshots came back with the
// page behind it sharp. Screenshots have to be taken with the same compositor the user has.
const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: false,
  args: [
    '--no-sandbox',
    '--hide-scrollbars',
    '--force-color-profile=srgb',
    '--window-size=1440,1000',
    '--window-position=0,0',
  ],
  defaultViewport: { width: 1440, height: 900, deviceScaleFactor: 1 },
})

const page = await browser.newPage()
// Light is the theme these drafts are designed in; without this a dark-mode machine would shoot
// the other one and the comparison would be between two different things.
await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'light' }])
await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 })

const problems = []
page.on('pageerror', (error) => problems.push('pageerror: ' + error.message))
page.on('console', (message) => {
  if (message.type() === 'error') problems.push('console: ' + message.text())
})
page.on('requestfailed', (request) => problems.push('missing: ' + request.url()))

await page.goto('file://' + resolve(src), { waitUntil: 'networkidle0', timeout: 20000 })
await new Promise((done) => setTimeout(done, 400))

const height = await page.evaluate(() => document.documentElement.scrollHeight)
mkdirSync(dirname(resolve(out)), { recursive: true })
await page.screenshot({ path: out, fullPage: true })
await browser.close()

console.log(JSON.stringify({ src, out, height, problems }, null, 2))
