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
import { createInterface } from 'node:readline/promises'
import { createRequire } from 'node:module'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

const rawArgs = process.argv.slice(2)
/* **Windowless by default; `--headful` when a window is wanted.** Measured 2026-10-16: windowless
   costs about 20% of the wall clock (9.7–10.4s against 11.9–12.6s, three runs each) and renders the
   same page to within anti-aliasing — except the scrollbar, which a windowless run does not lay
   out. Nothing in *this* file is about that scrollbar (the console has no scroll-locking overlay),
   so no assertion here is skipped; the summary still says which mode produced it. */
const HEADFUL = rawArgs.includes('--headful')
const [src, outDir, ...rest] = rawArgs.filter((a) => a !== '--headful')
if (!src || !outDir) {
  console.error(
    'usage: console-states.mjs <draft.html> <out-dir> [--only <name-prefix>[,<...>]] [--list] [--headful]',
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
  /* `?page=skill` 落在「信息」——那一屏一屏装得下（内容块在没点文件时不出现），所以它不滚动。 */
  { name: 'skill-ready', query: '?page=skill', page: 'skill', nav: '管理', scrolls: false },
  /* **文件栏**：这一版的文件清单与提交行。提交行右端的版本名是进这一版的链接（2026-10-20 定）。 */
  { name: 'skill-files', query: '?page=skill&tab=files', page: 'skill', nav: '管理', scrolls: true },
  { name: 'skill-versions', query: '?page=skill&tab=versions', page: 'skill', nav: '管理', scrolls: false },
  /* **过滤与搜索什么都没匹配上。** 一句话 + 一个清除筛选，不是一张空白卡片（2026-10-20 定）。 */
  { name: 'skill-versions-empty', query: '?page=skill&tab=versions&filter=live&q=zzz', page: 'skill', nav: '管理', scrolls: false },
  /* **版本 tab 的单版页。** 版本 tab 是唯一清单，而且只导航；地址钉住一版时，它从清单换成
     那一版，所有改变版本状态的动作都在那一版的「操作」栏里（ADR 0054）。图按三栏分开：
     概览（线上）、操作（草稿的发布 + 丢弃、线上回滚、历史版本的上线 / 下架、
     已下架的重新上架）、改动（线上对照上一版、草稿对照线上），以及不存在的版本钉。 */
  { name: 'version-overview', query: '?page=skill&tab=versions&version=1.2.3', page: 'skill', nav: '管理', scrolls: true },
  /* **草稿的概览。** 地址带着它的 8 位短码；右侧事实栏的「地址」是唯一能整串复制进 CLI 的一行。 */
  { name: 'version-draft', query: '?page=skill&tab=versions&version=7K3M9QX2', page: 'skill', nav: '管理', scrolls: true },
  { name: 'version-publish', query: '?page=skill&tab=versions&version=7K3M9QX2&sub=actions', page: 'skill', nav: '管理', scrolls: true },
  { name: 'version-rollback', query: '?page=skill&tab=versions&version=1.2.3&sub=actions', page: 'skill', nav: '管理', scrolls: false },
  { name: 'version-changes', query: '?page=skill&tab=versions&version=1.2.3&sub=diff', page: 'skill', nav: '管理', scrolls: true },
  { name: 'version-changes-draft', query: '?page=skill&tab=versions&version=7K3M9QX2&sub=diff', page: 'skill', nav: '管理', scrolls: true },
  /* **清单上方的筛选**：按路径收窄左边那份清单，右栏不动（2026-10-20 定）。 */
  { name: 'version-changes-filtered', query: '?page=skill&tab=versions&version=7K3M9QX2&sub=diff&fq=split', page: 'skill', nav: '管理', scrolls: true },
  /* **大改动**：清单先到、内容按需、单文件分段（2026-10-20 定）。三张图分别是首屏（正文
     一个都没取）、单文件模式里一个文件加载后（124 个文件 / 3,200 行的长文件）与只看文件列表。 */
  { name: 'version-big', query: '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&load=1', page: 'skill', nav: '管理', scrolls: true },
  { name: 'version-big-pending', query: '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&manual=1', page: 'skill', nav: '管理', scrolls: true },
  { name: 'version-big-list', query: '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&list=1', page: 'skill', nav: '管理', scrolls: true },
  { name: 'version-revoke', query: '?page=skill&tab=versions&version=1.2.2&sub=actions', page: 'skill', nav: '管理', scrolls: false },
  { name: 'version-revoked', query: '?page=skill&tab=versions&version=1.2.1&sub=actions', page: 'skill', nav: '管理', scrolls: false },
  { name: 'version-missing', query: '?page=skill&tab=versions&version=nope', page: 'skill', nav: '管理', scrolls: false },
  /* 只有草稿、从没上线过：页头与信息栏都说未上线。演示件用 `no-live=1` 造出这一态。 */
  { name: 'skill-no-live', query: '?page=skill&tab=files&no-live=1', page: 'skill', nav: '管理', scrolls: true },
  /* **整个 skill 下线。** 版本与指针都不动；页头多一枚「已下线」，设置页危险区第一行
     变成「恢复服务」。 */
  { name: 'skill-offline', query: '?page=skill&tab=settings&sub=general&offline=1', page: 'skill', nav: '管理', scrolls: true },
  { name: 'skill-missing', query: '?page=skill&state=missing', page: 'skill', nav: '管理', scrolls: false },
  { name: 'skill-settings-collaborators', query: '?page=skill&tab=settings&sub=collaborators', page: 'skill', nav: '管理', scrolls: true },
  /* 可见性三张卡 + 危险区在 960 高的窗口里放不下，这一页本来就滚。 */
  { name: 'skill-settings-visibility', query: '?page=skill&tab=settings&sub=general&vis=public-invoke', page: 'skill', nav: '管理', scrolls: true },
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
  /* 登录设备：与 API 密钥同一个父项（参照产品的 `Access` 组装的也是这两类东西）。 */
  { name: 'sessions-ready', query: '?page=sessions', page: 'sessions', nav: '登录设备', navParent: '权限', scrolls: false },
  { name: 'sessions-empty', query: '?page=sessions&state=empty', page: 'sessions', nav: '登录设备', navParent: '权限', scrolls: false },
  { name: 'sessions-failed', query: '?page=sessions&state=failed', page: 'sessions', nav: '登录设备', navParent: '权限', scrolls: false },
]

// Headful, like the other two scripts: a state screenshot should be the state the person sees.
const browser = await puppeteer.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: !HEADFUL,
  args: [
    '--no-sandbox',
    '--force-color-profile=srgb',
    '--window-size=1440,1000',
    '--window-position=0,0',
  ],
  defaultViewport: { width: 1440, height: 900, deviceScaleFactor: 1 },
})

const page = await browser.newPage()

/* **Wait for the page to stop moving, rather than for 250ms to pass.**
   Same reasoning as `interaction-check.mjs`, and the same measurement behind it: the two suites were
   spending 85 of their 126 seconds waiting on fixed sleeps and `networkidle0` navigations. What this
   is for has not changed — every assertion below wants a page that has finished reacting.
   **Infinite animations are skipped, not waited for**: the `loading` states carry spinners, and a
   spinner never finishes. It is also not a layout change, so two frames is the honest answer.
   The cap is the old 250ms doubled; reaching it means something is animating that never ends. */
const navigating = (error) =>
  /Execution context was destroyed|Cannot find context of any|Target closed/.test(String(error && error.message))
/* **A page that navigates while we are looking at it.** Several actions here are answers that
   navigate (`window.location.search = …`): the key screens' exits, the breadcrumb, the walk. A
   `waitForNavigation` at the call site catches the *first* hop only — pressing submit on the login
   screen makes two — and anything running inside the old document then dies with "execution context
   was destroyed". So the wait to look at the page is spelled in two halves: can it be read at all
   (retrying across a navigation), and has it stopped moving. */
const readable = async (timeout = 4000) => {
  const deadline = Date.now() + timeout
  for (;;) {
    try {
      if ((await page.evaluate(() => document.readyState)) === 'complete') return
    } catch (error) {
      if (!navigating(error)) throw error
    }
    if (Date.now() > deadline) throw new Error('the page never became readable: ' + page.url())
    await new Promise((done) => setTimeout(done, 25))
  }
}
const settled = (target) =>
  target.evaluate(async () => {
    if (document.fonts && document.fonts.ready) await document.fonts.ready
    const running = document.getAnimations().filter((animation) => {
      if (animation.playState === 'finished') return false
      const timing = animation.effect && animation.effect.getComputedTiming
      return !(timing && animation.effect.getComputedTiming().iterations === Infinity)
    })
    if (running.length > 0) {
      await Promise.race([
        Promise.allSettled(running.map((animation) => animation.finished)),
        new Promise((done) => setTimeout(done, 700)),
      ])
    }
    await new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done)))
  }).catch((error) => {
    /* The document we were waiting on was replaced mid-wait. The new one has no animation of ours
       to wait for, and the assertion after this reads it — so this is "done", not "failed". */
    if (!navigating(error)) throw error
  })
const settle = async () => {
  await readable()
  await settled(page)
}
/* **确认是一次模拟的请求（600ms）。** 等它给出答案：弹窗关掉（成功），或失败写进弹窗、
按钮复原（失败）。等待态本身是那一刻的读数，断言在点击后立刻取。 */
const confirmOutcome = async () => {
  await until(() => {
    const dialog = document.getElementById('dangerConfirm')
    if (!dialog || dialog.hidden) return true
    const error = document.getElementById('dangerConfirmError')
    return !!error && !error.hidden &&
      !document.getElementById('dangerConfirmAccept').disabled
  })
  return page.evaluate(() => {
    const dialog = document.getElementById('dangerConfirm')
    return {
      open: !dialog.hidden,
      error: dialog.hidden ? '' : document.getElementById('dangerConfirmError').textContent,
      accept: document.getElementById('dangerConfirmAccept').textContent,
      acceptDisabled: document.getElementById('dangerConfirmAccept').disabled,
      cancelDisabled: document.getElementById('dangerConfirmCancel').disabled,
      focus: document.activeElement?.id ?? null,
    }
  })
}
/* 档位下拉是自己画的，不是原生 `<select>`：先点开，再点那一项。 */
const pickRung = async (slot, value) => {
  await page.click(slot + ' .menu-trigger')
  await settle()
  await page.click(`${slot} .menu-item[data-value="${value}"]`)
  await settle()
}
/** The address the page navigates to by itself. Polled from Node — `page.url()` is read off the
    frame tree over CDP rather than out of the document — so a navigation landing mid-wait cannot
    destroy this the way it destroys an `evaluate`. */
/** Same shape as the one in `interaction-check.mjs`: when the answer arrives on a timer, wait for
    the answer. Serialised into the page, so the wanted value is passed rather than closed over. */
const until = (test, ...args) => page.waitForFunction(test, { timeout: 4000, polling: 'raf' }, ...args)
const atAddress = async (search, timeout = 4000) => {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (new URL(page.url()).search === search) return
    await new Promise((done) => setTimeout(done, 25))
  }
  throw new Error(`the page never reached ${search} — it is at ${page.url()}`)
}
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
const GROUPS = ['auth', 'keys', 'sessions', 'list', 'detail', 'settings', 'layout']
const selected = (name) => !only || only.some((prefix) => name.startsWith(prefix))

if (rest.includes('--list')) {
  SHOTS.forEach((shot) => console.log(`${shot.name}\t${shot.query}`))
  process.exit(0)
}

/* **A full walk needs a person's say-so** (rule set 2026-10-16, the owner's). `--only` runs the page
   somebody changed; **no `--only` runs every state**, and that is the act this gate is
   about. There are two ways through it and only two: **a terminal**, where it prints what it is
   about to do and asks once, or **not running it**. With no TTY — a script, an agent — it refuses
   outright rather than assuming an answer, because the whole point is that the decision is the
   owner's and silence is not consent. Exit code 3 on both refusals: a run that produced no checks
   is not a pass. The repository rule this enforces is in CLAUDE.md. */
/* **`DESIGN_CHECKS_APPROVED` is the hand-off from `check-all.mjs`, which asks once and then runs
   both suites.** It skips the question *here* because it was already asked — and nothing more: with
   it absent (every other way of running this file) the gate below is unchanged. **Setting it by hand
   is exactly the bypass this gate exists to prevent**, so don't; the rule is in CLAUDE.md. */
const APPROVED_ELSEWHERE = process.env.DESIGN_CHECKS_APPROVED === '1'

if (!only && !APPROVED_ELSEWHERE) {
  if (!process.stdin.isTTY) {
    console.error(
      '全量走查要人在终端里确认，而这里没有终端——拒绝执行（2026-10-16 定）。\n' +
        `  改哪一页就跑哪一页：--only <组>，组是 ${GROUPS.join(' / ')}；\n` +
        '  真要跑全套，请在终端里跑，那里会问你一次。',
    )
    process.exit(3)
  }
  const ask = createInterface({ input: process.stdin, output: process.stdout })
  let answer = ''
  try {
    answer = await ask.question(
      `将跑全部 ${SHOTS.length} 个状态、${GROUPS.length} 个分组。跑吗？[y/N] `,
    )
  } catch {
    /* Stdin closed before it answered — a pipe that ran out, a terminal that went away. That is
       not a yes, and it is not a crash either: it is the answer this gate treats as "no". */
    answer = ''
  }
  ask.close()
  if (!/^y(es)?$/i.test(answer.trim())) {
    console.error('没有批准，没有跑任何检查。')
    process.exit(3)
  }
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
  await page.goto(base + shot.query, { waitUntil: 'load' })
  await settle()

  /* A state that only exists after an interaction opens it here, before the assertions and the
     picture — the same address, plus the one click (or two) that reaches it. */
  if (shot.open) {
    await page.evaluate(shot.open)
    await settle()
  }

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
  `${HEADFUL ? '有窗口' : '无窗口'} · ${only ? `--only ${only.join(',')}` : '全量'}：${shots.length} 个状态` +
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
await page.goto(base + '?page=skills', { waitUntil: 'load' })
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
  await page.goto(base + view.query, { waitUntil: 'load' })
  await settle()

  /* **The focus is a timer, not a settling.** Opening a view focuses its first control from inside
     the partial's own `setTimeout(…, 70)`, so "the page has stopped moving" is exactly the wrong
     thing to wait for here — at 70ms the two are a coin toss. This waits for what the assertion
     below is about, which is also how the other timed answers in this file are handled. */
  await until(
    (wanted) => document.activeElement && document.activeElement.id === wanted,
    view.focus,
  )
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
await page.goto(base + '?page=skills', { waitUntil: 'load' })
await settle()
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
await page.goto(base + '?page=login', { waitUntil: 'load' })
await settle()
await type('#phone', '13800138000')
await type('#password', 'skillmaster')
/* **Two navigations, so wait for the address rather than for "a navigation".** Pressing submit runs
   the form's own submit first — the login screen reloading itself as `?page=login` — and the
   handler's `location.assign` second (`?page=skills`), one millisecond apart. `waitForNavigation`
   returns after the *first* one, so whatever runs inside the page next (a `settle`, an `evaluate`)
   dies with "execution context was destroyed" when the second lands. That is also why a 250ms sleep
   in Node used to cover this: it happened to be long enough for the second hop. */
await page.click('#submitBtn')
await atAddress('?page=skills')
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
  await page.goto(base + query, { waitUntil: 'load' })
  await settle()
  return page.evaluate(() => ({
    names: [...document.querySelectorAll('#skillList .skill-name')].map((el) => el.textContent),
    calls: [...document.querySelectorAll('#skillList .skill-calls')].map((el) => el.textContent),
    /* The other list. It is a second list under the same header, not a filter on this one, so it is
       read through its own rows. */
    gone: [...document.querySelectorAll('#deletedList .skill-name')].map((el) => el.textContent),
    goneWhen: [...document.querySelectorAll('#deletedList .skill-updated')].map((el) => el.textContent),
    goneRestore: document.querySelector('#deletedList .restore')?.textContent ?? null,
    goneNone: !document.getElementById('deletedNone').hidden,
    tabs: [...document.querySelectorAll('#listTabs .skill-tab')].map((el) => el.textContent),
    tabCurrent: document.querySelector('#listTabs .skill-tab.is-current')?.textContent ?? null,
    count: document.getElementById('skillCount').textContent.trim(),
    none: !document.getElementById('skillNone').hidden,
    pager: document.getElementById('skillPager').textContent.replace(/\s+/g, ' ').trim(),
    current: document.querySelector('#skillPager .is-current')?.textContent ?? null,
    sort: document.querySelector('#skillSortSlot .menu-trigger')?.dataset.value ?? null,
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
  await page.goto(base + '?page=keys&filter=all&rotated=k4', { waitUntil: 'load' })
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
  await page.goto(base + '?page=keys&filter=all&rotated=k2&rotated=k4', { waitUntil: 'load' })
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
    { waitUntil: 'load' },
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
  await page.goto(base + '?page=keys&state=new', { waitUntil: 'load' })
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

  await page.goto(base + CREATED, { waitUntil: 'load' })
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
  await page.goto(base + CREATED, { waitUntil: 'load' })
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
  await page.goto(base + CREATED, { waitUntil: 'load' })
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
  await page.goto(base + CREATED, { waitUntil: 'load' })
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
  await page.goto(base + '?page=keys&q=CI', { waitUntil: 'load' })
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
  await page.goto(base + '?page=keys', { waitUntil: 'load' })
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

sessionsGroup: {
  if (!selected('sessions')) break sessionsGroup

  /* 一屏新页，而且它与密钥页**共用同一套「先问后果」的确认**——所以这里钉住的不只是"画出来了"，
     还有那套确认在这一页上的行为：后果落在哪、答案接在哪、Esc 之后焦点回到哪。 */

  await page.goto(base + '?page=sessions', { waitUntil: 'load' })
  await settle()

  const crumbs = await page.$$eval('#page-sessions .crumbs > *', (nodes) =>
    nodes
      .filter((node) => !node.classList.contains('crumbs-sep'))
      .map((node) => node.textContent.trim() + (node.getAttribute('aria-current') === 'page' ? '*' : '')))
  check('登录设备：面包屑是 设置 / 登录设备，后者是当前项', crumbs.join(' / ') === '设置 / 登录设备*', crumbs.join(' / '))

  const nav = await page.evaluate(() => {
    const child = document.querySelector('#navAccessChildren .nav-item[data-page="sessions"]')
    const flyout = document.querySelector('.nav-flyout .nav-item[data-page="sessions"]')
    const disclosure = document.getElementById('navAccess')
    return {
      childActive: !!child && child.classList.contains('is-active'),
      flyoutCopy: !!flyout,
      disclosureSpeaks: disclosure.getAttribute('aria-current') === 'true',
      disclosureItself: disclosure.classList.contains('is-active'),
    }
  })
  check(
    '登录设备：在「权限」下、两份副本都在，而发声的是那个可展开项',
    nav.childActive && nav.flyoutCopy && nav.disclosureSpeaks && !nav.disclosureItself,
    `就地展开的副本活跃=${nav.childActive} 浮层副本=${nav.flyoutCopy} 可展开项发声=${nav.disclosureSpeaks}`,
  )

  const rows = await page.$$eval('#sessionList li', (els) =>
    els.map((el) => ({
      ip: el.querySelector('.session-ip').textContent.trim(),
      /* **下面那一行是一句话**（2026-10-16 起「什么设备　·　登录于 …　·　最近活动 …」并成了一行），
         所以整行读回来再判，而不是取三个格子。 */
      meta: el.querySelector('.session-meta').textContent.trim(),
      here: !el.querySelector('.session-here').hidden,
      /* **可见的**胶囊才算：模板里那个「当前设备」对每一行都在 DOM 里，靠 `hidden` 关掉。 */
      chips: [...el.querySelectorAll('.chip')].filter((chip) => !chip.hidden).length,
      kickable: !el.querySelector('[data-act="kick"]').hidden,
      id: el.dataset.session,
    })))
  const here = rows.filter((row) => row.here)
  check('登录设备：四行，其中一行标着「当前设备」', rows.length === 4 && here.length === 1, `${rows.length} 行／${here.length} 行标着当前设备`)
  check(
    '登录设备：当前这一台没有退出的控件，另外两台有',
    here.length === 1 && here[0].kickable === false && rows.filter((row) => !row.here).every((row) => row.kickable),
    rows.map((row) => `${row.ip}:${row.kickable ? '可退出' : '无控件'}`).join('、'),
  )
  check(
    '登录设备：地址完整显示，不打码',
    rows.every((row) => /^\d{1,3}(\.\d{1,3}){3}$/.test(row.ip)),
    rows.map((row) => row.ip).join('、'),
  )
  check(
    '登录设备：下面那一行说清了是什么、什么时候登进来、还在不在用',
    rows.every((row) => {
      const signedIn = row.meta.indexOf('　·　登录于 ')
      const seen = row.meta.indexOf('　·　最近活动 ')
      return signedIn > 0 && seen > signedIn
    }),
    rows[0].meta,
  )
  /* **两类登录同屏**（2026-10-16 起）：浏览器里的一次（第二段 `Chrome · macOS`）与 CLI 在一台
     机器上换来的一次（第二段 `CLI · 我的 MacBook`）。分辨**靠第二段自己说清，不加标记**——这一屏
     的规矩是「活跃的什么都不显示」，而两类都是活跃的，给每行挂一个种类标记就是每行都有标记。
     所以两条一起钉：两种都在，且页面上仍然只有一个胶囊（当前设备那一个）。 */
  check(
    '登录设备：浏览器的那次与 CLI 的那次，各自说清是哪一种',
    rows.some((row) => row.meta.indexOf('Chrome · macOS　·　') === 0) &&
      rows.some((row) => row.meta.indexOf('CLI · 我的 MacBook　·　') === 0),
    rows.map((row) => row.meta.split('　·　')[0]).join('、'),
  )
  check(
    '登录设备：没有给哪一类挂标记（活跃的行什么都不显示）',
    rows.filter((row) => row.chips > 0).length === 1 && rows.filter((row) => row.here)[0].chips === 1,
    rows.map((row) => row.chips).join('、'),
  )

  const head = await page.evaluate(() => ({
    count: document.getElementById('sessionCount').textContent.trim(),
    kickOthers: !!document.getElementById('sessionKickOthers'),
  }))
  check(
    '登录设备：头部是名词短语的计数，并给出「退出其他所有设备」',
    head.count === '4 台设备' && head.kickOthers,
    `${head.count}／按钮=${head.kickOthers}`,
  )

  /* 退出一台：问句落在**那一行自己的格子**里（与撤销/续期同一套），焦点落在「确认退出」上。 */
  await page.$eval('#sessionList li[data-session="s2"] [data-act="kick"]', (el) => el.click())
  await settle()
  const asked = await page.$$eval('#sessionList li[data-session="s2"]', (els) => {
    const row = els[0]
    return {
      question: row.querySelector('.session-meta').textContent.trim(),
      answers: [...row.querySelectorAll('.session-acts button')].map((b) => b.textContent.trim()),
      focus: document.activeElement ? document.activeElement.textContent.trim() : '',
      kickGone: row.querySelector('[data-act="kick"]') === null,
    }
  })
  check(
    '退出该设备：后果落在那一行自己的格子里，两个答案接在动作格上',
    asked.question === '退出后，该设备需要重新登录；当前设备不受影响。' &&
      asked.answers.join('／') === '确认退出／取消' &&
      asked.kickGone,
    `${asked.question}｜${asked.answers.join('／')}`,
  )
  check('退出该设备：焦点落在「确认退出」上', asked.focus === '确认退出', asked.focus || '无')

  await page.keyboard.press('Escape')
  await settle()
  const back = await page.evaluate(() => {
    const row = document.querySelector('#sessionList li[data-session="s2"]')
    const kick = row.querySelector('[data-act="kick"]')
    return {
      restored: kick !== null && !kick.hidden,
      stillAsking: [...row.querySelectorAll('.session-acts button')].some((b) => b.textContent.trim() === '确认退出'),
      focus: document.activeElement ? document.activeElement.getAttribute('data-act') : null,
    }
  })
  check(
    '退出该设备：Esc 回答取消，控件回来且焦点回到它身上',
    back.restored && !back.stillAsking && back.focus === 'kick',
    `控件回来=${back.restored} 还在问=${back.stillAsking} 焦点=${back.focus || '无'}`,
  )

  await page.$eval('#sessionList li[data-session="s2"] [data-act="kick"]', (el) => el.click())
  await settle()
  await page.$$eval('#sessionList li[data-session="s2"] .session-acts button', (buttons) => {
    buttons.find((b) => b.textContent.trim() === '确认退出').click()
  })
  await settle()
  const after = await page.evaluate(() => ({
    rows: document.querySelectorAll('#sessionList li').length,
    count: document.getElementById('sessionCount').textContent.trim(),
  }))
  check(
    '退出该设备：确认之后那一行消失，计数跟着变',
    after.rows === 3 && after.count === '3 台设备',
    `${after.rows} 行／${after.count}`,
  )

  /* 踢其他所有。**这一条参照产品的界面上没有**，所以它自己的两条路都要钉住。 */
  await page.goto(base + '?page=sessions', { waitUntil: 'load' })
  await settle()
  await page.$eval('#sessionKickOthers', (el) => el.click())
  await settle()
  const othersQ = await page.$eval('#sessionCount', (el) => el.textContent.trim())
  check(
    '退出其他所有：先问后果，并说清是几台、当前设备不受影响',
    othersQ === '退出后，其他 3 台设备都需要重新登录；当前设备不受影响。',
    othersQ,
  )
  await page.$$eval('#sessionHeadActs button', (buttons) => {
    buttons.find((b) => b.textContent.trim().indexOf('确认退出') === 0).click()
  })
  await settle()
  const done = await page.evaluate(() => ({
    rows: document.querySelectorAll('#sessionList li').length,
    here: document.querySelectorAll('#sessionList li .session-here:not([hidden])').length,
    count: document.getElementById('sessionCount').textContent.trim(),
    button: !!document.getElementById('sessionKickOthers'),
  }))
  check(
    '退出其他所有：只剩当前这一台，那个按钮自己也消失了',
    done.rows === 1 && done.here === 1 && done.count === '仅当前设备' && !done.button,
    `${done.rows} 行／${done.count}／按钮=${done.button}`,
  )

  await page.goto(base + '?page=sessions&state=empty', { waitUntil: 'load' })
  await settle()
  const empty = await page.evaluate(() => ({
    rows: document.querySelectorAll('#sessionList li').length,
    none: !document.getElementById('sessionNone').hidden,
    noneText: document.getElementById('sessionNone').textContent.trim(),
    button: !!document.getElementById('sessionKickOthers'),
  }))
  check(
    '「仅当前设备」：一行、一句说明、没有可退出的东西',
    empty.rows === 1 && empty.none && empty.noneText === '在别处登录过的设备会出现在这里。' && !empty.button,
    `${empty.rows} 行／说明=${empty.none}／按钮=${empty.button}`,
  )

  await page.goto(base + '?page=sessions&state=failed', { waitUntil: 'load' })
  await settle()
  const failed = await page.$eval('#page-sessions .hint[data-when="failed"]', (el) => el.textContent.trim())
  check(
    '加载失败：一句话，说清这是列表的事而不是账号的事',
    failed === '无法加载登录设备列表，请稍后重试。此处仅表示列表加载失败，不代表账号异常。',
    failed,
  )

  /* **列表里不该出现任何能当凭据用的串。** 夹具用的是 `s1/s2/s3` 这种短 id，正是为了这一点：
     这一页要按一个 id 寻址一行，而那个 id 绝不能长得像会话 id。 */
  const leaks = await page.evaluate(() => {
    const text = document.getElementById('page-sessions').innerHTML
    return (text.match(/\b[0-9a-f]{32}\b|\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/g) || []).length
  })
  check('登录设备：页面上没有任何形如会话 id 的长串', leaks === 0, `${leaks} 处`)
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
  (await page.$$eval('#skillSortSlot .menu-item', (els) => els.map((el) => el.dataset.value))).join(',') ===
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

/* **Taken-away skills are a list, not a filter.** Every row there can do one thing and no row in
   the other list can do it, so the two are read as two lists with two addresses. */
const gone = await listAt('?page=skills&show=deleted')
check(
  '已删除：是另一份列表，两行都在，而「在用」那份是空的',
  gone.tabs.join(',') === '在用,已删除' &&
    gone.tabCurrent === '已删除' &&
    gone.gone.join(',') === 'pdf-stamp,slug-check' &&
    gone.names.length === 0,
  `${gone.tabCurrent} · ${gone.gone.join(',')}`,
)
check(
  '已删除：每一行说的是什么时候删的，能做的只有恢复',
  gone.goneWhen.every((when) => when.startsWith('删除于 ')) && gone.goneRestore === '恢复',
  `${gone.goneWhen.join(' / ')} · ${gone.goneRestore}`,
)
check('已删除：计数说的是这一份', gone.count === '2 个已删除', gone.count)

} /* listGroup */

// ---------------------------------------------------------------------------------------------
// The skill page, which reads as a repository: one level of files at a time, and under them
// whichever file is being read. Both are addresses, so every case below is one URL rather than a
// sequence of clicks — and the sequence gets walked once at the end, because that is how a person
// gets from the root down to a file.

const skillAt = async (query) => {
  await page.goto(base + query, { waitUntil: 'load' })
  await settle()
  return page.evaluate(() => ({
    crumb: document.querySelector('.skill-ident h1').textContent.replace(/\s+/g, ' ').trim(),
    badge: document.querySelector('.skill-ident .chip')?.textContent ?? null,
    badgeHref: document.querySelector('.skill-ident .chip')?.getAttribute('href') ?? null,
    checked: document.querySelector('input[name="skill-visibility"]:checked')?.value ?? null,
    /* 只有「公开 · 浏览」这一档带一句代价（写在选项自己的说明下面），其余两档没有。 */
    warnings: [...document.querySelectorAll('#visibilityChoices .choice-warn')].map((warn) => ({
      title: warn.closest('.choice').querySelector('.choice-title').textContent,
      text: warn.textContent,
    })),
    /* A row's own words only. The role control is a `<select>`, and every `<option>` under it is a
       child in the DOM — leaving them in would make the row text depend on what is *offered*
       rather than on what the row *says*. */
    /* 名单第一行是所有者（只读），不是一条授权；头像那个字母也不是这一行说的话。两样都去掉，
       剩下的才是「谁 + 什么档位 + 附注」。 */
    grants: [...document.querySelectorAll('#grantList li:not(.is-owner)')].map((el) => {
      const copy = el.cloneNode(true)
      copy.querySelectorAll('.menu-list, .avatar, .member-type').forEach((node) => node.remove())
      return copy.textContent.replace(/\s+/g, ' ').trim()
    }),
    grantValues: [...document.querySelectorAll('#grantList .menu-trigger')].map((el) => el.dataset.value),
    grantOptions: [...document.querySelectorAll('#grantList .menu-trigger')].map((el) =>
      [...el.parentElement.querySelectorAll('.menu-item')].map((item) => item.textContent).join('/'),
    ),
    roles: [...document.querySelectorAll('#sharing-role .menu-item')].map((el) => el.textContent),
    hint: document.getElementById('grantsHint').textContent,
    visTitles: [...document.querySelectorAll('#visibilityChoices .choice-title')].map((el) => el.textContent),
    /* **设置正文的三处清单是同一张卡（2026-10-17）。** 可见性的选项、协作者的名单、详细信息的名值
       表是同一种东西——一组同级的项——所以长成同一个物体：`--border` 的框、`--radius` 的圆角。
       节标题下不再画线：框已经是这组东西的边界，再加一条就是两条同权重的线。 */
    listCards: ['#visibilityChoices', '#grantList', '#detailRows'].map((selector) => {
      const style = getComputedStyle(document.querySelector(selector))
      return `${style.borderTopWidth}/${style.borderTopStyle}/${style.borderRadius}`
    }),
    blockHeadingRule: getComputedStyle(document.querySelector('.settings-block > h3')).borderBottomWidth,
    /* **名单第一行是所有者，待处理的申请在它上面。** 申请是「还没成为协作者的人」：它有名字、
       想要的那一档、和两个决定。二级菜单上的数字与这一块同源。 */
    ownerRow: (() => {
      const row = document.querySelector('#grantList li.is-owner')
      if (!row) return null
      const copy = row.cloneNode(true)
      copy.querySelectorAll('.avatar, .member-type').forEach((node) => node.remove())
      return copy.textContent.replace(/\s+/g, ' ').trim()
    })(),
    ownerHasWithdraw: Boolean(document.querySelector('#grantList li.is-owner .link-button')),
    requests: [...document.querySelectorAll('#requestList li')].map((li) => ({
      who: li.querySelector('.grant-who').textContent,
      ask: li.querySelector('.grant-ask').textContent,
      rung: li.querySelector('.menu-trigger').dataset.value,
      acts: [...li.querySelectorAll('.button')].map((button) => button.textContent).join('/'),
    })),
    requestCount: document.getElementById('requestCount').textContent,
    navCount: document.getElementById('collabCount').hidden ? null : document.getElementById('collabCount').textContent,
    /* **协作者页的三块，按它们出现的顺序。** 共享给在最上面（不必往下翻就能加人），名单在中间
       （搜索与分页都在这一块里），申请在最后（它的长期归宿是通知面）。 */
    collabBlocks: [...document.querySelectorAll('.settings-pane[data-sub="collaborators"] > .settings-block')]
      .map((block) => block.querySelector('h3').textContent.replace(/\s+/g, ' ').trim()),
    memberCount: document.getElementById('memberCount').textContent,
    memberPager: document.getElementById('memberPager').textContent.replace(/\s+/g, ' ').trim(),
    /* **危险区域照 GitHub 的 Danger Zone：标题在框外，框里一行「名字 + 后果 + 一个描边的动作」。**
       它不再是一块填色的面板——底色必须透明，边界由 1px 的红边给。 */
    dangerTitles: [...document.querySelectorAll('#dangerZone .danger-title')].map((el) => el.textContent),
    dangerButton: document.getElementById('deleteSkill')?.textContent ?? null,
    offlineButton: document.getElementById('offlineSkill')?.textContent ?? null,
    offlineRowHidden: document.getElementById('offlineRow').hidden,
    offlineBadge: document.getElementById('skillOfflineBadge').hidden
      ? null
      : document.getElementById('skillOfflineBadge').textContent,
    dangerBoxBorder: getComputedStyle(document.querySelector('.danger-box')).borderTopWidth,
    dangerFill: getComputedStyle(document.getElementById('dangerZone')).backgroundColor,
    count: document.getElementById('versionCount').textContent,
    address: document.getElementById('skillAddress').textContent,
    tab: (() => {
      const el = document.querySelector('.skill-tab.is-current')
      return el?.querySelector('.skill-tab-label')?.textContent.trim() ?? el?.textContent.trim() ?? null
    })(),
    panels: [...document.querySelectorAll('.tab-panel')].filter((el) => !el.hidden).map((el) => el.dataset.tab),
    sub: document.querySelector('.settings-item.is-current')?.firstChild?.textContent.trim() ?? null,
    /* Only the ones this tier is shown: a settings item that is not drawn for somebody is not one of
       their sections, and reading it here would make every tier look identical. */
    /* 名字取第一个文本节点：协作者那一项右肩挂着一个数量标，算进 textContent 就成了「协作者1」。 */
    subs: [...document.querySelectorAll('.settings-item')].filter((el) => !el.hidden).map((el) => el.firstChild.textContent.trim()),
    subHrefs: [...document.querySelectorAll('.settings-item')].map((el) => el.getAttribute('href')),
    subPanes: [...document.querySelectorAll('.settings-pane')].filter((el) => !el.hidden).map((el) => el.dataset.sub),
    changed: [...document.querySelectorAll('#fileList .file-changed')].map((el) => el.textContent),
    /* The row's own words. A row has no actions at all any more: the only control in it is the
       version's name, which navigates to that version's page (ADR 0054). */
    /* 行自己说的话：右上角的「更多操作」菜单虽然平时藏着，它的条目也在 `textContent` 里，
       所以先摘掉菜单再读这一行。 */
    versions: [...document.querySelectorAll('#versionList li')].map((el) => {
      const copy = el.cloneNode(true)
      copy.querySelectorAll('.version-menu').forEach((node) => node.remove())
      return copy.textContent.replace(/\s+/g, ' ').trim()
    }),
    versionEntries: [...document.querySelectorAll('#versionList li')].map((el) => ({
      name: el.querySelector('.version-name').textContent,
      href: el.querySelector('.version-name').getAttribute('href'),
      /* 整行是这个链接，所以整行的悬停提示也由它给：名称一行、说明一行。 */
      title: el.querySelector('.version-name').getAttribute('title'),
      /* 调用档的身份是版本号，用等宽画；行尾只写上线时间。 */
      mono: el.querySelector('.version-name').classList.contains('is-mono'),
      meta: el.querySelector('.version-meta').textContent,
      /* **菜单以外只许有名称、「更多操作」与版本码的复制**；菜单里的每一项都是链接（2026-10-20 定）。 */
      extras: [...el.querySelectorAll('a, button')].filter(
        (node) =>
          !node.classList.contains('version-name') &&
          !node.classList.contains('version-menu-trigger') &&
          !node.classList.contains('vp-copy') &&
          !node.closest('.menu-list'),
      ).length,
      /* 版本码只给作者面；调用档这一格是 null。 */
      code: el.querySelector('.version-code > span:first-child')?.textContent ?? null,
    })),
    /* **行右上角的「更多操作」。** 它只导航：每一项都是链接；没有任何状态动作在这里执行。 */
    versionMenus: [...document.querySelectorAll('#versionList .version-menu')].map((el) => ({
      trigger: el.querySelector('.version-menu-trigger') !== null,
      items: [...el.querySelectorAll('.menu-item')].map((item) => item.textContent),
    })),
    /* **名称与版本号是两列（2026-10-20 定）**：前者是作者起的名字，后者是上线后分配的
       @版本（草稿为 —）。名字是这一行的入口，版本号是等宽的第二列。 */
    versionColumns: [...document.querySelectorAll('#versionList li')].map((el) => ({
      name: el.querySelector('.version-name').textContent,
      number: el.querySelector('.version-number').textContent,
    })),
    /* **版本表没有列头（2026-10-20 定）**：每行两段——上面名称 / 版本号 / 状态，
       下面左说明、右谁·时间。这里读的是结构，不是列头的字。 */
    versionColHeads: [...document.querySelectorAll('.version-cols span')].map((el) => el.textContent),
    versionRows: [...document.querySelectorAll('#versionList li')].map((el) => ({
      head: [...el.querySelectorAll('.version-head > *')].map((child) => child.className),
      sub: [...el.querySelectorAll('.version-sub > *')].map((child) => child.className),
    })),
    versionRowStyle: (() => {
      const name = document.querySelector('#versionList .version-name')
      /* 筛到一行都没有时没有行可量——空态由 `versionEmpty` 单独读。 */
      if (!name) return null
      const note = document.querySelector('#versionList .version-note')
      const meta = document.querySelector('#versionList .version-meta')
      const nameStyle = getComputedStyle(name)
      const noteStyle = getComputedStyle(note)
      const metaStyle = getComputedStyle(meta)
      return {
        nameSize: nameStyle.fontSize,
        nameWeight: nameStyle.fontWeight,
        noteOverflow: noteStyle.textOverflow,
        noteWhiteSpace: noteStyle.whiteSpace,
        metaMarginLeft: metaStyle.marginLeft,
      }
    })(),
    /* The picker's own groups: readable versions are choices, a pinned unreadable one is a disabled
       option under its state's group. */
    /* **版本选择器是一张平表，不是按状态分的组（2026-10-20 定）**：每一行是名字 + 状态 tag。
       `text` 连 tag 一起读出来（中间有空格），`tag` 单独留一份，好让断言既看名字也看状态。 */
    picker: [...document.querySelectorAll('#versionPickerSlot .menu-item')].map((option) => ({
      text: option.textContent.replace(/\s+/g, ' ').trim(),
      disabled: option.disabled,
      tag: option.querySelector('.menu-tag')?.textContent ?? null,
    })),
    versionCountBadge: document.getElementById('skillVersionCount').hidden
      ? null
      : document.getElementById('skillVersionCount').textContent,
    /* **版本列表上面是过滤与搜索（2026-10-20 定，照 GitHub 的 PR 列表）。** 状态胶囊带计数、
       计数不随搜索变；「全部」一直在。新建版本按钮只是占位，创建与编辑在下一个版本开放。 */
    versionFilters: [...document.querySelectorAll('#versionFilters .version-filter')].map((el) => ({
      label: el.firstChild.textContent.trim(),
      count: el.querySelector('.version-filter-count')?.textContent ?? null,
      current: el.classList.contains('is-current'),
      pressed: el.getAttribute('aria-pressed'),
    })),
    versionQuery: {
      value: document.getElementById('versionQuery').value,
      placeholder: document.getElementById('versionQuery').getAttribute('placeholder'),
      clearHidden: document.getElementById('versionQueryClear').hidden,
    },
    versionCreate: (() => {
      const el = document.getElementById('versionCreate')
      return {
        label: el.textContent.replace(/\s+/g, ' ').trim(),
        title: el.getAttribute('title'),
        disabled: el.disabled,
        hidden: el.hidden,
      }
    })(),
    versionEmpty: (() => {
      const el = document.getElementById('versionEmpty')
      if (el.hidden) return null
      const copy = el.cloneNode(true)
      copy.querySelectorAll('button').forEach((node) => node.remove())
      return copy.textContent.replace(/\s+/g, ' ').trim()
    })(),
    liveChip: document.querySelector('#versionList .chip.is-live') !== null,
    revokedRows: document.querySelectorAll('#versionList .chip.is-revoked').length,
    /* `title` is an optional extension: the line is drawn when the skill has one, and the way a
       reader knows is that it is not the name over again. */
    titleLine: document.querySelector('.skill-head .skill-title').hidden
      ? null
      : document.querySelector('.skill-head .skill-title').textContent,
    settingsTabHidden: document.querySelector('.skill-tab[data-tab="settings"]').hidden,
    /* 这一页自己的标签条，不带上「我的 skill」那两条——它们也在 DOM 里，但不在这一页上。 */
    tabs: [...document.querySelectorAll('#skillTabs .skill-tab')]
      .filter((el) => !el.hidden)
      .map((el) => el.querySelector('.skill-tab-label')?.textContent.trim() ?? el.textContent.trim()),
    tabIcons: [...document.querySelectorAll('#skillTabs .skill-tab')]
      .filter((el) => !el.hidden)
      .map((el) => el.querySelector('.skill-tab-icon') !== null),
    tabCounts: [...document.querySelectorAll('#skillTabs .skill-tab')]
      .filter((el) => !el.hidden)
      .map((el) => el.querySelector('.skill-tab-count')?.textContent ?? ''),
    activeTab: (() => {
      const el = document.querySelector('#skillTabs .skill-tab.is-current')
      if (!el) return null
      const cs = getComputedStyle(el)
      return {
        borderTopWidth: cs.borderTopWidth,
        borderTopRadius: cs.borderTopLeftRadius,
        background: cs.backgroundColor,
        borderBottomWidth: cs.borderBottomWidth,
      }
    })(),
    details: [...document.querySelectorAll('#detailRows > div')].map((row) => {
      const term = row.querySelector('dt').textContent
      const value = row.querySelector('dd').textContent.replace(/\s+/g, ' ').trim()
      return `${term} ${value}`
    }),
    /* 「信息」是一页散文：一段描述、一段「适用场景」、一行事实。 */
    /* 右栏：只给「瞥一眼」的东西。 */
    railVersion: document.querySelector('.info-rail #infoVersionText')?.textContent ?? null,
    railLabel: document.querySelector('.info-rail-label')?.textContent ?? null,
    /* 两栏的分隔靠「主内容是一个物体、右栏是页面上的注释」：左栏有边框，右栏没有。 */
    mainBorder: getComputedStyle(document.querySelector('.info-main')).borderTopWidth,
    railBorder: getComputedStyle(document.querySelector('.info-rail')).borderTopWidth,
    railTagsHidden: document.getElementById('infoTags').hidden,
    /* 标记是 SVG，没有文字，但它周围的空白会进 textContent——所以取的是 trim 之后的那一段。 */
    /* 只数信息页左栏那三块；版本页的「说明」用的是同一套小标题样式，但不是这一页的三段。 */
    infoLabels: [...document.querySelectorAll('.info-main .info-label')].map((el) =>
      el.textContent.trim(),
    ),
    infoDesc: document.getElementById('infoDesc').textContent,
    infoWhen: document.getElementById('infoWhen').hidden
      ? null
      : document.getElementById('infoWhenText').textContent,
    infoEnv: document.getElementById('infoEnv').hidden
      ? null
      : document.getElementById('infoEnvText').textContent,
    infoVersion: document.getElementById('infoVersionText').textContent,
    infoFacts: [...document.querySelectorAll('#infoFacts > div')].map((row) => {
      const term = row.querySelector('dt').textContent
      const value = row.querySelector('dd').textContent.replace(/\s+/g, ' ').trim()
      return `${term} ${value}`
    }),
    infoTags: [...document.querySelectorAll('#infoTags .chip')].map((chip) => chip.textContent),
    infoText: document.querySelector('.info-pane').textContent.replace(/\s+/g, ' ').trim(),
    badgeHidden: document.getElementById('skillBadge').hidden,
    missing: !document.querySelector('#page-skill [data-when="missing"]').hidden,
    /* The address of a deleted skill is a place somebody actually lands — so its way out has to
       reach the list the skill is now in. */
    missingExit: document.querySelector('#page-skill [data-when="missing"] a[href*="show=deleted"]') !== null,
    names: [...document.querySelectorAll('#fileList .file-name')].map((el) => el.textContent),
    marks: [...document.querySelectorAll('#fileList use')].map((el) => el.getAttribute('href')),
    sizes: [...document.querySelectorAll('#fileList .size')].map((el) => el.textContent),
    current: document.querySelector('#fileList .file-name.is-current')?.textContent ?? null,
    fileLabel: document.getElementById('fileTabLabel').textContent,
    paneHidden: document.getElementById('fileOpen').hidden,
    boxes: [...document.querySelectorAll('.file-table')].filter((el) => el.offsetParent !== null).length,
    commit: document.querySelector('.file-commit').textContent.replace(/\s+/g, ' ').trim(),
    commitBg: getComputedStyle(document.querySelector('.file-commit')).backgroundColor,
    /* **提交行右端的版本名是进这一版的链接**（2026-10-20 定）；时间仍是时间。 */
    commitMeta: document.getElementById('commitMeta').textContent,
    commitLink: (() => {
      const link = document.querySelector('#commitMeta .commit-version')
      return link ? { text: link.textContent, href: link.getAttribute('href') } : null
    })(),
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

/* 一个版本自己的页面：它读的东西与 skill 页不同（版本身份、说明、差异、动作），所以单开
   一个读取器，而不是把 skill 页那一大坨在单版页上跑一遍、返回一堆空的字段。 */
/* **The demo strip is fixed over the bottom of the window.** A control that lands under it is
   still "in the viewport" to Puppeteer, which then clicks the strip instead of the control. Scroll
   the target to the middle of the window before clicking — the same thing a person does. */
const scrollTo = async (selector) => {
  await page.$eval(selector, (el) => el.scrollIntoView({ block: 'center' }))
}
const clickIn = async (selector) => {
  await scrollTo(selector)
  await page.click(selector)
}

const versionAt = async (query) => {
  await page.goto(base + query, { waitUntil: 'load' })
  await settle()
  return page.evaluate(() => ({
    shown: !document.getElementById('versionDetail').hidden,
    listHidden: document.getElementById('versionListWrap').hidden,
    detailHidden: document.getElementById('versionDetail').hidden,
    missing: !document.getElementById('versionMissing').hidden,
    tab: (() => {
      const el = document.querySelector('.skill-tab.is-current')
      return el?.querySelector('.skill-tab-label')?.textContent.trim() ?? el?.textContent.trim() ?? null
    })(),
    tabs: [...document.querySelectorAll('#skillTabs .skill-tab')]
      .filter((el) => !el.hidden)
      .map((el) => el.querySelector('.skill-tab-label')?.textContent.trim() ?? el.textContent.trim()),
    url: window.location.search,
    title: document.getElementById('vpTitle').textContent,
    titleMono: document.getElementById('vpTitle').classList.contains('is-mono'),
    crumb: document.getElementById('vpCrumb').textContent,
    headHasTabs: document.querySelector('.vp-head').classList.contains('has-tabs'),
    headRule: getComputedStyle(document.querySelector('.vp-head')).borderBottomWidth,
    filesHref: document.getElementById('vpFiles').getAttribute('href'),
    filesHidden: document.getElementById('vpFiles').hidden,
    filesLabel: document.getElementById('vpFiles').textContent.replace(/\s+/g, ' ').trim(),
    number: document.getElementById('vpNumber').textContent,
    numberHidden: document.getElementById('vpNumber').hidden,
    states: document.getElementById('vpStates').textContent.replace(/\s+/g, ' ').trim(),
    meta: document.getElementById('vpMeta').textContent,
    statusText: document.getElementById('vpStatusText').textContent,
    statusTone: document.getElementById('vpStatus').className,
    statusHidden: document.getElementById('vpStatus').hidden,
    jumpHidden: document.getElementById('vpJump').hidden,
    jumpText: document.getElementById('vpJump').textContent,
    jumpHref: document.getElementById('vpJump').getAttribute('href'),
    facts: [...document.querySelectorAll('#vpFactsList > div')].map((row) => ({
      label: row.querySelector('dt').textContent,
      value: row.querySelector('.vp-fact-line > span:first-child')?.textContent ?? '',
    })),
    copyButtons: [...document.querySelectorAll('#vpFactsList .vp-copy')].map((button) => ({
      label: button.getAttribute('aria-label'),
      icon: button.querySelector('svg') !== null,
      text: button.textContent.trim(),
    })),
    timeline: [...document.querySelectorAll('#vpTimeline li')].map((el) =>
      el.textContent.replace(/\s+/g, ' ').trim(),
    ),
    note: document.getElementById('vpNote').textContent,
    noteLabel: document
      .querySelector('#vpNoteModule .info-label')
      ?.textContent.replace(/\s+/g, ' ')
      .trim() ?? null,
    noteLabelIcon:
      document.querySelector('#vpNoteModule .info-label .info-icon use')?.getAttribute('href') ??
      null,
    noteEmpty: document.getElementById('vpNote').classList.contains('is-empty'),
    tabsHidden: document.getElementById('vpTabs').hidden,
    tabs: [...document.querySelectorAll('#vpTabs .vp-tab')]
      .filter((el) => !el.hidden)
      .map((el) => el.textContent.replace(/\s+/g, ' ').trim()),
    subTab: (() => {
      const el = document.querySelector('#vpTabs .vp-tab.is-current')
      if (!el) return null
      const cs = getComputedStyle(el)
      return {
        borderTopWidth: cs.borderTopWidth,
        borderBottomWidth: cs.borderBottomWidth,
        background: cs.backgroundColor,
      }
    })(),
    currentTab: document
      .querySelector('#vpTabs .vp-tab.is-current:not([hidden])')
      ?.textContent.replace(/\s+/g, ' ').trim() ?? null,
    recordHidden: document.getElementById('vpRecordPane').hidden,
    diffPaneHidden: document.getElementById('vpDiffPane').hidden,
    actionsPaneHidden: document.getElementById('vpActionsPane').hidden,
    /* 概览是纯阅读页：动作与危险区都不能留在这一栏里。 */
    actionsInOverview:
      document.querySelector('#vpRecordPane #vpActions, #vpRecordPane #vpDanger') !== null,
    changeCount: document.getElementById('vpChangeCount').textContent,
    changeStats: document.getElementById('vpChangeStats').textContent,
    /* 「与哪一版比」：select 的当前值、选项与分组。默认是上一版。 */
    compareValue:
      document.querySelector('#vpCompareSlot .menu-trigger')?.dataset.value ?? null,
    compareOptions: [...document.querySelectorAll('#vpCompareSlot .menu-item')].map((el) =>
      el.textContent.trim(),
    ),
    compareGroups: [...document.querySelectorAll('#vpCompareSlot .menu-group-label')].map((el) =>
      el.textContent,
    ),
    fileCount: document.getElementById('vpFileCount').textContent,
    fileTree: [...document.querySelectorAll('#vpFileTree .vp-tree-item')].map((el) =>
      el.textContent.replace(/\s+/g, ' ').trim(),
    ),
    /* **清单上方那颗筛选框**：值、占位、可见行数，以及筛空时那一句。 */
    fileQuery: {
      value: document.getElementById('vpFileQuery').value,
      placeholder: document.getElementById('vpFileQuery').placeholder,
      clearHidden: document.getElementById('vpFileQueryClear').hidden,
      rows: document.querySelectorAll('#vpFileTree .vp-tree-item').length,
      visible: [...document.querySelectorAll('#vpFileTree li:not([hidden]) .vp-tree-item')].length,
      emptyHidden: document.getElementById('vpFileEmpty').hidden,
      emptyText: document.getElementById('vpFileEmpty').textContent.replace(/\s+/g, ' ').trim(),
    },
    big: {
      note: document.getElementById('vpBigNote')?.textContent ?? '',
      listOnly: document.getElementById('vpListOnly')?.textContent ?? null,
      mode: document.querySelector('.vp-file-mode')?.textContent.replace(/\s+/g, ' ').trim() ?? null,
      pager: document.querySelector('.vp-file-pager')?.textContent.replace(/\s+/g, ' ').trim() ?? null,
      current: document.querySelector('#vpFileTree .is-current')?.textContent.replace(/\s+/g, ' ').trim() ?? null,
      blocks: document.querySelectorAll('#vpFileList .vp-file').length,
      listNote: document.querySelector('#vpFileList .vp-file-list-note')?.textContent ?? null,
    },
    files: [...document.querySelectorAll('#vpFileList .vp-file')].map((el) => {
      /* 大改动的文件体可能还没加载：没有 `.diff-body` 时这些读数都是 0，而 `pending`
         说清它为什么是 0——不把「没取」写成「没变」。 */
      const body = el.querySelector('.diff-body')
      return {
        path: el.querySelector('.vp-file-path').textContent,
        stats: el.querySelector('.diff-stats').textContent,
        lines: body ? body.querySelectorAll(':scope > span').length : 0,
        nums: body ? body.querySelectorAll('.diff-num').length : 0,
        adds: body ? body.querySelectorAll('.is-add').length : 0,
        collapsed: body ? body.hidden : (el.querySelector('.vp-file-body')?.hidden ?? true),
        pending: el.querySelector('.diff-pending')?.textContent.replace(/\s+/g, ' ').trim() ?? null,
        more: el.querySelector('.diff-more')?.textContent.replace(/\s+/g, ' ').trim() ?? null,
      }
    }),
    actionsHidden: document.getElementById('vpActions').hidden,
    actions: [...document.querySelectorAll('#vpActionBody .vp-action')].map((el) =>
      el.textContent.replace(/\s+/g, ' ').trim(),
    ),
    hints: [...document.querySelectorAll('#vpActionBody .hint')].map((el) => el.textContent),
    /* **改名与改说明不在动作块里**：标题与说明旁的铅笔才是入口（2026-10-20 定）。 */
    titlePencil: !document.getElementById('vpTitleEdit').hidden,
    notePencil: !document.getElementById('vpNoteEdit').hidden,
    back: document.getElementById('vpBack').getAttribute('href'),
    release: document.getElementById('vpRelease')?.value ?? null,
    /* 发布表单不再有说明输入（2026-10-20 定）：说明只在草稿的概览里改。 */
    releaseNoteField: document.getElementById('vpReleaseNote') !== null,
    bumpValues: [...document.querySelectorAll('input[name="vp-bump"]')].map((el) => el.value),
    /* **版本号的选择问的是「旧的调用受不受影响」**（2026-10-20 定）：问题与三行说明都
       用调用者能直接回答的说法，术语仍是「调用」「版本号」。 */
    bumpLabel: document.querySelector('#vpActionBody .confirm-label')?.textContent ?? null,
    bumpTitles: [...document.querySelectorAll('#vpActionBody .choice-title')].map((el) => el.textContent),
    bumpNotes: [...document.querySelectorAll('#vpActionBody .choice-note')].map((el) =>
      el.textContent.replace(/\s+/g, ' ').trim(),
    ),
    firstButton: document.querySelector('#vpActionBody .button')?.textContent ?? null,
    actionButtons: [...document.querySelectorAll('#vpActionBody .button')].map((el) => el.textContent),
    actionButtonClasses: [...document.querySelectorAll('#vpActionBody .button')].map((el) => el.className),
    dangerHidden: document.getElementById('vpDanger').hidden,
    dangerTitles: [...document.querySelectorAll('#vpDangerBody .danger-title')].map((el) => el.textContent),
    dangerHints: [...document.querySelectorAll('#vpDangerBody .danger-what .hint')].map((el) => el.textContent),
    dangerButton: document.querySelector('#vpDangerBody .button')?.textContent ?? null,
    rollbackGroups: [...document.querySelectorAll('#vpRollbackSlot .menu-group-label')].map(
      (el) => el.textContent,
    ),
    rollbackOptions: [...document.querySelectorAll('#vpRollbackSlot .menu-item')].map((el) =>
      el.textContent.trim(),
    ),
    rollbackDefault:
      document.querySelector('#vpRollbackSlot .menu-trigger')?.dataset.value ?? null,
  }))
}

detailGroup: {
  if (!selected('detail')) break detailGroup

/* 信息是第一栏，所以凡是「文件那一栏」的事都要把 tab 说出来。 */
const root = await skillAt('?page=skill&tab=files')
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
  root.commit === 'd dev 把 split 的范围语法写成文档，并补了「按页」与「按分隔符」两个例子。 split 范围语法 @1.2.3 · 2026/10/07 14:32',
  root.commit,
)
/* **「现在看的是哪一版」就是那个链接（2026-10-20 定）。** 它进这一版自己的页面，与版本
   页头的「查看文件」互为镜像；时间不跟着可点。 */
check(
  'skill 页：表头右端的版本名是进这一版的链接，时间仍是时间',
  root.commitLink?.text === 'split 范围语法 @1.2.3' &&
    root.commitLink.href === '?page=skill&tab=versions&version=1.2.3' &&
    root.commitMeta === 'split 范围语法 @1.2.3 · 2026/10/07 14:32',
  JSON.stringify({ link: root.commitLink, meta: root.commitMeta }),
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
  root.count === '4 个版本' && root.address === 'dev/pdf-tools',
  `${root.count} · ${root.address}`,
)
check(
  'skill 页：文件行的中间一列是「最后改它的版本 · 那天」',
  root.changed.join('|') === '@1.2.3 · 2026/10/07|@1.2.3 · 2026/10/07|@1.2.1 · 2026/09/12',
  root.changed.join('|'),
)
check(
  'skill 页：默认停在「信息」那一栏',
  root.tab === '文件' && root.panels.join(',') === 'files',
  `${root.tab} / ${root.panels.join(',')}`,
)

/* 点它真的会走：落到那一版的概览（`sub` 缺省），不是只挂了一个地址。 */
await clickIn('#commitMeta .commit-version')
await settle()
const versionFromFiles = await page.evaluate(() => ({
  url: window.location.search,
  title: document.getElementById('vpTitle').textContent,
  pane: document.querySelector('#vpTabs .vp-tab.is-current')?.textContent.replace(/\s+/g, ' ').trim() ?? null,
}))
check(
  'skill 页：点提交行右端的版本名，落到这一版的概览',
  versionFromFiles.url.includes('tab=versions') &&
    versionFromFiles.url.includes('version=1.2.3') &&
    !versionFromFiles.url.includes('sub=') &&
    versionFromFiles.title === 'split 范围语法' && versionFromFiles.pane === '概览',
  JSON.stringify(versionFromFiles),
)

const versionsTab = await skillAt('?page=skill&tab=versions')
check(
  'skill 页：切到「版本」，文件树让位',
  versionsTab.tab === '版本' && versionsTab.panels.join(',') === 'versions',
  `${versionsTab.tab} / ${versionsTab.panels.join(',')}`,
)
/* **顶层四栏是 GitHub 的方框页签（2026-10-20 定）。** 每栏一枚图标；只有「版本」有
   有意义的计数，其余不硬凑 0。当前栏是一只上、左、右有发丝线、底边用白盖住横线的方框。 */
check(
  '顶层四栏：每栏一枚图标，只有「版本」带已上线计数',
  versionsTab.tabIcons.length === 4 && versionsTab.tabIcons.every(Boolean) &&
    versionsTab.tabCounts.join(',') === ',,4,',
  JSON.stringify({ icons: versionsTab.tabIcons, counts: versionsTab.tabCounts }),
)
check(
  '顶层当前栏：方框页签——上边线与上圆角，白色底压住整条横线',
  versionsTab.activeTab &&
    versionsTab.activeTab.borderTopWidth === '1px' &&
    versionsTab.activeTab.borderTopRadius === '6px' &&
    versionsTab.activeTab.background === 'rgb(255, 255, 255)' &&
    versionsTab.activeTab.borderBottomWidth === '1px',
  JSON.stringify(versionsTab.activeTab),
)
check(
  'skill 页：头里数的是上线过的版本，表里那行草稿不算一个',
  /* **A draft is not one of the versions this skill has** — it is one it might become, and the
     head's picker lists what can be read. A discarded version is not counted either: it never went
     live. So the two numbers have to differ by exactly those two rows, and each difference is named
     rather than counted. */
  versionsTab.count === '4 个版本' &&
    versionsTab.versions.length === 6 &&
    versionsTab.versions.filter((row) => row.includes(' 草稿 ')).length === 1 &&
    versionsTab.versionCountBadge === '4',
  `${versionsTab.versionCountBadge} · ${versionsTab.versions.length} 行 · ${versionsTab.count}`,
)
check(
  'skill 页：一行一个版本，写着版本、状态、说明、版本码、谁在什么时候提交',
  versionsTab.versions.join('\n') === [
    'split 范围语法 @1.2.3 线上 把 split 的范围语法写成文档，并补了「按页」与「按分隔符」两个例子。 Z8N2VK4Q dev · 2026/10/07 14:32',
    'compress 补文档 — 草稿 把 compress 的用法补进正文：参数怎么给、输出放在哪，以及扫描件要先 OCR 的那一段。 7K3M9QX2 yuhan · 2026/10/16 10:12',
    'split 拆页 @1.2.2 历史版本 拆分时保留原文件的元数据，合并的默认行为不变。 M5C7T1X9 dev · 2026/09/28 11:05',
    'compress 收进 skill @1.2.1 已下架 把 compress 收进这个 skill，原来单独的那份脚本不再需要。 H3R6B8K2 dev · 2026/09/12 20:41',
    '把分页做成参数 — 已丢弃 想给 split 加分页参数，还没写完就换了个方向。 Q4W8E2R6 yuhan · 2026/09/05 16:20',
    '第一版 @0.9.0 历史版本 第一版：拆页与合并 01J9Z6Q4M8V2K3X7R5T1B9C4D2 dev · 2026/08/30 09:20',
  ].join('\n'),
  versionsTab.versions.join(' | '),
)
check(
  'skill 页：草稿有名字、还没有版本号，那一格是破折号而不是一个假号',
  versionsTab.versions[1].startsWith('compress 补文档 — 草稿 '),
  versionsTab.versions[1],
)
check(
  '版本表：名称在前、版本号在后，各占一列；发布后两者都还在',
  versionsTab.versionColumns.map((row) => `${row.name}/${row.number}`).join(' | ') ===
    'split 范围语法/@1.2.3 | compress 补文档/— | split 拆页/@1.2.2 | compress 收进 skill/@1.2.1 | 把分页做成参数/— | 第一版/@0.9.0',
  versionsTab.versionColumns.map((row) => `${row.name}/${row.number}`).join(' | '),
)
check(
  '版本表：没有列头；每行两段——名称/版本号/状态在上，说明、版本码、谁·时间在下',
  versionsTab.versionColHeads.length === 0 &&
    versionsTab.versionRows.length === 6 &&
    versionsTab.versionRows.every((row) =>
      row.head.length === 4 &&
      row.head[0] === 'version-name' &&
      row.head[1].startsWith('version-number') &&
      row.head[2] === 'version-states' &&
      row.head[3] === 'version-menu' &&
      row.sub.join(',') === 'version-note,version-code-slot,version-meta'),
  JSON.stringify(versionsTab.versionRows),
)
check(
  '版本表：名称 15px/600，说明单行截断，时间靠右',
  versionsTab.versionRowStyle.nameSize === '15px' &&
    versionsTab.versionRowStyle.nameWeight === '600' &&
    versionsTab.versionRowStyle.noteOverflow === 'ellipsis' &&
    versionsTab.versionRowStyle.noteWhiteSpace === 'nowrap',
  JSON.stringify(versionsTab.versionRowStyle),
)
check(
  '版本表：整行的悬停提示由那个链接给——名称一行、说明一行',
  versionsTab.versionEntries[0].title ===
    'split 范围语法\n把 split 的范围语法写成文档，并补了「按页」与「按分隔符」两个例子。',
  JSON.stringify(versionsTab.versionEntries[0].title),
)

/* **整行就是那个链接（2026-10-20 定）**：名称是真链接，`::after` 铺满整行；悬停整行压一层
   `--wash`，点行里任何一处都进这一版。 */
const rowHit = await page.evaluate(() => {
  const row = document.querySelector('#versionList li')
  const box = row.getBoundingClientRect()
  const at = (x, y) => {
    const el = document.elementFromPoint(x, y)
    return el ? el.className : null
  }
  return {
    bottomRight: at(box.right - 3, box.bottom - 3),
    middle: at(box.left + box.width / 2, box.top + box.height / 2),
    restBg: getComputedStyle(row).backgroundColor,
  }
})
check(
  '版本表：整行都是那个链接——行内任意一处命中的都是版本名称',
  rowHit.bottomRight.startsWith('version-name') &&
    rowHit.middle.startsWith('version-name') &&
    rowHit.restBg === 'rgba(0, 0, 0, 0)',
  JSON.stringify(rowHit),
)
await page.hover('#versionList li .version-note')
const hoveredRow = await page.evaluate(() => {
  const row = document.querySelector('#versionList li')
  const name = row.querySelector('.version-name')
  const title = getComputedStyle(name)
  return {
    bg: getComputedStyle(row).backgroundColor,
    color: title.color,
    underline: title.textDecorationLine,
  }
})
check(
  '版本表：悬停整行压一层底色，标题转强调色并下划线',
  hoveredRow.bg === 'rgb(243, 245, 247)' &&
    hoveredRow.color === 'rgb(29, 78, 216)' &&
    hoveredRow.underline === 'underline',
  JSON.stringify(hoveredRow),
)

/* **右上角的「更多操作」（2026-10-20 定）。** 按钮压在整行那个链接之上；菜单里每一项都是
   链接，只把读者送到那一版自己的页面——菜单自己不执行任何动作。 */
await skillAt('?page=skill&tab=versions')
const kebab = await page.evaluate(() => {
  const trigger = document.querySelector('#versionList li .version-menu-trigger')
  const box = trigger.getBoundingClientRect()
  const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
  return {
    count: document.querySelectorAll('#versionList li .version-menu-trigger').length,
    label: trigger.getAttribute('aria-label'),
    aboveRowLink: Boolean(hit && hit.closest('.version-menu-trigger')),
  }
})
check(
  '更多操作：每行右上角一枚，压在整行链接之上，名字带这一版',
  kebab.count === 5 && kebab.label === '更多操作：split 范围语法' && kebab.aboveRowLink,
  JSON.stringify(kebab),
)

await clickIn('#versionList li:nth-child(2) .version-menu-trigger')
await settle()
const draftMenu = await page.evaluate(() => {
  const open = [...document.querySelectorAll('.version-menu .menu-list')].find((el) => !el.hidden)
  return {
    url: window.location.search,
    items: [...open.querySelectorAll('a.menu-item')].map((el) => ({
      label: el.textContent,
      href: el.getAttribute('href'),
      role: el.getAttribute('role'),
    })),
    focused: document.activeElement === open.querySelector('a.menu-item'),
  }
})
check(
  '更多操作（草稿）：查看文件 / 查看改动 / 上线 / 丢弃，四项都是链接',
  draftMenu.url === '?page=skill&tab=versions' &&
    draftMenu.items.map((item) => item.label).join('/') === '查看文件/查看改动/上线/丢弃' &&
    draftMenu.items.every((item) => item.role === 'menuitem') &&
    draftMenu.items[0].href === '?page=skill&tab=files&version=7K3M9QX2' &&
    draftMenu.items[1].href === '?page=skill&tab=versions&version=7K3M9QX2&sub=diff' &&
    draftMenu.items[2].href === '?page=skill&tab=versions&version=7K3M9QX2&sub=actions' &&
    draftMenu.items[3].href === '?page=skill&tab=versions&version=7K3M9QX2&sub=actions' &&
    draftMenu.focused,
  JSON.stringify(draftMenu),
)

/* Esc 关掉，焦点回到按钮。 */
await page.keyboard.press('Escape')
await settle()
const menuClosed = await page.evaluate(() => ({
  open: [...document.querySelectorAll('.version-menu .menu-list')].filter((el) => !el.hidden).length,
  focus: document.activeElement?.className ?? null,
}))
check(
  '更多操作：Esc 关闭，焦点回到按钮',
  menuClosed.open === 0 && menuClosed.focus === 'version-menu-trigger',
  JSON.stringify(menuClosed),
)

/* 点「重新上架」只是到那一版的「操作」栏：它没有执行，状态仍是已下架。 */
await clickIn('#versionList li:nth-child(4) .version-menu-trigger')
await settle()
await Promise.all([
  page.waitForNavigation({ waitUntil: 'load' }),
  page.click('#versionList li:nth-child(4) .menu-group:nth-child(2) .menu-item'),
])
await settle()
const restored = await page.evaluate(() => ({
  url: window.location.search,
  actions: !document.getElementById('vpActionsPane').hidden,
  chip: document.querySelector('#vpStates .chip-text')?.textContent ?? null,
  heading: document.querySelector('#vpActionsPane .vp-action h3')?.textContent ?? null,
}))
check(
  '更多操作：点「重新上架」到那一版的「操作」栏，动作本身没有执行（仍是已下架）',
  restored.url.includes('version=1.2.1') &&
    restored.url.includes('sub=actions') &&
    restored.actions &&
    restored.chip === '已下架' &&
    restored.heading === '重新上架',
  JSON.stringify(restored),
)

/* 点在说明那一块（不是标题）也进这一版。 */
await skillAt('?page=skill&tab=versions')
const noteSpot = await page.evaluate(() => {
  const note = document.querySelector('#versionList li .version-note').getBoundingClientRect()
  return { x: Math.round(note.left + 6), y: Math.round(note.top + note.height / 2) }
})
await Promise.all([
  page.waitForNavigation({ waitUntil: 'load' }),
  page.mouse.click(noteSpot.x, noteSpot.y),
])
await settle()
const clickedRow = await page.evaluate(() => ({
  url: window.location.search,
  detail: !document.getElementById('versionDetail').hidden,
}))
check(
  '版本表：点说明那一块也进这一版（整行可点的另一半）',
  clickedRow.detail && clickedRow.url.includes('version=1.2.3'),
  JSON.stringify(clickedRow),
)
check(
  'skill 页：0.y.z 只按状态标一枚章，不再另标「试用」（2026-10-20 定）',
  versionsTab.versions[5].includes(' 历史版本 第一版：拆页与合并 01J9Z6Q4M8V2K3X7R5T1B9C4D2') &&
    versionsTab.versions.every((row) => !row.includes('试用')),
  versionsTab.versions.join(' | '),
)

/* **列表上面是过滤与搜索（2026-10-20 定，照 GitHub 的 PR 列表）。** 状态胶囊带计数，计数
   数的是这一档能看见的所有版本、不随搜索变；「全部」一直在；新建版本按钮暂时只占位。 */
check(
  '版本过滤：一枚胶囊一个状态，带计数，当前那枚压深',
  versionsTab.versionFilters.map((f) => `${f.label} ${f.count}${f.current ? '*' : ''}`).join(' | ') ===
    '全部 6* | 线上 1 | 草稿 1 | 历史版本 2 | 已下架 1 | 已丢弃 1' &&
    versionsTab.versionFilters.every((f) => f.pressed === String(f.current)),
  JSON.stringify(versionsTab.versionFilters),
)
check(
  '版本搜索：整行在过滤下面，没输入时不画清除叉',
  versionsTab.versionQuery.value === '' &&
    versionsTab.versionQuery.placeholder === '搜索版本名称、版本号或说明' &&
    versionsTab.versionQuery.clearHidden === true,
  JSON.stringify(versionsTab.versionQuery),
)
check(
  '新建版本：先占位——不跳转，提示创建与编辑在下一个版本开放',
  versionsTab.versionCreate.label === '新建版本' &&
    versionsTab.versionCreate.disabled === false &&
    versionsTab.versionCreate.title === '创建与编辑版本将在下一个版本开放',
  JSON.stringify(versionsTab.versionCreate),
)

/* 筛一样东西：行收窄，地址跟着走，所以刷新还在同一个视图里。 */
await skillAt('?page=skill&tab=versions&filter=draft')
const draftOnly = await page.evaluate(() => ({
  url: window.location.search,
  rows: [...document.querySelectorAll('#versionList li .version-name')].map((el) => el.textContent),
  current: document.querySelector('#versionFilters .is-current')?.firstChild.textContent.trim() ?? null,
}))
check(
  '版本过滤：草稿只看得到草稿，地址写着 filter=draft',
  draftOnly.url.includes('filter=draft') &&
    draftOnly.rows.join(',') === 'compress 补文档' &&
    draftOnly.current === '草稿',
  JSON.stringify(draftOnly),
)

/* 换一枚胶囊：就地换行，不重新加载。 */
await clickIn('#versionFilters .version-filter:nth-child(4)')
await settle()
const switched = await page.evaluate(() => ({
  url: window.location.search,
  rows: [...document.querySelectorAll('#versionList li .version-name')].map((el) => el.textContent),
}))
check(
  '版本过滤：换一枚胶囊就地换行，不重新加载',
  switched.url.includes('filter=published') && switched.rows.join(',') === 'split 拆页,第一版',
  JSON.stringify(switched),
)

/* 从筛过的列表点进一版：回去还是那张列表，筛选与搜索都跟着这一行走。 */
await skillAt('?page=skill&tab=versions&filter=published&q=拆页')
const keptRow = await page.evaluate(() =>
  document.querySelector('#versionList .version-name').getAttribute('href'),
)
await page.goto(base + keptRow, { waitUntil: 'load' })
await settle()
const keptBack = await page.evaluate(() => document.getElementById('vpBack').getAttribute('href'))
check(
  '版本过滤：从筛过的列表进一版，「版本」面包屑带着筛选回去',
  keptRow.includes('filter=published') &&
    keptRow.includes('q=') &&
    keptBack.includes('filter=published') &&
    keptBack.includes('q='),
  `${keptRow} → ${keptBack}`,
)

/* 搜名称、版本号、说明与提交人：这四样都是行上看得见的字。 */
await skillAt('?page=skill&tab=versions&q=compress')
const searched = await page.evaluate(() => ({
  url: window.location.search,
  rows: [...document.querySelectorAll('#versionList li .version-name')].map((el) => el.textContent),
  value: document.getElementById('versionQuery').value,
  clearHidden: document.getElementById('versionQueryClear').hidden,
}))
check(
  '版本搜索：输入的字写进地址，行跟着收窄，清除叉出现',
  searched.url.includes('q=compress') &&
    searched.rows.join(',') === 'compress 补文档,compress 收进 skill' &&
    searched.value === 'compress' &&
    searched.clearHidden === false,
  JSON.stringify(searched),
)

await skillAt('?page=skill&tab=versions')
await page.type('#versionQuery', '0.9')
await settle()
const typed = await page.evaluate(() => ({
  url: window.location.search,
  rows: [...document.querySelectorAll('#versionList li .version-name')].map((el) => el.textContent),
}))
check(
  '版本搜索：边打边筛，不重新加载；版本号也搜得到',
  typed.url.includes('q=0.9') && typed.rows.join(',') === '第一版',
  JSON.stringify(typed),
)

/* 什么都没匹配上：一句话 + 一个清除，不是一张空白卡片。 */
await skillAt('?page=skill&tab=versions&filter=live&q=zzz')
const noMatch = await page.evaluate(() => ({
  hidden: document.getElementById('versionEmpty').hidden,
  text: document.getElementById('versionEmpty').textContent.replace(/\s+/g, ' ').trim(),
  clear: document.getElementById('versionEmptyClear').textContent,
}))
check(
  '版本搜索：没有匹配的版本时说一句话，并给一个清除筛选',
  !noMatch.hidden && noMatch.text === '没有匹配的版本。 清除筛选' && noMatch.clear === '清除筛选',
  JSON.stringify(noMatch),
)

await clickIn('#versionEmptyClear')
await settle()
const cleared = await page.evaluate(() => ({
  url: window.location.search,
  rows: document.querySelectorAll('#versionList li').length,
  value: document.getElementById('versionQuery').value,
  current: document.querySelector('#versionFilters .is-current')?.firstChild.textContent.trim() ?? null,
  clearHidden: document.getElementById('versionQueryClear').hidden,
}))
check(
  '清除筛选：过滤与搜索一起清掉，回到全部六行，输入框也清空',
  !cleared.url.includes('filter=') &&
    !cleared.url.includes('q=') &&
    cleared.rows === 6 &&
    cleared.value === '' &&
    cleared.current === '全部' &&
    cleared.clearHidden === true,
  JSON.stringify(cleared),
)

await skillAt('?page=skill&tab=versions')
const beforeCreate = await page.evaluate(() => window.location.search)
await clickIn('#versionCreate')
await settle()
const afterCreate = await page.evaluate(() => ({
  url: window.location.search,
  rows: document.querySelectorAll('#versionList li').length,
}))
check(
  '新建版本：按下去什么都不发生——地址不动、列表还在（创建与编辑在下一个版本做）',
  afterCreate.url === beforeCreate && afterCreate.rows === 6,
  `${beforeCreate} → ${afterCreate.url} / ${afterCreate.rows} 行`,
)
check(
  '版本选择器：列出这一档读得到的全部版本，名称 + 版本号 + 状态 tag，不分组',
  JSON.stringify(versionsTab.picker) === JSON.stringify([
    { text: 'split 范围语法 @1.2.3 线上', disabled: false, tag: '线上' },
    { text: 'compress 补文档 草稿', disabled: false, tag: '草稿' },
    { text: 'split 拆页 @1.2.2 历史版本', disabled: false, tag: '历史版本' },
    { text: 'compress 收进 skill @1.2.1 已下架', disabled: false, tag: '已下架' },
    { text: '第一版 @0.9.0 历史版本', disabled: false, tag: '历史版本' },
  ]),
  JSON.stringify(versionsTab.picker),
)
const pinnedRevoked = await skillAt('?page=skill&tab=files&version=1.2.1')
check(
  '版本选择器：已下架版本对 maintain+ 是可选的，带「已下架」tag',
  JSON.stringify(pinnedRevoked.picker) === JSON.stringify([
    { text: 'split 范围语法 @1.2.3 线上', disabled: false, tag: '线上' },
    { text: 'compress 补文档 草稿', disabled: false, tag: '草稿' },
    { text: 'split 拆页 @1.2.2 历史版本', disabled: false, tag: '历史版本' },
    { text: 'compress 收进 skill @1.2.1 已下架', disabled: false, tag: '已下架' },
    { text: '第一版 @0.9.0 历史版本', disabled: false, tag: '历史版本' },
  ]),
  JSON.stringify(pinnedRevoked.picker),
)
const pinnedDraft = await skillAt('?page=skill&tab=files&version=7K3M9QX2')
check(
  '版本选择器：草稿对 write+ 可选，不再只在地址钉住时出现',
  pinnedDraft.picker.some((option) =>
    option.text === 'compress 补文档 草稿' && option.tag === '草稿' && !option.disabled),
  JSON.stringify(pinnedDraft.picker),
)
const pinnedDraftAsBrowse = await skillAt('?page=skill&tab=files&version=7K3M9QX2&as=browse')
check(
  'skill 页：看的是草稿时，表头右端进的是那一份草稿的页面',
  pinnedDraft.commitLink?.text === 'compress 补文档' &&
    pinnedDraft.commitLink.href === '?page=skill&tab=versions&version=7K3M9QX2' &&
    pinnedDraft.commitMeta === 'compress 补文档',
  JSON.stringify({ link: pinnedDraft.commitLink, meta: pinnedDraft.commitMeta }),
)
check(
  '版本选择器：读不到草稿的档位，地址钉住它时是禁用项',
  pinnedDraftAsBrowse.picker.some((option) =>
    option.text === 'compress 补文档 草稿' && option.tag === '草稿' && option.disabled),
  JSON.stringify(pinnedDraftAsBrowse.picker),
)
const pickerAsBrowse = await skillAt('?page=skill&tab=files&as=browse')
check(
  '版本选择器：浏览档只列线上与历史，不列草稿与已下架',
  JSON.stringify(pickerAsBrowse.picker.map((option) => option.text)) === JSON.stringify([
    'split 范围语法 @1.2.3 线上',
    'split 拆页 @1.2.2 历史版本',
    '第一版 @0.9.0 历史版本',
  ]),
  JSON.stringify(pickerAsBrowse.picker),
)
const pickerAsWrite = await skillAt('?page=skill&tab=files&as=write')
check(
  '版本选择器：写入档加上草稿，但没有已下架',
  JSON.stringify(pickerAsWrite.picker.map((option) => option.text)) === JSON.stringify([
    'split 范围语法 @1.2.3 线上',
    'compress 补文档 草稿',
    'split 拆页 @1.2.2 历史版本',
    '第一版 @0.9.0 历史版本',
  ]),
  JSON.stringify(pickerAsWrite.picker),
)
/* **下拉是权限那一屏的同一个控件**：按钮 + 页面自己画的菜单，不是一个原生 select。 */
await skillAt('?page=skill&tab=files&version=7K3M9QX2')
await clickIn('#versionPickerSlot .menu-trigger')
const pickerOpen = await page.evaluate(() => ({
  hidden: document.querySelector('#versionPickerSlot .menu-list').hidden,
  groups: [...document.querySelectorAll('#versionPickerSlot .menu-group-label')].map(
    (el) => el.textContent,
  ),
  disabled: [...document.querySelectorAll('#versionPickerSlot .menu-item:disabled')].map(
    (el) => el.textContent.trim(),
  ),
  checked: document.querySelectorAll('#versionPickerSlot .menu-item[aria-selected="true"]').length,
}))
check(
  '版本选择器：点开是同一张菜单，草稿可选，当前项带勾',
  !pickerOpen.hidden &&
    pickerOpen.groups.length === 0 &&
    pickerOpen.disabled.length === 0 &&
    pickerOpen.checked === 1,
  JSON.stringify(pickerOpen),
)
/* **长名字不把控件撑开（2026-10-20 定）**：按钮 160–220px，菜单固定 320px，两段文字
   都单行省略；完整文字留在 `title` 里。 */
const pickerSizes = await page.evaluate(() => {
  const trigger = document.querySelector('#versionPickerSlot .menu-trigger')
  const list = document.querySelector('#versionPickerSlot .menu-list')
  const label = document.querySelector('#versionPickerSlot .menu-label')
  const number = document.querySelector('#versionPickerSlot .menu-number')
  return {
    triggerMax: getComputedStyle(trigger).maxWidth,
    triggerOverflow: getComputedStyle(trigger.querySelector('.menu-trigger-text')).textOverflow,
    menuWidth: Math.round(list.getBoundingClientRect().width),
    labelOverflow: getComputedStyle(label).textOverflow,
    numberMono: getComputedStyle(number).fontFamily.includes('mono'),
  }
})
check(
  '版本选择器：长名字截断成省略号，按钮与菜单宽度都有上限',
  pickerSizes.triggerMax === '220px' && pickerSizes.triggerOverflow === 'ellipsis' &&
    pickerSizes.menuWidth === 320 && pickerSizes.labelOverflow === 'ellipsis' &&
    pickerSizes.numberMono,
  JSON.stringify(pickerSizes),
)
/* **搜索框（2026-10-20 定）**：输入即筛，按名称或版本号匹配；匹配不到就说没有，不把空列表当答案。 */
await page.type('#versionPickerSlot .menu-search-input', '1.2')
const narrowPicker = await page.evaluate(() => ({
  visible: [...document.querySelectorAll('#versionPickerSlot .menu-item')]
    .filter((item) => !item.hidden)
    .map((item) => item.textContent.replace(/\s+/g, ' ').trim()),
  empty: !document.querySelector('#versionPickerSlot .menu-empty').hidden,
}))
check(
  '版本选择器：搜索框输入即筛，只留名字匹配的版本',
  JSON.stringify(narrowPicker.visible) ===
    JSON.stringify([
      'split 范围语法 @1.2.3 线上',
      'split 拆页 @1.2.2 历史版本',
      'compress 收进 skill @1.2.1 已下架',
    ]) &&
    !narrowPicker.empty,
  JSON.stringify(narrowPicker),
)
/* **发布过的版本仍能按名称找到（2026-10-20 定）**：开发者记得住的是「拆页」，不是 @1.2.2。 */
await page.evaluate(() => {
  const input = document.querySelector('#versionPickerSlot .menu-search-input')
  input.value = '拆页'
  input.dispatchEvent(new Event('input', { bubbles: true }))
})
const draftNameSearch = await page.evaluate(() => ({
  visible: [...document.querySelectorAll('#versionPickerSlot .menu-item')]
    .filter((item) => !item.hidden)
    .map((item) => item.textContent.replace(/\s+/g, ' ').trim()),
}))
check(
  '版本选择器：搜索也匹配发布后留下的名称',
  JSON.stringify(draftNameSearch.visible) ===
    JSON.stringify(['split 拆页 @1.2.2 历史版本']),
  JSON.stringify(draftNameSearch),
)
await page.evaluate(() => {
  const input = document.querySelector('#versionPickerSlot .menu-search-input')
  input.value = 'zzz'
  input.dispatchEvent(new Event('input', { bubbles: true }))
})
const noHitsPicker = await page.evaluate(() => ({
  visible: [...document.querySelectorAll('#versionPickerSlot .menu-item')].filter((item) => !item.hidden).length,
  empty: !document.querySelector('#versionPickerSlot .menu-empty').hidden,
  text: document.querySelector('#versionPickerSlot .menu-empty').textContent,
}))
check(
  '版本选择器：没有匹配时说「没有匹配的版本。」',
  noHitsPicker.visible === 0 && noHitsPicker.empty &&
    noHitsPicker.text === '没有匹配的版本。',
  JSON.stringify(noHitsPicker),
)
await page.evaluate(() => {
  const input = document.querySelector('#versionPickerSlot .menu-search-input')
  input.value = ''
  input.dispatchEvent(new Event('input', { bubbles: true }))
})
await page.keyboard.press('Escape')
check(
  '版本选择器：Esc 关掉菜单，焦点回到按钮',
  (await page.$eval('#versionPickerSlot .menu-list', (el) => el.hidden)) &&
    (await page.evaluate(() =>
      document.activeElement?.classList.contains('menu-trigger') ?? false)),
  await page.evaluate(() => document.activeElement?.className ?? ''),
)
/* 在搜索框里按 Enter：选第一条匹配的版本，并且像点它一样跳过去。 */
await skillAt('?page=skill&tab=files')
await clickIn('#versionPickerSlot .menu-trigger')
await page.type('#versionPickerSlot .menu-search-input', '0.9')
await Promise.all([
  page.waitForNavigation({ waitUntil: 'load' }),
  page.keyboard.press('Enter'),
])
await settle()
const pickedBySearch = await page.evaluate(() => ({
  trigger: document.querySelector('#versionPickerSlot .menu-trigger')?.textContent ?? null,
  url: window.location.search,
}))
check(
  '版本选择器：搜索框里按 Enter 选第一条并跳过去',
  pickedBySearch.trigger === '第一版' && pickedBySearch.url.includes('version=0.9.0'),
  JSON.stringify(pickedBySearch),
)
/* **版本很多时，菜单不跟着长高（2026-10-20 定）。** `?versions=24` 往夹具里补出版本；
   菜单最高 360px，列表在搜索框下面自己滚，搜索框不动。 */
await skillAt('?page=skill&tab=files&versions=24')
await clickIn('#versionPickerSlot .menu-trigger')
const menuScroll = await page.evaluate(() => {
  const list = document.querySelector('#versionPickerSlot .menu-list')
  const box = document.querySelector('#versionPickerSlot .menu-box')
  const input = document.querySelector('#versionPickerSlot .menu-search-input')
  const before = input.getBoundingClientRect().top
  box.scrollTop = box.scrollHeight
  return {
    listHeight: Math.round(list.getBoundingClientRect().height),
    client: box.clientHeight,
    scroll: box.scrollHeight,
    overflow: getComputedStyle(box).overflowY,
    searchFixed: Math.abs(input.getBoundingClientRect().top - before) < 1,
    items: box.querySelectorAll('.menu-item').length,
  }
})
check(
  '版本选择器：版本多时菜单固定高度，列表出现滚动条、搜索框不动',
  menuScroll.listHeight === 360 &&
    menuScroll.scroll > menuScroll.client &&
    menuScroll.overflow === 'auto' &&
    menuScroll.searchFixed &&
    menuScroll.items === 29,
  JSON.stringify(menuScroll),
)
await page.keyboard.press('Escape')
check(
  'skill 页：地址里的分区不认识就落回「信息」',
  (await skillAt('?page=skill&tab=nope')).tab === '信息',
)
/* 带着路径的地址仍然落在文件那一栏：那是它自己说的去处，多了一个标签不该换屏。 */
check(
  'skill 页：带路径的地址落在「文件」，不落在「信息」',
  (await skillAt('?page=skill&path=references')).tab === '文件',
)

/* **一页六种人。** The ladder decides what is drawn, not what is greyed out — the rule is GitLab's
   `return unless can?(...)`: a person who cannot do a thing is not shown a control that refuses
   them. Each case below is one rung, and what it answers is what that person's page actually is. */
check(
  '标题下那一行是 title，写了才画',
  root.titleLine === '把 PDF 拆页、合并与压缩。',
  String(root.titleLine),
)
/* 缺席时服务端把 title 填成 name，所以「没有」在界面上是「这一行不画」——不是画一行空字，
   也不是把名字再写一遍。 */
check(
  '没有 title 的 skill，那一行整个不占高度',
  (await skillAt('?page=skill&state=no-title')).titleLine === null,
  String((await skillAt('?page=skill&state=no-title')).titleLine),
)

const asOwner = versionsTab
/* **版本表只导航。** 行内只有两样控件：版本名称（去那一版的页面）与右上角的「更多操作」
   （菜单里每一项也只是链接，去文件 / 改动 / 操作三栏）。所有改变版本状态的动作仍然只在
   那一版自己的页面上执行（ADR 0054）。 */
check(
  '版本表：行内只有名称与「更多操作」，菜单项也都是链接，没有任何动作按钮',
  asOwner.versionEntries.every((row) => row.extras === 0) &&
    asOwner.versionEntries.every((row) => /^\?page=skill&tab=versions&version=/.test(row.href)) &&
    asOwner.versionEntries.map((row) => row.name).join(',') ===
      'split 范围语法,compress 补文档,split 拆页,compress 收进 skill,把分页做成参数,第一版' &&
    /* 已丢弃没有入口菜单（PRD：没有入口菜单，按钉打得到只读单版页）；其余五行各有一枚。 */
    asOwner.versionMenus.filter((menu) => menu.trigger).length === 5 &&
    asOwner.versionMenus.filter((menu) => menu.trigger).every((menu) => menu.items.length >= 3),
  JSON.stringify({ rows: asOwner.versionEntries, menus: asOwner.versionMenus }),
)
check(
  '更多操作：菜单按状态给项，每一项都只是去那一版页面的链接',
  asOwner.versionMenus.map((menu) => (menu.trigger ? menu.items.join('/') : '—')).join(' | ') ===
    '查看文件/查看改动/回滚 | 查看文件/查看改动/上线/丢弃 | ' +
      '查看文件/查看改动/上线/下架 | 查看文件/查看改动/重新上架 | — | 查看文件/查看改动/上线/下架',
  JSON.stringify(asOwner.versionMenus),
)
const asMaintain = await skillAt('?page=skill&tab=versions&as=maintain')
check(
  '维护：版本表与所有者看到同一份（含已下架），但没有「设置」这个标签',
  asMaintain.versions.length === 6 && asMaintain.settingsTabHidden,
  `${asMaintain.versions.length} 行 · 设置栏${asMaintain.settingsTabHidden ? '不见了' : '还在'}`,
)
const asWrite = await skillAt('?page=skill&tab=versions&as=write')
check(
  '写入：看得见草稿，但已下架那一行不出现；版本表仍只导航',
  asWrite.versions.length === 4 && asWrite.settingsTabHidden,
  `${asWrite.versions.length} 行`,
)
const asBrowse = await skillAt('?page=skill&tab=versions&as=browse')
check(
  '浏览：草稿与已下架那一行都不出现，徽章也不再指进可见性',
  asBrowse.versions.length === 3 &&
    asBrowse.badgeHidden &&
    asBrowse.settingsTabHidden,
  `${asBrowse.versions.length} 行 · 徽章${asBrowse.badgeHidden ? '不画' : '还在'}`,
)
check(
  '更多操作（浏览）：只剩「查看文件 / 查看改动」，没有状态动作',
  asBrowse.versionMenus.length === 3 &&
    asBrowse.versionMenus.every((menu) => menu.trigger) &&
    asBrowse.versionMenus.every((menu) => menu.items.join('/') === '查看文件/查看改动'),
  JSON.stringify(asBrowse.versionMenus),
)
const asInvokeRows = await skillAt('?page=skill&tab=versions&as=invoke')
check(
  '更多操作（调用）：没有可去的地方，连按钮都不画',
  asInvokeRows.versionMenus.length === 3 &&
    asInvokeRows.versionMenus.every((menu) => !menu.trigger),
  JSON.stringify(asInvokeRows.versionMenus),
)
check(
  '版本表 · 调用者：行身份是版本号（等宽），行尾只写上线时间，不出现作者名与提交人',
  asInvokeRows.versionEntries.map((row) => row.name).join(',') === '@1.2.3,@1.2.2,@0.9.0' &&
    asInvokeRows.versionEntries.every((row) => row.mono) &&
    asInvokeRows.versionEntries.every((row) => row.meta.startsWith('上线于 ')) &&
    /* 悬停提示的第一行是身份：调用者那里它必须是版本号，不能是作者起的名称。 */
    asInvokeRows.versionEntries.every(
      (row) => row.title.split('\n')[0] === row.name && !row.meta.includes('dev'),
    ) &&
    asInvokeRows.versionQuery.placeholder === '搜索版本号或说明',
  JSON.stringify(asInvokeRows.versionEntries),
)
check(
  '版本表 · 版本码只给作者面：作者档每行一枚行 id（8 位短码或旧 26 位 ULID），调用档一行都没有',
  asOwner.versionEntries.every((row) =>
    /^([0-9A-HJKMNP-TV-Z]{8}|[0-9A-HJKMNP-TV-Z]{26})$/.test(row.code || '')) &&
    asBrowse.versionEntries.every((row) =>
      /^([0-9A-HJKMNP-TV-Z]{8}|[0-9A-HJKMNP-TV-Z]{26})$/.test(row.code || '')) &&
    asInvokeRows.versionEntries.every((row) => row.code === null) &&
    asInvokeRows.versionRows.every((row) => !row.sub.includes('version-code-slot')),
  JSON.stringify({
    author: asOwner.versionEntries.map((row) => row.code),
    invoke: asInvokeRows.versionEntries.map((row) => row.code),
  }),
)
check(
  '版本表 · 浏览仍是作者面的读法：作者起的名称、提交人与时间都在',
  asBrowse.versionEntries.map((row) => row.name).join(',') ===
    'split 范围语法,split 拆页,第一版' &&
    asBrowse.versionEntries.every((row) => !row.mono) &&
    asBrowse.versionEntries.every((row) => row.meta.startsWith('dev · ')),
  JSON.stringify(asBrowse.versionEntries),
)

/* **六档各看一面（2026-10-20 定）。** 一档一列：看得见几行、计数章上是几、过滤栏有哪几枚、
 * 每行右上角给什么、有没有「新建版本」。六档全部对照同一份夹具，差别只由档位决定。 */
const asAdminRows = await skillAt('?page=skill&tab=versions&as=admin')
const roleView = (view) => ({
  rows: view.versions.length,
  badge: view.versionCountBadge,
  filters: view.versionFilters.map((filter) => `${filter.label} ${filter.count}`).join(' / '),
  create: !view.versionCreate.hidden,
  menus: view.versionMenus
    .map((menu) => (menu.trigger ? menu.items.join('/') : '—'))
    .join(' | '),
})
const sameAsOwner = {
  rows: 6,
  badge: '4',
  filters: '全部 6 / 线上 1 / 草稿 1 / 历史版本 2 / 已下架 1 / 已丢弃 1',
  create: true,
  menus:
    '查看文件/查看改动/回滚 | 查看文件/查看改动/上线/丢弃 | ' +
    '查看文件/查看改动/上线/下架 | 查看文件/查看改动/重新上架 | — | ' +
    '查看文件/查看改动/上线/下架',
}
check(
  '六档的版本视图：行、计数、过滤、行菜单、新建版本，一档一面',
  JSON.stringify({
    owner: roleView(versionsTab),
    admin: roleView(asAdminRows),
    maintain: roleView(asMaintain),
    write: roleView(asWrite),
    browse: roleView(asBrowse),
    invoke: roleView(asInvokeRows),
  }) ===
    JSON.stringify({
      owner: sameAsOwner,
      admin: sameAsOwner,
      maintain: sameAsOwner,
      write: {
        rows: 4,
        badge: '3',
        filters: '全部 4 / 线上 1 / 草稿 1 / 历史版本 2',
        create: true,
        menus: '查看文件/查看改动 | 查看文件/查看改动 | 查看文件/查看改动 | 查看文件/查看改动',
      },
      browse: {
        rows: 3,
        badge: '3',
        filters: '全部 3 / 线上 1 / 历史版本 2',
        create: false,
        menus: '查看文件/查看改动 | 查看文件/查看改动 | 查看文件/查看改动',
      },
      invoke: {
        rows: 3,
        badge: '3',
        filters: '全部 3 / 线上 1 / 历史版本 2',
        create: false,
        menus: '— | — | —',
      },
    }),
  JSON.stringify({
    owner: roleView(versionsTab),
    admin: roleView(asAdminRows),
    maintain: roleView(asMaintain),
    write: roleView(asWrite),
    browse: roleView(asBrowse),
    invoke: roleView(asInvokeRows),
  }),
)
/* **发布区块先说当前线上是哪一版（2026-10-20 修）。** README §上线一个草稿与 PRD 范围 #5
   都要求这一行；演示件曾经只给建议号，把「线上是 @1.2.3」留给人自己从号里推。 */
const draftActions = await versionAt('?page=skill&tab=versions&version=7K3M9QX2&sub=actions')
check(
  '版本 tab · 草稿：发布区块第一句说清当前线上是哪一版',
  draftActions.actions[0]?.includes('当前线上是 @1.2.3') === true,
  draftActions.actions[0],
)

/* **说明的上限是 200 个码点，不是 200 个 UTF-16 单元（2026-10-20 修）。** 一个 emoji 在
   `maxLength` 里算两个、在服务端的 `char_length` 里算一个；不自己数，两边就会对不上。 */
await page.goto(base + '?page=skill&tab=versions&version=7K3M9QX2', { waitUntil: 'load' })
await settle()
await clickIn('#vpNoteEdit')
const noteCap = await page.evaluate(() => {
  const input = document.getElementById('vpNoteEditor')
  input.value = '😀'.repeat(201)
  input.dispatchEvent(new Event('input', { bubbles: true }))
  return { units: input.value.length, points: Array.from(input.value).length }
})
check(
  '版本 tab · 说明：200 个码点封顶——201 个 emoji 被截到 200 个',
  noteCap.points === 200 && noteCap.units === 400,
  JSON.stringify(noteCap),
)

/* **已丢弃：行留下、没有入口菜单，按钉打得到只读单版页（2026-10-20 修）。** 演示件曾经在
   丢弃时把行删掉，于是第五个状态在界面上根本不存在。 */
const discardedPage = await versionAt('?page=skill&tab=versions&version=Q4W8E2R6')
const discardedAsWrite = await versionAt('?page=skill&tab=versions&version=Q4W8E2R6&as=write')
check(
  '版本 tab · 已丢弃：只读单版页——没有动作、没有文件快照，写入档打不开',
  discardedPage.shown &&
    discardedPage.title === '把分页做成参数' &&
    discardedPage.meta.startsWith('提交于 ') &&
    discardedPage.states.includes('已丢弃') &&
    discardedPage.statusText.includes('从未上线') &&
    discardedPage.actions.length === 0 &&
    discardedPage.filesHidden &&
    !discardedPage.titlePencil &&
    !discardedPage.notePencil &&
    discardedAsWrite.missing,
  JSON.stringify({
    title: discardedPage.title,
    states: discardedPage.states,
    actions: discardedPage.actions.length,
    filesHidden: discardedPage.filesHidden,
    asWriteMissing: discardedAsWrite.missing,
  }),
)

/* **已丢弃不进低档位的比较选择器（2026-10-20 修）。** 改动页的两侧可见性：已丢弃要
   `maintain`；写入与浏览不该在这里读到它的名称与说明。 */
const compareAsWrite = await page.goto(base + '?page=skill&tab=versions&version=7K3M9QX2&as=write&sub=diff', { waitUntil: 'load' })
await settle()
const writeCompare = await page.evaluate(() =>
  [...document.querySelectorAll('#vpCompareSlot .menu-item')].map((el) => el.textContent.trim()),
)
const maintainCompare = await (async () => {
  await page.goto(base + '?page=skill&tab=versions&version=7K3M9QX2&sub=diff', { waitUntil: 'load' })
  await settle()
  return page.evaluate(() =>
    [...document.querySelectorAll('#vpCompareSlot .menu-item')].map((el) => el.textContent.trim()),
  )
})()
/* **默认基线也按档位解析（2026-10-20 修）。** @1.2.2 的上一版是已下架的 @1.2.1；
   浏览与写入读不到它，基线就该退回线上，而不是把一个看不见的版本画进改动页。 */
await page.goto(base + '?page=skill&tab=versions&version=1.2.2&as=browse&sub=diff', { waitUntil: 'load' })
await settle()
const browseBaseline = await page.evaluate(() => ({
  trigger: document.querySelector('#vpCompareSlot .menu-trigger')?.textContent.trim() ?? null,
  options: [...document.querySelectorAll('#vpCompareSlot .menu-item')].map((el) => el.textContent.trim()),
}))
check(
  '版本 tab · 改动：浏览打开 @1.2.2 时默认基线退回线上，不点名看不见的已下架 @1.2.1',
  browseBaseline.trigger === 'split 范围语法' &&
    !browseBaseline.options.some((text) => text.includes('compress 收进 skill')),
  JSON.stringify(browseBaseline),
)
/* 维护读得到 @1.2.1，所以同一页的默认基线仍是上一版——过滤只该过滤看不见的。 */
await page.goto(base + '?page=skill&tab=versions&version=1.2.2&sub=diff', { waitUntil: 'load' })
await settle()
const maintainBaseline = await page.evaluate(() =>
  document.querySelector('#vpCompareSlot .menu-trigger')?.textContent.trim() ?? null,
)
check(
  '版本 tab · 改动：维护及以上打开 @1.2.2 时默认基线仍是上一版 @1.2.1',
  maintainBaseline === 'compress 收进 skill（上一版）',
  maintainBaseline,
)

check(
  '版本 tab · 改动：比较选择器不给低档位看已丢弃，维护及以上看得到',
  !writeCompare.some((text) => text.includes('把分页做成参数') || text.includes('已丢弃')) &&
    maintainCompare.some((text) => text.includes('把分页做成参数') && text.includes('已丢弃')),
  JSON.stringify({ write: writeCompare, maintain: maintainCompare }),
)

/* **丢弃之后这一行还在，状态变「已丢弃」（2026-10-20 修）。** 成功不是跳回清单，而是就地
   变成只读的单版页；清单里那一行标着「已丢弃」。 */
await page.goto(base + '?page=skill&tab=versions&version=7K3M9QX2&sub=actions', { waitUntil: 'load' })
await settle()
await page.evaluate(() => {
  const block = [...document.querySelectorAll('#vpActionBody .vp-action')].find(
    (el) => el.querySelector('h3')?.textContent.trim() === '丢弃',
  )
  block.querySelector('.button').click()
})
await settle()
await clickIn('#dangerConfirmAccept')
await confirmOutcome()
await settle()
const afterDiscard = await page.evaluate(() => ({
  url: window.location.search,
  states: document.getElementById('vpStates').textContent.replace(/\s+/g, ' ').trim(),
  status: document.getElementById('vpStatusText').textContent,
  actions: document.querySelectorAll('#vpActionBody .vp-action').length,
  filesHidden: document.getElementById('vpFiles').hidden,
  rows: document.querySelectorAll('#versionList li').length,
  rowStates: [...document.querySelectorAll('#versionList .version-states')].map((el) =>
    el.textContent.trim(),
  ),
}))
check(
  '版本 tab · 丢弃：就地变只读，行仍在清单里并标着「已丢弃」',
  /version=7K3M9QX2/.test(afterDiscard.url) &&
    afterDiscard.states.includes('已丢弃') &&
    afterDiscard.status.includes('从未上线') &&
    afterDiscard.actions === 0 &&
    afterDiscard.filesHidden &&
    afterDiscard.rows === 6 &&
    afterDiscard.rowStates.filter((text) => text === '已丢弃').length === 2,
  JSON.stringify(afterDiscard),
)

const asAdmin = await skillAt('?page=skill&tab=settings&sub=delete&as=admin')
check(
  '管理：设置里只有协作者与可见性，危险操作不在',
  asAdmin.subs.join(',') === '常规,协作者,详细信息' &&
    asAdmin.sub === '常规' &&
    await page.evaluate(() => document.getElementById('dangerZone').hidden),
  asAdmin.subs.join(','),
)
/* **调用打得开「信息」与「版本」，没有文件与设置。** 版本清单是寻址的前提：地址本来就按版本调用
   （ADR 0035），列不出来就只能靠别人口头告诉他有哪几版。文件树与设置则都不是他的。 */
const asInvoke = await skillAt('?page=skill&as=invoke')
check(
  '调用：有「信息」与「版本」两栏，没有文件与设置',
  asInvoke.tab === '信息' && asInvoke.tabs.join(',') === '信息,版本',
  `${asInvoke.tabs.join(',')} · ${asInvoke.tab}`,
)
/* 草稿在 `renderVersions` 里就被滤掉了；版本表本身也没有动作可给。 */
check(
  '调用：版本表只列可读的三版（线上 + 历史版本），草稿与已下架不出现',
  asInvoke.versions.length === 3 &&
    asInvoke.versions.every((row) => !row.includes('草稿') && !row.includes('已下架')),
  `${asInvoke.versions.length} 行`,
)
/* 「信息」回答的是「要不要用它」：做什么、什么时候用、跑不跑得动、会动用什么、哪一版。
   许可、digest、`metadata` 的原文不在这里——它们是作者要的细节，在 设置 › 详细信息。 */
check(
  '调用：「信息」左栏读、右栏瞥——描述 / 适用场景 / 运行环境在左，版本在右',
  asInvoke.infoDesc.startsWith('把 PDF 拆页、合并与压缩。') &&
    asInvoke.infoWhen === '手里有 PDF，要拆页、合并或压缩的时候' &&
    asInvoke.infoEnv === '需要 ghostscript 10 以上' &&
    asInvoke.railVersion === '@1.2.3' &&
    asInvoke.infoFacts.length === 0,
  `${asInvoke.infoWhen} · ${asInvoke.infoEnv} · 右栏 ${asInvoke.railVersion}`,
)
/* `allowed-tools` 在这一版不显示：我们既不放行也不阻止，摆出来只会让人以为我们发了授权。
   它仍然在「设置 › 详细信息」里按字段名排。 */
check(
  '调用：「信息」里没有 allowed-tools',
  !/Bash|Read/.test(asInvoke.infoText),
  asInvoke.infoText.slice(0, 60),
)
/* 两块主要属性用同一种排法：都有小标题。少一个，另一块看起来就像附注。 */
/* 左栏三段，同一种排法：标记 + 小标题 + 正文。少一个，另一块看起来就像附注。 */
check(
  '左栏三段各有小标题，排法一致',
  asInvoke.infoLabels.join(',') === '描述,适用场景,运行环境',
  asInvoke.infoLabels.join(','),
)
/* **卡片高度跟着内容走，不撑满一屏。** 钉住的是反面：卡片的高度不该明显大于它的内容——
   底下空出一大块，框就不再像一个装满了东西的物体。 */
const cardBox = await page.evaluate(() => {
  const card = document.querySelector('.info-main')
  const content = [...card.children].reduce((sum, el) => sum + el.getBoundingClientRect().height, 0)
  return { h: Math.round(card.getBoundingClientRect().height), content: Math.round(content) }
})
check(
  '信息页的卡片高度贴着内容，不在框里留一大段空',
  cardBox.h - cardBox.content < 120,
  `卡片 ${cardBox.h}px · 内容 ${cardBox.content}px`,
)
/* 分隔不靠标题、不靠底色：**左栏是一张卡，右栏在卡外**（GitHub 的文件区 + About 栏就是这个结构）。 */
check(
  '左栏是一张带边框的卡，右栏在卡外、自己是干净的一行',
  parseFloat(asInvoke.mainBorder) > 0 && parseFloat(asInvoke.railBorder) === 0 &&
    asInvoke.railLabel === '版本' && asInvoke.railVersion === '@1.2.3',
  `左栏边框 ${asInvoke.mainBorder} · 右栏边框 ${asInvoke.railBorder} · ${asInvoke.railLabel} ${asInvoke.railVersion}`,
)
/* 标签的位置留着（`#infoTags` + 渲染逻辑），但这一版不显示——它是一个为以后留的空位，不是漏画。 */
check(
  '标签这一版不显示，但槽位还在',
  asInvoke.infoTags.length === 0 &&
    (await page.evaluate(() => document.getElementById('infoTags').hidden)),
  `${asInvoke.infoTags.length} 个标签`,
)
/* 细节没有消失，只是挪到了只有作者该看的那一栏。 */
check(
  '设置 › 详细信息：digest、许可、作者自己的键都在这里',
  (await skillAt('?page=skill&tab=settings&sub=details')).details.some((row) => /sha256/.test(row)) &&
    (await skillAt('?page=skill&tab=settings&sub=details')).details.some((row) => /许可 MIT/.test(row)) &&
    (await skillAt('?page=skill&tab=settings&sub=details')).details.some((row) => /owner: dev/.test(row)),
  (await skillAt('?page=skill&tab=settings&sub=details')).details.map((r) => r.split(' ')[0]).join(','),
)
check(
  '调用：「信息」里没有 digest、没有许可',
  !/sha256|许可/.test(asInvoke.infoText),
  asInvoke.infoText.slice(0, 80),
)
/* **差异是「发布」tab 上的审阅材料。** 上线一个草稿、把一个旧版本重新上线，看的都是它；
   版本表本身不再画差异——它只列入口。 */
const draftPage = await versionAt('?page=skill&tab=versions&version=7K3M9QX2')
check(
  '版本 tab：草稿的标题是名称，还没有版本号，带着状态章',
  draftPage.shown && !draftPage.missing && draftPage.title === 'compress 补文档' &&
    !draftPage.titleMono && draftPage.numberHidden &&
    draftPage.states === '草稿' &&
    !draftPage.filesHidden && draftPage.filesLabel === '查看文件' &&
    draftPage.filesHref === '?page=skill&tab=files&version=7K3M9QX2',
  `${draftPage.title} / ${draftPage.states}`,
)
check(
  '版本 tab：草稿写「提交于」与提交人，说明是正文',
  draftPage.meta === '提交于 2026/10/16 10:12 · yuhan' &&
    draftPage.note === '把 compress 的用法补进正文：参数怎么给、输出放在哪，以及扫描件要先 OCR 的那一段。' &&
    /* 小标题与信息页同一套：13px、强调色图标，图标是描述那一枚。 */
    draftPage.noteLabel === '说明' && draftPage.noteLabelIcon === '#descMark',
  `${draftPage.meta} · ${draftPage.note} · ${draftPage.noteLabel}`,
)
check(
  '版本 tab · 状态条：先说后果，再说动作；草稿的调用者读不到它',
  draftPage.statusTone.includes('is-draft') &&
    draftPage.statusText.includes('这个版本还没有上线') &&
    draftPage.statusText.includes('调用者读不到它') &&
    !draftPage.jumpHidden && draftPage.jumpText === '上线' &&
    draftPage.jumpHref.includes('sub=actions'),
  `${draftPage.statusTone} / ${draftPage.statusText} / ${draftPage.jumpText}`,
)
check(
  '版本 tab · 事实栏：名称、版本号、版本码、提交、首次上线、带短码的地址与时间线；空的「最近状态变更」不画',
  draftPage.facts.length === 9 &&
    draftPage.facts[0].label === '名称' && draftPage.facts[0].value === 'compress 补文档' &&
    draftPage.facts[1].label === '版本号' && draftPage.facts[1].value === '—' &&
    draftPage.facts[2].label === '版本码' && draftPage.facts[2].value === '7K3M9QX2' &&
    draftPage.facts[3].value === '草稿' &&
    draftPage.facts[4].value === 'yuhan · 2026/10/16 10:12' &&
    draftPage.facts[5].value === '—' &&
    draftPage.facts[8].label === '地址' &&
    draftPage.facts[8].value === 'dev/pdf-tools@7K3M9QX2' &&
    !draftPage.facts.some((row) => row.label === '最近状态变更') &&
    !draftPage.facts.some((row) => row.label === '版本标识') &&
    draftPage.timeline.join(' | ') === '提交 2026/10/16 10:12',
  JSON.stringify({ facts: draftPage.facts, timeline: draftPage.timeline }),
)
check(
  '版本 tab · 事实栏：复制是图标动作，没有可见文字，名字留给读屏',
  draftPage.copyButtons.length === 4 &&
    draftPage.copyButtons.map((button) => button.label).join(',') ===
      '复制名称,复制版本码,复制内容摘要,复制地址' &&
    draftPage.copyButtons.every((button) => button.icon && button.text === ''),
  JSON.stringify(draftPage.copyButtons),
)
/* 复制成功才换对勾；1.2 秒后回到复制图标。失败不假装成功。 */
await page.evaluate(() => document.querySelector('#vpFactsList .vp-copy').click())
await settle()
const copied = await page.evaluate(() => {
  const button = document.querySelector('#vpFactsList .vp-copy')
  return {
    done: button.classList.contains('is-done'),
    label: button.getAttribute('aria-label'),
    title: button.title,
  }
})
check(
  '版本 tab · 复制：点一下换成对勾，读屏名字说已复制',
  copied.done && copied.label === '已复制名称' && copied.title === '已复制',
  JSON.stringify(copied),
)
await new Promise((resolve) => setTimeout(resolve, 1400))
const copyReset = await page.evaluate(() => {
  const button = document.querySelector('#vpFactsList .vp-copy')
  return { done: button.classList.contains('is-done'), label: button.getAttribute('aria-label') }
})
check(
  '版本 tab · 复制：对勾驻留后回到复制图标',
  !copyReset.done && copyReset.label === '复制名称',
  JSON.stringify(copyReset),
)
check(
  '版本 tab · 概览：三个页签，改动带着文件数与总行数，概览是默认那一栏',
  !draftPage.tabsHidden &&
    draftPage.tabs.join(' | ') === '概览 | 改动 2 | 操作' &&
    draftPage.currentTab === '概览' &&
    !draftPage.recordHidden && draftPage.diffPaneHidden && draftPage.actionsPaneHidden &&
    draftPage.changeStats === '+4 −1',
  JSON.stringify({ tabs: draftPage.tabs, stats: draftPage.changeStats }),
)
check(
  '版本 tab · 概览是纯阅读页：动作与危险区不在这一栏里',
  draftPage.actionsInOverview === false &&
    draftPage.actions.length === 2 && draftPage.dangerHidden,
  `${draftPage.actions.length} 块动作 · 危险区${draftPage.dangerHidden ? '不画' : '画了'}`,
)
/* **改动是一栏，不是页面上的一段。** 文件树在左、一个文件一块在右，对照线上。 */
const draftChanges = await versionAt('?page=skill&tab=versions&version=7K3M9QX2&sub=diff')
check(
  '版本 tab · 改动：对照线上，文件树列出两个文件，逐文件一块',
  draftChanges.currentTab === '改动 2' &&
    draftChanges.recordHidden && !draftChanges.diffPaneHidden &&
    /* 草稿没有「上一版」，默认退回当前线上——它上线时要替换的那一版。 */
    draftChanges.compareValue === '@1.2.3' &&
    draftChanges.compareOptions.includes('split 范围语法 @1.2.3 线上') &&
    draftChanges.fileCount === '2 个文件' &&
    draftChanges.fileTree.join(' | ') === 'SKILL.md +2 −1 | references/split.md +2 −0' &&
    draftChanges.files.map((file) => file.path).join(',') === 'SKILL.md,references/split.md' &&
    draftChanges.files[0].stats === '+2 −1' &&
    draftChanges.files[0].lines === 11 && draftChanges.files[0].nums === 11 &&
    draftChanges.files[0].adds === 2 &&
    draftChanges.files.every((file) => !file.collapsed),
  JSON.stringify({ title: draftChanges.diffTitle, tree: draftChanges.fileTree }),
)
await clickIn('#vpFile0 .vp-file-toggle')
check(
  '版本 tab · 改动：一个文件可以单独收起，按钮说得出状态',
  (await page.$eval('#vpFile0 .diff-body', (el) => el.hidden)) &&
    (await page.$eval('#vpFile0 .vp-file-toggle', (el) => el.getAttribute('aria-expanded'))) ===
      'false' &&
    !(await page.$eval('#vpFile1 .diff-body', (el) => el.hidden)),
  await page.$eval('#vpFile0 .vp-file-toggle', (el) => el.getAttribute('aria-expanded')),
)
/* **清单上方那颗筛选框（2026-10-20 定）。** 它只收窄左边这份清单，右栏的文件块不动；
   值写进地址的 `fq`，所以截图与直接打开都能落到同一样结果。 */
const filteredChanges = await versionAt('?page=skill&tab=versions&version=7K3M9QX2&sub=diff&fq=split')
check(
  '版本 tab · 改动：清单上方有筛选框，按路径收窄左边这份清单，右栏不动',
  filteredChanges.fileQuery.value === 'split' &&
    filteredChanges.fileQuery.placeholder === '搜索文件路径' &&
    filteredChanges.fileQuery.clearHidden === false &&
    filteredChanges.fileQuery.rows === 2 && filteredChanges.fileQuery.visible === 1 &&
    filteredChanges.fileQuery.emptyHidden === true &&
    filteredChanges.files.length === 2,
  JSON.stringify(filteredChanges.fileQuery),
)
const noMatchChanges = await versionAt('?page=skill&tab=versions&version=7K3M9QX2&sub=diff&fq=zzz')
check(
  '版本 tab · 改动：筛不到时清单留一句「没有匹配的文件」，带清除',
  noMatchChanges.fileQuery.visible === 0 &&
    noMatchChanges.fileQuery.emptyHidden === false &&
    noMatchChanges.fileQuery.emptyText === '没有匹配的文件。 清除筛选',
  JSON.stringify(noMatchChanges.fileQuery),
)
/* 边打边筛：不重新加载，值跟着写进地址。 */
await versionAt('?page=skill&tab=versions&version=7K3M9QX2&sub=diff')
await type('#vpFileQuery', 'SKILL')
const typedFileQuery = await page.evaluate(() => ({
  fq: new URLSearchParams(location.search).get('fq'),
  visible: [...document.querySelectorAll('#vpFileTree li:not([hidden]) .vp-tree-item')].length,
  clearHidden: document.getElementById('vpFileQueryClear').hidden,
}))
check(
  '版本 tab · 改动：边打边筛，字写进地址，清除叉出现',
  typedFileQuery.fq === 'SKILL' && typedFileQuery.visible === 1 &&
    typedFileQuery.clearHidden === false,
  JSON.stringify(typedFileQuery),
)
/* **大改动：清单先到、内容按需、单文件分段（2026-10-20 定）。** 124 个文件、一个 3,200 行
   的文件、一个二进制、一个超限文件；`load=1` 让当前文件立刻加载，`manual=1` 关掉进入视野
   自动加载，好让「首屏只有文件头」这一态能被稳定地读到。 */
const bigSingle = await versionAt(
  '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&load=1',
)
check(
  '版本 tab · 大改动：默认一次只显示一个文件，124 个文件都在树上',
  bigSingle.big.mode !== null &&
    bigSingle.big.pager.includes('1 / 124') &&
    bigSingle.big.blocks === 1 &&
    bigSingle.files.length === 1 &&
    bigSingle.files[0].nums === 14 &&
    bigSingle.files[0].stats === '+5 −2' &&
    bigSingle.fileTree.length === 124 &&
    bigSingle.changeStats === '+1418 −490' &&
    bigSingle.big.note === '这次改动有 124 个文件。文件内容按需加载。',
  JSON.stringify({ pager: bigSingle.big.pager, blocks: bigSingle.big.blocks }),
)
const bigNext = await page.evaluate(async () => {
  const next = [...document.querySelectorAll('.vp-file-pager a')][1]
  if (!next) return null
  const target = next.href
  next.click()
  return target
})
await page.waitForNavigation({ waitUntil: 'load' })
await settle()
const bigSecond = await page.evaluate(() => ({
  pager: document.querySelector('.vp-file-pager')?.textContent.replace(/\s+/g, ' ').trim() ?? null,
  path: document.querySelector('#vpFileList .vp-file-path')?.textContent ?? null,
  blocks: document.querySelectorAll('#vpFileList .vp-file').length,
}))
check(
  '版本 tab · 大改动：下一页只换那一个文件，不同时取第二个',
  bigNext !== null &&
    bigSecond.blocks === 1 &&
    bigSecond.path === 'scripts/batch.sh' &&
    bigSecond.pager.includes('2 / 124'),
  JSON.stringify(bigSecond),
)
const bigLongPage = await versionAt(
  '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&load=1&file=121',
)
check(
  '版本 tab · 大改动：单文件首屏只画 400 行，并说清还剩多少',
  bigLongPage.files[0].nums === 400 &&
    bigLongPage.files[0].more.includes('2800') &&
    bigLongPage.files[0].more.includes('显示更多（500 行）') &&
    bigLongPage.files[0].stats === '+457 −249',
  JSON.stringify({ nums: bigLongPage.files[0].nums, more: bigLongPage.files[0].more }),
)
await clickIn('#vpFileList .diff-more .link-button')
const bigMore = await page.evaluate(() => ({
  nums: document.querySelectorAll('#vpFileList .diff-num').length,
  more: document.querySelector('#vpFileList .diff-more')?.textContent.replace(/\s+/g, ' ').trim() ?? null,
}))
check(
  '版本 tab · 大改动：显示更多每次追加 500 行，剩余行数跟着减',
  bigMore.nums === 900 && bigMore.more.includes('2300'),
  JSON.stringify(bigMore),
)
const bigAll = await versionAt(
  '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&all=1&manual=1',
)
check(
  '版本 tab · 大改动：没有进入视野的文件体一律不取，行数仍跟清单一起到',
  bigAll.files.length === 124 &&
    bigAll.files.slice(0, 122).every((file) => file.nums === 0 && /^\+\d+ −\d+$/.test(file.stats)) &&
    bigAll.files[122].stats === '—' && bigAll.files[123].stats === '—' &&
    bigAll.files.filter((file) => file.pending && file.pending.startsWith('进入视野后加载')).length === 122 &&
    bigAll.files[122].pending === '二进制文件，不显示内容。' &&
    bigAll.files[123].pending.startsWith('这个文件太大，没有展开'),
  JSON.stringify({
    blocks: bigAll.files.length,
    first: bigAll.files[0].pending,
    binary: bigAll.files[122].pending,
    big: bigAll.files[123].pending,
  }),
)
const bigListOnly = await versionAt(
  '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&list=1',
)
check(
  '版本 tab · 大改动：只看文件列表时一个正文都不画，树与计数仍在',
  bigListOnly.big.blocks === 0 &&
    bigListOnly.files.length === 0 &&
    bigListOnly.fileTree.length === 124 &&
    bigListOnly.fileTree.filter((row) => row.endsWith('—')).length === 2 &&
    bigListOnly.fileTree.filter((row) => /\+\d+ −\d+$/.test(row)).length === 122 &&
    bigListOnly.big.listOnly === '逐个查看' &&
    bigListOnly.big.listNote !== null,
  JSON.stringify({ blocks: bigListOnly.big.blocks, tree: bigListOnly.fileTree.length }),
)
const bigFailed = await versionAt(
  '?page=skill&tab=versions&version=1.2.3&sub=diff&big=1&load=1&difffail=1',
)
check(
  '版本 tab · 大改动：加载失败留在这一块里，原因与重试都在',
  bigFailed.files[0].pending !== null &&
    bigFailed.files[0].pending.includes('加载失败') &&
    bigFailed.files[0].pending.includes('重试') &&
    bigFailed.files[0].stats === '+5 −2',
  JSON.stringify({ pending: bigFailed.files[0].pending }),
)
await clickIn('#vpFileList .diff-failed .link-button')
await new Promise((done) => setTimeout(done, 350))
const bigRetried = await page.evaluate(() => ({
  nums: document.querySelectorAll('#vpFileList .diff-num').length,
  failed: document.querySelector('#vpFileList .diff-failed') !== null,
  stats: document.querySelector('#vpFileList .diff-stats')?.textContent.trim() ?? null,
}))
check(
  '版本 tab · 大改动：重试成功，正文出现在同一块里',
  bigRetried.nums === 14 && !bigRetried.failed && bigRetried.stats === '+5 −2',
  JSON.stringify(bigRetried),
)

/* **动作在「操作」这一栏。** 点页签切过去——下面的表单与按钮都在那一栏里。
   大改动的几条检查刚把页面带到别的版本，这里先回到这一版的概览，再从页签切过去。 */
await page.goto(base + '?page=skill&tab=versions&version=7K3M9QX2', { waitUntil: 'load' })
await settle()
await Promise.all([
  page.waitForNavigation({ waitUntil: 'load' }),
  page.click('#vpTabs [data-vpane="actions"]'),
])
await settle()
const draftActionsPane = await page.evaluate(() => ({
  currentTab: document.querySelector('#vpTabs .vp-tab.is-current')?.textContent.trim() ?? null,
  recordHidden: document.getElementById('vpRecordPane').hidden,
  actionsPaneHidden: document.getElementById('vpActionsPane').hidden,
  url: window.location.search,
  overviewButtons: document.querySelectorAll('#vpRecordPane .button').length,
}))
check(
  '版本 tab · 操作：点页签切过去，概览让位；地址带着 sub=actions',
  draftActionsPane.currentTab === '操作' &&
    draftActionsPane.recordHidden && !draftActionsPane.actionsPaneHidden &&
    draftActionsPane.url.includes('sub=actions') &&
    draftActionsPane.overviewButtons === 0,
  JSON.stringify(draftActionsPane),
)
check(
  '版本 tab · 草稿：操作里是上线与丢弃（改名与改说明不在这里），发布表单问改动类型并给建议号',
  draftPage.actions.length === 2 &&
    draftPage.actions[0].includes('上线') &&
    draftPage.actions[1].includes('丢弃') && /无法找回/.test(draftPage.actions[1]) &&
    /不会再上线/.test(draftPage.actions[1]) && /维护及以上仍能在版本表里看到/.test(draftPage.actions[1]) &&
    /恢复的入口/.test(draftPage.actions[1]) &&
    draftPage.bumpValues.join(',') === 'patch,minor,major' &&
    draftPage.bumpLabel === '这次改动会影响旧的调用吗' &&
    JSON.stringify(draftPage.bumpTitles) === JSON.stringify(['只是修正', '新增能力', '旧调用会失效']) &&
    JSON.stringify(draftPage.bumpNotes) === JSON.stringify([
      '修好了问题，调用方式不变 · 1.2.4',
      '多了新东西，旧调用照旧能用 · 1.3.0',
      '旧的调用要改，才能继续用 · 2.0.0',
    ]) &&
    draftPage.release === '1.2.4' &&
    draftPage.releaseNoteField === false &&
    draftPage.actionButtons.join(',') === '上线 @1.2.4,丢弃' &&
    /* **丢弃不染红、也不填充**：它不是危险动作，按钮是描边的普通按钮（2026-10-20 定）。 */
    draftPage.actionButtonClasses[1] === 'button is-quiet',
  JSON.stringify(draftPage),
)

/* **丢弃不是危险动作，但无法找回（2026-10-20 定）。** 它不进页底危险区，确认钮不染红；
   弹窗正文的第一句就说「无法找回」——这是这次确认唯一要回答的问题。 */
await page.goto(base + '?page=skill&tab=versions&version=7K3M9QX2&sub=actions', {
  waitUntil: 'load',
})
await settle()
const discardButton = await page.$('#vpActionBody .button.is-quiet')
await discardButton.click()
await settle()
const askingDiscard = await page.evaluate(() => ({
  open: !document.getElementById('dangerConfirm').hidden,
  title: document.getElementById('dangerConfirmTitle').textContent,
  body: document.getElementById('dangerConfirmBody').textContent,
  accept: document.getElementById('dangerConfirmAccept').textContent,
  acceptClass: document.getElementById('dangerConfirmAccept').className,
  focus: document.activeElement?.id,
}))
check(
  '版本 tab · 丢弃：弹窗说清这一版无法找回，确认钮不染红；危险区在这版上不画',
  askingDiscard.open &&
    askingDiscard.title === '丢弃这个版本？' &&
    /无法找回/.test(askingDiscard.body) &&
    askingDiscard.accept === '确认丢弃' &&
    askingDiscard.acceptClass === 'button' &&
    askingDiscard.focus === 'dangerConfirmCancel' &&
    draftPage.dangerHidden,
  JSON.stringify(askingDiscard),
)
await clickIn('#dangerConfirmCancel')
await settle()

/* **改名在标题上、改说明在概览上（2026-10-20 定）。** 铅笔把正文换成输入框，保存/取消
   跟着；Esc 取消。两者都不再是「操作」里的动作块。 */
await page.goto(base + '?page=skill&tab=versions&version=7K3M9QX2', { waitUntil: 'load' })
await settle()
const pencils = await page.evaluate(() => ({
  title: !document.getElementById('vpTitleEdit').hidden,
  note: !document.getElementById('vpNoteEdit').hidden,
  titleLabel: document.getElementById('vpTitleEdit').getAttribute('aria-label'),
  noteLabel: document.getElementById('vpNoteEdit').getAttribute('aria-label'),
}))
check(
  '版本 tab · 草稿：标题与说明旁各有一个铅笔，读屏名字说得出改什么',
  pencils.title && pencils.note &&
    pencils.titleLabel === '改名' && pencils.noteLabel === '编辑说明',
  JSON.stringify(pencils),
)
await clickIn('#vpTitleEdit')
const titleEditor = await page.evaluate(() => ({
  open: !!document.getElementById('vpTitleEditor'),
  value: document.querySelector('#vpTitleEditor input')?.value ?? null,
  buttons: [...document.querySelectorAll('#vpTitleEditor .button')].map((b) => b.textContent),
  titleHidden: document.getElementById('vpTitle').hidden,
}))
check(
  '版本 tab · 改名：点铅笔，标题就地变成输入框，带保存与取消',
  titleEditor.open && titleEditor.titleHidden &&
    titleEditor.value === 'compress 补文档' &&
    titleEditor.buttons.join(',') === '保存,取消',
  JSON.stringify(titleEditor),
)
/* Enter 保存：标题与面包屑一起换，铅笔回来。 */
await page.evaluate(() => {
  const input = document.querySelector('#vpTitleEditor input')
  input.value = 'compress 用法补文档'
  input.dispatchEvent(new Event('input', { bubbles: true }))
})
await page.keyboard.press('Enter')
await settle()
const renamed = await page.evaluate(() => ({
  title: document.getElementById('vpTitle').textContent,
  crumb: document.getElementById('vpCrumb').textContent,
  editorGone: !document.getElementById('vpTitleEditor'),
  pencilBack: !document.getElementById('vpTitleEdit').hidden,
  focus: document.activeElement?.id ?? null,
}))
check(
  '版本 tab · 改名：Enter 保存，标题与面包屑一起换，焦点回到铅笔',
  renamed.title === 'compress 用法补文档' &&
    renamed.crumb === 'compress 用法补文档' &&
    renamed.editorGone && renamed.pencilBack && renamed.focus === 'vpTitleEdit',
  JSON.stringify(renamed),
)
/* Esc 取消：改到一半退出来，标题不动。 */
await clickIn('#vpTitleEdit')
await page.evaluate(() => {
  const input = document.querySelector('#vpTitleEditor input')
  input.value = '不该被保存的名字'
  input.dispatchEvent(new Event('input', { bubbles: true }))
})
await page.keyboard.press('Escape')
await settle()
const cancelled = await page.evaluate(() => ({
  title: document.getElementById('vpTitle').textContent,
  editorGone: !document.getElementById('vpTitleEditor'),
  focus: document.activeElement?.id ?? null,
}))
check(
  '版本 tab · 改名：Esc 取消，标题保持原样，焦点回到铅笔',
  cancelled.title === 'compress 用法补文档' &&
    cancelled.editorGone && cancelled.focus === 'vpTitleEdit',
  JSON.stringify(cancelled),
)
/* 说明：点铅笔，正文就地变成多行框；Cmd/Ctrl+Enter 保存。 */
await clickIn('#vpNoteEdit')
const noteEditor = await page.evaluate(() => ({
  open: !!document.getElementById('vpNoteEditor'),
  value: document.getElementById('vpNoteEditor')?.value ?? null,
  buttons: [...document.querySelectorAll('#vpNoteModule .vp-inline-acts .button')].map((b) => b.textContent),
}))
check(
  '版本 tab · 改说明：点铅笔，概览上的说明就地变成多行框',
  noteEditor.open &&
    noteEditor.value === '把 compress 的用法补进正文：参数怎么给、输出放在哪，以及扫描件要先 OCR 的那一段。' &&
    noteEditor.buttons.join(',') === '保存,取消',
  JSON.stringify(noteEditor),
)
await page.evaluate(() => {
  const input = document.getElementById('vpNoteEditor')
  input.value = '把 compress 的用法与退出码补进正文'
  input.dispatchEvent(new Event('input', { bubbles: true }))
})
await page.keyboard.down('Meta')
await page.keyboard.press('Enter')
await page.keyboard.up('Meta')
await settle()
const noteSaved = await page.evaluate(() => ({
  note: document.getElementById('vpNote').textContent,
  editorGone: !document.getElementById('vpNoteEditor'),
  pencilBack: !document.getElementById('vpNoteEdit').hidden,
}))
check(
  '版本 tab · 改说明：保存后说明就地更新，铅笔回来',
  noteSaved.note === '把 compress 的用法与退出码补进正文' &&
    noteSaved.editorGone && noteSaved.pencilBack,
  JSON.stringify(noteSaved),
)

/* 回到「操作」接着走发布流程。换一页会按地址重建夹具，刚才那两次就地编辑也回到初始值——
   这是演示件的方式，不影响上面已经断言过的结果。 */
await Promise.all([
  page.waitForNavigation({ waitUntil: 'load' }),
  page.click('#vpTabs [data-vpane="actions"]'),
])
await settle()

/* 就地改名之后再上线：改的是这一条草稿本身，发布后名称也跟着换成新的。 */
await clickIn('#vpTitleEdit')
/* **名称最多 40 个 Unicode 码点（2026-10-20 定）。** 中文、英文、数字、符号都行，只限长度；
   超出的部分当场截掉，不把长名字带出去。按码点数，不按 UTF-16 单元数。 */
const nameLimit = await page.evaluate(() => {
  const input = document.querySelector('#vpTitleEditor input')
  input.value = '名'.repeat(41)
  input.dispatchEvent(new Event('input', { bubbles: true }))
  const afterCjk = input.value.length
  input.value = '😀'.repeat(41)
  input.dispatchEvent(new Event('input', { bubbles: true }))
  return {
    afterCjk,
    afterEmoji: Array.from(input.value).length,
    label: input.getAttribute('aria-label'),
    title: input.title,
  }
})
check(
  '版本 tab · 改名：名称最多 40 个字符，中文与符号都行，按码点截断',
  nameLimit.afterCjk === 40 && nameLimit.afterEmoji === 40 &&
    nameLimit.label.includes('最多 40 个字符') &&
    nameLimit.title.includes('中文、英文、数字与符号'),
  JSON.stringify(nameLimit),
)
await page.evaluate(() => {
  const input = document.querySelector('#vpTitleEditor input')
  input.value = 'compress 用法补文档'
  input.dispatchEvent(new Event('input', { bubbles: true }))
})
await page.keyboard.press('Enter')
await settle()

/* 换一个 bump，建议号跟着换；按下按钮才上线。**按文字找上线按钮**——草稿页现在还有一块
   「丢弃」，它的按钮排在后面，用 `:last-child` 会点错。 */
await clickIn('input[name="vp-bump"][value="minor"]')
const publishLabel = await page.evaluate(
  () => [...document.querySelectorAll('#vpActionBody .button')]
    .find((el) => el.textContent.startsWith('上线 @'))?.textContent ?? null,
)
check(
  '版本 tab · 上线草稿：换 bump 换建议号，按钮跟着说',
  (await page.$eval('#vpRelease', (el) => el.value)) === '1.3.0' &&
    publishLabel === '上线 @1.3.0',
)
await page.evaluate(
  () => [...document.querySelectorAll('#vpActionBody .button')]
    .find((el) => el.textContent.startsWith('上线 @')).click(),
)
await settle()
/* **上线先弹窗**（2026-10-20 定）：说清这一刻名称与说明锁定、调用者会读到它们。
   它不是危险动作——确认钮不染红，取消仍是默认焦点。 */
const askingPublish = await page.evaluate(() => ({
  open: !document.getElementById('dangerConfirm').hidden,
  title: document.getElementById('dangerConfirmTitle').textContent,
  body: document.getElementById('dangerConfirmBody').textContent,
  cancel: document.getElementById('dangerConfirmCancel').textContent,
  accept: document.getElementById('dangerConfirmAccept').textContent,
  acceptClass: document.getElementById('dangerConfirmAccept').className,
  focus: document.activeElement?.id ?? null,
}))
check(
  '版本 tab · 上线：弹窗说清名称与说明从此锁定，确认钮不染红',
  askingPublish.open && askingPublish.title === '上线 compress 用法补文档？' &&
    askingPublish.body.includes('这一版会拿到版本号 @1.3.0') &&
    askingPublish.body.includes('名称与说明在这一刻锁定') &&
    askingPublish.body.includes('上线后不能再改') &&
    askingPublish.accept === '确认上线' &&
    !askingPublish.acceptClass.includes('is-danger') &&
    askingPublish.focus === 'dangerConfirmCancel',
  JSON.stringify(askingPublish),
)
await clickIn('#dangerConfirmAccept')
await confirmOutcome()
await settle()
const published = await page.evaluate(() => ({
  title: document.getElementById('vpTitle').textContent,
  number: document.getElementById('vpNumber').textContent,
  numberHidden: document.getElementById('vpNumber').hidden,
  crumb: document.getElementById('vpCrumb').textContent,
  meta: document.getElementById('vpMeta').textContent,
  /* 发布不碰说明：概览里仍是草稿期那句。 */
  note: document.getElementById('vpNote').textContent,
  states: document.getElementById('vpStates').textContent.trim(),
  status: document.getElementById('vpStatusText').textContent,
  statusHidden: document.getElementById('vpStatus').hidden,
  jump: document.getElementById('vpJump').textContent,
  actions: document.querySelectorAll('#vpActionBody .vp-action').length,
  actionsHidden: document.getElementById('vpActions').hidden,
  danger: document.querySelector('#vpDangerBody .danger-title')?.textContent ?? null,
}))
check(
  '版本 tab · 上线之后：标题仍是名称，上线拿到的版本号跟在旁边，主动作变成回滚',
  published.title === 'compress 用法补文档' && published.states === '线上' &&
    !published.numberHidden && published.number === '@1.3.0' &&
    published.crumb === 'compress 用法补文档' &&
    published.meta === '上线于 2026/10/16 10:12 · yuhan' &&
    published.note === '把 compress 的用法补进正文：参数怎么给、输出放在哪，以及扫描件要先 OCR 的那一段。' &&
    /* 线上这一态不画状态条：章已经说了它是什么，替换时别人断没断是动作说明的事。 */
    published.statusHidden === true &&
    published.jump === '回滚' &&
    /* 回滚不是危险操作：上线之后它落在「操作」这一栏里，危险区是空的。 */
    published.actions === 1 &&
    published.actionsHidden === false &&
    published.danger === null,
  JSON.stringify(published),
)

/* **当前版本不能直接下架。** 「操作」一栏给的是回滚：候选是历史版本，默认最近
   发布的那一版；不新建版本，也不改版本号。 */
const livePage = await versionAt('?page=skill&tab=versions&version=1.2.3')
check(
  '版本 tab · 线上版本：回滚在「操作」里，不在危险区；候选默认上一版',
  /* 回滚不是危险操作：它在这条普通动作里，页底没有危险区。 */
  livePage.actions.length === 1 &&
    livePage.actions[0].includes('回滚') &&
    livePage.actionsHidden === false &&
    livePage.title === 'split 范围语法' && livePage.crumb === 'split 范围语法' &&
    !livePage.numberHidden && livePage.number === '@1.2.3' &&
    livePage.meta === '上线于 2026/10/07 14:32 · dev' &&
    livePage.statusHidden === true &&
    livePage.headHasTabs && livePage.headRule === '0px' &&
    livePage.subTab && livePage.subTab.borderTopWidth === '0px' &&
    livePage.subTab.borderBottomWidth === '2px' &&
    livePage.subTab.background === 'rgba(0, 0, 0, 0)' &&
    !livePage.filesHidden && livePage.filesLabel === '查看文件' &&
    livePage.filesHref === '?page=skill&tab=files&version=1.2.3' &&
    livePage.jumpText === '回滚' && livePage.jumpHref.includes('sub=actions') &&
    !livePage.tabsHidden &&
    livePage.tabs.join(' | ') === '概览 | 改动 2 | 操作' &&
    livePage.currentTab === '概览' &&
    !livePage.recordHidden && livePage.actionsPaneHidden &&
    livePage.actionsInOverview === false &&
    livePage.changeStats === '+6 −1' &&
    livePage.facts.length === 9 &&
    livePage.facts[2].label === '版本码' && livePage.facts[2].value === 'Z8N2VK4Q' &&
    livePage.facts[8].label === '地址' && livePage.facts[8].value === 'dev/pdf-tools@1.2.3' &&
    !livePage.facts.some((row) => row.label === '最近状态变更') &&
    !livePage.facts.some((row) => row.label === '版本标识') &&
    livePage.copyButtons.map((button) => button.label).join(',') ===
      '复制名称,复制版本号,复制版本码,复制内容摘要,复制地址' &&
    livePage.timeline.join(' | ') === '提交 2026/10/07 10:12 | 首次上线 2026/10/07 14:32' &&
    livePage.dangerTitles.join(',') === '' && livePage.dangerHidden &&
    livePage.rollbackGroups.length === 0 &&
    livePage.rollbackOptions.join(',') ===
      'split 拆页 @1.2.2 历史版本,第一版 @0.9.0 历史版本' &&
    livePage.rollbackDefault === '@1.2.2' &&
    livePage.actionButtons.includes('回滚'),
  JSON.stringify({
    actions: livePage.actions,
    titles: livePage.dangerTitles,
    options: livePage.rollbackOptions,
    fallback: livePage.rollbackDefault,
  }),
)
/* **「查看文件」把版本钉带到文件栏（2026-10-20 定）。** 它是页头的次级动作，不是新的
   页内 tab；落地后文件栏的版本选择器停在这一版。 */
await Promise.all([
  page.waitForNavigation({ waitUntil: 'load' }),
  clickIn('#vpFiles'),
])
await settle()
const filesFromVersion = await page.evaluate(() => ({
  tab: document.querySelector('.skill-tab.is-current .skill-tab-label')?.textContent.trim() ?? null,
  trigger: document.querySelector('#versionPickerSlot .menu-trigger-text')?.textContent ?? null,
  url: window.location.search,
}))
check(
  '版本 tab · 查看文件：落到文件栏，版本选择器停在这一版',
  filesFromVersion.tab === '文件' &&
    filesFromVersion.trigger === 'split 范围语法' &&
    filesFromVersion.url.includes('page=skill') &&
    filesFromVersion.url.includes('tab=files') &&
    filesFromVersion.url.includes('version=1.2.3'),
  JSON.stringify(filesFromVersion),
)
/* 线上版本的改动栏对照上一版——「这一版带来了什么」，不是「把它上线会改什么」。 */
const liveChanges = await versionAt('?page=skill&tab=versions&version=1.2.3&sub=diff')
check(
  '版本 tab · 线上改动：默认对照上一版，选项里也有未发布的草稿',
  liveChanges.currentTab === '改动 2' &&
    liveChanges.compareValue === '@1.2.2' &&
    liveChanges.compareOptions.includes('split 拆页 @1.2.2 历史版本 上一版') &&
    liveChanges.compareOptions.includes('compress 补文档 草稿') &&
    liveChanges.fileCount === '2 个文件' &&
    liveChanges.fileTree.join(' | ') === 'SKILL.md +4 −1 | references/split.md +2 −0' &&
    liveChanges.changeStats === '+6 −1',
  JSON.stringify({ compare: liveChanges.compareValue, tree: liveChanges.fileTree }),
)
/* 选另一版就换基线，而且写回地址（`from=`）——一屏对比可以发给别人。 */
await clickIn('#vpCompareSlot .menu-trigger')
await Promise.all([
  page.waitForNavigation({ waitUntil: 'load' }),
  page.click('#vpCompareSlot .menu-item[data-value="7K3M9QX2"]'),
])
await settle()
const pickedCompare = await page.evaluate(() => ({
  value: document.querySelector('#vpCompareSlot .menu-trigger').dataset.value,
  url: window.location.search,
}))
check(
  '版本 tab · 改动：选任意一版（包括草稿）就换基线，地址带着 from',
  pickedCompare.value === '7K3M9QX2' && pickedCompare.url.includes('from=7K3M9QX2'),
  JSON.stringify(pickedCompare),
)
/* 回滚在「操作」一栏，直接进那一栏再点。 */
await versionAt('?page=skill&tab=versions&version=1.2.3&sub=actions')
/* **回滚的选择器也是同一个下拉。** 原生 select 的弹出菜单由系统画，这里换成了页面自己的
   菜单：当前项带勾，名称用正文字体、版本号用等宽小字。 */
await clickIn('#vpRollbackSlot .menu-trigger')
const rollbackOpen = await page.evaluate(() => ({
  hidden: document.querySelector('#vpRollbackSlot .menu-list').hidden,
  native: document.querySelector('#vpRollbackSlot select') !== null,
  groups: [...document.querySelectorAll('#vpRollbackSlot .menu-group-label')].map(
    (el) => el.textContent,
  ),
  items: [...document.querySelectorAll('#vpRollbackSlot .menu-item')].map((el) =>
    el.textContent.trim(),
  ),
  selected: document.querySelector('#vpRollbackSlot .menu-item[aria-selected="true"]')
    ?.textContent.trim() ?? null,
}))
check(
  '版本 tab · 回滚下拉：是权限那一屏的同一个菜单，当前项带勾、没有原生 select',
  !rollbackOpen.hidden && !rollbackOpen.native &&
    rollbackOpen.groups.length === 0 &&
    rollbackOpen.items.join(',') ===
      'split 拆页 @1.2.2 历史版本,第一版 @0.9.0 历史版本' &&
    rollbackOpen.selected === 'split 拆页 @1.2.2 历史版本',
  JSON.stringify(rollbackOpen),
)
await page.keyboard.press('Escape')
await clickIn('#vpActionBody .button')
const askingRollback = await page.evaluate(() => ({
  open: !document.getElementById('dangerConfirm').hidden,
  title: document.getElementById('dangerConfirmTitle').textContent,
  body: document.getElementById('dangerConfirmBody').textContent,
  cancel: document.getElementById('dangerConfirmCancel').textContent,
  accept: document.getElementById('dangerConfirmAccept').textContent,
  acceptClass: document.getElementById('dangerConfirmAccept').className,
  focus: document.activeElement?.id ?? null,
}))
check(
  '版本 tab · 回滚：打开弹窗问后果，取消是默认焦点；确认按钮不染红（不是危险操作）',
  askingRollback.open &&
    askingRollback.title === '回滚到 split 拆页？' &&
    askingRollback.body.includes('split 拆页（@1.2.2） 会重新成为当前版本') &&
    askingRollback.body.includes('split 范围语法（@1.2.3） 变成历史版本') &&
    askingRollback.body.includes('指定 @1.2.3 的调用仍可读') &&
    askingRollback.cancel === '取消' && askingRollback.accept === '确认回滚' &&
    !askingRollback.acceptClass.includes('is-danger') &&
    askingRollback.focus === 'dangerConfirmCancel',
  JSON.stringify(askingRollback),
)
await clickIn('#dangerConfirmAccept')
/* **等待态就在这张弹窗里。** 点下去之后按钮说正在做什么，两个按钮都不可用；Esc 也不关——
   请求已经发出，结果不能没有地方落下。 */
const rollingPending = await page.evaluate(() => ({
  open: !document.getElementById('dangerConfirm').hidden,
  accept: document.getElementById('dangerConfirmAccept').textContent,
  acceptDisabled: document.getElementById('dangerConfirmAccept').disabled,
  cancelDisabled: document.getElementById('dangerConfirmCancel').disabled,
}))
await page.keyboard.press('Escape')
const pendingStillOpen = await page.evaluate(() => !document.getElementById('dangerConfirm').hidden)
check(
  '版本 tab · 回滚：点确认之后是等待态——按钮说正在回滚，取消与 Esc 都不可用',
  rollingPending.open && rollingPending.accept === '正在回滚…' &&
    rollingPending.acceptDisabled && rollingPending.cancelDisabled && pendingStillOpen,
  JSON.stringify(rollingPending) + ' / Esc ' + (pendingStillOpen ? '不关' : '关掉了'),
)
const rollbackOutcome = await confirmOutcome()
check(
  '版本 tab · 回滚成功：弹窗关闭，没有第二张结果卡',
  !rollbackOutcome.open,
  JSON.stringify(rollbackOutcome),
)
await settle()
const rolledBack = await page.evaluate(() => ({
  states: document.getElementById('vpStates').textContent.trim(),
  liveNote: document.getElementById('commitMsg').textContent,
  facts: [...document.querySelectorAll('#vpFactsList > div')].map((row) => {
    const term = row.querySelector('dt').textContent
    const value = row.querySelector('.vp-fact-line > span:first-child')?.textContent ?? ''
    return `${term} ${value}`
  }),
}))
check(
  '版本 tab · 回滚之后：这一页变成历史版本，当前版本移到回滚目标，最近状态变更落在这一刻',
  rolledBack.states === '历史版本' &&
    rolledBack.liveNote === '拆分时保留原文件的元数据，合并的默认行为不变。' &&
    rolledBack.facts.includes('最近状态变更 成为历史版本 2026/10/20 15:40'),
  JSON.stringify(rolledBack.facts),
)

/* **失败也在这张弹窗里。** 地址带 `fail=1` 时下一次模拟的请求失败：原因写在正文下面，
   按钮复原、可以原地重试；页面不动——这一版仍是线上。之后再点一次就成功。 */
await page.goto(base + '?page=skill&tab=versions&version=1.2.3&sub=actions&fail=1', { waitUntil: 'load' })
await settle()
await clickIn('#vpActionBody .button')
await clickIn('#dangerConfirmAccept')
const failedOutcome = await confirmOutcome()
const afterFailure = await page.evaluate(() => ({
  states: document.getElementById('vpStates').textContent.trim(),
  dialogOpen: !document.getElementById('dangerConfirm').hidden,
}))
check(
  '版本 tab · 回滚失败：原因写在弹窗里，按钮复原、可以重试；这一版仍是线上',
  failedOutcome.open && afterFailure.dialogOpen &&
    failedOutcome.error.includes('回滚失败') &&
    failedOutcome.accept === '确认回滚' &&
    failedOutcome.acceptDisabled === false &&
    failedOutcome.cancelDisabled === false &&
    failedOutcome.focus === 'dangerConfirmAccept' &&
    afterFailure.states === '线上',
  JSON.stringify(failedOutcome) + ' / ' + afterFailure.states,
)
/* 原地重试：失败那一次不留下任何改动，所以这一次成功。 */
await clickIn('#dangerConfirmAccept')
const retryOutcome = await confirmOutcome()
check(
  '版本 tab · 回滚失败后原地重试：这一次成功，弹窗关闭',
  !retryOutcome.open,
  JSON.stringify(retryOutcome),
)

/* 历史版本：上线在上，下架在页底；原因在弹窗里填。整条流程在「操作」栏。 */
const historyPage = await versionAt('?page=skill&tab=versions&version=1.2.2&sub=actions')
check(
  '版本 tab · 历史版本：标题是名称、版本号跟在旁边；上线在上，下架在页底',
  historyPage.title === 'split 拆页' && historyPage.crumb === 'split 拆页' &&
    !historyPage.numberHidden && historyPage.number === '@1.2.2' &&
    historyPage.actions.length === 1 &&
    historyPage.actions[0].includes('上线') &&
    /* 状态条不拿「这一版」指代自己（2026-10-20 定）：先说指定它的调用还读得到。 */
    historyPage.statusText.startsWith('指定 @1.2.2 的调用仍可读取') &&
    historyPage.statusText.includes('不指定版本的调用现在读到 @1.2.3') &&
    !historyPage.statusText.includes('这一版') &&
    historyPage.jumpText === '上线' &&
    historyPage.facts.length === 9 &&
    !historyPage.facts.some((row) => row.label === '最近状态变更') &&
    historyPage.timeline.join(' | ') ===
      '提交 2026/09/28 09:05 | 首次上线 2026/09/28 11:05' &&
    historyPage.dangerTitles.join(',') === '下架',
  `${historyPage.actions.join(' | ')} · ${historyPage.dangerTitles.join(',')}`,
)
await clickIn('#vpActionBody .vp-action .button')
const twoStepRow = await page.evaluate(
  () => document.querySelector('#vpActionBody .vp-action-acts')?.textContent ?? null,
)
check(
  '版本 tab · 两步确认：第一下只展开确认，不生效',
  (twoStepRow || '').includes('确认后立即生效'),
  JSON.stringify(twoStepRow),
)

/* 下架：弹窗里必填一句原因；下架之后原因写进状态条，动作只剩重新上架。 */
await clickIn('#vpDangerBody .button.is-danger')
const delistAsk = await page.evaluate(() => ({
  open: !document.getElementById('dangerConfirm').hidden,
  title: document.getElementById('dangerConfirmTitle').textContent,
  body: document.getElementById('dangerConfirmBody').textContent,
  fieldHidden: document.getElementById('dangerConfirmField').hidden,
  acceptDisabled: document.getElementById('dangerConfirmAccept').disabled,
}))
check(
  '版本 tab · 下架：原因可选，空着时确认也可用；写了原因的状态条会显示',
  delistAsk.open && delistAsk.title === '下架 split 拆页（@1.2.2）？' &&
    delistAsk.body.includes('使用这个版本的调用会立刻失败') &&
    !delistAsk.fieldHidden && delistAsk.acceptDisabled === false,
  JSON.stringify(delistAsk),
)
await page.type('#dangerConfirmInput', '这个版本有安全问题')
await clickIn('#dangerConfirmAccept')
await confirmOutcome()
await settle()
const delisted = await page.evaluate(() => ({
  states: document.getElementById('vpStates').textContent.trim(),
  status: document.getElementById('vpStatusText').textContent,
  actions: [...document.querySelectorAll('#vpActionBody .vp-action')].map((el) =>
    el.textContent.replace(/\s+/g, ' ').trim(),
  ),
  dangerHidden: document.getElementById('vpDanger').hidden,
  facts: [...document.querySelectorAll('#vpFactsList > div')].map((row) => {
    const term = row.querySelector('dt').textContent
    const value = row.querySelector('.vp-fact-line > span:first-child')?.textContent ?? ''
    return `${term} ${value}`
  }),
}))
check(
  '版本 tab · 下架之后：状态说已下架，原因写进状态条，动作只剩重新上架',
  delisted.states === '已下架' &&
    delisted.status.includes('这个版本有安全问题') &&
    delisted.actions.length === 1 &&
    delisted.actions[0].includes('重新上架') &&
    delisted.dangerHidden &&
    delisted.facts.includes('最近状态变更 下架于 2026/10/20 15:40'),
  JSON.stringify(delisted),
)

/* **不写原因也能下架（ADR 0059）。** 最常见的情形是「没人用了」；状态条不该留一个空的「原因：」。 */
await versionAt('?page=skill&tab=versions&version=0.9.0&sub=actions')
await clickIn('#vpDangerBody .button.is-danger')
await clickIn('#dangerConfirmAccept')
await confirmOutcome()
await settle()
const delistedQuiet = await page.evaluate(() => ({
  states: document.getElementById('vpStates').textContent.trim(),
  status: document.getElementById('vpStatusText').textContent,
}))
check(
  '版本 tab · 下架不写原因：照样成功，状态条不出现「原因：」',
  delistedQuiet.states.includes('已下架') && !delistedQuiet.status.includes('原因：'),
  JSON.stringify(delistedQuiet),
)

/* 已下架的版本：`maintain` 及以上看得到，动作是重新上架。 */
const revokedPage = await versionAt('?page=skill&tab=versions&version=1.2.1')
check(
  '版本 tab · 已下架：原因在提示里，动作只有重新上架',
  revokedPage.states === '已下架' &&
    /* 状态章已经说了「已下架」，句子不再复述；不再用「这一版」指代自己（2026-10-20 定）。 */
    revokedPage.statusText.startsWith('指定这个版本的调用会失败（410）') &&
    revokedPage.statusText.includes('它仍对你可见，可以重新上架') &&
    !revokedPage.statusText.includes('这一版') &&
    revokedPage.statusText.includes('它引用了已经删除的临时脚本') &&
    revokedPage.jumpText === '重新上架' &&
    revokedPage.timeline.join(' | ') ===
      '提交 2026/09/12 18:41 | 首次上线 2026/09/12 20:41 | 下架 2026/09/20 11:30' &&
    revokedPage.actions.length === 1 &&
    revokedPage.actions[0].includes('重新上架'),
  `${revokedPage.states} · ${revokedPage.actions.join(' | ')}`,
)
check(
  '版本 tab · 已下架：真的变过才画「最近状态变更」；版本码与地址各出现一次',
  revokedPage.facts.length === 10 &&
    revokedPage.facts.filter((row) => row.label === '版本码').length === 1 &&
    revokedPage.facts.find((row) => row.label === '最近状态变更')?.value === '下架于 2026/09/20 11:30' &&
    !revokedPage.facts.some((row) => row.label === '版本标识'),
  JSON.stringify(revokedPage.facts),
)
const revokedAsWrite = await versionAt('?page=skill&tab=versions&version=1.2.1&as=write')
const revokedAsBrowse = await versionAt('?page=skill&tab=versions&version=1.2.1&as=browse')
check(
  '版本 tab · 单版页：已下架的版本对 write 与 browse 都是「没有这一版」',
  revokedAsWrite.missing && revokedAsBrowse.missing,
  `${revokedAsWrite.missing ? 'write 打不开' : 'write 打开了'} · ${revokedAsBrowse.missing ? 'browse 打不开' : 'browse 打开了'}`,
)
const draftAsWrite = await versionAt('?page=skill&tab=versions&version=7K3M9QX2&as=write')
check(
  '版本 tab · 单版页：write 看得到说明与差异，但别人的草稿不能改、也不能发布',
  draftAsWrite.actions.length === 0 &&
    !draftAsWrite.titlePencil && !draftAsWrite.notePencil &&
    draftAsWrite.jumpHidden &&
    draftAsWrite.actionsPaneHidden &&
    !draftAsWrite.tabsHidden &&
    draftAsWrite.tabs.join(' | ') === '概览 | 改动 2' &&
    draftAsWrite.statusText.includes('上线需要「维护」档位'),
  `${draftAsWrite.actions.length} 块动作 · 页签 ${draftAsWrite.tabs.join(' | ')}`,
)
/* 地址要了「操作」但没有动作时，落回概览——页签也不出现。 */
const noActionAsActions = await versionAt(
  '?page=skill&tab=versions&version=7K3M9QX2&as=write&sub=actions',
)
check(
  '版本 tab · 操作：没有动作的人即使地址写着 sub=actions，也落回概览',
  !noActionAsActions.recordHidden && noActionAsActions.actionsPaneHidden &&
    noActionAsActions.currentTab === '概览' &&
    noActionAsActions.tabs.join(' | ') === '概览 | 改动 2',
  `${noActionAsActions.currentTab} · ${noActionAsActions.tabs.join(' | ')}`,
)
/* 调用档打得开可读版本的页面，但差异是作者面的读法，动作、页签与主动作都不画。 */
const liveAsInvoke = await versionAt('?page=skill&tab=versions&version=1.2.3&as=invoke')
check(
  '版本 tab · 单版页：调用档读得到状态与事实，没有改动页签与任何动作',
  liveAsInvoke.shown && liveAsInvoke.tabsHidden &&
    !liveAsInvoke.headHasTabs && liveAsInvoke.headRule === '1px' &&
    liveAsInvoke.filesHidden &&
    liveAsInvoke.actions.length === 0 && liveAsInvoke.jumpHidden &&
    liveAsInvoke.facts.length === 4,
  `${liveAsInvoke.facts.length} 条事实 · 页签${liveAsInvoke.tabsHidden ? '不画' : '在'} · 动作 ${liveAsInvoke.actions.length}`,
)
check(
  '版本 tab · 单版页：调用者的身份是版本号，事实只剩消费契约里的那几项',
  liveAsInvoke.title === '@1.2.3' &&
    liveAsInvoke.titleMono &&
    liveAsInvoke.crumb === '@1.2.3' &&
    liveAsInvoke.numberHidden &&
    liveAsInvoke.meta === '上线于 2026/10/07 14:32' &&
    liveAsInvoke.facts.map((row) => row.label).join(',') ===
      '版本号,状态,首次上线,地址' &&
    liveAsInvoke.facts.every((row) => !row.value.includes('split 范围语法')) &&
    liveAsInvoke.timeline.join(' | ') === '首次上线 2026/10/07 14:32',
  JSON.stringify({
    title: liveAsInvoke.title,
    meta: liveAsInvoke.meta,
    facts: liveAsInvoke.facts,
    timeline: liveAsInvoke.timeline,
  }),
)
check(
  '版本 tab · 操作：调用档要 sub=actions 也只停在概览，没有页签',
  (await versionAt('?page=skill&tab=versions&version=1.2.3&as=invoke&sub=actions'))
    .actionsPaneHidden &&
    !(await page.$eval('#vpRecordPane', (el) => el.hidden)),
  await page.$eval('#vpActionsPane', (el) => String(el.hidden)),
)
check(
  '版本 tab · 单版页：不存在的版本钉是一屏「没有这一版」，给一条回到版本列表的路',
  (await versionAt('?page=skill&tab=versions&version=nope')).missing &&
    (await page.$eval('#versionMissing a', (el) => el.textContent)) ===
      '回到版本列表',
)
/* **版本 tab 是唯一清单。** 地址钉住一版时，它从清单换成那一版自己的页面——那是「一条
   记录」，不是第二份清单（ADR 0054）。 */
const versionList = await versionAt('?page=skill&tab=versions')
check(
  '版本 tab：没有版本钉时就是清单，单版页不出现',
  !versionList.listHidden &&
    versionList.detailHidden &&
    !versionList.missing &&
    versionList.tab === '版本',
  `清单${versionList.listHidden ? '收起' : '在'} · 单版页${versionList.detailHidden ? '不画' : '在'}`,
)
const draftDetail = await versionAt('?page=skill&tab=versions&version=7K3M9QX2')
check(
  '版本 tab · 钉住一版：清单收起，那一版自己的页面出来；返回回到清单',
  draftDetail.listHidden &&
    !draftDetail.detailHidden &&
    !draftDetail.missing &&
    draftDetail.back === '?page=skill&tab=versions' &&
    draftDetail.tab === '版本',
  `清单${draftDetail.listHidden ? '收起' : '在'} · 单版页${draftDetail.detailHidden ? '不画' : '在'} · 返回 ${draftDetail.back}`,
)

/* **只有草稿、从没上线过。** 页头与信息栏都说未上线；版本表里只有那一行草稿。 */
const noLive = await skillAt('?page=skill&tab=files&no-live=1')
check(
  '只有草稿的 skill：信息栏说未上线，文件栏默认落到最新草稿',
  noLive.commit.includes('yuhan') && noLive.commit.includes('compress 补文档') &&
    noLive.infoVersion === '未上线' &&
    noLive.liveChip === false &&
    noLive.versions.length === 1 &&
    noLive.versionCountBadge === null &&
    noLive.versionFilters.map((f) => `${f.label} ${f.count}`).join(' | ') === '全部 1 | 草稿 1',
  JSON.stringify({
    commit: noLive.commit,
    info: noLive.infoVersion,
    liveChip: noLive.liveChip,
    rows: noLive.versions.length,
    badge: noLive.versionCountBadge,
    filters: noLive.versionFilters,
  }),
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

/* **Switching sections must not move the page.** The tab row is pinned — it is the same row in all
   three — so what a person notices is the content jumping 4px because one panel's top margin was
   written as 26 and the others as 22. Measured as the first visible block's top, which is the thing
   that actually moves. */
const firstTops = {}
for (const query of ['?page=skill', '?page=skill&tab=versions', '?page=skill&tab=settings']) {
  await page.goto(base + query, { waitUntil: 'load' })
  await settle()
  firstTops[query] = await page.evaluate(() => {
    const panel = [...document.querySelectorAll('.tab-panel')].find((el) => !el.hidden)
    const first = [...panel.children].find((el) => !el.hidden)
    return Math.round(first.getBoundingClientRect().top * 10) / 10
  })
}
/* **The other half of "it must not move" is horizontal.** The three panels are different heights, so a
   classic scrollbar appears on one and not the others — and a scrollbar coming and going shifts the
   whole page 15px, which reads as the title itself drifting. Headless Chrome draws overlay
   scrollbars, so this cannot be measured by width here; what it can pin is that the gutter was
   reserved. */
check(
  '切换标签不改变整页宽度——滚动条槽位是常驻的',
  await page.evaluate(() => getComputedStyle(document.documentElement).scrollbarGutter === 'stable'),
  await page.evaluate(() => getComputedStyle(document.documentElement).scrollbarGutter),
)

check(
  '三个标签的第一块从同一个高度开始，切换时正文不跳',
  new Set(Object.values(firstTops)).size === 1,
  Object.entries(firstTops)
    .map(([query, top]) => `${query.split('tab=')[1] ?? 'files'} ${top}px`)
    .join(' · '),
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
  '设置：二级不写就落在第一项「常规」，也只开着一个二级页面',
  // **钉名字，不钉个数。** 原先是 `subs.length === 2`——那是「形状没变」的钉子，加一项就得改它，
  // 而改完仍然说明不了加的是哪一项。钉成三个名字之后，这条断言自己说得出二级菜单是什么。
  settings.sub === '常规' && settings.subPanes.join(',') === 'general' &&
    settings.subs.join(',') === '常规,协作者,详细信息',
  `${settings.sub} / ${settings.subPanes.join(',')} / ${settings.subs.join(',')}`,
)
check(
  '设置：默认私有，头上的标也是私有，而那个标通向「可见性」那一页',
  settings.checked === 'private' && settings.badge === '私有' &&
    settings.badgeHref === '?page=skill&tab=settings&sub=general',
  `${settings.checked} / ${settings.badge} / ${settings.badgeHref}`,
)
check(
  '设置：私有的时候五档都能授，而「所有者」不在其中',
  settings.roles.join(',') === '调用,浏览,写入,维护,管理',
  settings.roles.join(','),
)
check(
  '设置：二级菜单的第二项通向协作者那一页',
  settings.subHrefs[1] === '?page=skill&tab=settings&sub=collaborators',
  settings.subHrefs.join(' '),
)
check(
  '设置：可见性的三档照梯子命名',
  settings.visTitles.join(',') === '私有,公开 · 调用,公开 · 浏览',
  settings.visTitles.join(','),
)
check(
  '设置：正文的三处清单是同一张卡，节标题下不再画线',
  settings.listCards.join(',') === '1px/solid/10px,1px/solid/10px,1px/solid/10px' &&
    settings.blockHeadingRule === '0px',
  `${settings.listCards.join(' | ')} / 标题下 ${settings.blockHeadingRule}`,
)
check(
  '设置：危险区域是「标题 + 红边框住的几行」，停服务在删除之前、动作在右边',
  settings.dangerTitles.join(',') === '下线这个 skill,删除这个 skill' &&
    settings.offlineButton === '下线' && settings.dangerButton === '删除' &&
    settings.dangerBoxBorder === '1px' && settings.dangerFill === 'rgba(0, 0, 0, 0)',
  `${settings.dangerTitles.join(' / ')} / ${settings.offlineButton} / ${settings.dangerButton} / ${settings.dangerBoxBorder} / ${settings.dangerFill}`,
)
/* **整个 skill 下线。** 版本与指针都不动；页头多一枚「已下线」，危险区第一行变成恢复服务。
   它对所有者之外的档位整行不画。 */
const offlineSettings = await skillAt('?page=skill&tab=settings&sub=general&offline=1')
check(
  '设置 · 已下线：页头有「已下线」章，危险区第一行变成「恢复服务」',
  offlineSettings.offlineBadge === '已下线' &&
    offlineSettings.dangerTitles.join(',') === '恢复服务,删除这个 skill' &&
    offlineSettings.offlineButton === '恢复服务',
  `${offlineSettings.offlineBadge} / ${offlineSettings.dangerTitles.join(',')} / ${offlineSettings.offlineButton}`,
)
const offlineAsAdmin = await skillAt('?page=skill&tab=settings&sub=general&as=admin')
check(
  '设置 · 管理看不到「下线」那一行：停整个 skill 是所有者的事',
  offlineAsAdmin.offlineRowHidden,
  String(offlineAsAdmin.offlineRowHidden),
)

/* 下线与恢复是同一条开关：确认之后页头加章、这一行换成恢复服务，地址也跟着走。 */
await page.goto(base + '?page=skill&tab=settings&sub=general', { waitUntil: 'load' })
await settle()
await clickIn('#offlineSkill')
const offlineAsk = await page.evaluate(() => ({
  open: !document.getElementById('dangerConfirm').hidden,
  title: document.getElementById('dangerConfirmTitle').textContent,
  body: document.getElementById('dangerConfirmBody').textContent,
  accept: document.getElementById('dangerConfirmAccept').textContent,
}))
await clickIn('#dangerConfirmAccept')
await confirmOutcome()
await settle()
const offlineNow = await page.evaluate(() => ({
  badge: document.getElementById('skillOfflineBadge').hidden
    ? null
    : document.getElementById('skillOfflineBadge').textContent,
  button: document.getElementById('offlineSkill').textContent,
  url: window.location.search,
}))
check(
  '设置 · 下线：弹窗问后果，确认后页头有章、这一行变成恢复服务，地址带上 offline=1',
  offlineAsk.open && offlineAsk.body.includes('所有调用都会失败') &&
    offlineAsk.accept === '确认下线' &&
    offlineNow.badge === '已下线' &&
    offlineNow.button === '恢复服务' &&
    offlineNow.url.includes('offline=1'),
  JSON.stringify(offlineNow),
)
await clickIn('#offlineSkill')
/* 名字是「恢复服务」：`恢复` 留给删除掉的 skill（voice-spec §三）。确认钮跟着同一个名字走。 */
const restoreAsk = await page.evaluate(() => ({
  title: document.getElementById('dangerConfirmTitle').textContent,
  accept: document.getElementById('dangerConfirmAccept').textContent,
}))
check(
  '设置 · 恢复服务：弹窗标题与确认钮都用「恢复服务」这个名字',
  restoreAsk.title === '恢复服务' && restoreAsk.accept === '确认恢复服务',
  JSON.stringify(restoreAsk),
)
await clickIn('#dangerConfirmAccept')
await confirmOutcome()
await settle()
const onlineAgain = await page.evaluate(() => ({
  badgeHidden: document.getElementById('skillOfflineBadge').hidden,
  url: window.location.search,
}))
check(
  '设置 · 恢复服务：章消失，地址不再带 offline',
  onlineAgain.badgeHidden && !onlineAgain.url.includes('offline=1'),
  JSON.stringify(onlineAgain),
)

/* **删除走弹窗。** 取消之后弹窗关闭，页面上的动作原样还在——危险操作不再把页面本身
   改造成一个确认框。 */
await page.goto(base + '?page=skill&tab=settings&sub=general', { waitUntil: 'load' })
await settle()
await clickIn('#deleteSkill')
const deleteAsk = await page.evaluate(() => ({
  open: !document.getElementById('dangerConfirm').hidden,
  title: document.getElementById('dangerConfirmTitle').textContent,
  body: document.getElementById('dangerConfirmBody').textContent,
  accept: document.getElementById('dangerConfirmAccept').textContent,
}))
await clickIn('#dangerConfirmCancel')
const afterCancel = await page.evaluate(() => ({
  open: !document.getElementById('dangerConfirm').hidden,
  acts: document.getElementById('deleteActs').textContent.trim(),
  focus: document.activeElement?.id ?? null,
}))
check(
  '设置：删除走弹窗，取消之后弹窗关闭、动作回到原位',
  deleteAsk.open && deleteAsk.title === '删除这个 skill？' &&
    deleteAsk.body.includes('当前地址立即失效') && deleteAsk.accept === '确认删除' &&
    !afterCancel.open && afterCancel.acts === '删除' && afterCancel.focus === 'deleteSkill',
  `${deleteAsk.title} → ${afterCancel.acts} / 焦点 ${afterCancel.focus}`,
)

/* **申请与批准。** 有人够不到「写入」却看得见这个 skill，于是他申请；管理与所有者在这一页批准或
   拒绝。名单第一行是所有者——只读、没有撤销，所以「谁能碰它」是一列完整的事实。 */
check(
  '设置：名单第一行是所有者，只读、没有撤销',
  settings.ownerRow === 'dev所有者' && !settings.ownerHasWithdraw,
  `${settings.ownerRow} / 撤销${settings.ownerHasWithdraw ? '在' : '不在'}`,
)
check(
  '设置：一条待处理的申请带着他想要的那一档，批准前可以改',
  settings.requests.length === 1 &&
    settings.requests[0].who === 'lin' &&
    settings.requests[0].ask === '申请「写入」' &&
    settings.requests[0].rung === 'write' &&
    settings.requests[0].acts === '批准/拒绝',
  JSON.stringify(settings.requests[0]),
)
check(
  '设置：二级菜单与申请块上的数字是同一条事实',
  settings.requestCount === '1' && settings.navCount === '1',
  `${settings.requestCount} / ${settings.navCount}`,
)
check(
  '设置：三块的顺序是 共享给 → 协作者名单 → 待处理的申请',
  settings.collabBlocks.length === 3 &&
    settings.collabBlocks[0] === '共享给' &&
    settings.collabBlocks[1].startsWith('协作者名单') &&
    settings.collabBlocks[2].startsWith('待处理的申请'),
  settings.collabBlocks.join(' → '),
)
check(
  '设置：名单是十三个协作者加所有者，正好两页，第一页十行',
  settings.memberCount === '14' && /共 14 人，第 1–10 人/.test(settings.memberPager),
  `${settings.memberCount} 人 / 分页「${settings.memberPager}」`,
)

/* **档位是一列，不是一个跟在名字后面的东西。** 名单里的每一行、申请里的那一行、以及所有者那一格，
   都落在同一条竖线上；控件本身要看得出来能点开——箭头在、右侧留了它的位置。 */
await page.goto(base + '?page=skill&tab=settings&sub=collaborators', { waitUntil: 'load' })
await settle()
const roleColumn = await page.evaluate(() => {
  const lefts = [...document.querySelectorAll('#grantList li .menu-trigger, #requestList li .menu-trigger, #grantList li.is-owner .role-tag')]
    .map((el) => Math.round(el.getBoundingClientRect().left))
  const style = getComputedStyle(document.querySelector('#grantList li .menu-trigger'))
  return {
    lefts,
    /* **类型列**：这一版只有一个值「个人」，位置在名字与档位之间。它是给以后的「组织」留的。 */
    types: [...document.querySelectorAll('#grantList .member-type, #requestList .member-type')]
      .map((el) => el.textContent),
    typeLefts: [...document.querySelectorAll('#grantList .member-type, #requestList .member-type')]
      .map((el) => Math.round(el.getBoundingClientRect().left)),
    chevron: style.backgroundImage !== 'none',
    padRight: style.paddingRight,
    minWidth: style.minWidth,
  }
})
check(
  '设置：名单、所有者与申请里的档位排在同一条竖线上，而且看得出来能点开',
  roleColumn.lefts.length === 11 && new Set(roleColumn.lefts).size === 1 &&
    roleColumn.chevron && roleColumn.padRight === '28px' && roleColumn.minWidth === '84px',
  `${roleColumn.lefts.join(',')} / 箭头${roleColumn.chevron ? '有' : '无'} / 右内边距 ${roleColumn.padRight}`,
)
check(
  '设置：类型列在名字与档位之间，这一版只有「个人」一个值',
  roleColumn.types.length === 11 && roleColumn.types.every((type) => type === '个人') &&
    new Set(roleColumn.typeLefts).size === 1,
  `${roleColumn.types.join(',')} / ${roleColumn.typeLefts.join(',')}`,
)

/* **窄屏是同一条规矩。** 那一套轨道换了（名字那列松成 1fr），所以档位与撤销两列必须写死——
   两行之间的内容不同（有撤销 / 没有、有按钮 / 没有），跟着内容走的轨道会把竖线算散。 */
await page.setViewport({ width: 900, height: 900 })
await settle()
const narrowColumn = await page.evaluate(() => {
  const lefts = [...document.querySelectorAll('#grantList li .menu-trigger, #requestList li .menu-trigger, #grantList li.is-owner .role-tag')]
    .map((el) => Math.round(el.getBoundingClientRect().left))
  return { lefts, overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth }
})
await page.setViewport({ width: 1440, height: 900 })
check(
  '设置：窄屏下所有档位仍排在一条竖线上，而且页面不横向溢出',
  narrowColumn.lefts.length === 11 && new Set(narrowColumn.lefts).size === 1 && !narrowColumn.overflow,
  `${narrowColumn.lefts.join(',')}${narrowColumn.overflow ? ' / 横向溢出' : ''}`,
)

/* **一份人也没有（`?members=none`）。** 名单不是一个空框——所有者那一行确实碰得到它，框照画；
   要换的是脚下那句话：为什么空、下一步在哪。公开档那一侧的分支同样要说清。 */
await page.goto(base + '?page=skill&tab=settings&sub=collaborators&members=none', { waitUntil: 'load' })
await settle()
const noMembers = await page.evaluate(() => ({
  count: document.getElementById('memberCount').textContent,
  rows: [...document.querySelectorAll('#grantList li')].map((li) => li.textContent.replace(/\s+/g, ' ').trim()),
  hint: document.getElementById('grantsHint').textContent,
  pager: document.getElementById('memberPager').textContent.trim(),
  matchedNoneHidden: document.getElementById('memberNone').hidden,
}))
check(
  '设置：一个协作者都没有时，名单只剩所有者一行，脚下那句换成空态的三件事',
  noMembers.count === '1' && noMembers.rows.length === 1 && /所有者/.test(noMembers.rows[0]) &&
    noMembers.pager === '' && noMembers.matchedNoneHidden &&
    /只有你可以访问/.test(noMembers.hint) && /共享给/.test(noMembers.hint),
  `${noMembers.count} 人 / ${noMembers.rows.join('、')} / ${noMembers.hint}`,
)
await page.goto(base + '?page=skill&tab=settings&sub=collaborators&members=none&vis=public-invoke', { waitUntil: 'load' })
await settle()
const noMembersPublic = await page.evaluate(() => document.getElementById('grantsHint').textContent)
check(
  '设置：公开而没有人被单独授权时，那一侧的空态说「还没有单独授给某个人的档位」',
  /「调用」/.test(noMembersPublic) && /没有单独授予个人的档位/.test(noMembersPublic),
  noMembersPublic,
)

/* **名单的搜索与分页。** 夹具本身就是十三个协作者（加所有者十四人）——一页十行，所以默认这一页
   就看得见分页；`?members=none` 是另一半。 */
await page.goto(base + '?page=skill&tab=settings&sub=collaborators', { waitUntil: 'load' })
await settle()
const manyFirst = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('#grantList li')]
  return {
    rows: rows.map((li) => li.querySelector('.grant-who').textContent),
    count: document.getElementById('memberCount').textContent,
    pager: document.getElementById('memberPager').textContent.replace(/\s+/g, ' ').trim(),
    lastBorder: getComputedStyle(rows[rows.length - 1]).borderBottomWidth,
  }
})
check(
  '设置：名单超过一页就分页，第一页十行、人数是全部',
  manyFirst.rows.length === 10 && manyFirst.count === '14' &&
    /共 14 人，第 1–10 人/.test(manyFirst.pager),
  `${manyFirst.rows.length} 行 / ${manyFirst.count} 人 / ${manyFirst.pager}`,
)

await page.goto(base + '?page=skill&tab=settings&sub=collaborators&p=2', { waitUntil: 'load' })
await settle()
const manySecond = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('#grantList li')]
  return {
    rows: rows.map((li) => li.querySelector('.grant-who').textContent),
    pager: document.getElementById('memberPager').textContent.replace(/\s+/g, ' ').trim(),
    partial: document.getElementById('grantList').classList.contains('is-partial'),
    lastBorder: getComputedStyle(rows[rows.length - 1]).borderBottomWidth,
  }
})
check(
  '设置：第二页是剩下的四行，地址里带着页码',
  manySecond.rows.join(',') === 'zheng,wang,chen,yang' && /第 11–14 人/.test(manySecond.pager),
  `${manySecond.rows.join(',')} / ${manySecond.pager}`,
)
/* 满页的最后一行贴着卡片的边，线由边框给；装不满时下面是预留的空白，那一行必须自己收一条线。 */
check(
  '设置：满页最后一行不加线，装不满的那一页最后一行收一条分割线',
  manyFirst.lastBorder === '0px' && manySecond.partial && manySecond.lastBorder === '1px',
  `第一页 ${manyFirst.lastBorder} / 第二页 ${manySecond.lastBorder}`,
)

/* **翻页只换框里的行，不动页面的形状。** 第二页装不满，框要是跟着缩，底下的申请块和整页高度
   会往上跳；点完再跳回顶部是第二种动。三样都要量：滚动位置、整页高度、申请表的位置。 */
await page.goto(base + '?page=skill&tab=settings&sub=collaborators', { waitUntil: 'load' })
await settle()
await page.evaluate(() => window.scrollTo(0, 420))
const beforeFlip = await page.evaluate(() => ({
  y: Math.round(window.scrollY),
  docH: document.documentElement.scrollHeight,
  requestTop: Math.round(document.getElementById('requestBlock').getBoundingClientRect().top),
}))
await page.click('#memberPager .pager-page')
await settle()
const afterFlip = await page.evaluate(() => ({
  y: Math.round(window.scrollY),
  docH: document.documentElement.scrollHeight,
  requestTop: Math.round(document.getElementById('requestBlock').getBoundingClientRect().top),
  page: location.search,
}))
check(
  '设置：翻到第二页，滚动位置、整页高度与申请表的位置都不动',
  /p=2/.test(afterFlip.page) && beforeFlip.y === afterFlip.y && beforeFlip.docH === afterFlip.docH &&
    beforeFlip.requestTop === afterFlip.requestTop,
  `滚 ${beforeFlip.y}→${afterFlip.y} / 页高 ${beforeFlip.docH}→${afterFlip.docH} / 申请表 ${beforeFlip.requestTop}→${afterFlip.requestTop}`,
)

await page.goto(base + '?page=skill&tab=settings&sub=collaborators&q=zhao', { waitUntil: 'load' })
await settle()
const searched = await page.evaluate(() => ({
  rows: [...document.querySelectorAll('#grantList li')].map((li) => li.querySelector('.grant-who').textContent),
  count: document.getElementById('memberCount').textContent,
  pager: document.getElementById('memberPager').textContent.trim(),
}))
check(
  '设置：按用户名搜到一个人，人数与分页都跟着只剩他',
  searched.rows.join(',') === 'zhao' && searched.count === '1' && searched.pager === '',
  `${searched.rows.join(',')} / ${searched.count} 人`,
)

await page.goto(base + '?page=skill&tab=settings&sub=collaborators&q=zzz', { waitUntil: 'load' })
await settle()
const noMatch = await page.evaluate(() => ({
  rows: document.querySelectorAll('#grantList li').length,
  noneHidden: document.getElementById('memberNone').hidden,
  count: document.getElementById('memberCount').textContent,
}))
check(
  '设置：搜不到就是一句「没有匹配的人」，不是一张空卡',
  noMatch.rows === 0 && !noMatch.noneHidden && noMatch.count === '0',
  `${noMatch.rows} 行 / 空态${noMatch.noneHidden ? '没画' : '在'}`,
)

/* 打字就筛，而且筛完落回第一页——留在第二页的搜索看起来像「什么都没有」。 */
await page.goto(base + '?page=skill&tab=settings&sub=collaborators&p=2', { waitUntil: 'load' })
await settle()
await page.type('#memberQuery', 'sun')
await settle()
const typedSearch = await page.evaluate(() => ({
  rows: [...document.querySelectorAll('#grantList li')].map((li) => li.querySelector('.grant-who').textContent),
  search: location.search,
}))
check(
  '设置：搜索是即时的，筛完落回第一页，关键词进地址',
  typedSearch.rows.join(',') === 'sun' && /q=sun/.test(typedSearch.search) && !/p=2/.test(typedSearch.search),
  `${typedSearch.rows.join(',')} / ${typedSearch.search}`,
)

/* 批准 = 按选中的那一档授权：申请消失、人进名单、二级菜单上的数字跟着消失。 */
await page.goto(base + '?page=skill&tab=settings&sub=collaborators', { waitUntil: 'load' })
await settle()
await page.click('#requestList .button')
await settle()
const approved = await page.evaluate(() => ({
  blockHidden: document.getElementById('requestBlock').hidden,
  badgeHidden: document.getElementById('collabCount').hidden,
  count: document.getElementById('memberCount').textContent,
}))
/* 批准的人排在名单末尾——十五个人，他落在第二页。翻过去看，不重载页面（重载会把夹具复位）。 */
await page.click('#memberPager .pager-page')
await settle()
const approvedLast = await page.evaluate(() =>
  [...document.querySelectorAll('#grantList li:not(.is-owner)')]
    .map((li) => li.querySelector('.grant-who').textContent + ':' + li.querySelector('.menu-trigger').dataset.value),
)
check(
  '设置：批准一条申请，人进名单（排在末尾，翻到第二页能看到），申请块与数字一起收起',
  approved.blockHidden && approved.badgeHidden && approved.count === '15' &&
    approvedLast.includes('lin:write'),
  `${approved.blockHidden ? '收起' : '还在'} / ${approved.count} 人 / 末页 ${approvedLast.join(',')}`,
)

/* 拒绝 = 只让它消失，名单一个人都不多。 */
await page.goto(base + '?page=skill&tab=settings&sub=collaborators', { waitUntil: 'load' })
await settle()
await page.evaluate(() => {
  const acts = [...document.querySelectorAll('#requestList .button')]
  acts[acts.length - 1].click()
})
await settle()
const denied = await page.evaluate(() => ({
  blockHidden: document.getElementById('requestBlock').hidden,
  count: document.getElementById('memberCount').textContent,
  hasLin: [...document.querySelectorAll('#grantList .grant-who')].some((el) => el.textContent === 'lin'),
}))
check(
  '设置：拒绝一条申请，只让它消失，名单一个人都不多',
  denied.blockHidden && denied.count === '14' && !denied.hasLin,
  `${denied.blockHidden ? '收起' : '还在'} / ${denied.count} 人 / lin ${denied.hasLin ? '进了名单' : '没进'}`,
)

/* **申请人那一侧。** 信息页底部一块「申请」：只要梯子上还有比你现在高的档就画（所以「管理」与
   所有者没有这一块，而「写入」「维护」有），申请表里只列比他现在高的那些档，选中的那一档下面写着
   它会多出什么、还缺什么；已经在名单里的人按钮只写「申请」。发出之后换成「等待处理」+「等谁批」，
   并给一个撤回。 */
await page.goto(base + '?page=skill&as=browse', { waitUntil: 'load' })
await settle()
const askBrowse = await page.evaluate(() => ({
  hidden: document.getElementById('askBlock').hidden,
  heading: document.querySelector('#askBlock h3').textContent.trim(),
  why: document.getElementById('askWhy').textContent,
  submit: document.getElementById('askSubmit').textContent.trim(),
  options: [...document.querySelectorAll('#askRole .menu-item')].map((item) => item.textContent),
  label: document.getElementById('askRoleLabel')?.textContent.trim(),
  labelled: document.querySelector('#askRole .menu-trigger')?.getAttribute('aria-labelledby') === 'askRoleLabel',
  note: document.getElementById('askNote').textContent,
}))
check(
  '信息：浏览看到「申请」，还不是协作者的人按钮写全「申请成为协作者」',
  !askBrowse.hidden && askBrowse.heading === '申请' && askBrowse.submit === '申请成为协作者' &&
    askBrowse.options.join(',') === '写入,维护,管理' && /当前档位为「浏览」/.test(askBrowse.why),
  `${askBrowse.heading} / ${askBrowse.submit} / ${askBrowse.options.join(',')} / ${askBrowse.why}`,
)
check(
  '信息：申请的那一档有名字，而名字连着控件',
  askBrowse.label === '申请档位' && askBrowse.labelled,
  `${askBrowse.label ?? '（没有名字）'} / ${askBrowse.labelled ? '连着' : '没连'}`,
)
check(
  '信息：选中的那一档下面写着它会多出什么、还缺什么',
  /提交新版本/.test(askBrowse.note) && /上线/.test(askBrowse.note),
  askBrowse.note,
)
await pickRung('#askRole', 'maintain')
await settle()
const maintainNote = await page.evaluate(() => document.getElementById('askNote').textContent)
check(
  '信息：换一档，说明跟着换——「维护」说的是「管理」缺的那一半',
  /丢弃任何人/.test(maintainNote) && /「管理」/.test(maintainNote),
  maintainNote,
)
await pickRung('#askRole', 'write')
await settle()
await page.click('#askSubmit')
await settle()
const askedJoin = await page.evaluate(() => ({
  formHidden: document.getElementById('askForm').hidden,
  noteHidden: document.getElementById('askNote').hidden,
  pendingHidden: document.getElementById('askPending').hidden,
  chip: (document.querySelector('#askPending .chip') || {}).textContent?.trim(),
  text: document.getElementById('askPendingText').textContent,
  why: document.getElementById('askWhy').textContent,
}))
check(
  '信息：申请之后换成「等待处理」+「等谁批」，并给一个撤回',
  askedJoin.formHidden && !askedJoin.pendingHidden && askedJoin.chip === '等待处理' &&
    /「写入」/.test(askedJoin.text) && /dev/.test(askedJoin.text),
  `${askedJoin.chip ?? '（没有状态）'} / ${askedJoin.text}`,
)
check(
  '信息：申请发出之后，说明那一行收起，上文也不再给出申请指引',
  askedJoin.noteHidden && !/如需更高的档位/.test(askedJoin.why),
  `${askedJoin.noteHidden ? '收起' : '还在'} / ${askedJoin.why}`,
)
await page.click('#askWithdraw')
await settle()
check(
  '信息：撤回之后回到可以申请的那一态',
  await page.evaluate(() => !document.getElementById('askForm').hidden),
  '回到表单',
)

await page.goto(base + '?page=skill&as=invoke', { waitUntil: 'load' })
await settle()
const askInvoke = await page.evaluate(() => ({
  hidden: document.getElementById('askBlock').hidden,
  options: [...document.querySelectorAll('#askRole .menu-item')].map((item) => item.textContent),
}))
check(
  '信息：调用也看得到「申请」，范围从「浏览」起',
  !askInvoke.hidden && askInvoke.options.join(',') === '浏览,写入,维护,管理',
  askInvoke.options.join(','),
)

/* **已经在名单里的人要的是再上一档，不是"成为协作者"。** 按钮与那一句话跟着档位变：写入看到
   「申请」、能选的只剩「维护」「管理」；维护只差「管理」一档；到「管理」就没有更高的了。 */
await page.goto(base + '?page=skill&as=write', { waitUntil: 'load' })
await settle()
const askWrite = await page.evaluate(() => ({
  hidden: document.getElementById('askBlock').hidden,
  why: document.getElementById('askWhy').textContent,
  submit: document.getElementById('askSubmit').textContent.trim(),
  options: [...document.querySelectorAll('#askRole .menu-item')].map((item) => item.textContent),
  note: document.getElementById('askNote').textContent,
}))
check(
  '信息：写入也看得到「申请」，按钮只写「申请」，能选的是「维护」「管理」',
  !askWrite.hidden && askWrite.submit === '申请' && askWrite.options.join(',') === '维护,管理' &&
    /当前档位为「写入」/.test(askWrite.why) && /无法上线/.test(askWrite.why),
  `${askWrite.submit} / ${askWrite.options.join(',')} / ${askWrite.why}`,
)
check(
  '信息：写入看到的默认说明是「维护」会多出什么',
  /丢弃任何人/.test(askWrite.note) && /「管理」/.test(askWrite.note),
  askWrite.note,
)
await page.goto(base + '?page=skill&as=maintain', { waitUntil: 'load' })
await settle()
const askMaintain = await page.evaluate(() => ({
  hidden: document.getElementById('askBlock').hidden,
  submit: document.getElementById('askSubmit').textContent.trim(),
  options: [...document.querySelectorAll('#askRole .menu-item')].map((item) => item.textContent),
  note: document.getElementById('askNote').textContent,
}))
check(
  '信息：维护只剩「管理」一档可申请，说明说的是所有者那两条边界',
  !askMaintain.hidden && askMaintain.submit === '申请' &&
    askMaintain.options.join(',') === '管理' && /所有者/.test(askMaintain.note),
  `${askMaintain.submit} / ${askMaintain.options.join(',')} / ${askMaintain.note}`,
)
await page.goto(base + '?page=skill&as=admin', { waitUntil: 'load' })
await settle()
check(
  '信息：管理及以上没有更高的可要，这一块不画',
  await page.evaluate(() => document.getElementById('askBlock').hidden),
  '管理',
)

const opened = await skillAt('?page=skill&tab=settings&sub=general&vis=public-browse')
check(
  '设置：地址里的分栏把可见性那一页打开',
  opened.sub === '常规' && opened.subPanes.join(',') === 'general' && opened.checked === 'public-browse',
  `${opened.sub} / ${opened.subPanes.join(',')} / ${opened.checked}`,
)
check('设置：公开之后头上是「公开」', opened.badge === '公开', opened.badge)
check(
  '设置：公开到「浏览」之后，能授的只剩比它高的三档——谁都能读了，再授「浏览」是空动作',
  opened.roles.join(',') === '写入,维护,管理',
  opened.roles.join(','),
)
check(
  '设置：每一行的档位是一个控件，不是一个标签',
  settings.grantValues.slice(0, 3).join(',') === 'browse,write,write' &&
    settings.grantValues.length === 9 &&
    settings.grantOptions.every((options) => options === '调用/浏览/写入/维护/管理'),
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
  '设置：那句「对所有人开放」是从梯子上算出来的，不是写死的',
  opened.hint.includes('公开之后「调用」、「浏览」对所有人开放') &&
    openedInvoke.hint.includes('公开之后「调用」对所有人开放') &&
    /单独授予个人的档位/.test(opened.hint),
  `${opened.hint} | ${openedInvoke.hint}`,
)
check(
  '设置：代价只写在「公开 · 浏览」这一档，而且只说灰字没说的那一半——收不回来',
  opened.warnings.length === 1 && opened.warnings[0].title === '公开 · 浏览' &&
    /下载与复制无法收回/.test(opened.warnings[0].text),
  opened.warnings.map((warn) => `${warn.title}：${warn.text}`).join(' | ') || '（一句都没有）',
)
/* **「公开 · 浏览」要过一次确认。** 它是唯一一档要把整个 skill 交出去的：取消 = 什么也没发生
   （选择器与地址都还在原来那一档）；确认之后才真正切换。 */
await page.goto(base + '?page=skill&tab=settings&sub=general', { waitUntil: 'load' })
await settle()
const readVisibility = () =>
  page.evaluate(() => ({
    dialogOpen: !document.getElementById('publicBrowseConfirm').hidden,
    title: document.getElementById('publicBrowseConfirmTitle').textContent,
    checked: document.querySelector('input[name="skill-visibility"]:checked').value,
    search: location.search,
  }))
await page.click('input[name="skill-visibility"][value="public-browse"]')
await settle()
const askPublic = await readVisibility()
check(
  '设置：点「公开 · 浏览」先弹确认，确认之前选择器不改、地址也不变',
  askPublic.dialogOpen && askPublic.title === '改为「公开 · 浏览」' &&
    askPublic.checked === 'private' && !/vis=/.test(askPublic.search),
  `弹窗${askPublic.dialogOpen ? '在' : '不在'} / 选中 ${askPublic.checked} / ${askPublic.search || '（无参数）'}`,
)
await page.click('#publicBrowseCancel')
await settle()
const cancelled = await readVisibility()
check(
  '设置：取消确认，什么也没发生',
  !cancelled.dialogOpen && cancelled.checked === 'private' && !/vis=/.test(cancelled.search),
  `弹窗${cancelled.dialogOpen ? '还在' : '收起'} / 选中 ${cancelled.checked} / ${cancelled.search || '（无参数）'}`,
)
await page.click('input[name="skill-visibility"][value="public-browse"]')
await settle()
await page.click('#publicBrowseAccept')
await settle()
const accepted = await readVisibility()
check(
  '设置：确认之后才切到「公开 · 浏览」，地址跟着写',
  !accepted.dialogOpen && accepted.checked === 'public-browse' && /vis=public-browse/.test(accepted.search),
  `选中 ${accepted.checked} / ${accepted.search}`,
)

check(
  '设置：地址里认不出的档落回私有',
  (await skillAt('?page=skill&tab=settings&sub=general&vis=nope')).checked === 'private',
)
check(
  '设置：地址里认不出的分栏落回「常规」',
  (await skillAt('?page=skill&tab=settings&sub=nope')).sub === '常规',
)

/* The one thing the second level must not do: walking to the other sub-page and back cannot quietly
   put the skill back to 私有. Both links carry the choice that is in the address right now. */
const carried = await skillAt('?page=skill&tab=settings&sub=general&vis=public-invoke')
check(
  '设置：两个分栏的链接都带着当下选的档，来回走不会把选择丢掉',
  carried.subHrefs.every((href) => href.endsWith('&vis=public-invoke')),
  carried.subHrefs.join(' '),
)

await page.goto(base + '?page=skill&tab=settings&sub=general', { waitUntil: 'load' })
await settle()
await page.click('input[value="public-invoke"]')
await settle()
const afterPick = decodeURIComponent(new URL(page.url()).search)
check(
  '设置：点一下就把可见性写进地址，头上的标跟着变',
  afterPick === '?page=skill&tab=settings&sub=general&vis=public-invoke' &&
    (await page.$eval('#skillBadge', (el) => el.textContent)) === '公开',
  afterPick,
)

/* Moving somebody up is the other half of handing out access — and the note that said their rung was
   redundant has to go with them when they leave the rungs the public already covers. */
await page.goto(base + '?page=skill&tab=settings&sub=collaborators&vis=public-browse', { waitUntil: 'load' })
await settle()
const movedFrom = await page.$eval('#grantList li:not(.is-owner)', (el) => el.textContent.replace(/\s+/g, ' ').trim())
await pickRung('#grantList li .menu', 'write')
await settle()
const moved = await page.evaluate(() => ({
  text: document.querySelector('#grantList li:not(.is-owner)').textContent.replace(/\s+/g, ' ').trim(),
  value: document.querySelector('#grantList li .menu-trigger').dataset.value,
}))
check(
  '设置：在名单上把一个人往上挪一档，控件就地生效',
  movedFrom !== moved.text && moved.value === 'write',
  `${movedFrom} → ${moved.value}`,
)

/* And the walk itself: the files tab → a folder → the file inside it → back up by the skill's name.
   It starts on the files tab, because the page now lands on 信息 — the list is not on screen there. */
await page.goto(base + '?page=skill&tab=files', { waitUntil: 'load' })
await settle()
const hop = async (selector) => {
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }), page.click(selector)])
  return new URL(page.url()).search
}
const toDir = await hop('#fileList li:nth-child(2) .file-name')
const toFile = await hop('#fileList .file-name')
const backToRoot = await hop('.skill-ident h1 .name')
check('skill 页：点文件夹下到那一层', toDir === '?page=skill&tab=files&path=references', toDir)
check('skill 页：点文件读到它', toFile === '?page=skill&tab=files&file=references/split.md', toFile)
/* 名字回的是这个 skill 的首页，而首页现在是「信息」——不是它自己的文件根。 */
check('skill 页：标题里的 skill 名走回「信息」', backToRoot === '?page=skill', backToRoot)

/* And the second level, which is walked the same way: the tab leads in, and the menu beside it
   moves between the two pages it holds. */
const intoSettings = await hop('.skill-tab[data-tab="settings"]')
const toVisibility = await hop('.settings-item[data-sub="general"]')
const backToCollaborators = await hop('.settings-item[data-sub="collaborators"]')
check('设置：一级分栏点得进来', intoSettings === '?page=skill&tab=settings', intoSettings)
check('设置：点二级菜单换页', toVisibility === '?page=skill&tab=settings&sub=general', toVisibility)
check('设置：点二级菜单也走得回去', backToCollaborators === '?page=skill&tab=settings&sub=collaborators', backToCollaborators)

} /* settingsGroup */

layoutGroup: {
  if (!selected('layout')) break layoutGroup

/* The other half of the same requirement. The rows are given whatever height the frame has left
   after the head and the pager, so a window with room to spare gets taller rows — not the same rows
   with a hole under them, which is what a fixed row height leaves and what this section exists to
   catch. Compared against the 900px measurement above rather than against a number of its own. */
await page.setViewport({ width: 1440, height: 1200 })
await page.goto(base + '?page=skills', { waitUntil: 'load' })
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
  await page.goto(base + '?page=skills', { waitUntil: 'load' })
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

const failed = checks.filter((entry) => entry.pass === false)
const skipped = checks.filter((entry) => entry.skipped === true)
console.log(
  JSON.stringify(
    {
      src,
      mode: HEADFUL ? 'headful' : 'headless',
      checks,
      problems,
      skipped: skipped.length,
      failed: failed.length,
    },
    null,
    2,
  ),
)
process.exit(failed.length > 0 || problems.length > 0 ? 1 : 0)
