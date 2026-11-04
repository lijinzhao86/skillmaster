import { readFileSync } from 'node:fs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render } from '@testing-library/react'
import CliSignInPage from '../src/pages/CliSignInPage'
import { loadSession, session } from '../src/store'
import {
  clearCookies,
  errorBody,
  flush,
  json,
  setCookie,
  stubFetch,
  stubLocation,
  type RecordedRequest,
} from './support'

/**
 * 授权请求 — the screen the CLI hands the browser to.
 *
 * This is the product's only moment of disclosure: somebody who typed `skmr login` has not
 * thereby learned what this machine will be able to do, or how long it lasts, or how to end it. So
 * the tests below are mostly about what the screen says, plus the two structural facts that make the
 * two buttons work at all — the four parameters come off the address and go back unread, and the
 * refusal goes through the server because the page has never been told the CLI's loopback address.
 *
 * **Everything asserted here is the design's own string or a value from the address.** The page is a
 * copy of the design's consent screen (`design/src/shared/auth/`: `views/ConsentView.tsx` and the
 * sentences in `authModel.ts`), so a sentence that drifts from there is a defect rather than a
 * wording preference.
 */

const CLIENT = 'skillmaster-cli'
const STATE = 'state-from-the-cli'
const CHALLENGE = 'challenge-from-the-cli'

const LOOPBACK = `http://127.0.0.1:51004/callback?code=one-time-code&state=${STATE}`
const REFUSED = `http://127.0.0.1:51004/callback?error=access_denied&state=${STATE}`

let location = stubLocation('/cli-sign-in')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/cli-sign-in')
  // The session is a module singleton; every test says for itself whether there is one.
  session.reset()
  vi.restoreAllMocks()
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/**
 * The address the CLI opens: the four parameters, the multi-valued one space-separated, **plus the
 * machine's name** — the CLI reads it off the system, and it is the only reason this screen can start
 * with the box filled (`design/README.md` §怎么打开「授权请求」).
 */
function address(overrides: Record<string, string | null> = {}): string {
  const params = new URLSearchParams({
    client_id: CLIENT,
    state: STATE,
    scope: 'skills:read skills:write',
    code_challenge: CHALLENGE,
    name: '我的 MacBook',
  })
  for (const [key, value] of Object.entries(overrides)) {
    if (value === null) {
      params.delete(key)
    } else {
      params.set(key, value)
    }
  }
  return `?${params.toString()}`
}

/**
 * Renders the page at its own address, with answers for the requests it makes.
 *
 * The session read is not answered here because the page does not make it: `App` does, and gates
 * rendering on it, so by the time this page renders there is a session. The one case about somebody
 * who has none drives the store itself, and is last in this file because the store is a singleton
 * that stays loaded for the rest of the module.
 */
async function showPage(url = address(), ...answers: Array<() => Response | Promise<Response>>) {
  location = stubLocation('/cli-sign-in', url)
  const stub = stubFetch(...answers)
  const view = render(<CliSignInPage />)
  await flush()
  return { ...view, ...stub }
}

const ACCOUNT = { user_id: 'user-a', username: 'alice', namespace: 'alice' }

/**
 * The same page with somebody signed in — the state `App` guarantees in a browser.
 *
 * The first stubbed answer is the session read (`/web/session`), which `App` performs before this
 * page renders; the rest answer what the test makes the page ask.
 */
async function showPageAs(
  url = address(),
  account = ACCOUNT,
  ...answers: Array<() => Response | Promise<Response>>
) {
  location = stubLocation('/cli-sign-in', url)
  const stub = stubFetch(() => json(200, account), ...answers)
  await act(async () => {
    await loadSession()
  })
  const view = render(<CliSignInPage />)
  await flush()
  return { ...view, ...stub }
}

function postPaths(requests: RecordedRequest[]): string[] {
  return requests.filter((request) => request.method === 'POST').map((request) => request.path)
}

function typeName(container: HTMLElement, value: string): void {
  fireEvent.change(container.querySelector('#consentName') as HTMLInputElement, {
    target: { value },
  })
}

const text = (element: Element | null): string => (element?.textContent ?? '').trim()

describe('the authorization-request screen', () => {
  it('says what the tool may do, for how long, and how to end it', async () => {
    const { container } = await showPage()

    expect(text(container.querySelector('h2'))).toBe('授权请求')

    // The name is the one thing the person answers, and the card says what it is for.
    expect(text(container.querySelector('label[for="consentName"]'))).toBe('名称')
    expect(container.querySelector('#consentName')?.getAttribute('placeholder')).toBe('我的 MacBook')
    expect(container.querySelector('#consentName')?.getAttribute('maxlength')).toBe('60')
    expect(container.textContent).toContain('这台机器的名称。它只出现在你的「登录设备」里。')

    // Who is asking, then what it may do — written as consequences rather than as scope names.
    // The space between the client name and the sentence is part of the design's string: without
    // it the line reads 「SkillMaster CLI请求以下权限：」.
    expect(text(container.querySelector('.consent-who'))).toContain(
      'SkillMaster CLI 请求以下权限：',
    )
    const scopes = [...container.querySelectorAll('.consent-scopes li')].map((item) => text(item))
    expect(scopes).toEqual([
      '读取你可见的全部 skill，包括未公开的',
      '以你的身份提交新版本、管理草稿与共享；从未上线过的 skill 还可以删除',
    ])
    // **「发布」 is still a word this plane cannot do** (ADR 0031 §决定 2). 「删除」 is back but
    // narrowed (ADR 0127): the exact list above is the assertion — a bare 「删除 skill」 would mean
    // deleting a live one, which nobody can do. The word count is part of it: a second 「删除」
    // anywhere else on this screen would promise more than the list says. The boundary paragraph
    // that used to spell negatives out stays removed.
    expect((container.textContent?.match(/删除/g) ?? []).length).toBe(1)
    expect(container.textContent).not.toContain('发布')

    // The disposition, in the two lines the design measured: the lifetime, then the way out.
    expect(container.textContent).toContain('同意后不再询问。7 天或闲置 24 小时后失效。')
    expect(container.textContent).toContain('如需提前结束，请在控制台的「登录设备」中退出。')
    // It used to say the credential was long-lived and could only be ended by resetting the password.
    // Both halves were false promises, and this screen is the only place the person is told.
    expect(container.textContent).not.toContain('长期凭据')
    expect(container.textContent).not.toContain('重置密码')

    // The two answers, and the one that ends nothing is the quiet one.
    expect(text(container.querySelector('.dialog-submit'))).toBe('同意并继续')
    expect(text(container.querySelector('.dialog-foot .link'))).toBe('拒绝')
  })

  it('starts from the name the CLI read off this machine, so one click is enough', async () => {
    const { container } = await showPage()

    // The CLI is the only party that knows this, and it puts it on the address — see the helper above.
    expect((container.querySelector('#consentName') as HTMLInputElement).value).toBe('我的 MacBook')
    // Filled is not fixed: it is still an input, and it can still be corrected.
    expect(container.querySelector('.dialog-submit')?.hasAttribute('disabled')).toBe(false)
  })

  it('still requires a name, and says so by disabling the button rather than refusing later', async () => {
    // An address with no `name` on it: an older CLI, or somebody who typed the address themselves.
    // The box is empty, which is where this screen started before the pre-fill existed.
    const { container } = await showPage(address({ name: null }))

    // Cheaper than a refusal afterwards, which is the design's own reason for checking here.
    expect((container.querySelector('#consentName') as HTMLInputElement).value).toBe('')
    expect(container.querySelector('.dialog-submit')?.hasAttribute('disabled')).toBe(true)
    // Whitespace is not a name: the server trims, and to the person " laptop" and "laptop" are one
    // machine — which is what a repeated login finds the one it replaces by (Q7).
    typeName(container, '  ')
    expect(container.querySelector('.dialog-submit')?.hasAttribute('disabled')).toBe(true)
    typeName(container, '我的 MacBook')
    expect(container.querySelector('.dialog-submit')?.hasAttribute('disabled')).toBe(false)
  })

  it('posts the name and the four parameters it read off its own address', async () => {
    const { container, requests } = await showPage(address(), () =>
      json(201, { redirect_to: LOOPBACK }),
    )

    fireEvent.click(container.querySelector('.dialog-submit') as HTMLButtonElement)
    await flush()

    expect(postPaths(requests)).toEqual(['/web/cli-sign-ins'])
    expect(requests.at(-1)?.body).toEqual({
      name: '我的 MacBook',
      client_id: CLIENT,
      // Space-separated: the spelling the credential itself carries, and the one the address used —
      // so this screen never has to know a second format.
      scope: 'skills:read skills:write',
      state: STATE,
      code_challenge: CHALLENGE,
    })
    // The answer is an address, and the navigation is the browser's: the code on it is spent by the
    // CLI's own redemption, not by this page.
    expect(location.assign).toHaveBeenCalledWith(LOOPBACK)
  })

  it('asks the server where a refusal goes, and sends the browser there', async () => {
    const { container, requests } = await showPage(address(), () =>
      json(200, { redirect_to: REFUSED }),
    )

    fireEvent.click(container.querySelector('.dialog-foot .link') as HTMLButtonElement)
    await flush()

    expect(postPaths(requests)).toEqual(['/web/cli-sign-ins/refuse'])
    // No name, and no scopes: a refusal is a login that did not happen, so nothing about it is sent
    // back as though the person had agreed to something.
    expect(requests.at(-1)?.body).toEqual({ client_id: CLIENT, state: STATE })
    // The page has never been told the loopback address — it is read from the registered client row
    // on the server, deliberately, because a target the request could choose is an open redirect with
    // a one-time code on the end of it. So the refusal comes back through the server too.
    expect(location.assign).toHaveBeenCalledWith(REFUSED)
  })

  it('says the link is incomplete, in the design’s sentence, when the address cannot work', async () => {
    // All three are holes the sentence names, and the loop takes each of them away in turn — the
    // challenge included, since it is what the code is created with on the server.
    for (const missing of ['client_id', 'state', 'code_challenge']) {
      const { container } = await showPage(address({ [missing]: null }))

      expect(text(container.querySelector('.dialog-view'))).toBe(
        '这个链接不完整，缺少 client_id、state 或 code_challenge，无法完成授权。请回到终端重新运行 skmr login。',
      )
      // The card still says what it is, and there is nothing to answer.
      expect(text(container.querySelector('h2'))).toBe('授权请求')
      expect(container.querySelector('#consentName')).toBeNull()
      expect(container.querySelector('.dialog-submit')).toBeNull()
      expect(container.querySelector('.dialog-foot')).toBeNull()
    }
  })

  it('tells an unknown client and an unknown scope apart from the known ones', async () => {
    const { container } = await showPage(
      address({ client_id: 'some-other-tool', scope: 'skills:read skills:teleport' }),
    )

    // Shown as itself, with the warning — inventing a friendly name for a client nobody has heard of
    // would be the lie the warning exists to prevent.
    expect(text(container.querySelector('.consent-who strong'))).toBe('some-other-tool')
    expect(text(container.querySelector('.consent-who .hint'))).toBe(
      '（这个客户端不在已知名单里，请确认它确实是你安装的）',
    )
    // And the scope with no wording is shown raw **and said to be undescribed**: a blank would read as
    // harmless, which is the one thing an unknown scope must not read as.
    const items = [...container.querySelectorAll('.consent-scopes li')]
    expect(items).toHaveLength(2)
    expect(text(items[0] ?? null)).toBe('读取你可见的全部 skill，包括未公开的')
    expect(text(items[1]?.querySelector('code') ?? null)).toBe('skills:teleport')
    expect(text(items[1]?.querySelector('.hint') ?? null)).toBe('（我们不知道这一项的含义，请谨慎同意）')
  })

  /**
   * **The id and the scopes come from an address, so they can name a prototype key.** A plain
   * object answers `__proto__` with an object — a blank page when React tries to render it — and
   * `constructor` with a function, which would make an unknown client look known and remove the
   * warning that exists for exactly this case.
   */
  it('treats prototype keys in the address as unknown, not as known', async () => {
    for (const [id, scope] of [
      ['__proto__', 'skills:read __proto__'],
      ['constructor', 'skills:read toString'],
      ['toString', 'skills:read constructor'],
    ] as const) {
      const { container, unmount } = await showPage(address({ client_id: id, scope }))
      const rawScope = scope.split(' ')[1]

      expect(text(container.querySelector('.consent-who strong'))).toBe(id)
      expect(text(container.querySelector('.consent-who .hint'))).toBe(
        '（这个客户端不在已知名单里，请确认它确实是你安装的）',
      )
      const items = [...container.querySelectorAll('.consent-scopes li')]
      expect(text(items[1]?.querySelector('code') ?? null)).toBe(rawScope)
      expect(text(items[1]?.querySelector('.hint') ?? null)).toBe(
        '（我们不知道这一项的含义，请谨慎同意）',
      )
      unmount()
    }
  })

  it('says so in the card when the request did not go through', async () => {
    // **A state the design does not draw**, and the reason the sentence is the server's own rather
    // than one written here: every other sentence on this screen has a source, and this one's is the
    // error envelope. Reported in the delivery notes rather than settled here.
    const { container } = await showPage(address(), () =>
      json(500, errorBody('internal_error', 'The login could not be started.')),
    )

    fireEvent.click(container.querySelector('.dialog-submit') as HTMLButtonElement)
    await flush()

    expect(text(container.querySelector('.form-error'))).toBe('服务暂时不可用，请稍后重试。')
    expect(container.querySelector('.form-error')?.getAttribute('role')).toBe('alert')
    expect(location.assign).not.toHaveBeenCalled()
    // And the button comes back, so a second attempt is possible.
    expect(container.querySelector('.dialog-submit')?.hasAttribute('disabled')).toBe(false)
  })

  /**
   * Who is granting (v46). The screen used to show only the client and the scopes, so a browser
   * still signed in as the previous account silently issued the credential to it.
   */
  it('shows which account the credential will belong to, above the name', async () => {
    const { container } = await showPageAs()

    const card = container.querySelector('.consent-account')
    expect(text(container.querySelector('#consentAccount'))).toBe(ACCOUNT.username)
    expect(text(card?.querySelector('.consent-account-hint'))).toBe('授权将发给这个账号。')
    expect(text(container.querySelector('#consentSwitch'))).toBe('不是我，换一个账号')

    // The name is the largest thing in the card — the numbers are the design's own. jsdom does not
    // process stylesheet imports, so the rule is read from the file the browser loads.
    const styles = readFileSync('src/styles.css', 'utf8')
    const nameRule = styles.slice(
      styles.indexOf('.dialog .consent-account-who strong {'),
      styles.indexOf('}', styles.indexOf('.dialog .consent-account-who strong {')),
    )
    const hintRule = styles.slice(
      styles.indexOf('.dialog .consent-account-hint {'),
      styles.indexOf('}', styles.indexOf('.dialog .consent-account-hint {')),
    )
    expect(nameRule).toContain('font-size: 18px')
    expect(hintRule).toContain('font-size: 13px')

    // And it comes before the one thing the person answers.
    const input = container.querySelector('#consentName') as Element
    expect(
      (card as Element).compareDocumentPosition(input) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
  })

  it('switches by ending the session and signing in again, back on this address', async () => {
    const { container, requests } = await showPageAs(
      address(),
      ACCOUNT,
      () => new Response(null, { status: 204 }),
    )

    fireEvent.click(container.querySelector('#consentSwitch') as HTMLButtonElement)
    await flush()

    expect(postPaths(requests)).toEqual(['/web/logout'])
    // The whole address is the return trip: those parameters are the login, and losing them would
    // land the person on a screen that cannot work.
    expect(location.replace).toHaveBeenCalledWith(
      `/login?return_to=${encodeURIComponent(`/cli-sign-in${address()}`)}`,
    )
  })

  it('keeps the account and says why when the switch could not end the session', async () => {
    const { container } = await showPageAs(address(), ACCOUNT, () =>
      json(500, errorBody('internal_error', 'The session could not be ended.')),
    )

    fireEvent.click(container.querySelector('#consentSwitch') as HTMLButtonElement)
    await flush()

    expect(text(container.querySelector('.form-error'))).toBe('服务暂时不可用，请稍后重试。')
    expect(location.replace).not.toHaveBeenCalled()
    // Nothing changed, so the card must still name the same account.
    expect(text(container.querySelector('#consentAccount'))).toBe(ACCOUNT.username)
  })

  /**
   * Last in the file on purpose: it leaves the app's single session store with no account.
   */
  it('sends somebody with no session to sign in, and back here afterwards', async () => {
    location = stubLocation('/cli-sign-in', address())
    stubFetch(() => json(401, errorBody('unauthenticated', 'Authentication is required.')))
    render(<CliSignInPage />)
    // The bootstrap `App` performs, driven here because this test renders the page by itself.
    await act(async () => {
      await loadSession()
    })
    await flush()

    // The whole address, query included: those parameters *are* the login, and losing them across the
    // sign-in would land the person on a screen that cannot work. `replace` rather than `assign`, so
    // that the page that refused them is not waiting behind the Back button.
    expect(location.replace).toHaveBeenCalledWith(
      `/login?return_to=${encodeURIComponent(`/cli-sign-in${address()}`)}`,
    )
  })
})
