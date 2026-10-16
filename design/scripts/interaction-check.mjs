// Drive the chosen direction through every interaction spec.md §5 requires, and shoot each state.
//
//   node design/scripts/interaction-check.mjs "design/skillmaster-首页演示.html" design/shots/states
//
// A click test rather than a read of the source: the point of this deliverable is that the page can
// actually be operated, and markup that looks right is not evidence that it behaves right.

import { execSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

const [src, outDir] = process.argv.slice(2)
if (!src || !outDir) {
  console.error('usage: interaction-check.mjs "<built demo>.html" <out-dir>')
  process.exit(1)
}
mkdirSync(resolve(outDir), { recursive: true })

const require = createRequire(execSync('npm root -g', { encoding: 'utf8' }).trim() + '/')
const puppeteer = require('puppeteer-core')

// Headful, so the state screenshots this writes are the ones the user would see: headless skips
// `backdrop-filter`, which is the login scrim.
const browser = await puppeteer.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: false,
  args: [
    '--no-sandbox',
    '--force-color-profile=srgb',
    '--window-size=1440,1000',
    '--window-position=0,0',
    // Force a scrollbar that takes up layout width. macOS defaults to overlay scrollbars, which take
    // none, and `--hide-scrollbars` (used by shot.mjs, deliberately not here) makes them take none
    // anywhere. Either one leaves the "opening the overlay must not move the page" check below
    // passing without ever exercising the compensation it exists to protect.
    '--disable-features=OverlayScrollbar',
  ],
  defaultViewport: { width: 1440, height: 900, deviceScaleFactor: 1 },
})

const page = await browser.newPage()
await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'light' }])
await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 })

const problems = []
page.on('pageerror', (error) => problems.push('pageerror: ' + error.message))
page.on('console', (message) => {
  if (message.type() === 'error') problems.push('console: ' + message.text())
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

const shot = (name) => page.screenshot({ path: resolve(outDir, name) })
// Longer than the submit handler's simulated round trip (420ms): the success branch is reached by
// a timer, so a shorter wait would assert against the state *before* the answer came back.
const settle = () => new Promise((done) => setTimeout(done, 700))
// Clearing is done with `el.select()` and then typing over the selection.
//
// Measured against this headless build: a triple click removes a single character, and neither
// Meta+A nor Control+A produces a selection at all — so every one of those leaves the old value in
// place and the next value silently becomes a concatenation. Typing over a real selection still
// goes through the page's own key and input handlers, which is what we want to exercise.
const type = async (selector, value) => {
  await page.click(selector)
  await page.$eval(selector, (el) => el.select())
  await page.type(selector, value, { delay: 8 })
}

await page.goto('file://' + resolve(src), { waitUntil: 'networkidle0', timeout: 20000 })
await settle()

// 1 · the overlay opens, and its backdrop covers the whole viewport rather than part of it
check('浮层初始关闭', !(await visible('#overlay')))
const leftBefore = await page.$eval('.nav-inner', (el) => Math.round(el.getBoundingClientRect().left))
await page.click('#loginBtn')
await settle()
check('点「登录」打开浮层', await visible('#overlay'))
const leftOpen = await page.$eval('.nav-inner', (el) => Math.round(el.getBoundingClientRect().left))
check('打开浮层不移动首页', leftOpen === leftBefore, `导航左边缘 ${leftBefore} → ${leftOpen}`)

const cover = await page.$eval('#overlay', (el) => {
  const rect = el.getBoundingClientRect()
  return { w: Math.round(rect.width), h: Math.round(rect.height) }
})
const view = await page.evaluate(() => ({ w: window.innerWidth, h: window.innerHeight }))
check('遮罩覆盖整个视口', cover.w >= view.w && cover.h >= view.h, `${cover.w}×${cover.h} vs ${view.w}×${view.h}`)
await shot('01-overlay-open.png')

// 2 · empty submit puts a message under each field, and nothing at the top of the form
await page.click('#submitBtn')
await settle()
const emptyState = await page.evaluate(() => {
  const text = (sel) => {
    const el = document.querySelector(sel)
    return el ? el.textContent.trim() : null
  }
  return { phone: text('#phoneErr'), password: text('#pwErr') }
})
check('空提交：手机号字段有提示', Boolean(emptyState.phone), String(emptyState.phone))
check('空提交：密码字段有提示', Boolean(emptyState.password), String(emptyState.password))
await shot('02-empty-submit.png')

// 3 · wrong password: one banner at the top of the form, and nothing hung on a field
await type('#phone', '13800138000')
await type('#password', 'wrongpass')
await page.click('#revealBtn')
await settle()
const revealed = await page.$eval('#password', (el) => el.type)
check('眼睛图标切换明文', revealed === 'text', 'type=' + revealed)
await page.click('#revealBtn')
await settle()

await page.click('#submitBtn')
await settle()
const wrongState = await page.evaluate(() => {
  // Only rendered text counts. A bare `textContent` search also matches <script>, which is how a
  // first version of this check reported a banner that was not on the page at all.
  const rendered = (el) => el.getClientRects().length > 0
  const banner = [...document.querySelectorAll('body *')].find(
    (el) =>
      !/^(SCRIPT|STYLE|TEMPLATE)$/.test(el.tagName) &&
      rendered(el) &&
      el.textContent.includes('手机号或密码不正确'),
  )
  const pwField = document.querySelector('#password').closest('.field')
  return {
    bannerText: banner ? banner.textContent.trim() : null,
    bannerInsidePasswordField: banner && pwField ? pwField.contains(banner) : null,
    fieldErrorText: (document.querySelector('#pwErr')?.textContent || '').trim(),
  }
})
check('密码错误：出现错误条', Boolean(wrongState.bannerText), String(wrongState.bannerText))
check('密码错误：错误条不挂在密码字段上', wrongState.bannerInsidePasswordField === false)
check(
  '密码错误：字段上没有「不正确」这类提示',
  !wrongState.fieldErrorText.includes('不正确'),
  wrongState.fieldErrorText || '(空)',
)
await shot('03-wrong-password.png')

// 4 · correct password: the overlay closes and the nav becomes the signed-in one
await type('#password', 'skillmaster')
await page.click('#submitBtn')
await settle()
check('登录成功：浮层关闭', !(await visible('#overlay')))
check('登录成功：导航切成已登录态', (await visible('#navUser')) && !(await visible('#navGuest')))
const who = await page.$eval('#navUser', (el) => el.innerText.replace(/\s+/g, ' ').trim())
check('已登录导航有账号与退出', who.includes('@') && who.includes('退出'), who)
await page.evaluate(() => window.scrollTo(0, 0))
await settle()
await shot('04-signed-in.png')

// 5 · the hero button opens the same overlay, and both ways out of it close it
await page.click('#startBtnHero')
await settle()
check('hero「开始使用」也能开浮层', await visible('#overlay'))
await page.keyboard.press('Escape')
await settle()
check('Esc 关闭浮层', !(await visible('#overlay')))

// Signed in, so the guest nav is gone — log out first rather than clicking a hidden control.
await page.click('#logoutBtn')
await settle()
check('退出后回到未登录态', await visible('#navGuest'))

await page.click('#startBtnHero')
await settle()
// Well clear of the centred dialog, so this lands on the scrim rather than on the form.
await page.mouse.click(120, 450)
await settle()
check('点遮罩关闭浮层', !(await visible('#overlay')))

// ---------------------------------------------------------------------------------------------
// The two flows added after login: 找回密码 and 注册. Both live in the same overlay as views.

const title = () => page.$eval('#dialogTitle', (el) => el.textContent.trim())
const errText = (selector) => page.$eval(selector, (el) => el.textContent.trim())
const errClass = (selector) => page.$eval(selector, (el) => el.className)
const isDisabled = (selector) => page.$eval(selector, (el) => el.disabled)
const waitMs = (ms) => new Promise((done) => setTimeout(done, ms))

// The challenge is drawn as SVG text, so the answer can be read back out of the image. That is the
// prototype's own weakness — the real one is a server-generated PNG — and reading it is the only way
// for a test to drive the flow. Register also needs it, so this is defined before either flow.
const captchaAnswer = (selector) =>
  page.$eval(selector, (img) =>
    [...decodeURIComponent(img.src.split(',')[1]).matchAll(/<text[^>]*>([^<])<\/text>/g)]
      .map((match) => match[1])
      .join('')
      .toUpperCase(),
  )

// 6 · 找回密码. Three steps: the number and the picture buy the message, the message proves the
// number, and only then is a new password worth asking for.
await page.click('#startBtnHero')
await settle()
await page.click('#toReset')
await settle()
check('点「忘记密码」切到找回密码视图', (await title()) === '找回密码', await title())
check('第一步只有手机号与图形验证码', (await visible('#resetPhone')) && (await visible('#resetCaptcha')))
check(
  '第一步没有短信码与新密码栏',
  !(await visible('#resetCode')) && !(await visible('#resetPassword')),
)
check('找回密码一开始就有图形验证码', await visible('#resetCaptchaImg'))
await shot('05-reset-step1.png')

// A number nobody registered is refused here, at the message — not at the end of the walk.
await type('#resetPhone', '13900139000')
await type('#resetCaptcha', await captchaAnswer('#resetCaptchaImg'))
await page.click('#resetNextBtn')
await settle()
check(
  '未注册的手机号：第一步就被拒',
  (await errText('#resetPhoneErr')).includes('这个手机号还没有注册'),
  await errText('#resetPhoneErr'),
)

await type('#resetPhone', '13800138000')
await page.click('#resetNextBtn')
await settle()
check('点「下一步」进第二步「验证手机号」', (await title()) === '验证手机号', await title())
check(
  '第二步提示里写明了发到哪个号',
  (await page.$eval('#resetCodeHint', (el) => el.textContent)).includes('13800138000'),
)
check(
  '第二步没有提交按钮（填满六位就自己走）',
  (await page.$eval('#resetCodeForm', (el) => el.querySelectorAll('button[type=submit]').length)) === 0,
)
await shot('06-reset-code.png')

// Six digits is the whole answer, so this screen judges itself — and a refusal empties the boxes,
// because six wrong digits would otherwise sit there looking answered with nothing left to type.
await type('#resetCode', '000000')
await settle()
const resetCodeErr = await errText('#resetCodeErr')
check('短信码错：落在验证码字段', resetCodeErr.includes('短信验证码不正确或已过期'), resetCodeErr)
check('短信码错：六格被清空，可以重填', (await page.$eval('#resetCode', (el) => el.value)) === '')

await type('#resetCode', '123456')
await settle()
check('短信码对：自动进第三步「设置新密码」', (await title()) === '设置新密码', await title())
await shot('07-reset-password.png')

await type('#resetPassword', 'newpass123')
await page.click('#resetSubmitBtn')
await settle()
check('重置成功：进「密码已重置」', (await title()) === '密码已重置', await title())
check('重置成功：不自动登录', await visible('#navGuest'))
const resetCopy = await page.$eval('#resetDoneView', (el) => el.innerText.replace(/\s+/g, ' '))
check('重置成功：写明了会话已失效', resetCopy.includes('所有设备上的登录都已经失效'), resetCopy.slice(0, 60))
await shot('08-reset-done.png')

await page.click('#resetDoneToLogin')
await settle()
check('成功页「去登录」回到登录视图', (await title()) === '登录', await title())

// 7 · 注册. Two steps — details first, then the code.
await page.click('#toRegister')
await settle()
check('点「注册」切到注册视图', (await title()) === '注册', await title())
check('注册第一步没有图形验证码（首个发码免它）', !(await visible('#regCaptchaWrap')))
await shot('09-register.png')

// A five-character name is refused for its length before anybody asks whether it is free — that order
// is the real one, and it is worth pinning because the other answer would be the intuitive guess.
await type('#regUsername', 'admin')
await page.click('#regPhone')
await settle()
check(
  '用户名太短：长度先生效，不问是否被占用',
  (await errText('#regUsernameErr')).includes('长度需要 6–30 个字符'),
  await errText('#regUsernameErr'),
)

await type('#regUsername', 'adminuser')
await page.click('#regPhone')
await settle()
check(
  '用户名被占用：字段级提示',
  (await errClass('#regUsernameErr')) === 'field-error' &&
    (await errText('#regUsernameErr')).includes('这个用户名已经被占用'),
  await errText('#regUsernameErr'),
)

await type('#regUsername', 'newuser01')
await settle()
check('用户名可用：字段下方变成「通过」', (await errClass('#regUsernameErr')) === 'field-ok')

await type('#regPhone', '13800138000')
await type('#regPassword', 'longpassphrase1')
await page.click('#regNextBtn')
await settle()
check(
  '已注册的手机号：字段级提示',
  (await errText('#regPhoneErr')).includes('这个手机号已经注册过了'),
  await errText('#regPhoneErr'),
)

await type('#regPhone', '13900139000')
await page.click('#regNextBtn')
await settle()
check('号码可用：进第二步「验证手机号」', (await title()) === '验证手机号', await title())
check('第二步提示里写明了发到哪个号', (await page.$eval('#regCodeHint', (el) => el.textContent)).includes('13900139000'))
await shot('10-register-code.png')

// Going back is what makes the picture appear: the first send was free, the next one is not. Waiting
// out the cooldown is part of the same rule — asking for another message too soon is what the real
// server refuses, so the button stays dark until the countdown is done.
await page.click('#regResend')
await settle()
check('退回第一步：填过的值还在', (await page.$eval('#regUsername', (el) => el.value)) === 'newuser01')
check('退回第一步：图形验证码这时才出现', await visible('#regCaptchaWrap'))
check('退回第一步：冷却没走完，「下一步」是灰的', await isDisabled('#regNextBtn'))

await waitMs(11000)
await type('#regCaptcha', await captchaAnswer('#regCaptchaImg'))
await settle()
check('冷却走完且填了图形码：「下一步」重新可用', !(await isDisabled('#regNextBtn')))
await page.click('#regNextBtn')
await settle()
check('再发一次码后回到第二步', (await title()) === '验证手机号', await title())

await type('#regCode', '000000')
await page.click('#regSubmitBtn')
await settle()
check(
  '注册：短信码错落在验证码字段',
  (await errText('#regCodeErr')).includes('短信验证码不正确或已过期'),
  await errText('#regCodeErr'),
)

await type('#regCode', '123456')
await page.click('#regSubmitBtn')
await settle()
check('注册成功：进「注册成功」', (await title()) === '注册成功', await title())
const doneCopy = await page.$eval('#registerDoneView', (el) => el.innerText.replace(/\s+/g, ' '))
check('注册成功：欢迎语带上了用户名', doneCopy.includes('欢迎，newuser01。'), doneCopy.slice(0, 70))
check('注册成功：写明了用户名不可更改', doneCopy.includes('用户名不可更改'))
/* **The line that pointed at a gap which no longer exists.** It said the browsing and publishing
   interface had not been built and that the CLI would eventually publish; both halves are false
   today (the console is `/`, and publishing is a browser action — ADR 0031 §决定 2). What replaces
   it points at the console instead of repeating what that page's empty state already says. */
check(
  '注册成功：指到控制台，不再说「界面还没做」',
  doneCopy.includes('控制台') && !doneCopy.includes('还没做'),
  doneCopy.slice(-38),
)
await shot('11-register-done.png')

await page.click('#doneStartBtn')
await settle()
check('注册成功「开始使用」：关浮层', !(await visible('#overlay')))
check('注册成功「开始使用」：导航切成已登录态', (await visible('#navUser')) && !(await visible('#navGuest')))

// ---------------------------------------------------------------------------------------------
// 8 · 授权请求 — the one screen no page leads to. The CLI hands the browser a URL carrying
// client_id, state and scope, so these checks navigate rather than click.

const pageUrl = 'file://' + resolve(src)
const openWith = (query) => page.goto(pageUrl + query, { waitUntil: 'networkidle0' })
const SCOPE_QUERY = '?client_id=skillmaster-cli&state=abc123&scope=skills:read%20skills:write'
/* The address `skillmaster login` actually opens: the four parameters **plus the name the CLI read
   off this machine** (`name`, prd 做 #1). The demo has no CLI, so the value is written into the link
   the same way the CLI writes it into its own — which is the only difference between this and the
   fallback case below. */
const NAMED_QUERY = SCOPE_QUERY + '&name=' + encodeURIComponent('我的 MacBook')

await openWith(NAMED_QUERY)
await settle()
check('带参数的地址：直接进「授权请求」', (await title()) === '授权请求', await title())
check(
  '授权请求压掉了首页（不是浮在首页上的弹窗）',
  await page.$eval('#overlay', (el) => el.classList.contains('is-standalone')),
)
check('写明了是哪个客户端', (await page.$eval('#consentWho', (el) => el.textContent)).includes('SkillMaster CLI'))
const scopeCopy = await page.$eval('#consentScopes', (el) => el.innerText.replace(/\s+/g, ' '))
check(
  '权限写成后果，不是名字，而且不说「删除」',
  scopeCopy.includes('以你的身份提交新版本、管理共享') && !scopeCopy.includes('删除'),
  scopeCopy.slice(0, 80),
)
check('已知客户端不显示那句警告', !(await visible('#consentUnknownClient')))
/* **No × on this screen, in any of its states** (2026-10-16): the card is the whole page, so the
   control would have nowhere to send anybody. Asserted beside the other "this is the whole page"
   check above rather than with the result page's, because it is the same fact. */
check('授权请求那一屏不画关闭按钮', !(await visible('#closeBtn')))
/* **The disclosure sentence, pinned because it has been a promise nobody could keep — twice.**
   It first said the credential could be revoked with `skillmaster logout`, which since v2 deletes
   only the local copy and leaves the server-side authorization alive (ADR 0038 §决定 7). Then it said
   「要结束它，请重置密码」, which was true while this login had no entry of its own — and since v4 it
   *is* a 登录设备 like any other, ended with 退出, so that became the second false instruction.
   Four things are pinned: the lifetime, the way out, and that neither dead instruction is back.
   `p.hint` and not `.hint`: the unknown-client warning is a `span.hint` earlier in the document. */
/* Two paragraphs since 2026-10-16 — the lifetime and the way out were one sentence joined by 「；」,
   and read together as one thing. Both are still pinned; they are just read as one string here. */
const consentHint = await page.$$eval('#consentDisclosure, #consentExit', (els) =>
  els.map((el) => el.textContent.replace(/\s+/g, ' ')).join(' '),
)
check(
  '披露语说清了寿命：7 天，以及闲置 24 小时',
  consentHint.includes('7 天') &&
    consentHint.includes('闲置 24 小时') &&
    consentHint.includes('失效'),
  consentHint.slice(0, 60),
)
check(
  '出路是到「登录设备」里退出，不是重置密码、也不是 logout',
  consentHint.includes('登录设备') &&
    consentHint.includes('退出') &&
    !consentHint.includes('重置密码') &&
    !/logout/.test(consentHint),
  consentHint.slice(-30),
)
/* **The box arrives filled, and that is the change of 2026-10-16.** The CLI reads the name this
   machine already has (`scutil` on macOS, `hostnamectl` on Linux, `COMPUTERNAME` on Windows) and
   puts it on the address, so the common case became one click instead of one click plus typing. The
   requirement itself is unchanged and is exercised in its own case below, on an address without it. */
check(
  '名称预填：地址带了 name，框里就是它',
  (await page.$eval('#consentName', (el) => el.value)) === '我的 MacBook',
)
check(
  '预填之后「同意并继续」直接可点',
  !(await page.$eval('#consentAgree', (el) => el.disabled)),
)
/* **The scope line, and the two words that were wrong in it.** It said the tool could 「发布」 — but
   publishing exists only on the browser plane (`WebSkillRoutes.PUBLISH`, ADR 0031 §决定 2), so a
   credential on the API plane can never do it. It also said 「删除」, which *is* true of the
   credential (`DELETE /api/v1/skills/{ns}/{name}`, gated by `skills:write`) — and that is the defect:
   the product's rule for a destructive act is that a person does it in the browser and confirms it
   there, and that rule had been applied to 上线 and not to 删除. The list is now the only thing on
   this screen holding that line: a boundary paragraph used to say both words were out of reach, and
   it was removed the same day. */
const scopeItems = await page.$$eval('#consentScopes li', (items) =>
  items.map((li) => li.textContent.trim()),
)
check(
  '权限那条不说「发布」也不说「删除」（这两个动作凭据做不了）',
  scopeItems.some((t) => t.includes('提交新版本')) &&
    !scopeItems.some((t) => t.includes('发布')) &&
    !scopeItems.some((t) => t.includes('删除')),
  scopeItems.join(' / '),
)
check(
  '授权请求不把焦点落在「同意」上（否则一个回车就等于同意）',
  (await page.evaluate(() => document.activeElement.id)) !== 'consentAgree',
  await page.evaluate(() => document.activeElement.id || document.activeElement.tagName),
)
await shot('12-consent.png')

await page.click('#consentAgree')
await settle()
check('同意后落到「授权成功」', (await title()) === '授权成功', await title())
check(
  '成功那句是 CLI 的原话',
  (await page.$eval('#consentDoneNote', (el) => el.textContent)).includes('回到终端继续'),
)
/* **The result page has no close button** (design/README.md §授权结果): it is the CLI's own page
   standing alone, there is nothing behind it to dismiss, and the CLI could not draw one anyway. */
check('结果页不画关闭按钮', !(await visible('#closeBtn')))
/* **And it says nothing about closing itself.** This card used to carry a second line — a
   three-second countdown promising to close, and a replacement sentence for when the browser
   refused — and both halves went on 2026-10-16. The close can never be granted here (the page fails
   both of the HTML standard's *script-closable* conditions; see `closeAfterReading` in the partial),
   so that line was a promise about a future that did not exist, on the card whose job is to report
   what happened. **So what is asserted now is the exact copy**: one sentence, and not one word about
   the page. The attempt is still made, and the block at the end of this section watches it happen. */
const doneCard = await page.$eval('#consentDoneView', (el) => el.innerText)
check('结果页只有那一句，不承诺自己会关掉', doneCard.trim() === '请关闭此页面回到终端继续。', doneCard)
await shot('13-consent-agreed.png')

await openWith(NAMED_QUERY)
await settle()
await page.click('#consentRefuse')
await settle()
check('拒绝后落到「授权未完成」', (await title()) === '授权未完成', await title())
const refuseCopy = await page.$eval('#consentDoneNote', (el) => el.textContent)
check('拒绝那句是 CLI 的原话', refuseCopy.includes('你拒绝了这次授权请求'), refuseCopy)
/* **What the person did, and nothing after it.** This sentence was wrong twice on 2026-10-16, both
   times by owing an instruction the card does not owe: 「回到终端查看原因」 sends somebody to look up
   a reason they *are*, and 「可以回到终端重新登录」 answers a person who has just said no by inviting
   them to say yes. So the two shapes being kept out are pinned as hard as the sentence itself — the
   second one because "go and retry" is the most plausible way for this to come back. */
check(
  '拒绝那句只说人做过什么，不欠下一步',
  !refuseCopy.includes('查看原因') && !refuseCopy.includes('重新登录') && !refuseCopy.includes('回到终端'),
  refuseCopy,
)
/* Shot since 2026-10-16, and it is new because until then only the agreed state had one: the page
   half of this flow had a picture of one of its two outcomes. */
await shot('16-consent-refused.png')

/* **The address without a name, which is the whole of what the pre-fill replaced.** An older CLI, or
   a machine whose name could not be read: the box is empty, and the requirement does its work — an
   unnamed row in 登录设备 is one with nothing to recognize it by, and a disabled button says so
   before the click rather than after it. */
await openWith(SCOPE_QUERY)
await settle()
check('地址没带 name：框是空的', (await page.$eval('#consentName', (el) => el.value)) === '')
check('空名称：「同意并继续」点不动', await isDisabled('#consentAgree'))
await page.type('#consentName', '我的 MacBook')
check('填了名称，按钮就可点', !(await page.$eval('#consentAgree', (el) => el.disabled)))

/* **The close attempt, watched on a page of its own.** Its own page because the close may actually
   be granted — a tab this script opened is no more script-closable than one a launcher opened, but
   "no more" is an expectation rather than a guarantee, and a closed page would take every check
   after this one with it.

   **Three things are asserted, and they are the three that a future edit could break**: the attempt
   is made, it is made *silently* (nothing on the card mentions it), and **nothing is ever rewritten**
   — the sentence is read twice, before and after the attempt, and has to be identical. That last one
   is the inverse of what this block used to say, and deliberately: it guards the thing that was
   removed. LangChain's deepagents removed the same rewrite for the same reason — "the static message
   already reads correctly whether or not the tab closes, so the text never shifts" (PR #4463). */
const closingPage = await browser.newPage()
await closingPage.evaluateOnNewDocument(() => {
  window.__closeCalls = 0
  const refused = window.close.bind(window)
  window.close = function () {
    window.__closeCalls += 1
    return refused()
  }
})
await closingPage.goto(pageUrl + NAMED_QUERY, { waitUntil: 'networkidle0' })
await closingPage.click('#consentAgree')
await new Promise((resolve) => setTimeout(resolve, 400))
const quietStart = await closingPage.$eval('#consentDoneNote', (el) => el.textContent)
/* **The card says nothing about anything happening by itself.** Not the countdown that used to be
   here, and not the withdrawal sentence that used to replace it. **It does tell the person to close
   the page**, and that is a different thing: an instruction they carry out versus a promise the page
   makes on its own. The line between the two is the whole of this change, so it is the thing pinned
   — `/自动/` is what may not appear, and 「请关闭此页面」 is what must. */
const closingCard = await closingPage.$eval('#consentDoneView', (el) => el.innerText)
check(
  '结果页只有指令，没有"会自动发生"的承诺',
  !/自动/.test(closingCard) && closingCard.includes('请关闭此页面'),
  closingCard,
)
await new Promise((resolve) => setTimeout(resolve, 4000))
const quietLater = await closingPage.$eval('#consentDoneNote', (el) => el.textContent)
check('过了那一拍，那句话还是原来那句（没有改口）', quietLater === quietStart, quietStart + ' → ' + quietLater)
const closeCalls = await closingPage.evaluate(() => window.__closeCalls)
check('它确实静默地试过一次关闭', closeCalls === 1, 'window.close 被调了 ' + closeCalls + ' 次')
await closingPage.close()

await openWith('?client_id=skillmaster-cli')
await settle()
const incompleteCopy = await page.$eval('#consentIncompleteView', (el) => el.innerText)
check('缺 state：进「链接不完整」态', incompleteCopy.includes('这个链接不完整'), incompleteCopy.slice(0, 50))
check('并说清要回终端重跑哪个命令', incompleteCopy.includes('skillmaster login'))
await shot('14-consent-incomplete.png')

await openWith('?client_id=nobody-knows-this&state=abc&scope=skills:read%20mystery:scope')
await settle()
const unknownCopy = await page.$eval('#consentView', (el) => el.innerText.replace(/\s+/g, ' '))
check(
  '未知客户端：显示原始 id 并给警告',
  unknownCopy.includes('nobody-knows-this') && unknownCopy.includes('不在已知名单里'),
)
check('未知 scope：明说不知道它的含义', unknownCopy.includes('我们不知道这一项的含义'))
/* Shot since 2026-10-16. This is the only state of this screen that carries **both** additions at
   once — a warning spliced into the lead-in and a second line under a raw scope — so it is the one
   that shows whether either of them breaks the card's rhythm. It was measured by hand twice before
   anybody noticed there was no picture of it. */
await shot('15-consent-unknown.png')

await openWith('')
await settle()
check('不带参数：就是普通首页，浮层不开', !(await visible('#overlay')))

await browser.close()

const failed = checks.filter((c) => !c.pass)
console.log(JSON.stringify({ src, checks, problems, failed: failed.length }, null, 2))
process.exit(failed.length > 0 || problems.length > 0 ? 1 : 0)
