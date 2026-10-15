// Walk every state the console demo can be in, shoot each one, and check the two things that are
// true of all of them.
//
//   node design/scripts/console-states.mjs "design/skillmaster-控制台演示.html" design/shots/console
//   node design/scripts/console-states.mjs "design/skillmaster-控制台演示.html" design/shots/console --only account
//
// **Run it on the page you changed, not on everything.** `--only` takes name prefixes, and `--list`
// prints the names. The full walk is for a direction being settled or a file about to be handed over.
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

const [src, outDir, ...rest] = process.argv.slice(2)
if (!src || !outDir) {
  console.error(
    'usage: console-states.mjs <draft.html> <out-dir> [--only <name-prefix>[,<...>]] [--list]',
  )
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
  /* `scrolls: true` since the top bar went in: the ten-rows crossing moved to 928px, so at 900 the
     list is a document — see the media query's own comment for the arithmetic. */
  { name: 'skills-ready', query: '?page=skills', page: 'skills', nav: '管理', scrolls: true },
  { name: 'skills-empty', query: '?page=skills&state=empty', page: 'skills', nav: '管理', scrolls: false },
  { name: 'skills-loading', query: '?page=skills&state=loading', page: 'skills', nav: '管理', scrolls: false },
  { name: 'skills-failed', query: '?page=skills&state=failed', page: 'skills', nav: '管理', scrolls: false },
  { name: 'skill-ready', query: '?page=skill', page: 'skill', nav: '管理', scrolls: true },
  { name: 'skill-missing', query: '?page=skill&state=missing', page: 'skill', nav: '管理', scrolls: false },
  { name: 'skill-settings-collaborators', query: '?page=skill&tab=settings', page: 'skill', nav: '管理', scrolls: false },
  { name: 'skill-settings-visibility', query: '?page=skill&tab=settings&sub=visibility&vis=public-invoke', page: 'skill', nav: '管理', scrolls: false },
  { name: 'account-ready', query: '?page=account', page: 'account', nav: '账号', scrolls: false },
  { name: 'account-anonymous', query: '?page=account&state=anonymous', page: 'account', nav: '账号', scrolls: false },
  { name: 'account-unknown', query: '?page=account&state=unknown', page: 'account', nav: '账号', scrolls: false },
  { name: 'keys-ready', query: '?page=keys', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  { name: 'keys-new', query: '?page=keys&state=new', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  { name: 'keys-empty', query: '?page=keys&state=empty', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  /* These two carry a key, because a moment with nothing to be about falls back to 正常 now — and the
     same pair is what the demo strip's 已创建 / 已续期 options put in the address. */
  { name: 'keys-created', query: '?page=keys&state=created&made-id=k6&made-name=演示密钥&made-scope=read&made-expires=2027-01-13&made-note=' + encodeURIComponent('从演示条打开的，不是真的签出来的'), page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  { name: 'keys-rotated', query: '?page=keys&state=rotated&rotated=k2', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  { name: 'keys-failed', query: '?page=keys&state=failed', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  /* The filter is an address, so both ends of it are worth a state: one slice, and the whole list. */
  { name: 'keys-filter-revoked', query: '?page=keys&filter=revoked', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
  /* The fullest state of this page: five rows, the four counts and the filter box. It fitted before
     the top bar and it fits again now — the bar added 48px of chrome and the panel gave 12 of them
     back by dropping a top margin it no longer needed. */
  { name: 'keys-filter-all', query: '?page=keys&filter=all', page: 'keys', nav: 'API 密钥', navParent: '权限', scrolls: false },
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

/**
 * **This walk is not meant to be run whole every time.** A change to one page is checked against that
 * page's own states; the full sweep is for a direction being settled, or before the file goes to
 * somebody. Running all of it after every edit is a minute of headful Chrome to re-learn what is
 * already known — and it makes the cheap check feel expensive, which is how a check stops being run.
 *
 * `--only` takes name prefixes: `--only account` means every state whose name starts with `account`,
 * and `--only account,skill-settings` means those plus that one.
 */
const onlyIndex = rest.indexOf('--only')
const only =
  onlyIndex < 0
    ? null
    : (rest[onlyIndex + 1] || '')
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean)

/* The groups, defined up here because the "matched nothing" guard below has to know about them: a
   `--only list` names a group and no state at all, and exiting on that would be the tool refusing a
   perfectly good request. Their guards and bodies are further down. */
const GROUPS = ['auth', 'keys', 'list', 'detail', 'settings', 'layout']
const selected = (name) => !only || only.some((prefix) => name.startsWith(prefix))

if (rest.includes('--list')) {
  SHOTS.forEach((shot) => console.log(`${shot.name}\t${shot.query}`))
  process.exit(0)
}

const shots = only ? SHOTS.filter((s) => only.some((p) => s.name.startsWith(p))) : SHOTS

if (only && shots.length === 0 && !GROUPS.some((name) => selected(name))) {
  console.error(
    `--only ${only.join(',')} matched no state and no group. ` +
      `--list shows the states; the groups are ${GROUPS.join(' ')}.`,
  )
  process.exit(1)
}

for (const shot of shots) {
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

  /* **The visible current item, and there must be exactly one.** `.nav-item.is-active` also matches
     the copy inside the 权限 flyout, which is `display:none` until the pointer is over it — reading
     the first match in document order meant this assertion passed for a reason nobody could see, and
     would have gone on passing if only the item actually on screen had lost its marking. */
  const navState = await page.evaluate(() => {
    const visible = (el) => el.offsetParent !== null
    const label = (el) => (el.querySelector('.nav-label') || el).textContent.trim()
    const items = [...document.querySelectorAll('.nav-item.is-active')].filter(visible)
    const childDrawn = items.some((el) => el.closest('.nav-children, .nav-flyout'))
    const parents = childDrawn
      ? []
      : [...document.querySelectorAll('.nav-disclosure.has-current-child')].filter(visible)
    return { marks: items.map(label).concat(parents.map(label)) }
  })
  check(
    `${shot.name}：导航当前项是「${shot.nav}」`,
    navState.marks.length === 1 &&
      (navState.marks[0] === shot.nav || navState.marks[0] === shot.navParent),
    navState.marks.length === 1 ? navState.marks[0] : `${navState.marks.length} 个可见的当前标记（${navState.marks.join('、')}）`,
  )

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

/**
 * The six groups, and why the guards look like this.
 *
 * Everything below is one of `auth` / `keys` / `list` / `detail` / `settings` / `layout` (the list is
 * `GROUPS`, above — this sentence said five until `keys` was added, which is exactly how a comment
 * about a list goes stale). A group that is not selected is skipped outright — its navigations, its
 * screenshots and its checks. **That is what makes a targeted run actually cheap.** Without it, a
 * `--only account` run still walked the other pages' states: a minute of headful Chrome to re-learn
 * what was already known, which is how a check stops being run at all.
 *
 * **The bodies are labelled blocks rather than `if (…) { … }`, and that is a choice about the diff.**
 * Re-indenting five hundred lines of the file that decides whether things work is the worst place to
 * make a large mechanical change. `break <label>` skips the block, the body does not move, and the
 * guard is one line at the top of each. The cost is that this reads as unusual — hence this note.
 *
 * The rule it serves: **a change is checked against the page it changed.** The whole walk is for a
 * direction being settled, or before the file goes to somebody.
 */
/* Printed because selection is by prefix and prefixes overlap — `--only skill` also takes
   `skills-ready`, which is a thing to see happen rather than to read about afterwards. */
console.log(
  `${only ? `--only ${only.join(',')}` : '全量'}：${shots.length} 个状态` +
    `（${shots.map((s) => s.name).join(' ')}）` +
    `，分组 ${GROUPS.filter((name) => selected(name)).join(' ') || '（无）'}`,
)

/* **`fits` is measured here, at the top, because two groups need it.** The list asserts it (ten rows
   above the strip on a 900px window) and the layout compares against it (a taller window must give
   taller rows). A value one group computes and another reads is exactly what the `--only` split would
   have broken **silently** — skipping `list` would have left `layout` reading an undefined variable,
   and the failure would have looked like a bug in the demo rather than in this file. It navigates for
   itself so neither group has to have run first. */
/* **Measured above the crossing, not at 900.** The top bar moved that crossing to 928px, so 900 is no
   longer a window in which ten rows are supposed to fit — measuring there would be asking the design
   to do something it no longer claims. */
await page.setViewport({ width: 1440, height: 960 })
await page.goto(base + '?page=skills', { waitUntil: 'networkidle0' })
await settle()
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

authGroup: {
  if (!selected('auth')) break authGroup

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

  /* **The eye swaps its icon, and for a while it could not.** `hidden` is a property of *HTML*
     elements; on an `<svg>` it is a plain expando, so `svg.hidden = true` changed nothing at all —
     the password unmasked and the icon stayed an open eye. Reading the *attribute* is what makes this
     assertion possible, and it is the same two icons the console's own secret field uses. */
  if (view.name === 'auth-login') {
    const eye = await page.evaluate(() => {
      const button = document.querySelector('#loginForm .reveal') ||
        document.querySelector('.reveal')
      const shown = (cls) => {
        const el = button.querySelector(cls)
        return { attr: el.hasAttribute('hidden'), display: getComputedStyle(el).display }
      }
      const before = { show: shown('.i-show'), hide: shown('.i-hide') }
      button.click()
      const after = { show: shown('.i-show'), hide: shown('.i-hide') }
      return { before, after, type: document.querySelector('#loginForm input[type="text"], #loginForm input[type="password"]').type }
    })
    check(
      '登录：眼睛图标真的换成划掉的那只',
      eye.after.show.display === 'none' && eye.after.hide.display !== 'none' &&
        eye.before.show.display !== 'none' && eye.before.hide.display === 'none' &&
        eye.type === 'text',
      `点之前 显示=${eye.before.show.display} 隐藏=${eye.before.hide.display}；点之后 显示=${eye.after.show.display} 隐藏=${eye.after.hide.display}；输入框 ${eye.type}`,
    )
  }

  await page.screenshot({ path: resolve(outDir, view.name + '.png') })
}

/* Signing out leaves the console for the dialog. */
await page.goto(base + '?page=skills', { waitUntil: 'networkidle0' })
/* The account lives behind the avatar in the top bar now, so signing out is two clicks. */
await page.click('#meBtn')
await settle()
/* **A check that would have caught the menu that could not open.** The avatar flipped its own
   `aria-expanded` and the panel stayed `display:none`, so 退出登录 was unreachable by mouse and by
   keyboard and this run died further down on an un-clickable node — naming a symptom, not the cause.
   The step below depends on the menu being open; this is the assertion that it is. */
check('头像菜单：点一下能开', await page.$eval('#meMenu', (el) => el.offsetParent !== null))
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

} /* authGroup */

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

keysGroup: {
  if (!selected('keys')) break keysGroup

  /* The credentials page had ten states and no interaction coverage at all, and that is exactly where
     three rounds of audit kept finding bugs. These are the assertions that would have caught them. */

  /* **A renewal re-dates the row it replaced.** Rotating an expired key used to change nothing: the
     moment announced a replacement and the list kept the old date, so the console's own answer to
     an expired key — the one the PRD points the CLI's error message at — was a dead end. */
  await page.goto(base + '?page=keys&filter=all&rotated=k4', { waitUntil: 'networkidle0' })
  await settle()
  const renewed = await page.$eval('#keyList li[data-key="k4"]', (row) => ({
    date: row.querySelector('.key-date').textContent.trim(),
    chip: row.querySelector('.chip').hidden ? '' : row.querySelector('.chip-text').textContent,
    used: row.querySelector('.key-used-text').textContent.trim(),
  }))
  check(
    '续期：那一行换成新的到期日，不再是过期的那把',
    renewed.date === '到期 2027/01/13' && renewed.chip === '' && renewed.used === '最后使用 从未使用',
    `${renewed.date}／${renewed.chip || '活跃'}／${renewed.used}`,
  )

  /* Two renewals are two facts, and the address keeps both — the second used to overwrite the first,
     so a key renewed a minute earlier went quietly back to its old date on the way out. */
  await page.goto(base + '?page=keys&filter=all&rotated=k2&rotated=k4', { waitUntil: 'networkidle0' })
  await settle()
  const both = await page.$$eval('#keyList li', (rows) =>
    rows.map((row) => row.dataset.key + ':' + row.querySelector('.key-date').textContent.trim()))
  check(
    '续期：两把各自续过，两条都在地址里',
    both.includes('k2:到期 2027/01/13') && both.includes('k4:到期 2027/01/13'),
    both.join('、'),
  )

  /* **The card says which key this moment is about.** A created key leaves its `made-*` family in the
     address and every link on the page carries it onward, so a renewal used to describe the *created*
     key under the renewed one's heading: right subject, wrong scope and wrong date. */
  await page.goto(
    base + '?page=keys&state=rotated&rotated=k3&made-id=k9&made-name=另一把&made-scope=write&made-expires=2026-12-24',
    { waitUntil: 'networkidle0' },
  )
  await settle()
  const moment = await page.evaluate(() => ({
    title: document.getElementById('keyRotatedTitle').textContent.trim(),
    facts: document.getElementById('keyMomentFacts').textContent.trim(),
  }))
  check(
    '续期那一刻：标题与卡片说的是同一把',
    moment.title === '「CI」已续期' && moment.facts === '权限 可读 · 到期 2027/01/13',
    `${moment.title} ｜ ${moment.facts}`,
  )

  /* **The icons are the state change, and `hidden` on an `<svg>` is a no-op.** The eye and the copy
     button both toggled a property no stylesheet reads, so neither icon ever moved. */
  await page.click('#keyReveal')
  await settle()
  const eyeIcons = await page.evaluate(() => ({
    show: getComputedStyle(document.querySelector('#keyReveal .i-show')).display,
    hide: getComputedStyle(document.querySelector('#keyReveal .i-hide')).display,
  }))
  check(
    '那一刻：眼睛图标真的换成划掉的那只',
    eyeIcons.show === 'none' && eyeIcons.hide !== 'none',
    `点之后 显示=${eyeIcons.show} 隐藏=${eyeIcons.hide}`,
  )
  await page.click('#keyCopy')
  await settle()
  const copyIcons = await page.evaluate(() => ({
    copy: getComputedStyle(document.querySelector('#keyCopy .i-copy')).display,
    copied: getComputedStyle(document.querySelector('#keyCopy .i-copied')).display,
    disabled: document.getElementById('keyDone').disabled,
  }))
  check(
    '那一刻：复制之后图标从拷贝变成勾，出口才开',
    copyIcons.copy === 'none' && copyIcons.copied !== 'none' && copyIcons.disabled === false,
    `拷贝=${copyIcons.copy} 勾=${copyIcons.copied} 出口禁用=${copyIcons.disabled}`,
  )

  /* **完成 lands on a list the key it just announced is actually on.** This one is created with
     today's date, which the form allows (`min` is today) and which makes it 两周内到期 — so a 完成 that
     merely dropped the filter landed on the 活跃 default and the key was nowhere on it, one screen
     after it was announced. The address says `filter=all`, which is the only way to say "no filter". */
  await page.goto(base + '?page=keys&state=new', { waitUntil: 'networkidle0' })
  await settle()
  await page.type('#keyName', '今天到期')
  await page.$eval('#keyExpiry', (el) => {
    el.value = el.min
    el.dispatchEvent(new Event('input', { bubbles: true }))
  })
  await page.click('#keySubmit')
  await settle()
  await page.click('#keyCopy')
  await settle()
  await page.click('#keyDone')
  await settle()
  const handedOver = await page.evaluate(() => ({
    url: location.search,
    named: [...document.querySelectorAll('#keyList li .key-name')].map((el) => el.textContent.trim()),
  }))
  check(
    '完成：刚建的那把就在落到的列表里（哪怕是两周内到期）',
    handedOver.named.includes('今天到期') && handedOver.url.includes('filter=all'),
    `${handedOver.url} ｜ ${handedOver.named.join('、')}`,
  )

  /* **Leaving a moment is allowed, and it is not silent.**
     The way out below the card states its own precondition and always did; the rest of the exits the
     console draws — the crumb above it, the navigation, the top bar's search, the sign-out button —
     were free, and a reader who took one of them lost a string that exists nowhere else. So this
     screen asks first, in that same block, in the shape every other irreversible thing here is asked
     in: the consequence, then two answers that say where they go. */
  const CREATED = '?page=keys&state=created&made-id=k7&made-name=面包屑&made-scope=read&made-expires=2027-01-13'
  const clickCrumb = () => page.evaluate(() => [...document.querySelectorAll('.crumbs a')]
    .find((a) => a.textContent.trim() === 'API 密钥').click())
  const answer = (label) => page.evaluate((wanted) => [...document.querySelectorAll('.key-exit .link-button')]
    .find((button) => button.textContent.trim() === wanted).click(), label)

  await page.goto(base + CREATED, { waitUntil: 'networkidle0' })
  await settle()
  await clickCrumb()
  await settle()
  const askedOnLeave = await page.evaluate(() => ({
    url: location.search,
    question: document.querySelector('.key-exit .hint')
      ? document.querySelector('.key-exit .hint').textContent.trim() : '',
    answers: [...document.querySelectorAll('.key-exit .link-button')]
      .map((button) => button.textContent.trim()).join('/'),
    exitHidden: document.getElementById('keyDone').hidden,
    focused: document.activeElement.textContent.trim(),
    secret: document.getElementById('keySecret').textContent.trim().slice(0, 4),
  }))
  check(
    '那一刻：从面包屑离开会先问一次，问句说后果、两个答案说自己去哪',
    askedOnLeave.url.includes('state=created') &&
      askedOnLeave.question.startsWith('这把密钥还没复制') &&
      askedOnLeave.answers === '仍然离开/留在这一屏' &&
      askedOnLeave.exitHidden && askedOnLeave.focused === '仍然离开' &&
      askedOnLeave.secret === 'skm_',
    `${askedOnLeave.question} ｜ ${askedOnLeave.answers} ｜ 焦点=${askedOnLeave.focused} ｜ 仍在 ${askedOnLeave.url}`,
  )

  /* Answering 留在这一屏 hands the exit block back, and the string with it — which is the whole point
     of asking rather than blocking: the reader may want to put it somewhere else first. */
  await page.keyboard.press('Escape')
  await settle()
  const stayed = await page.evaluate(() => ({
    answers: document.querySelectorAll('.key-exit .link-button').length,
    exitHidden: document.getElementById('keyDone').hidden,
    exitText: document.getElementById('keyDone').textContent.trim(),
    focused: document.activeElement.textContent.trim(),
    secret: document.getElementById('keySecret').textContent.trim().slice(0, 4),
  }))
  /* **And the focus comes back to the control that was pressed, not to the button in this block.**
     That button is disabled while the string is uncopied — a disabled control cannot take the focus —
     so handing it back there put the reader on `<body>` with no way to reach what they were doing.
     The assertion is on the crumb because that is what the click was. */
  check(
    '那一刻：Esc 回答「留在这一屏」，出口、整串与焦点都还在',
    stayed.answers === 0 && !stayed.exitHidden && stayed.exitText === '复制后方可完成' &&
      stayed.focused === 'API 密钥' && stayed.secret === 'skm_',
    `答案还在=${stayed.answers} 出口「${stayed.exitText}」禁用=${stayed.exitHidden} 焦点=${stayed.focused}`,
  )

  /* **The breadcrumb is still this screen's parent, not a way somewhere else.** It was a literal
     `?page=keys`, which on a moment means a second exit that silently drops the key the screen just
     handed over. It now sends the reader the same way 完成 goes — one answer further along. */
  await clickCrumb()
  await settle()
  await answer('仍然离开')
  await settle()
  const viaCrumb = await page.evaluate(() => ({
    url: location.search,
    named: [...document.querySelectorAll('#keyList li .key-name')].map((el) => el.textContent.trim()),
  }))
  check(
    '那一刻：面包屑与完成去同一处，刚建的那把跟着走',
    viaCrumb.named.includes('面包屑') && viaCrumb.url.includes('filter=all') && !viaCrumb.url.includes('state='),
    `${viaCrumb.url} ｜ ${viaCrumb.named.join('、')}`,
  )

  /* **A second exit while the question stands is held too, and the question moves to it.** Waved
     through — which is what this guard did first — the reader presses another exit with the question
     already up and leaves with the string and no answer. */
  await page.goto(base + CREATED, { waitUntil: 'networkidle0' })
  await settle()
  await clickCrumb()
  await settle()
  await page.evaluate(() => [...document.querySelectorAll('.nav-item')]
    .find((item) => item.textContent.trim() === '账号').click())
  await settle()
  const retargeted = await page.evaluate(() => ({
    url: location.search,
    answers: document.querySelectorAll('.key-exit .link-button').length,
    focused: document.activeElement.textContent.trim(),
  }))
  check(
    '那一刻：问题挂着时再按另一个出口，也不会被放行',
    retargeted.url.includes('state=created') && retargeted.answers === 2 && retargeted.focused === '仍然离开',
    `${retargeted.url} ｜ 答案 ${retargeted.answers} 个 ｜ 焦点=${retargeted.focused}`,
  )
  /* And the gesture it replays is the one pressed last: the navigation, not the crumb. */
  await page.evaluate(() => [...document.querySelectorAll('.key-exit .link-button')]
    .find((button) => button.textContent.trim() === '仍然离开').click())
  await settle()
  const leftByNav = await page.evaluate(() => location.search)
  check(
    '那一刻：答「仍然离开」走的是最后按的那个出口',
    leftByNav.includes('page=account') && !leftByNav.includes('state=created'),
    leftByNav,
  )

  /* The navigation is the exit a reader is most likely to take, and it is a link like the crumb. */
  await page.goto(base + CREATED, { waitUntil: 'networkidle0' })
  await settle()
  await page.evaluate(() => [...document.querySelectorAll('.nav-item')]
    .find((item) => item.textContent.trim() === '账号').click())
  await settle()
  const askedOnNav = await page.evaluate(() => ({
    url: location.search,
    answers: document.querySelectorAll('.key-exit .link-button').length,
  }))
  check(
    '那一刻：左侧导航也先问一次，没有被绕过',
    askedOnNav.url.includes('state=created') && askedOnNav.answers === 2,
    `${askedOnNav.url} ｜ 答案 ${askedOnNav.answers} 个`,
  )

  /* **And once the string is in the reader's hands there is nothing to ask about.** The guard stands
     down on the copy, not on the answer: asking about a string somebody already has would be the
     question nagging rather than the question informing. */
  await page.goto(base + CREATED, { waitUntil: 'networkidle0' })
  await settle()
  await page.click('#keyCopy')
  await settle()
  await clickCrumb()
  await settle()
  const afterCopy = await page.evaluate(() => ({
    url: location.search,
    answers: document.querySelectorAll('.key-exit .link-button').length,
    named: [...document.querySelectorAll('#keyList li .key-name')].map((el) => el.textContent.trim()),
  }))
  check(
    '那一刻：整串拿走之后，离开不再问',
    afterCopy.answers === 0 && afterCopy.named.includes('面包屑'),
    `${afterCopy.url} ｜ 答案 ${afterCopy.answers} 个`,
  )

  /* **The counts are of the searched list**, so a card can be clicked to the very rows it counted. */
  await page.goto(base + '?page=keys&q=CI', { waitUntil: 'networkidle0' })
  await settle()
  const searched = await page.evaluate(() => ({
    cards: [...document.querySelectorAll('.stat')].map((s) =>
      s.querySelector('.stat-number').textContent + '@' + s.getAttribute('href')),
    rows: document.querySelectorAll('#keyList li').length,
  }))
  check(
    '计数：搜索也算在内，而且格子带着搜索走',
    searched.cards.every((card) => card.split('@')[1].includes('q=CI')) &&
      searched.cards.reduce((sum, card) => sum + Number(card.split('@')[0]), 0) === searched.rows,
    searched.cards.join(' ') + ` ｜ 列表 ${searched.rows} 行`,
  )

  /* 取消 has to hand the focus back: answering it rebuilds the row, and without that the reader lands
     on `<body>` with no way back to the control they were on. */
  await page.goto(base + '?page=keys', { waitUntil: 'networkidle0' })
  await settle()
  await page.click('#keyList li[data-key="k3"] [data-act="revoke"]')
  await settle()
  /* The prompt's own tell is `is-warn` on the row's state cell — the question has replaced it. Asking
     it first is what keeps the assertion below from passing because nothing ever opened. */
  const asked = await page.$eval('#keyList li[data-key="k3"] .key-state', (cell) => cell.classList.contains('is-warn'))
  await page.keyboard.press('Escape')
  await settle()
  const back = await page.evaluate(() => {
    const active = document.activeElement
    return {
      stillAsking: !!document.querySelector('#keyList li[data-key="k3"] .key-state.is-warn'),
      act: active.dataset ? active.dataset.act : null,
      row: active.closest && active.closest('li') ? active.closest('li').dataset.key : null,
    }
  })
  check(
    '撤销的提示：Esc 回答取消，焦点回到原来那个按钮',
    asked && !back.stillAsking && back.act === 'revoke' && back.row === 'k3',
    `问了=${asked} 取消后还在问=${back.stillAsking} 焦点=${back.act || '无'}@${back.row || '无'}`,
  )
}

listGroup: {
  if (!selected('list')) break listGroup

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
   the bottom, on a 900px window, with no scrollbar anywhere. **The measurement itself is at the top of
   the file**, because the layout group reads the same number. */
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

} /* listGroup */

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

detailGroup: {
  if (!selected('detail')) break detailGroup

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
/* **This assertion used to demand the opposite**, and the flip is the point: it read 「表头不靠底色
   区分，靠那条发丝线」 and checked for a transparent background. That was right while the console
   drew no surfaces at all — and it is exactly the rule that made the page read as unfinished, because
   a table whose head is the same colour as its rows has no head. The header is filled now (2026-10-15,
   the console moving onto the product's own palette), so the check says the new thing. **A check that
   pins a decision has to be flipped when the decision is** — otherwise the next person reads the
   failure as a bug and reverts the design. */
check(
  'skill 页：表头靠底色与下面的行分开',
  root.commitBg === 'rgb(243, 245, 247)',
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

} /* detailGroup */

settingsGroup: {
  if (!selected('settings')) break settingsGroup

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

} /* settingsGroup */

layoutGroup: {
  if (!selected('layout')) break layoutGroup

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
  /* **The bound is 40, and the measured value is 27.** It used to be 80 — three times what the layout
     actually leaves, which is wide enough to pass while the pager floats in exactly the empty band
     this assertion exists to catch. A bound is only an assertion if the thing it measures is near it. */
  taller.rows === 10 && taller.rowHeight > fits.rowHeight && taller.gapBelowPager <= 40 && taller.overflow <= 0,
  `1200px 窗口：行高 ${fits.rowHeight.toFixed(1)}px → ${taller.rowHeight.toFixed(1)}px，分页条下留 ${taller.gapBelowPager}px`,
)

/* **The property this pins is what the shell is made of**: the navigation column and the panel beside
   it end on the same line, so the two read as one object rather than as two boxes of different
   heights.

   It used to compare the pager with the account block, which lived in the sidebar's foot. **The
   account moved to the top bar** (2026-10-15, the layout change), which left the sidebar with no foot
   — so the comparison moved to the two columns' own edges. The property did not disappear, only its
   subject did.

   Measured at both heights, because what carries the panel's foot down there is the row height, and
   the row height is the window's. */
const feet = {}
/* **Above the crossing, for the same reason `fits` is**: the property only holds while the page fills
   its row. When the content is taller than the row — a scrolling document — the panel grows with the
   content while the navigation stays pinned to the window, and the two bottoms part company. That is
   what a scrolling page is supposed to look like. */
for (const height of [960, 1200]) {
  await page.setViewport({ width: 1440, height })
  await page.goto(base + '?page=skills', { waitUntil: 'networkidle0' })
  await settle()
  feet[height] = await page.evaluate(() => {
    const bottom = (selector) => document.querySelector(selector).getBoundingClientRect().bottom
    return bottom('.sidenav') - bottom('.content')
  })
}
check(
  '列表：导航栏与正文面板在同一个高度收底',
  Object.values(feet).every((offset) => Math.abs(offset) <= 1.5),
  `960px 下差 ${feet[960].toFixed(1)}px，1200px 下差 ${feet[1200].toFixed(1)}px`,
)

} /* layoutGroup */

await browser.close()

const failed = checks.filter((entry) => !entry.pass)
console.log(JSON.stringify({ src, checks, problems, failed: failed.length }, null, 2))
process.exit(failed.length > 0 || problems.length > 0 ? 1 : 0)
