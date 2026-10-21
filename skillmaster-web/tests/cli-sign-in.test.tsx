import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render } from '@testing-library/react'
import CliSignInPage from '../src/pages/CliSignInPage'
import { loadSession } from '../src/store'
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
 * This is the product's only moment of disclosure: somebody who typed `skillmaster login` has not
 * thereby learned what this machine will be able to do, or how long it lasts, or how to end it. So
 * the tests below are mostly about what the screen says, plus the two structural facts that make the
 * two buttons work at all — the four parameters come off the address and go back unread, and the
 * refusal goes through the server because the page has never been told the CLI's loopback address.
 *
 * **Everything asserted here is the design's own string or a value from the address.** The page is a
 * copy of `design/partials/auth-overlay.html`'s consent fragment, so a sentence that drifts from that
 * file is a defect rather than a wording preference.
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
    expect(text(container.querySelector('.consent-who'))).toContain('SkillMaster CLI')
    expect(text(container.querySelector('.consent-who'))).toContain('请求以下权限：')
    const scopes = [...container.querySelectorAll('.consent-scopes li')].map((item) => text(item))
    expect(scopes).toEqual(['读取你可见的全部 skill，包括未公开的', '以你的身份提交新版本、管理草稿与共享'])
    // **Neither word a credential on this plane cannot do.** 「发布」 exists only on the browser plane
    // (ADR 0031 §决定 2), and deleting moved to the browser in v4 (ADR 0046). The list is the only
    // thing on this screen holding that line: the boundary paragraph that used to spell both out was
    // removed, because a consent screen that enumerates negatives reads as a list of things to worry
    // about.
    expect(container.textContent).not.toContain('发布')
    expect(container.textContent).not.toContain('删除')

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
        '这个链接不完整，缺少 client_id、state 或 code_challenge，无法完成授权。请回到终端重新运行 skillmaster login。',
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
   * Last in the file on purpose: it loads the app's single session store, and nothing resets it. Every
   * test above ran before the page could have a session, which is the state `App` puts it in.
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
