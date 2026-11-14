import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render } from '@testing-library/react'
import CliSignInPage from '../src/pages/CliSignInPage'
import { loadSession, session } from '../src/store'
import {
  clearCookies,
  empty,
  errorBody,
  flush,
  json,
  setCookie,
  stubFetch,
  stubLocation,
  type RecordedRequest,
} from './support'

/**
 * 授权请求 — the screen the CLI's link opens (v68).
 *
 * This is the product's only moment of disclosure: somebody who typed `skmr login` has not thereby
 * learned what the asking machine will be able to do, for how long, or how to end it. So the tests
 * below are mostly about what the screen says, plus the structural facts that make the two buttons
 * work at all — the ticket comes off the address and goes back unread, and the answer is a POST that
 * writes nothing the page itself reads back.
 *
 * **The page does not poll.** It reads the question once and answers it once; the machine that asked
 * is the one waiting. These tests fail if the page ever starts asking on a timer.
 *
 * **Everything asserted here is the design's own string or a value from the server's view.** The page
 * is a copy of the design's consent screen (`design/src/shared/auth/`), so a sentence that drifts
 * from there is a defect rather than a wording preference.
 */

const TICKET = 'ticket-from-the-cli'

const ACCOUNT = { user_id: 'user-a', username: 'alice', namespace: 'alice' }

/** What the server counted when the question was asked, so ages stay stable across a run. */
const AGE_MINUTES = 2

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
 * The address the CLI opens. **The ticket is the whole of it**: the device secret and the PKCE
 * verifier stayed on the machine that asked, so there is nothing else a link could carry.
 */
function address(overrides: Record<string, string | null> = {}): string {
  const params = new URLSearchParams({ ticket: TICKET })
  for (const [key, value] of Object.entries(overrides)) {
    if (value === null) {
      params.delete(key)
    } else {
      params.set(key, value)
    }
  }
  return `?${params.toString()}`
}

/** What the server answers a read of the question. */
function viewBody(overrides: Record<string, unknown> = {}) {
  return {
    status: 'pending',
    name: '我的 MacBook',
    client: 'skillmaster-cli',
    scopes: ['skills:read', 'skills:write'],
    from: '47.117.36.14',
    created_at: new Date(Date.now() - AGE_MINUTES * 60_000).toISOString(),
    expires_at: new Date(Date.now() + 600_000).toISOString(),
    age_minutes: AGE_MINUTES,
    ...overrides,
  }
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
  await settle(view.container)
  return { ...view, ...stub }
}

/**
 * Waits until the card has something in it.
 *
 * **One macrotask is not always enough.** `flush` gives the pending fetch one turn; under a loaded
 * test worker the mocked response can need another, and a test that then clicks a button finds no
 * button. This polls a bounded number of turns instead of betting on one.
 */
async function settle(container: HTMLElement): Promise<void> {
  for (let turn = 0; turn < 20; turn++) {
    await flush()
    if (container.querySelector('.dialog-view') !== null) return
  }
}

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
  await settle(view.container)
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

/** The sentence the design uses when the answer can no longer be given. */
const GONE = '这条链接已经用过或过期了，不能再批准。请回到那台设备，重新运行 skmr login。'
const INCOMPLETE = '这个链接不完整，无法完成授权。请回到那台设备，重新运行 skmr login。'

describe('the authorization-request screen', () => {
  it('says what the machine may do, for how long, and how to end it', async () => {
    const { container, requests } = await showPage(address(), () => json(200, viewBody()))

    expect(text(container.querySelector('h2'))).toBe('授权请求')

    // The link is a one-time invitation, and the warning comes before the identity card because it
    // is about the address the person followed.
    expect(text(container.querySelector('#consentLinkOnly'))).toBe(
      '这条链接只能批准一次登录，不要转发给别人。',
    )

    // The name is the one thing the person answers, and the card says what it is for.
    expect(text(container.querySelector('label[for="consentName"]'))).toBe('名称')
    expect(container.querySelector('#consentName')?.getAttribute('placeholder')).toBe('我的 MacBook')
    expect(container.querySelector('#consentName')?.getAttribute('maxlength')).toBe('60')
    expect(container.textContent).toContain('这台机器的名称。它只出现在你的「登录设备」里。')

    // Who is asking, then what it may do — written as consequences rather than as scope names.
    // **One string, space included**: the design writes `{' 请求以下权限：'}` and a missing space
    // reads as a typo, so a two-part `toContain` would let it through.
    expect(text(container.querySelector('#consentWho'))).toBe('SkillMaster CLI')
    // The space before 请求 is the design's (`{' 请求以下权限：'}`). The hidden warning still sits in
    // `textContent`, so this pins the suffix rather than the whole paragraph.
    expect(container.querySelector('.consent-who')?.textContent?.endsWith(' 请求以下权限：')).toBe(true)
    expect(container.querySelector('#consentUnknownClient')?.hasAttribute('hidden')).toBe(true)
    const scopes = [...container.querySelectorAll('.consent-scopes li')].map((item) => text(item))
    expect(scopes).toEqual([
      '读取你可见的全部 skill，包括未公开的',
      '以你的身份提交新版本、管理草稿与共享；从未上线过的 skill 还可以删除',
    ])

    // **The permission copy is a contract, not a sentence this page may drift from**
    // (ADR 0031 §决定 2, ADR 0127): 「删除」 appears exactly once — the narrowed one — and
    // 「发布」 never appears at all. The two promises this screen used to make (a long-lived
    // credential, ending it by resetting the password) are gone and must not come back.
    expect((container.textContent?.match(/删除/g) ?? []).length).toBe(1)
    expect(container.textContent).not.toContain('发布')
    expect(container.textContent).not.toContain('长期凭据')
    expect(container.textContent).not.toContain('重置密码')

    // The disposition, in the two lines the design measured: the lifetime, then the way out.
    expect(container.textContent).toContain('同意后不再询问。7 天或闲置 24 小时后失效。')
    expect(container.textContent).toContain('如需提前结束，请在控制台的「登录设备」中退出。')

    // The two answers, and the one that ends nothing is the quiet one.
    expect(text(container.querySelector('.dialog-submit'))).toBe('同意并继续')
    expect(text(container.querySelector('.dialog-foot .link'))).toBe('拒绝')

    // It reads the question once, by the ticket alone, and never asks again on a timer.
    expect(requests.map((request) => `${request.method} ${request.path}`)).toEqual([
      `GET /cli-device-sign-ins/${TICKET}`,
    ])
  })

  it('shows where the request came from and when, because the CLI cannot make that up', async () => {
    const { container } = await showPage(address(), () => json(200, viewBody()))

    // The name above is what the CLI reports; this line is the server's own reading of the caller.
    expect(text(container.querySelector('#consentOrigin'))).toBe(
      '请求来自 47.117.36.14，2 分钟前发起。',
    )
  })

  it('says 刚刚 for a request that is less than a minute old', async () => {
    // **The age is the server's count.** A browser clock hours ahead would otherwise turn a
    // just-asked question into 「120 分钟前发起」 on a link that lives ten minutes.
    const { container } = await showPage(address(), () =>
      json(200, viewBody({ age_minutes: 0 })),
    )

    expect(text(container.querySelector('#consentOrigin'))).toBe('请求来自 47.117.36.14，刚刚发起。')
  })

  it('leaves the age out when the server did not count one', async () => {
    // A server older than the field can still say where the request came from; it cannot say how
    // old it is, and 「刚刚发起」 would be a guess on the one line that exists to be exact.
    const { container } = await showPage(address(), () =>
      json(200, viewBody({ age_minutes: null })),
    )

    expect(text(container.querySelector('#consentOrigin'))).toBe('请求来自 47.117.36.14。')
  })

  it('leaves the source line out when the server did not record one', async () => {
    const { container } = await showPage(address(), () => json(200, viewBody({ from: null })))

    expect(container.querySelector('#consentOrigin')).toBeNull()
    // The rest of the card is intact: a missing address is not a reason to hide the question.
    expect(container.querySelector('#consentName')).not.toBeNull()
  })

  it('starts from the name the CLI read off this machine, so one click is enough', async () => {
    const { container } = await showPage(address(), () => json(200, viewBody()))

    expect((container.querySelector('#consentName') as HTMLInputElement).value).toBe('我的 MacBook')
    expect(container.querySelector('.dialog-submit')?.hasAttribute('disabled')).toBe(false)
  })

  it('still requires a name, and says so by disabling the button rather than refusing later', async () => {
    const { container } = await showPage(address(), () => json(200, viewBody({ name: '' })))

    expect((container.querySelector('#consentName') as HTMLInputElement).value).toBe('')
    expect(container.querySelector('.dialog-submit')?.hasAttribute('disabled')).toBe(true)

    // Whitespace is not a name: the server trims, and to the person " laptop" and "laptop" are one
    // machine — which is what a repeated login finds the one it replaces by.
    typeName(container, '  ')
    expect(container.querySelector('.dialog-submit')?.hasAttribute('disabled')).toBe(true)
    typeName(container, '我的 MacBook')
    expect(container.querySelector('.dialog-submit')?.hasAttribute('disabled')).toBe(false)
  })

  it('posts the name to the ticket, and then tells the person the machine continues', async () => {
    const { container, requests } = await showPage(address(), () => json(200, viewBody()), () =>
      empty(204),
    )

    typeName(container, '  我的笔记本  ')
    fireEvent.click(container.querySelector('.dialog-submit') as HTMLButtonElement)
    await flush()

    expect(postPaths(requests)).toEqual([`/web/cli-device-sign-ins/${TICKET}/approve`])
    // Trimmed here as well as on the server: the person meant the name, not the spaces around it.
    expect(requests.at(-1)?.body).toEqual({ name: '我的笔记本' })
    // **No navigation and no redirect target.** The machine that asked is the one collecting the
    // credential, so the page's whole remaining job is to say it is safe to close.
    expect(location.assign).not.toHaveBeenCalled()
    expect(text(container.querySelector('#consentDoneView'))).toBe('请关闭此页面，那台设备会继续。')
    // **The title moves with the answer** — the card is the whole page, so it is the only signal that
    // the click landed. Leaving it at 「授权请求」 reads as "nothing happened".
    expect(text(container.querySelector('h2'))).toBe('授权成功')
  })

  it('does not offer to close the page, because closing is the person’s choice', async () => {
    const { container } = await showPage(address(), () => json(200, viewBody()), () => empty(204))

    fireEvent.click(container.querySelector('.dialog-submit') as HTMLButtonElement)
    await flush()

    // The design's card has no close button in this standalone state; the sentence is the instruction.
    expect(container.querySelector('#closeBtn')).toBeNull()
  })

  it('tries once, silently, to close the tab after the answer lands', async () => {
    const close = vi.fn()
    vi.spyOn(window, 'close').mockImplementation(close)
    const { container } = await showPage(address(), () => json(200, viewBody()), () => empty(204))

    fireEvent.click(container.querySelector('.dialog-submit') as HTMLButtonElement)
    await flush()
    expect(close).not.toHaveBeenCalled()

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 3200))
    })
    expect(close).toHaveBeenCalledTimes(1)
    // Nothing on the card says a tab will close: it may refuse, and that would be a promise broken.
    expect(container.textContent).not.toContain('自动关闭')
  })

  it('refuses without a name, and says the request ended', async () => {
    const { container, requests } = await showPage(address(), () => json(200, viewBody()), () =>
      empty(204),
    )

    fireEvent.click(container.querySelector('.dialog-foot .link') as HTMLButtonElement)
    await flush()

    expect(postPaths(requests)).toEqual([`/web/cli-device-sign-ins/${TICKET}/refuse`])
    // No name, and no scopes: a refusal is a login that did not happen, so nothing about it is sent
    // back as though the person had agreed to something.
    expect(requests.at(-1)?.body).toBeUndefined()
    expect(text(container.querySelector('#consentDoneView'))).toBe('你拒绝了这次授权请求。')
    expect(text(container.querySelector('h2'))).toBe('授权未完成')
  })

  it('draws a question that is already over as the expired state, in the design’s sentence', async () => {
    for (const status of ['denied', 'expired', 'used'] as const) {
      const { container, unmount } = await showPage(address(), () =>
        json(200, viewBody({ status })),
      )

      expect(container.textContent).toContain(GONE)
      // Nothing to answer: no name box, no buttons, no scope list.
      expect(container.querySelector('#consentName')).toBeNull()
      expect(container.querySelector('.dialog-submit')).toBeNull()
      expect(container.querySelector('.dialog-foot')).toBeNull()
      unmount()
    }
  })

  it('draws an approved question as done, not as another chance to approve', async () => {
    const { container } = await showPage(address(), () =>
      json(200, viewBody({ status: 'approved' })),
    )

    expect(container.textContent).toContain('请关闭此页面，那台设备会继续。')
    expect(container.querySelector('.dialog-submit')).toBeNull()
  })

  it('treats a question the server does not have as gone, not as a page that failed', async () => {
    const { container } = await showPage(address(), () =>
      json(404, errorBody('session_not_found', 'No such sign-in.')),
    )

    expect(container.textContent).toContain(GONE)
    expect(container.querySelector('.dialog-submit')).toBeNull()
  })

  it('draws a 409 from the answer as the same expired state', async () => {
    // Somebody answered it in another tab, or the ten minutes ran out between reading and choosing.
    const { container } = await showPage(address(), () => json(200, viewBody()), () => empty(409))

    fireEvent.click(container.querySelector('.dialog-submit') as HTMLButtonElement)
    await flush()

    expect(container.textContent).toContain(GONE)
    expect(container.querySelector('.dialog-submit')).toBeNull()
  })

  it('says the link is incomplete when there is no ticket, and asks the server nothing', async () => {
    const { container, requests } = await showPage(address({ ticket: null }))

    expect(text(container.querySelector('.dialog-view'))).toBe(INCOMPLETE)
    expect(container.querySelector('#consentName')).toBeNull()
    expect(container.querySelector('.dialog-submit')).toBeNull()
    // A link with no ticket names no question, so there is nothing to ask about.
    expect(requests).toEqual([])
  })

  it('shows a question that could not be read as an error, not as gone', async () => {
    const { container } = await showPage(address(), () =>
      json(500, errorBody('internal_error', 'The sign-in could not be read.')),
    )

    expect(text(container.querySelector('.form-error'))).toBe('服务暂时不可用，请稍后重试。')
    expect(container.querySelector('.form-error')?.getAttribute('role')).toBe('alert')
    // The link may still be good; the person can reload rather than start over.
    expect(container.textContent).not.toContain(GONE)
    expect(container.textContent).toContain('刷新这个页面即可。')
    expect(container.querySelector('.dialog-submit')).toBeNull()
  })

  it('tells an unknown client and an unknown scope apart from the known ones', async () => {
    const { container } = await showPage(address(), () =>
      json(
        200,
        viewBody({ client: 'some-other-tool', scopes: ['skills:read', 'skills:teleport'] }),
      ),
    )

    // Shown as itself, with the warning — inventing a friendly name for a client nobody has heard of
    // would be the lie the warning exists to prevent.
    expect(text(container.querySelector('.consent-who strong'))).toBe('some-other-tool')
    expect(text(container.querySelector('.consent-who .hint'))).toBe(
      '（这个客户端不在已知名单里，请确认它确实是你安装的）',
    )
    // Visible, not merely present: `hidden` on the warning would pass a text-only assertion while
    // the person sees nothing.
    expect(container.querySelector('#consentUnknownClient')?.hasAttribute('hidden')).toBe(false)
    // And the scope with no wording is shown raw **and said to be undescribed**: a blank would read as
    // harmless, which is the one thing an unknown scope must not read as.
    const items = [...container.querySelectorAll('.consent-scopes li')]
    expect(items).toHaveLength(2)
    expect(text(items[0] ?? null)).toBe('读取你可见的全部 skill，包括未公开的')
    expect(text(items[1]?.querySelector('code') ?? null)).toBe('skills:teleport')
    expect(text(items[1]?.querySelector('.hint') ?? null)).toBe('（我们不知道这一项的含义，请谨慎同意）')
  })

  it('lists the two permissions in the design order whatever order the server sends', async () => {
    /* The ticket carries a *set*, so the array arrives in whatever order it serialised to — the
       local run came back write-first, and the page rendered the broader promise above the narrower
       one while the design lists read first. Found by putting the product's screenshot next to the
       design's (2026-11-14). */
    const { container } = await showPage(address(), () =>
      json(
        200,
        viewBody({ scopes: ['skills:write', 'skills:teleport', 'skills:read'] }),
      ),
    )

    const items = [...container.querySelectorAll('.consent-scopes li')]
    expect(text(items[0] ?? null)).toBe('读取你可见的全部 skill，包括未公开的')
    expect(text(items[1] ?? null)).toBe(
      '以你的身份提交新版本、管理草稿与共享；从未上线过的 skill 还可以删除',
    )
    // Nothing is dropped: a scope this page cannot describe still has to be shown, and shown last.
    expect(text(items[2]?.querySelector('code') ?? null)).toBe('skills:teleport')
  })

  /**
   * Who is granting. The screen shows the client and the scopes; without the account, a browser still
   * signed in as the previous person would silently issue the credential to them.
   */
  it('shows which account the credential will belong to, above the name', async () => {
    const { container } = await showPageAs(address(), ACCOUNT, () => json(200, viewBody()))

    const card = container.querySelector('.consent-account')
    expect(text(container.querySelector('#consentAccount'))).toBe(ACCOUNT.username)
    expect(text(card?.querySelector('.consent-account-hint'))).toBe('授权将发给这个账号。')
    expect(text(container.querySelector('#consentSwitch'))).toBe('不是我，换一个账号')

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
      () => json(200, viewBody()),
      () => new Response(null, { status: 204 }),
    )

    fireEvent.click(container.querySelector('#consentSwitch') as HTMLButtonElement)
    await flush()

    expect(postPaths(requests)).toEqual(['/web/logout'])
    // The whole address is the return trip: the ticket is the login, and losing it would land the
    // person on a screen that cannot work.
    expect(location.replace).toHaveBeenCalledWith(
      `/login?return_to=${encodeURIComponent(`/cli-sign-in${address()}`)}`,
    )
  })

  it('keeps the account and says why when the switch could not end the session', async () => {
    const { container } = await showPageAs(address(), ACCOUNT, () => json(200, viewBody()), () =>
      json(500, errorBody('internal_error', 'The session could not be ended.')),
    )

    fireEvent.click(container.querySelector('#consentSwitch') as HTMLButtonElement)
    await flush()

    expect(text(container.querySelector('.form-error'))).toBe('服务暂时不可用，请稍后重试。')
    expect(location.replace).not.toHaveBeenCalled()
    // Nothing changed, so the card must still name the same account.
    expect(text(container.querySelector('#consentAccount'))).toBe(ACCOUNT.username)
  })

  it('keeps the question on screen when the answer did not go through', async () => {
    const { container } = await showPage(address(), () => json(200, viewBody()), () =>
      json(500, errorBody('internal_error', 'The sign-in could not be answered.')),
    )

    fireEvent.click(container.querySelector('.dialog-submit') as HTMLButtonElement)
    await flush()

    expect(text(container.querySelector('.form-error'))).toBe('服务暂时不可用，请稍后重试。')
    expect(location.assign).not.toHaveBeenCalled()
    // And the button comes back, so a second attempt is possible.
    expect(container.querySelector('.dialog-submit')?.hasAttribute('disabled')).toBe(false)
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

    // The whole address, ticket included: that is the question, and losing it across the sign-in
    // would land the person on a screen that cannot work. `replace` rather than `assign`, so that
    // the page that refused them is not waiting behind the Back button.
    expect(location.replace).toHaveBeenCalledWith(
      `/login?return_to=${encodeURIComponent(`/cli-sign-in${address()}`)}`,
    )
  })
})
