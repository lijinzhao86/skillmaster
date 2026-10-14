// Walk every state the console demo can be in, shoot each one, and check the two things that are
// true of all of them.
//
//   node design/scripts/console-states.mjs design/demos/dir3-hara-console.html design/shots/console
//
// The console's empty, loading and failed states cannot be reached by clicking: with no server
// behind the file, nothing can be made to load or fail. So they are put on a switch in the demo
// strip, and this walks the switch.
//
// Every address below is one a person could paste into a bar. That is not a convenience — it is the
// property the demo is built on, and the same property the real app has: the address is the whole
// state, so a screen can be linked to, reloaded and sent to somebody else.

import { execSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

const [src, outDir] = process.argv.slice(2)
if (!src || !outDir) {
  console.error('usage: console-states.mjs <draft.html> <out-dir>')
  process.exit(1)
}
mkdirSync(resolve(outDir), { recursive: true })

const require = createRequire(execSync('npm root -g', { encoding: 'utf8' }).trim() + '/')
const puppeteer = require('puppeteer-core')

const base = 'file://' + resolve(src)

/**
 * Each state: its address, the page it should land on, the nav item that should be current, and
 * whether its content is tall enough to scroll.
 *
 * `scrolls` is not decoration. The strip along the bottom is fixed, and the obvious way to keep
 * content out from under it — padding the body by the strip's height — makes *every* page scroll by
 * exactly that much, including the ones with three lines on them. This is the field that catches it.
 *
 * It is also, in the other direction, the assertion that the list is a *page*: ten rows are meant to
 * be one screen, so `skills-ready` is one of the ones that must not scroll.
 */
const SHOTS = [
  { name: 'skills-ready', query: '?page=skills', page: 'skills', nav: '管理', scrolls: false },
  { name: 'skills-empty', query: '?page=skills&state=empty', page: 'skills', nav: '管理', scrolls: false },
  { name: 'skills-loading', query: '?page=skills&state=loading', page: 'skills', nav: '管理', scrolls: false },
  { name: 'skills-failed', query: '?page=skills&state=failed', page: 'skills', nav: '管理', scrolls: false },
  { name: 'skill-ready', query: '?page=skill', page: 'skill', nav: '管理', scrolls: true },
  { name: 'skill-missing', query: '?page=skill&state=missing', page: 'skill', nav: '管理', scrolls: false },
  { name: 'skill-settings-collaborators', query: '?page=skill&tab=settings', page: 'skill', nav: '管理', scrolls: false },
  { name: 'skill-settings-visibility', query: '?page=skill&tab=settings&sub=visibility&vis=public-invoke', page: 'skill', nav: '管理', scrolls: false },
  { name: 'account-ready', query: '?page=account', page: 'account', nav: '设置', scrolls: false },
  { name: 'account-anonymous', query: '?page=account&state=anonymous', page: 'account', nav: '设置', scrolls: false },
  { name: 'account-unknown', query: '?page=account&state=unknown', page: 'account', nav: '设置', scrolls: false },
]

// Headful, like the other two scripts: a state screenshot should be the state the person sees.
const browser = await puppeteer.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: false,
  args: [
    '--no-sandbox',
    '--force-color-profile=srgb',
    '--window-size=1440,1000',
    '--window-position=0,0',
  ],
  defaultViewport: { width: 1440, height: 900, deviceScaleFactor: 1 },
})

const page = await browser.newPage()
await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'light' }])

const problems = []
page.on('pageerror', (error) => problems.push('pageerror: ' + error.message))
page.on('console', (message) => {
  if (message.type() === 'error') problems.push('console: ' + message.text())
})

const checks = []
const check = (name, pass, detail = '') => checks.push({ name, pass, detail })

for (const shot of SHOTS) {
  await page.goto(base + shot.query, { waitUntil: 'networkidle0' })
  await new Promise((done) => setTimeout(done, 150))

  // Only the chosen page is in the document, so a page that rendered is a page that was chosen —
  // and exactly one of them, which is the failure a hide-the-others approach would hide.
  const shown = await page.evaluate(() =>
    [...document.querySelectorAll('.page')].filter((el) => !el.hidden).map((el) => el.id),
  )
  check(
    `${shot.name}：只显示一个页面`,
    shown.length === 1 && shown[0] === 'page-' + shot.page,
    shown.join(','),
  )

  const nav = await page.$eval('.nav-item.is-active', (el) => el.textContent.trim())
  check(`${shot.name}：导航当前项是「${shot.nav}」`, nav === shot.nav, nav)

  // How much taller the document is than the window — which is exactly whether a scrollbar exists.
  // A page that fits reads 0; the padding-the-body bug reads the strip's height on every page.
  const overflow = await page.evaluate(
    () => document.documentElement.scrollHeight - window.innerHeight,
  )
  check(
    `${shot.name}：${shot.scrolls ? '照常滚动' : '不多出一条滚动条'}`,
    shot.scrolls ? overflow > 0 : overflow <= 0,
    `${overflow}px`,
  )

  await page.screenshot({ path: resolve(outDir, shot.name + '.png'), fullPage: true })
}

// ---------------------------------------------------------------------------------------------
// The session. Everything about signing in, registering and recovering a password belongs to the
// fragment shared with the marketing demo; what is checked here is the console's own half — that
// the dialog is the only thing on the page, that it cannot be dismissed, where the address goes,
// and where each answer leaves you.

const AUTH = [
  { name: 'auth-login', query: '?page=login', title: '登录', focus: 'phone' },
  { name: 'auth-register', query: '?page=register', title: '注册', focus: 'regUsername' },
  { name: 'auth-reset', query: '?page=reset', title: '找回密码', focus: 'resetPhone' },
]

/** Clearing is done with `el.select()` — see the note in interaction-check.mjs for why not Ctrl+A. */
const type = async (selector, value) => {
  await page.click(selector)
  await page.$eval(selector, (el) => el.select())
  await page.type(selector, value, { delay: 8 })
}

const settle = () => new Promise((done) => setTimeout(done, 250))

for (const view of AUTH) {
  await page.goto(base + view.query, { waitUntil: 'networkidle0' })
  await settle()

  const state = await page.evaluate(() => ({
    open: document.getElementById('overlay').classList.contains('is-open'),
    standalone: document.getElementById('overlay').classList.contains('is-standalone'),
    frameHidden: document.querySelector('.app').hidden,
    closeHidden: document.getElementById('closeBtn').hidden,
    title: document.getElementById('dialogTitle').textContent.trim(),
    focus: document.activeElement.id,
  }))
  check(`${view.name}：浮层开着`, state.open)
  check(`${view.name}：压掉了控制台，而不是浮在它上面`, state.standalone && state.frameHidden)
  check(`${view.name}：标题是「${view.title}」`, state.title === view.title, state.title)
  check(`${view.name}：焦点落在第一个输入框`, state.focus === view.focus, state.focus)
  check(`${view.name}：没有关闭按钮`, state.closeHidden)

  /* Nothing to go back to, so nothing gets you out — not the scrim, and not Escape. A console with
     no session has no page behind this, and a dialog that closed would close onto nothing. */
  await page.keyboard.press('Escape')
  await page.mouse.click(60, 60)
  await settle()
  check(
    `${view.name}：Esc 与点遮罩都关不掉`,
    await page.$eval('#overlay', (el) => el.classList.contains('is-open')),
  )

  await page.screenshot({ path: resolve(outDir, view.name + '.png') })
}

/* Signing out leaves the console for the dialog. */
await page.goto(base + '?page=skills', { waitUntil: 'networkidle0' })
await page.click('#signOutBtn')
await settle()
check('退出登录：落到登录屏', (await page.$eval('#dialogTitle', (el) => el.textContent.trim())) === '登录')
check('退出登录：地址是 ?page=login', new URL(page.url()).search === '?page=login', page.url())

/* The dialog's screens are addresses here, so the address follows it — and the strip with it. */
await page.click('#toRegister')
await settle()
check('对话框里点「注册」：地址跟着走', new URL(page.url()).search === '?page=register', new URL(page.url()).search)
check(
  '对话框里点「注册」：演示条也跟着',
  (await page.$eval('.demo-opts a.is-on', (el) => el.textContent.trim())) === '注册',
)

/* Back to the login screen, and in. */
await page.goto(base + '?page=login', { waitUntil: 'networkidle0' })
await type('#phone', '13800138000')
await type('#password', 'skillmaster')
await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }), page.click('#submitBtn')])
await settle()
const afterSignIn = await page.evaluate(() => ({
  overlayOpen: document.getElementById('overlay').classList.contains('is-open'),
  frameHidden: document.querySelector('.app').hidden,
  title: document.getElementById('dialogTitle').textContent.trim(),
}))
check('登录成功：回到控制台', !afterSignIn.overlayOpen && !afterSignIn.frameHidden)
check('登录成功：地址是控制台', new URL(page.url()).search === '?page=skills', new URL(page.url()).search)

// ---------------------------------------------------------------------------------------------
// The list. What it shows, and the three things that rearrange it. Search, sort and pages are all in
// the address, so every case below is one URL rather than a sequence of clicks — which is also the
// property being checked: a filtered list is a thing somebody can send to somebody else.

const listAt = async (query) => {
  await page.goto(base + query, { waitUntil: 'networkidle0' })
  await settle()
  return page.evaluate(() => ({
    names: [...document.querySelectorAll('#skillList .skill-name')].map((el) => el.textContent),
    calls: [...document.querySelectorAll('#skillList .skill-calls')].map((el) => el.textContent),
    count: document.getElementById('skillCount').textContent.trim(),
    none: !document.getElementById('skillNone').hidden,
    pager: document.getElementById('skillPager').textContent.replace(/\s+/g, ' ').trim(),
    current: document.querySelector('#skillPager .is-current')?.textContent ?? null,
    sort: document.getElementById('skillSort').value,
    query: document.getElementById('skillQuery').value,
  }))
}

const first = await listAt('?page=skills')
check('列表：一页十条', first.names.length === 10, String(first.names.length))
check('列表：标题里的数量是所有，不是当页', first.count === '24 个', first.count)
check('列表：默认按最近更新，最新的在前', first.names[0] === 'changelog-writer', first.names[0])
check('列表：每一行都带调用次数', first.calls.length === 10 && first.calls[0].includes('次调用'), first.calls[0])
check('列表：一页装不下时才出现分页', first.pager.includes('上一页') && first.current === '1', first.pager)
check(
  '排序：只给三项，没有「待上线最多」',
  (await page.$$eval('#skillSort option', (els) => els.map((el) => el.value))).join(',') ===
    'recent,calls,name',
)
check(
  '列表：一行都不再提「待上线 / 未上线 / 已丢弃」',
  !(await page.$eval('#skillList', (el) =>
    /待上线|未上线|已丢弃|都被丢弃/.test(el.textContent),
  )),
)
check(
  '列表：每一行都报的是它的线上版本',
  first.calls.length === 10 && (await page.$$eval('#skillList .chip-text', (els) => els.map((el) => el.textContent))).every((text) => /^已上线 @/.test(text)),
)

/* The row's own requirement, measured rather than reasoned about: all ten rows above the strip along
   the bottom, on a 900px window, with no scrollbar anywhere. */
const fits = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('#skillList .skill')]
  return {
    rows: rows.length,
    rowHeight: rows[0].getBoundingClientRect().height,
    lastRowBottom: Math.round(rows[rows.length - 1].getBoundingClientRect().bottom),
    barTop: Math.round(document.querySelector('.demo-bar').getBoundingClientRect().top),
    overflow: document.documentElement.scrollHeight - window.innerHeight,
  }
})
check(
  '列表：十条一屏放下，没被底部演示条压住',
  fits.rows === 10 && fits.overflow <= 0 && fits.lastRowBottom <= fits.barTop,
  `${fits.rows} 行，末行底 ${fits.lastRowBottom}px，演示条顶 ${fits.barTop}px，溢出 ${fits.overflow}px`,
)

const byCalls = await listAt('?page=skills&sort=calls')
check('排序：调用最多在前', byCalls.names[0] === 'regex-explainer', byCalls.names[0])
check('排序：连地址里的值也认', byCalls.sort === 'calls', byCalls.sort)

const byName = await listAt('?page=skills&sort=name')
check('排序：按名称是字母序', byName.names[0] === 'api-doc-writer', byName.names[0])

const searched = await listAt('?page=skills&q=pdf')
check('搜索：命中一条', searched.names.length === 1 && searched.names[0] === 'pdf-tools', searched.names.join(','))
check('搜索：数量跟着变', searched.count === '1 个', searched.count)
check('搜索：装得下就不分页', searched.pager === '', searched.pager)
check('搜索：词进到输入框里', searched.query === 'pdf', searched.query)

const hitsTitle = await listAt('?page=skills&q=' + encodeURIComponent('日志'))
check('搜索：也搜说明，不只搜名字', hitsTitle.names.length === 2, hitsTitle.names.join(','))

const nothing = await listAt('?page=skills&q=zzz')
check('搜索：无结果是自己的状态，不是一张空列表', nothing.none && nothing.count === '0 个', nothing.count)

const second = await listAt('?page=skills&p=2')
check('分页：第二页是第 11–20 个', second.pager.includes('第 11–20 个') && second.current === '2', second.pager)

/* A page nobody has is not an error and not a blank screen: it is the last page there is. Sixteen is
   not a multiple of ten either, so the last page is the short one. */
const beyond = await listAt('?page=skills&p=99')
check('分页：页码越界收在末页，末页是短的', beyond.current === '3' && beyond.names.length === 4, beyond.pager)

const lastByRecency = await listAt('?page=skills&p=3')
check(
  '最近更新：最旧的排在最后',
  lastByRecency.names[lastByRecency.names.length - 1] === 'sql-review',
  lastByRecency.names[lastByRecency.names.length - 1],
)

/* Five skills have never been invoked, and 「调用最多」 must not put them at the top of the list. They
   sink to the bottom together, in the order they were already in. */
const sortedLastPage = await listAt('?page=skills&sort=calls&p=3')
check(
  '排序与分页可以叠加：没被调用过的沉到最后一页',
  sortedLastPage.names[0] === 'sql-review' && sortedLastPage.names[3] === 'bundle-triage',
  sortedLastPage.names.join(','),
)

// ---------------------------------------------------------------------------------------------
// The skill page, which reads as a repository: one level of files at a time, and under them
// whichever file is being read. Both are addresses, so every case below is one URL rather than a
// sequence of clicks — and the sequence gets walked once at the end, because that is how a person
// gets from the root down to a file.

const skillAt = async (query) => {
  await page.goto(base + query, { waitUntil: 'networkidle0' })
  await settle()
  return page.evaluate(() => ({
    crumb: document.querySelector('.skill-ident h1').textContent.replace(/\s+/g, ' ').trim(),
    badge: document.querySelector('.skill-ident .chip')?.textContent ?? null,
    badgeHref: document.querySelector('.skill-ident .chip')?.getAttribute('href') ?? null,
    checked: document.querySelector('input[name="skill-visibility"]:checked')?.value ?? null,
    warning: document.getElementById('invokeWarning').hidden ? null : document.getElementById('invokeWarning').textContent,
    /* A row's own words only. The role control is a `<select>`, and every `<option>` under it is a
       child in the DOM — leaving them in would make the row text depend on what is *offered*
       rather than on what the row *says*. */
    grants: [...document.querySelectorAll('#grantList li')].map((el) => {
      const copy = el.cloneNode(true)
      copy.querySelectorAll('select').forEach((control) => control.remove())
      return copy.textContent.replace(/\s+/g, ' ').trim()
    }),
    grantValues: [...document.querySelectorAll('#grantList select')].map((el) => el.value),
    grantOptions: [...document.querySelectorAll('#grantList select')].map((el) =>
      [...el.options].map((option) => option.textContent).join('/'),
    ),
    roles: [...document.querySelectorAll('#sharing-role option')].map((el) => el.textContent),
    hint: document.getElementById('grantsHint').textContent,
    visTitles: [...document.querySelectorAll('#visibilityChoices .choice-title')].map((el) => el.textContent),
    count: document.getElementById('versionCount').textContent,
    address: document.getElementById('skillAddress').textContent,
    tab: document.querySelector('.skill-tab.is-current')?.textContent ?? null,
    panels: [...document.querySelectorAll('.tab-panel')].filter((el) => !el.hidden).map((el) => el.dataset.tab),
    sub: document.querySelector('.settings-item.is-current')?.textContent ?? null,
    subs: [...document.querySelectorAll('.settings-item')].map((el) => el.textContent),
    subHrefs: [...document.querySelectorAll('.settings-item')].map((el) => el.getAttribute('href')),
    subPanes: [...document.querySelectorAll('.settings-pane')].filter((el) => !el.hidden).map((el) => el.dataset.sub),
    changed: [...document.querySelectorAll('#fileList .file-changed')].map((el) => el.textContent),
    versions: [...document.querySelectorAll('#versionList li')].map((el) => el.textContent.replace(/\s+/g, ' ').trim()),
    names: [...document.querySelectorAll('#fileList .file-name')].map((el) => el.textContent),
    marks: [...document.querySelectorAll('#fileList use')].map((el) => el.getAttribute('href')),
    sizes: [...document.querySelectorAll('#fileList .size')].map((el) => el.textContent),
    current: document.querySelector('#fileList .file-name.is-current')?.textContent ?? null,
    fileLabel: document.getElementById('fileTabLabel').textContent,
    paneHidden: document.getElementById('fileOpen').hidden,
    boxes: [...document.querySelectorAll('.file-table')].filter((el) => el.offsetParent !== null).length,
    commit: document.querySelector('.file-commit').textContent.replace(/\s+/g, ' ').trim(),
    commitBg: getComputedStyle(document.querySelector('.file-commit')).backgroundColor,
    panes: [...document.querySelectorAll('.file-view')].filter((el) => !el.hidden).map((el) => el.dataset.file),
    rendered: document.querySelectorAll('.file-view:not([hidden]) .doc').length,
    sources: document.querySelectorAll('.file-view:not([hidden]) .file-source').length,
    /* Visible headings only: the ones this page keeps for structure are for screen readers, the
       document's own are inside it, and the missing-skill state is not on screen. */
    headings: [...document.querySelectorAll('#page-skill h2')]
      .filter((h) => !h.classList.contains('visually-hidden') && !h.closest('[hidden]') && !h.closest('.doc'))
      .map((h) => h.textContent.trim()),
  }))
}

const root = await skillAt('?page=skill')
check(
  'skill 页：根目录只列这一层的三项，文件夹带斜杠',
  root.names.join(',') === 'SKILL.md,references/,scripts/',
  root.names.join(','),
)
check(
  'skill 页：文件夹和文件不是同一个标记',
  root.marks.join(',') === '#fileMark,#folderMark,#folderMark',
  root.marks.join(','),
)
check('skill 页：文件夹没有大小，文件有', root.sizes.join('|') === '1.8 KB||', root.sizes.join('|'))
check(
  'skill 页：默认读根目录的 SKILL.md，并在列表里指出来',
  root.current === 'SKILL.md' && root.panes.join(',') === 'SKILL.md',
  `${root.current} / ${root.panes.join(',')}`,
)
check('skill 页：还没进目录时，标题就只有 skill 名', root.crumb === 'dev / pdf-tools', root.crumb)
check(
  'skill 页：列表和内容各是一个带边框的表',
  root.boxes === 2 && root.paneHidden === false,
  `${root.boxes} 个可见的表`,
)
check(
  'skill 页：表头一行写着版本、提交人和时间',
  root.commit === 'd dev 把 split 的范围语法写成文档 @1.2.3 · 2026/10/07 14:32',
  root.commit,
)
check(
  'skill 页：表头不靠底色区分，靠那条发丝线',
  root.commitBg === 'rgba(0, 0, 0, 0)',
  root.commitBg,
)
/* Nothing on this page announces itself in a heading: the two boxes say what they are in their own
   header rows, and the sharing panel is parked. */
check(
  'skill 页：没有一块用标题开场的区块',
  root.headings.length === 0,
  root.headings.join(',') || '（无）',
)

check(
  'skill 页：头一行是名字和「私有」',
  root.badge === '私有' && root.crumb === 'dev / pdf-tools',
  `${root.crumb} · ${root.badge}`,
)
check(
  'skill 页：版本那一行给出数量和地址',
  root.count === '3 个版本' && root.address === 'dev/pdf-tools',
  `${root.count} · ${root.address}`,
)
check(
  'skill 页：文件行的中间一列是「最后改它的版本 · 那天」',
  root.changed.join('|') === '@1.2.3 · 2026/10/07|@1.2.3 · 2026/10/07|@1.2.1 · 2026/09/12',
  root.changed.join('|'),
)
check(
  'skill 页：默认停在「文件」那一栏',
  root.tab === '文件' && root.panels.join(',') === 'files',
  `${root.tab} / ${root.panels.join(',')}`,
)

const versionsTab = await skillAt('?page=skill&tab=versions')
check(
  'skill 页：切到「版本」，文件树让位',
  versionsTab.tab === '版本' && versionsTab.panels.join(',') === 'versions',
  `${versionsTab.tab} / ${versionsTab.panels.join(',')}`,
)
check(
  'skill 页：版本表里就是头里数的那个数',
  versionsTab.versions.length === 3 && versionsTab.count === '3 个版本',
  `${versionsTab.versions.length} 行 · ${versionsTab.count}`,
)
check(
  'skill 页：一行一个版本，写着版本、状态、说明、谁在什么时候提交',
  versionsTab.versions[0] === '@1.2.3 线上 把 split 的范围语法写成文档 dev · 2026/10/07 14:32',
  versionsTab.versions[0],
)
check(
  'skill 页：地址里的分区不认识就落回「文件」',
  (await skillAt('?page=skill&tab=nope')).tab === '文件',
)

const inDir = await skillAt('?page=skill&path=references')
check('skill 页：进目录后只列这一层', inDir.names.join(',') === 'split.md', inDir.names.join(','))
check('skill 页：标题跟到这一层', inDir.crumb === 'dev / pdf-tools / references', inDir.crumb)
check(
  'skill 页：还没点文件，整个内容块都不出现',
  inDir.paneHidden && inDir.panes.length === 0,
  `隐藏=${inDir.paneHidden} 显示=${inDir.panes.length}`,
)

const reading = await skillAt('?page=skill&file=references/split.md')
check(
  'skill 页：读子目录里的文件时，列表仍是它所在的那一层',
  reading.names.join(',') === 'split.md' && reading.current === 'split.md',
  `${reading.names.join(',')} / ${reading.current}`,
)
check(
  'skill 页：下面的内容换成那个文件，表头写着它的路径',
  !reading.paneHidden && reading.panes.join(',') === 'references/split.md' && reading.fileLabel === 'references/split.md',
  `${reading.panes.join(',')} / ${reading.fileLabel}`,
)

const source = await skillAt('?page=skill&file=scripts/compress.sh')
check(
  'skill 页：不是 markdown 的文件按源码读，不按文档',
  source.rendered === 0 && source.sources === 1 && source.panes.join(',') === 'scripts/compress.sh',
  `文档 ${source.rendered} · 源码 ${source.sources}`,
)

/* The address is typed by hand as often as it is linked to. */
const nowhere = await skillAt('?page=skill&path=nowhere')
check(
  'skill 页：地址里的目录不存在就落回根目录',
  nowhere.names.length === 3 && nowhere.crumb === 'dev / pdf-tools',
  nowhere.crumb,
)

/* The third section: who may do what. Two questions the product keeps apart — visibility is about
   anybody, a grant is about somebody — so they are two pages under one tab, and that second level is
   an address like everything else here. */
const settings = await skillAt('?page=skill&tab=settings')
check(
  '设置：一级落在「设置」上，开着的也只有这一个分栏',
  settings.tab === '设置' && settings.panels.join(',') === 'settings',
  `${settings.tab} / ${settings.panels.join(',')}`,
)
check(
  '设置：二级不写就落在第一项「协作者」，也只开着一个二级页面',
  settings.sub === '协作者' && settings.subPanes.join(',') === 'collaborators' && settings.subs.length === 2,
  `${settings.sub} / ${settings.subPanes.join(',')} / ${settings.subs.join(',')}`,
)
check(
  '设置：默认私有，头上的标也是私有，而那个标通向「可见性」那一页',
  settings.checked === 'private' && settings.badge === '私有' &&
    settings.badgeHref === '?page=skill&tab=settings&sub=visibility',
  `${settings.checked} / ${settings.badge} / ${settings.badgeHref}`,
)
check(
  '设置：私有的时候五档都能授，而「所有者」不在其中',
  settings.roles.join(',') === '调用,浏览,写入,维护,管理',
  settings.roles.join(','),
)
check(
  '设置：二级菜单的第二项通向可见性那一页',
  settings.subHrefs[1] === '?page=skill&tab=settings&sub=visibility',
  settings.subHrefs.join(' '),
)
check(
  '设置：可见性的三档照梯子命名',
  settings.visTitles.join(',') === '私有,公开 · 调用,公开 · 浏览',
  settings.visTitles.join(','),
)

const opened = await skillAt('?page=skill&tab=settings&sub=visibility&vis=public-browse')
check(
  '设置：地址里的分栏把可见性那一页打开',
  opened.sub === '可见性' && opened.subPanes.join(',') === 'visibility' && opened.checked === 'public-browse',
  `${opened.sub} / ${opened.subPanes.join(',')} / ${opened.checked}`,
)
check('设置：公开之后头上是「公开」', opened.badge === '公开', opened.badge)
check(
  '设置：公开到「浏览」之后，能授的只剩比它高的三档——谁都能读了，再授「浏览」是空动作',
  opened.roles.join(',') === '写入,维护,管理',
  opened.roles.join(','),
)
check(
  '设置：公开之后，授在它盖住的那一档上的行自己说明不再需要',
  opened.grantValues[0] === 'browse' && /公开之后不再需要/.test(opened.grants[0]) &&
    !/不再需要/.test(opened.grants[1]),
  `${opened.grantValues.join(',')} | ${opened.grants.join(' | ')}`,
)
check(
  '设置：每一行的档位是一个控件，不是一个标签',
  settings.grantValues.join(',') === 'browse,write' &&
    settings.grantValues.length === 2 &&
    settings.grantOptions[0] === '调用/浏览/写入/维护/管理',
  `${settings.grantValues.join(',')} / ${settings.grantOptions.join(' ')}`,
)
check(
  '设置：公开盖住的那一行，控件里仍然留着他自己那一档——控件得说真话',
  opened.grantValues[0] === 'browse' && opened.grantOptions[0] === '浏览/写入/维护/管理',
  `${opened.grantValues[0]} / ${opened.grantOptions[0]}`,
)

const openedInvoke = await skillAt('?page=skill&tab=settings&sub=collaborators&vis=public-invoke')
check(
  '设置：公开到「调用」之后，能授的从「浏览」起',
  openedInvoke.roles.join(',') === '浏览,写入,维护,管理',
  openedInvoke.roles.join(','),
)
check(
  '设置：那句「不用再授了」是从梯子上算出来的，不是写死的',
  opened.hint.includes('公开之后「调用」、「浏览」不用再授了') &&
    openedInvoke.hint.includes('公开之后「调用」不用再授了'),
  `${opened.hint} | ${openedInvoke.hint}`,
)
check(
  '设置：「调用」那一档当场写出代价——没有目录，正文没点名的文件对它等于不存在',
  /它不止一个文件（SKILL.md、references\/split.md、scripts\/compress.sh）/.test(opened.warning ?? '') &&
    /正文一个文件名都没提/.test(opened.warning ?? ''),
  opened.warning,
)
check(
  '设置：地址里认不出的档落回私有',
  (await skillAt('?page=skill&tab=settings&sub=visibility&vis=nope')).checked === 'private',
)
check(
  '设置：地址里认不出的分栏落回「协作者」',
  (await skillAt('?page=skill&tab=settings&sub=nope')).sub === '协作者',
)

/* The one thing the second level must not do: walking to the other sub-page and back cannot quietly
   put the skill back to 私有. Both links carry the choice that is in the address right now. */
const carried = await skillAt('?page=skill&tab=settings&sub=visibility&vis=public-invoke')
check(
  '设置：两个分栏的链接都带着当下选的档，来回走不会把选择丢掉',
  carried.subHrefs.every((href) => href.endsWith('&vis=public-invoke')),
  carried.subHrefs.join(' '),
)

await page.goto(base + '?page=skill&tab=settings&sub=visibility', { waitUntil: 'networkidle0' })
await settle()
await page.click('input[value="public-invoke"]')
await settle()
const afterPick = decodeURIComponent(new URL(page.url()).search)
check(
  '设置：点一下就把可见性写进地址，头上的标跟着变',
  afterPick === '?page=skill&tab=settings&sub=visibility&vis=public-invoke' &&
    (await page.$eval('#skillBadge', (el) => el.textContent)) === '公开',
  afterPick,
)

/* Moving somebody up is the other half of handing out access — and the note that said their rung was
   redundant has to go with them when they leave the rungs the public already covers. */
await page.goto(base + '?page=skill&tab=settings&vis=public-browse', { waitUntil: 'networkidle0' })
await settle()
const movedFrom = await page.$eval('#grantList li', (el) => el.textContent.replace(/\s+/g, ' ').trim())
await page.select('#grantList li select', 'write')
await settle()
const moved = await page.evaluate(() => ({
  text: document.querySelector('#grantList li').textContent.replace(/\s+/g, ' ').trim(),
  value: document.querySelector('#grantList li select').value,
}))
check(
  '设置：把一个人往上挪一档，他那行「公开之后不再需要」跟着消失',
  /公开之后不再需要/.test(movedFrom) && !/公开之后不再需要/.test(moved.text) && moved.value === 'write',
  `${movedFrom} → ${moved.value}`,
)

/* And the walk itself: root → a folder → the file inside it → back up by the breadcrumb. */
await page.goto(base + '?page=skill', { waitUntil: 'networkidle0' })
await settle()
const hop = async (selector) => {
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }), page.click(selector)])
  return new URL(page.url()).search
}
const toDir = await hop('#fileList li:nth-child(2) .file-name')
const toFile = await hop('#fileList .file-name')
const backToRoot = await hop('.skill-ident h1 .name')
check('skill 页：点文件夹下到那一层', toDir === '?page=skill&path=references', toDir)
check('skill 页：点文件读到它', toFile === '?page=skill&file=references/split.md', toFile)
check('skill 页：标题里的 skill 名走回根目录', backToRoot === '?page=skill', backToRoot)

/* And the second level, which is walked the same way: the tab leads in, and the menu beside it
   moves between the two pages it holds. */
const intoSettings = await hop('.skill-tab[data-tab="settings"]')
const toVisibility = await hop('.settings-item[data-sub="visibility"]')
const backToCollaborators = await hop('.settings-item[data-sub="collaborators"]')
check('设置：一级分栏点得进来', intoSettings === '?page=skill&tab=settings', intoSettings)
check('设置：点二级菜单换页', toVisibility === '?page=skill&tab=settings&sub=visibility', toVisibility)
check('设置：点二级菜单也走得回去', backToCollaborators === '?page=skill&tab=settings&sub=collaborators', backToCollaborators)

/* The other half of the same requirement. The rows are given whatever height the frame has left
   after the head and the pager, so a window with room to spare gets taller rows — not the same rows
   with a hole under them, which is what a fixed row height leaves and what this section exists to
   catch. Compared against the 900px measurement above rather than against a number of its own. */
await page.setViewport({ width: 1440, height: 1200 })
await page.goto(base + '?page=skills', { waitUntil: 'networkidle0' })
await settle()
const taller = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('#skillList .skill')]
  const pager = document.querySelector('#skillPager').getBoundingClientRect()
  return {
    rows: rows.length,
    rowHeight: rows[0].getBoundingClientRect().height,
    gapBelowPager: Math.round(document.querySelector('.demo-bar').getBoundingClientRect().top - pager.bottom),
    overflow: document.documentElement.scrollHeight - window.innerHeight,
  }
})
check(
  '列表：窗口高出来的部分进到行里，不是留在页脚',
  taller.rows === 10 && taller.rowHeight > fits.rowHeight && taller.gapBelowPager <= 80 && taller.overflow <= 0,
  `1200px 窗口：行高 ${fits.rowHeight.toFixed(1)}px → ${taller.rowHeight.toFixed(1)}px，分页条下留 ${taller.gapBelowPager}px`,
)

/* And the thing all of the above exists for: the two columns end on one line. The sidebar's account
   block is pinned to the foot of its column and the pager is the foot of the content column, so the
   pair reads as one rule across the page. Measured at both heights, because what carries the pager
   down there is the row height, and the row height is the window's. */
const feet = {}
for (const height of [900, 1200]) {
  await page.setViewport({ width: 1440, height })
  await page.goto(base + '?page=skills', { waitUntil: 'networkidle0' })
  await settle()
  feet[height] = await page.evaluate(() => {
    const middle = (selector) => {
      const box = document.querySelector(selector).getBoundingClientRect()
      return (box.top + box.bottom) / 2
    }
    return middle('.pager .pager-count') - middle('.nav-signout')
  })
}
check(
  '列表：分页条和侧栏的「退出登录」在同一条水平线上',
  Object.values(feet).every((offset) => Math.abs(offset) <= 1.5),
  `900px 下差 ${feet[900].toFixed(1)}px，1200px 下差 ${feet[1200].toFixed(1)}px`,
)

await browser.close()

const failed = checks.filter((entry) => !entry.pass)
console.log(JSON.stringify({ src, checks, problems, failed: failed.length }, null, 2))
process.exit(failed.length > 0 || problems.length > 0 ? 1 : 0)
