// v65 console click-through: drive the real front end (Vite :5173) against the real server (:8080)
// the way a person would, and shoot each state.
//
//   CLICK_PHONE=... CLICK_PASSWORD=... npm start
//
// Read-only: it signs in and browses; it never publishes or edits. Every check is recorded, the run
// does not stop at the first failure, and the exit code is non-zero when one fails.
//
// **Not part of CI.** It needs a live stack, a seeded demo skill (see seed.sql) and a real account.
// `qa/role-matrix` covers the authorization ladder; this file covers what v65 changed on the screen.

import { createRequire } from 'node:module'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const puppeteer = require('puppeteer-core')

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(HERE, '../..')

const BASE = process.env.CLICK_BASE ?? 'http://localhost:5173'
const PHONE = process.env.CLICK_PHONE ?? ''
const PASSWORD = process.env.CLICK_PASSWORD ?? ''
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const OUT = resolve(process.argv[2] ?? process.env.CLICK_OUT ?? `${ROOT}/artifacts/qa/console-click-through`)

if (PHONE === '' || PASSWORD === '') {
  console.error('set CLICK_PHONE and CLICK_PASSWORD (the account must already exist on that stack)')
  process.exit(2)
}
mkdirSync(OUT, { recursive: true })

const checks = []
function check(name, pass, detail = '') {
  checks.push({
    name,
    pass: Boolean(pass),
    detail: typeof detail === 'string' ? detail : JSON.stringify(detail),
  })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + (typeof detail === 'string' ? detail : JSON.stringify(detail)) : ''}`)
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function shot(page, name) {
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: false })
}

async function rowData(page, namePart) {
  return page.evaluate((needle) => {
    const row = [...document.querySelectorAll('.results .result')].find((r) =>
      (r.querySelector('.result-name')?.textContent ?? '').includes(needle))
    if (!row) return null
    return {
      name: row.querySelector('.result-name')?.textContent ?? '',
      description: row.querySelector('.result-title')?.textContent ?? '',
      attrs: [...row.querySelectorAll('.result-attrs .chip.is-blocker')].map((c) => c.textContent.trim()),
      attrLinks: [...row.querySelectorAll('.result-attrs .chip.is-blocker')]
        .map((c) => c.tagName + ':' + (c.getAttribute('href') ?? '')),
      more: row.querySelector('.result-attrs .result-more')?.textContent.trim() ?? '',
      category: row.querySelector('.result-category')?.textContent.trim() ?? '',
      text: row.textContent,
    }
  }, namePart)
}

async function search(page, text) {
  await page.click('#discoverQuery')
  await page.keyboard.down('Meta')
  await page.keyboard.press('A')
  await page.keyboard.up('Meta')
  await page.keyboard.press('Backspace')
  await page.type('#discoverQuery', text)
  await page.keyboard.press('Enter')
  await page.waitForFunction(
    (t) => [...document.querySelectorAll('.results .result .result-name')]
      .some((a) => (a.textContent ?? '').includes(t)),
    { timeout: 20000 },
    text.replace(/\s+/g, '-'),
  )
  await sleep(300)
}

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ['--no-first-run', '--no-default-browser-check'],
})

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 900 })

  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle2' })
  await page.type('#phone', PHONE)
  await page.type('#password', PASSWORD)
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 20000 }),
    page.click('form button[type="submit"]'),
  ])
  check('登录后离开登录页', !page.url().includes('/login'), page.url())

  await page.goto(`${BASE}/discover`, { waitUntil: 'networkidle2' })
  await page.waitForSelector('#discoverQuery', { timeout: 20000 })
  await page.waitForFunction(
    () => document.querySelectorAll('#discoverLanding .results .result').length > 0,
    { timeout: 20000 },
  )
  const landingRows = await page.$$eval('#discoverLanding .results .result', (rows) => rows.length)
  check('发现页落地有推荐结果', landingRows > 0, `${landingRows} 行`)

  await search(page, 'ab-testing')
  const title = await page.$eval('#discoverResultsTitle', (el) => el.textContent.trim())
  const note = await page.$eval('#discoverResultsNote', (el) => el.textContent.trim())
  check('搜索结果标题', title === '搜索结果', title)
  check('搜索结果小字按相关度', note.includes('按相关度排序'), note)
  const headText = await page.evaluate(() =>
    [...document.querySelectorAll('#discoverResultsHead, .pager')].map((n) => n.textContent).join(' '))
  check('结果页与分页条没有结果数', !/\d+\s*个结果|共\s*\d+\s*个/.test(headText), headText)
  check('分页条不画总数', (await page.$('.pager-count')) === null)
  const pageCount = await page.evaluate(() => {
    const el = document.querySelector('#page-discover .count')
    return el === null ? null : (el.textContent ?? '').trim()
  })
  check('发现页头没有公域计数', pageCount === null || pageCount === '', pageCount)

  const ab = await rowData(page, 'ab-testing')
  check('ab-testing 结果行存在', ab !== null)
  check('结果行只写作者描述',
    ab && ab.description.length > 0 && !ab.text.includes('一份可以直接拿去跑的实验设计'),
    ab?.description?.slice(0, 40))
  check('属性三枚且顺序正确', ab && ab.attrs.join('|') === 'Python|在线服务|付费', ab?.attrs)
  check('属性是链接', ab && ab.attrLinks.every((l) => l.startsWith('A:')), ab?.attrLinks)
  check('没有多余的 +N', ab && ab.more === '', ab?.more)
  await shot(page, '01-search-results')

  await page.evaluate(() => {
    const row = [...document.querySelectorAll('.results .result')]
      .find((r) => (r.querySelector('.result-name')?.textContent ?? '').includes('ab-testing'))
    row.querySelector('.result-category').click()
  })
  await page.waitForFunction(
    () => document.querySelector('#discoverResultsTitle')?.textContent.trim() === '筛选结果',
    { timeout: 20000 },
  )
  await sleep(500)
  const filterTitle = await page.$eval('#discoverResultsTitle', (el) => el.textContent.trim())
  const filterNote = await page.$eval('#discoverResultsNote', (el) => el.textContent.trim())
  const clearText = await page.$eval('.discover-clear', (el) => el.textContent.trim()).catch(() => '')
  check('分类筛选的标题', filterTitle === '筛选结果', filterTitle)
  check('分类筛选的小字写筛了什么', filterNote.includes('数据分析'), filterNote)
  check('分类筛选有清除', clearText === '清除', clearText)
  const filtered = await page.$$eval('.results .result .result-name', (n) => n.map((x) => x.textContent))
  check('分类筛选只返回该货架', filtered.length > 0 && filtered.every((n) => n.includes('ab-testing')), filtered)
  await shot(page, '02-category-filter')

  await page.click('.discover-clear')
  await page.waitForFunction(() => document.querySelector('#discoverLanding')?.hidden === false,
    { timeout: 20000 })
  check('清除后回推荐页', await page.$eval('#discoverLanding', (el) => el.hidden === false))

  await search(page, 'dispatching-parallel-agents')
  const dp = await rowData(page, 'dispatching-parallel-agents')
  check('四枚属性收成三枚 + +1', dp && dp.attrs.length === 3 && dp.more === '+1', dp)
  await page.evaluate(() => {
    const row = [...document.querySelectorAll('.results .result')]
      .find((r) => (r.querySelector('.result-name')?.textContent ?? '').includes('dispatching-parallel-agents'))
    row.querySelector('.result-attrs .result-more').click()
  })
  await page.waitForSelector('.tags-pop', { timeout: 5000 })
  const popCount = await page.$$eval('.tags-pop .tags-pop-attr', (n) => n.length)
  check('+N 浮层里是全部四个属性', popCount === 4, `${popCount} 枚`)
  await shot(page, '03-attributes-popover')
  await page.keyboard.press('Escape')

  await search(page, 'brainstorming')
  const bs = await rowData(page, 'brainstorming')
  check('纯提示词 / 免费不上结果行', bs && bs.attrs.length === 0, bs?.attrs)

  await search(page, 'ab-testing')
  await page.evaluate(() => {
    const row = [...document.querySelectorAll('.results .result')]
      .find((r) => (r.querySelector('.result-name')?.textContent ?? '').includes('ab-testing'))
    row.querySelector('.result-name').click()
  })
  await page.waitForSelector('#infoOutcome', { timeout: 20000 })
  const info = await page.evaluate(() => {
    const outcome = document.querySelector('#infoOutcome')
    const labels = [...document.querySelectorAll('.info-block .info-label')].map((n) => n.textContent.trim())
    const attrs = document.querySelector('.info-attributes-row')
    const env = [...document.querySelectorAll('.info-block')]
      .find((s) => s.querySelector('.info-label')?.textContent.trim() === '兼容性') ?? null
    const desc = [...document.querySelectorAll('.info-block')]
      .find((s) => s.querySelector('.info-label')?.textContent.trim() === '描述') ?? null
    const follows = (a, b) => Boolean(a && b && (a.compareDocumentPosition(b) & 4))
    return {
      labels,
      outcomeIndex: labels.indexOf('预期结果'),
      envIndex: labels.indexOf('兼容性'),
      outcomeText: outcome?.querySelector('.info-text')?.textContent ?? '',
      attrs: [...(attrs?.querySelectorAll('.chip.is-attr') ?? [])].map((c) => c.textContent.trim()),
      attrHrefs: [...(attrs?.querySelectorAll('a.chip.is-attr') ?? [])].map((a) => a.getAttribute('href')),
      descBeforeOutcome: follows(desc, outcome),
      attrsAfterOutcome: follows(outcome, attrs),
      attrsAfterEnv: env === null ? null : follows(env, attrs),
    }
  })
  check('信息页有预期结果', info.outcomeText.includes('实验设计'), info.outcomeText)
  check('描述在预期结果之前', info.descBeforeOutcome, info.labels)
  // 兼容性是作者原文，没有就不画；顺序只在它存在时才可判。
  check('预期结果在兼容性之前（有兼容性时）',
    info.envIndex < 0 || info.envIndex > info.outcomeIndex, info.labels)
  check('属性胶囊在预期结果之后', info.attrsAfterOutcome, info.labels)
  check('属性胶囊在兼容性之后（有兼容性时）', info.attrsAfterEnv === null || info.attrsAfterEnv, info.labels)
  check('信息页属性三枚', info.attrs.join('|') === 'Python|在线服务|付费', info.attrs)
  check('信息页属性可点', info.attrHrefs.every((h) => h && h.includes('label=')), info.attrHrefs)
  await shot(page, '04-info-page')

  await page.goto(`${BASE}/my-tags?tab=public`, { waitUntil: 'networkidle2' })
  await page.waitForSelector('#publicTagPanel', { timeout: 20000 })
  await sleep(500)
  const tags = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('#publicTagList li')]
    return {
      rows: rows.length,
      actions: rows.map((r) => r.querySelector('.tag-action')?.textContent.trim() ?? ''),
      labels: rows.map((r) => r.querySelector('.tag-action')?.getAttribute('aria-label') ?? ''),
      counts: rows.filter((r) => r.querySelector('.tag-count')).length,
    }
  })
  // An account with no skills of its own sees an empty list; that is a pass, not a skip.
  check('公开标签每行是「查看 skill」',
    tags.rows === 0 || (tags.actions.every((a) => a === '查看 skill') && tags.counts === 0), tags)
  check('公开标签读屏名字带名字',
    tags.rows === 0 || tags.labels.every((l) => l.startsWith('查看 ')), tags.labels.slice(0, 2))
  await shot(page, '05-my-tags-public')

  await page.setViewport({ width: 400, height: 900 })
  await page.goto(`${BASE}/discover?tag=data-analysis`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(() => document.querySelectorAll('.results .result').length > 0,
    { timeout: 20000 })
  await sleep(300)
  const overflow = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    inner: window.innerWidth,
  }))
  check('窄屏没有横向滚动', overflow.scroll <= overflow.inner + 1, overflow)
  await shot(page, '06-narrow-400')
} finally {
  await browser.close()
}

const failed = checks.filter((c) => !c.pass).length
console.log('\n' + JSON.stringify({ out: OUT, total: checks.length, failed, checks }, null, 2))
process.exit(failed === 0 ? 0 : 1)
