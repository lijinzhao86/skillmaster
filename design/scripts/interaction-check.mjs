// Drive the chosen direction through every interaction spec.md §5 requires, and shoot each state.
//
//   node design/scripts/interaction-check.mjs design/demos/dir3-hara.html design/shots/states
//
// A click test rather than a read of the source: the point of this deliverable is that the page can
// actually be operated, and markup that looks right is not evidence that it behaves right.

import { execSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

const [src, outDir] = process.argv.slice(2)
if (!src || !outDir) {
  console.error('usage: interaction-check.mjs <draft.html> <out-dir>')
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

await openWith(SCOPE_QUERY)
await settle()
check('带参数的地址：直接进「授权请求」', (await title()) === '授权请求', await title())
check(
  '授权屏压掉了首页（不是浮在首页上的弹窗）',
  await page.$eval('#overlay', (el) => el.classList.contains('is-standalone')),
)
check('写明了是哪个客户端', (await page.$eval('#consentWho', (el) => el.textContent)).includes('SkillMaster CLI'))
const scopeCopy = await page.$eval('#consentScopes', (el) => el.innerText.replace(/\s+/g, ' '))
check('权限写成后果，不是名字', scopeCopy.includes('以你的名义发布、更新和删除 skill'), scopeCopy.slice(0, 80))
check('已知客户端不显示那句警告', !(await visible('#consentUnknownClient')))
check(
  '授权屏不把焦点落在「同意」上（否则一个回车就等于同意）',
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
await shot('13-consent-agreed.png')

await openWith(SCOPE_QUERY)
await settle()
await page.click('#consentRefuse')
await settle()
check('拒绝后落到「授权未完成」', (await title()) === '授权未完成', await title())
check(
  '拒绝那句是 CLI 的原话',
  (await page.$eval('#consentDoneNote', (el) => el.textContent)).includes('回到终端查看原因'),
)

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

await openWith('')
await settle()
check('不带参数：就是普通首页，浮层不开', !(await visible('#overlay')))

await browser.close()

const failed = checks.filter((c) => !c.pass)
console.log(JSON.stringify({ src, checks, problems, failed: failed.length }, null, 2))
process.exit(failed.length > 0 || problems.length > 0 ? 1 : 0)
