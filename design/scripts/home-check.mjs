// Drive the marketing homepage through the interactions v15 keeps.
//
//   node design/scripts/home-check.mjs "design/skillmaster-首页演示.html" design/shots/home
//
// **This page holds no session and shows no dialog.** It is www; signing in happens on console, and
// the page only asks console once who the visitor is (ADR 0086). What is worth clicking is: the two
// outbound links in the signed-out navigation, the signed-in navigation's account menu, the three
// product links in the footer, the placeholder links that must not navigate, the prompt's copy
// button, the scroll hint, the two-slide deck (hero + cards → footer), the static navigation,
// and the footer's reveal. The sign-in flows themselves moved to the console demo and are driven
// by `interaction-check.mjs`.

import { execSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

const rawArgs = process.argv.slice(2)
const [src, outDir] = rawArgs
if (!src || !outDir) {
  console.error('usage: home-check.mjs "<built homepage>.html" <out-dir>')
  process.exit(1)
}
mkdirSync(resolve(outDir), { recursive: true })

const require = createRequire(execSync('npm root -g', { encoding: 'utf8' }).trim() + '/')
const puppeteer = require('puppeteer-core')

const browser = await puppeteer.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: true,
  args: ['--no-sandbox', '--force-color-profile=srgb', '--window-size=1440,1000'],
  defaultViewport: { width: 1440, height: 900, deviceScaleFactor: 1 },
})

const page = await browser.newPage()
await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'light' }])

const problems = []
page.on('pageerror', (error) => problems.push('pageerror: ' + error.message))
page.on('console', (message) => {
  if (message.type() === 'error') problems.push('console: ' + message.text())
})

/* **A homepage that talks to an API is not this version.** The count is the assertion; it is
   installed before any of the page's own script runs. */
await page.evaluateOnNewDocument(() => {
  window.__fetches = []
  const real = window.fetch
  window.fetch = function (...args) {
    window.__fetches.push(String(args[0]))
    return real.apply(window, args)
  }
})

const checks = []
const check = (name, pass, detail = '') => checks.push({ name, pass, detail })
const visible = (selector) =>
  page.$eval(selector, (el) => {
    const rect = el.getBoundingClientRect()
    const style = getComputedStyle(el)
    return (
      !el.hasAttribute('hidden') &&
      style.display !== 'none' &&
      style.visibility !== 'hidden' &&
      rect.width > 0 &&
      rect.height > 0
    )
  })
const shotViewport = (name) => page.screenshot({ path: resolve(outDir, name) })
const settle = () =>
  page.evaluate(async () => {
    if (document.fonts && document.fonts.ready) await document.fonts.ready
    await new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done)))
  })
/* The deck settles on a whole screen; wait for the transform, not just the class. Slide 0 is the
   hero (with the cards), 1 the footer — the one we asked for must sit exactly at the top. */
const waitForSlide = (index) =>
  page
    .waitForFunction(
      (target) => {
        const node = document.querySelector(target === 0 ? '.hero' : '#foot')
        if (!node) return false
        return Math.abs(node.getBoundingClientRect().top) <= 1
      },
      { timeout: 2500 },
      index,
    )
    .then(() => true)
    .catch(() => false)
/* The deck refuses a second gesture until its transition is over. */
const unlock = () => new Promise((done) => setTimeout(done, 140))
/* A keyboard gesture makes the browser draw `:focus-visible` on whatever was last clicked. */
const blurActive = () =>
  page.evaluate(() => {
    const active = document.activeElement
    if (active instanceof HTMLElement) active.blur()
  })
const hrefOf = (selector) => page.$eval(selector, (el) => el.getAttribute('href'))

await page.goto('file://' + resolve(src), { waitUntil: 'load', timeout: 20000 })
await settle()

/* **The strip along the bottom is the demo's, not the design's**, so every measurement of “a whole
   screen” is measured against what is left above it. */
const bar = await page.$eval('#demoBar', (el) => el.getBoundingClientRect().height)
check('演示条在，两个态可选', (await page.$$eval('#demoBar .demo-opts a', (nodes) => nodes.map((n) => n.textContent?.trim()).join('/'))) === '未登录/已登录')
check('演示条在页面最底下', Math.abs((await page.$eval('#demoBar', (el) => el.getBoundingClientRect().bottom)) - (await page.evaluate(() => window.innerHeight))) <= 1)

// 1 · the page is the marketing page, and nothing else
check('没有浮层', (await page.$('#overlay')) === null)
check('没有登录表单', (await page.$('form')) === null)
/* **The signed-out state is asserted by what it has, not by an id that no longer exists.** Until
   2026-10-24 this read `#navUser`, which nothing renders any more — so it passed on every state,
   including `?signed-in`. */
check('未登录：没有账号控件', (await page.$('#meBtn')) === null && (await page.$('#toConsoleBtn')) === null)
check('未登录导航在', await visible('#navGuest'))
check('标题是设计稿那句', (await page.title()) === 'SkillMaster — 把你的 skill 托管起来，任何 agent 一行加载。')

// 2 · the ways out are ordinary links to the console
check('登录去 console 的登录页', (await hrefOf('#loginBtn')).endsWith('?page=login'), await hrefOf('#loginBtn'))
check('导航的开始使用去 console 的注册页', (await hrefOf('#startBtnNav')).endsWith('?page=register'), await hrefOf('#startBtnNav'))
check('页脚控制台去 console', (await hrefOf('#footConsole')) === 'skillmaster-控制台演示.html', await hrefOf('#footConsole'))
check('页脚的开始使用去注册页', (await hrefOf('#footStart')).endsWith('?page=register'), await hrefOf('#footStart'))
check('页脚的登录去登录页', (await hrefOf('#footLogin')).endsWith('?page=login'), await hrefOf('#footLogin'))

// 3 · the placeholders render but do not navigate
const before = await page.evaluate(() => window.location.href)
const placeholders = await page.$$eval('a[href="#"]', (nodes) => nodes.length)
check(
  '占位链接都在（品牌 + 页脚 7 = 8）',
  placeholders === 8,
  String(placeholders),
)
await page.click('a.brand')
await settle()
check('点品牌占位：地址不变', (await page.evaluate(() => window.location.href)) === before)

// 4 · the first screen is one line and one sentence
check('Hero 标题在', await visible('.hero h1'))
check('Hero 是主句', (await page.$eval('.hero h1', (el) => el.textContent?.trim())) === 'SkillMaster 随时在场')
check('prompt 在 Hero 里', (await page.$('.hero #agentPrompt')) !== null)
check('下滑提示在', await visible('.scroll-hint'))
check('旧的导览段落不在', (await page.$$('.horizon, .term, .split, .points, .steps')).length === 0)
check('导航不再放产品/文档链接', (await page.$$('.nav-links')).length === 0)
check(
  '页脚三列：产品 / 文档 / 关于',
  (await page.$$eval('.foot-col h3', (nodes) => nodes.map((node) => node.textContent?.trim()).join('/'))) === '产品/文档/关于',
)
const prompt = await page.$eval('#agentPrompt', (el) => el.textContent ?? '')
check(
  'prompt 是带安装说明页地址的那一句',
  prompt.trim() === '根据 https://www.skillmasterai.com/install/skillmaster.md 开始 SkillMaster 的旅程。',
  prompt,
)
const hint = await page.$eval('.prompt-hint', (el) => el.textContent?.trim() ?? '')
check('安装提示在', hint === '复制下面这句，发给你常用的 agent。', hint)
const label = await page.$eval('.prompt-label', (el) => el.textContent?.trim() ?? '')
check('prompt 标签是安装指令', label === '安装指令', label)
check('没有图片', (await page.$$eval('img', (nodes) => nodes.length)) === 0)

// 4b · the three cards, on the first screen directly under the prompt
check('卡片上方没有单独标题', (await page.$('#whyTitle, .cards-head')) === null)
const cardCount = await page.$$eval('.card', (nodes) => nodes.length)
check('三张卡片在', cardCount === 3, String(cardCount))
check('卡片没有眉标', (await page.$$('.card-eyebrow')).length === 0)
const cardHeads = await page.$$eval('.card h3', (nodes) =>
  nodes.map((node) => node.textContent?.trim()).join('/'),
)
check(
  '三张卡片标题',
  cardHeads === '云端加载，随处调用/技能共享，知识沉淀/遇到问题，总有办法',
  cardHeads,
)
const cardBodies = await page.$$eval('.card-body', (nodes) =>
  nodes.map((node) => node.textContent?.trim()).join('/'),
)
check(
  '三段正文',
  cardBodies ===
    '把 skill 保存到云端，换哪个 agent 都能用。/把 skill 分享给朋友和同事，他们随时都能用上最新的知识。/1000+ 个精选 skill，总有一个能解决问题。',
  cardBodies,
)
const cardsOnFirstScreen = await page.evaluate((bar) => {
  const grid = document.querySelector('.cards-grid')?.getBoundingClientRect()
  return {
    viewport: window.innerHeight,
    top: grid?.top ?? null,
    bottom: grid?.bottom ?? null,
    bar,
  }
}, bar)
check(
  '三张卡片整块都在首屏里',
  cardsOnFirstScreen.top !== null &&
    cardsOnFirstScreen.bottom !== null &&
    cardsOnFirstScreen.top > 0 &&
    cardsOnFirstScreen.bottom <= cardsOnFirstScreen.viewport - cardsOnFirstScreen.bar + 1,
  JSON.stringify(cardsOnFirstScreen),
)

const initial = await page.evaluate(() => ({
  viewport: window.innerHeight,
  hero: document.querySelector('.hero')?.getBoundingClientRect().height ?? 0,
  footTop: document.querySelector('#foot')?.getBoundingClientRect().top ?? 0,
}))
check('首屏至少一屏高', initial.hero >= initial.viewport - bar - 1, JSON.stringify(initial))
check('页脚在首屏之下', initial.footTop >= initial.viewport - bar - 1, JSON.stringify(initial))

// 5 · the copy button copies exactly what is on screen
await page.evaluate(() => {
  window.__copied = null
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: async (text) => { window.__copied = text } },
  })
})
await page.click('#copyPromptBtn')
await settle()
check('复制的内容与页面一致', (await page.evaluate(() => window.__copied)) === prompt)
check('复制后有反馈', (await page.$eval('#copyPromptBtn', (el) => el.textContent.trim())) === '已复制')

// 6 · no request to a backend
const calls = await page.evaluate(() => window.__fetches.slice())
check('没有 fetch 调用', calls.length === 0, calls.join(', '))

// 6b · light-only, deliberately (the design has no dark state)
const lightBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }])
await settle()
const darkBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
check('深色系统下仍是浅色', darkBg === lightBg, `${lightBg} → ${darkBg}`)
await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'light' }])
await settle()

/* The copy check leaves the button saying 「已复制」. Waiting for it to come back is both the
   「feedback resets」 assertion and what makes the next picture the page at rest. */
const reset = await page
  .waitForFunction(
    () => document.querySelector('#copyPromptBtn')?.textContent?.trim() === '复制',
    { timeout: 3000 },
  )
  .then(() => true)
  .catch(() => false)
check('反馈两秒后回到「复制」', reset)

// 7 · the deck: one gesture, one full screen; no position in between
await blurActive()
await settle()
await shotViewport('00-home.png')

const navBefore = await page.$eval('#nav', (el) => {
  const rect = el.getBoundingClientRect()
  const style = getComputedStyle(el)
  return {
    x: rect.x,
    y: rect.y,
    width: rect.width,
    height: rect.height,
    position: style.position,
    background: style.backgroundColor,
    radius: style.borderRadius,
  }
})

await page.evaluate(() =>
  window.dispatchEvent(new WheelEvent('wheel', { deltaY: 120, bubbles: true, cancelable: true })),
)
const toFoot = await waitForSlide(1)
check('滚轮下滑切到页脚', toFoot)
const navAfter = await page.$eval('#nav', (el) => {
  const rect = el.getBoundingClientRect()
  const style = getComputedStyle(el)
  return {
    x: rect.x,
    y: rect.y,
    width: rect.width,
    height: rect.height,
    position: style.position,
    background: style.backgroundColor,
    radius: style.borderRadius,
  }
})
check(
  '导航在切换时不动',
  JSON.stringify(navBefore) === JSON.stringify(navAfter),
  JSON.stringify({ before: navBefore, after: navAfter }),
)

const shown = await page
  .waitForFunction(
    () => getComputedStyle(document.querySelector('#foot')).opacity === '1',
    { timeout: 3000 },
  )
  .then(() => true)
  .catch(() => false)
check('页脚完全显示', shown)
const settled = await page.evaluate(() => {
  const hero = document.querySelector('.hero')?.getBoundingClientRect()
  const foot = document.querySelector('#foot')?.getBoundingClientRect()
  return {
    viewport: window.innerHeight,
    heroTop: hero?.top ?? null,
    footTop: foot?.top ?? null,
  }
})
check(
  '停在整屏，不停在中间',
  settled.heroTop !== null &&
    settled.footTop !== null &&
    Math.abs(settled.footTop) <= 1 &&
    Math.abs(settled.heroTop + settled.viewport - bar) <= 1,
  JSON.stringify(settled),
)
await blurActive()
await shotViewport('00-home-scrolled.png')

await unlock()
await page.keyboard.press('Home')
const returned = await waitForSlide(0)
check('回到首屏', returned)
await unlock()

/* The two breakpoints the design defines. The assertion is only “no sideways scroll” — whether the
   layout *looks* right at each width is a person's call, and the pictures are the evidence. */
for (const width of [1000, 720]) {
  await page.setViewport({ width, height: 900, deviceScaleFactor: 1 })
  await page.keyboard.press('Home')
  await waitForSlide(0)
  await settle()
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  )
  check(`${width}px：没有横向滚动`, !overflow)
  await blurActive()
  await shotViewport(`00-home-${width}.png`)
  await unlock()
  await page.keyboard.press('ArrowDown')
  const foot = await waitForSlide(1)
  check(`${width}px：下滑切到页脚`, foot)
  await blurActive()
  await shotViewport(`00-home-${width}-scrolled.png`)
  await unlock()
  await page.keyboard.press('Home')
  await waitForSlide(0)
  await unlock()
}

/* 8 · the signed-in state. **The flag is the demo's**: the page reads `?signed-in` here, while the
   product decides it from a real session read (ADR 0086). What is worth pinning either way: signing
   in replaces the two ways in with one way across, the footer stays as it was, and the account menu
   opens, closes on Escape, and hands the focus back. */
await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 })
await page.goto('file://' + resolve(src) + '?signed-in', { waitUntil: 'load', timeout: 20000 })
await settle()
check('已登录：没有登录 / 开始使用', (await page.$('#navGuest')) === null)
check(
  '已登录：进入控制台指向 console',
  (await hrefOf('#toConsoleBtn')) === 'skillmaster-控制台演示.html',
  await hrefOf('#toConsoleBtn'),
)
check('已登录：账号在', await visible('#meBtn'))
check('已登录：菜单默认关着', !(await visible('#meMenu')))
await page.click('#meBtn')
await settle()
check('已登录：点账号打开菜单', await visible('#meMenu'))
check(
  '已登录：Esc 关菜单并把焦点还给按钮',
  await page
    .keyboard.press('Escape')
    .then(() => settle())
    .then(() => page.evaluate(() => document.activeElement?.id === 'meBtn' && document.querySelector('#meMenu').hidden)),
)
check(
  '已登录：页脚三条链接仍在',
  (await page.$('#footConsole')) !== null &&
    (await page.$('#footStart')) !== null &&
    (await page.$('#footLogin')) !== null,
)
await blurActive()
await settle()
await shotViewport('00-home-signed-in.png')

await browser.close()

const failed = checks.filter((c) => c.pass === false)
console.log(JSON.stringify({ src, checks, problems, failed: failed.length }, null, 2))
process.exit(failed.length > 0 || problems.length > 0 ? 1 : 0)
