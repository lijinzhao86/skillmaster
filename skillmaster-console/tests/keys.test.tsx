import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import AccountKeysPage from '../src/pages/AccountKeysPage'
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
 * 访问凭据 — the screen a key comes into existence on, and the only place one can.
 *
 * These are about the parts a person would notice going wrong: a count that disagrees with the list
 * under it, an answer to "are you sure" that cannot be taken back, a string shown once and then
 * shown again, and a form that sends a date the deployment did not ask for.
 *
 * The page is rendered directly rather than through `App`: the route is asserted there, and the
 * session gate has its own test. What is here is this page's behaviour.
 */

// `latest_day` comes from the server (ADR 0042 §决定 5): the bound is a fact about the deployment, and
// the form takes the day from here rather than working it out from this browser's clock.
const POLICY = { expires: true, lifetime_days: 90, latest_day: '2027-01-13' }

function key(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 'k1',
    name: '笔记本',
    note: '我自己的这台',
    scope: 'skills:read',
    expires_at: '2027-01-13T15:59:59Z',
    last_used_at: null,
    revoked_at: null,
    created_at: '2026-10-15T01:00:00Z',
    ...overrides,
  }
}

const LISTING = { keys: [key()], policy: POLICY }

let location = stubLocation('/account/keys')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/account/keys')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  // jsdom has no clipboard; the one test that provides it has to take it away again, or the next
  // test would silently exercise the other path.
  Reflect.deleteProperty(navigator, 'clipboard')
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function showPage(
  url = '/account/keys',
  // Async thunks included, as `stubFetch` allows: a response that has not arrived yet is how a test
  // reaches the window between a request going out and its answer coming back.
  ...responses: Array<() => Response | Promise<Response>>
): Promise<{ container: HTMLElement; requests: RecordedRequest[]; mock: unknown }> {
  const [path, query] = url.split('?')
  location = stubLocation(path ?? '/account/keys', query === undefined ? '' : `?${query}`)
  const stub = stubFetch(...responses)
  const { container } = render(<AccountKeysPage />)
  await flush()
  return { container, ...stub }
}

const text = (element: Element | null): string => (element?.textContent ?? '').trim()

/** Focus lands inside `requestAnimationFrame`, which jsdom runs on its own timer. */
function nextFrame(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 20))
}

function requested(
  requests: RecordedRequest[],
  path: string,
  method = 'GET',
): RecordedRequest | undefined {
  return requests.find((request) => request.path === path && request.method === method)
}

function posted(requests: RecordedRequest[], path: string): RecordedRequest[] {
  return requests.filter((request) => request.path === path && request.method === 'POST')
}

describe('the credential screen', () => {
  it('lists what the server sent, and counts each state', async () => {
    const { container } = await showPage('/account/keys', () =>
      json(200, {
        keys: [
          key(),
          key({ id: 'k2', name: 'CI', expires_at: '2026-10-20T15:59:59Z' }),
          key({ id: 'k3', name: '旧机', expires_at: '2026-09-30T15:59:59Z' }),
          key({ id: 'k4', name: '已撤', revoked_at: '2026-10-02T01:00:00Z' }),
        ],
        policy: POLICY,
      }),
    )

    // Default is 活跃 — the dead ones belong one click away rather than in the way — so the table
    // shows the one active key while the counts describe all four.
    expect(container.textContent).toContain('笔记本')
    expect(container.textContent).not.toContain('旧机')
    expect(container.textContent).toContain('1 个密钥')
  })

  it('says when each key was last used, in the four shapes the design draws', async () => {
    // The design's own key fixture, row by row (`skillmaster-控制台演示.html`): 今天 09:41 / 昨天 03:12 /
    // 2026/10/11 22:04 / 从未使用, plus 「刚刚」 from the other fixture. This column used to render an
    // absolute time on every row — a deviation from `design/voice-spec.md` §六 — and nothing pinned it:
    // the only fixture in this file has `last_used_at: null`, so reverting the word would have gone
    // green.
    //
    // The clock is fixed, in this file's own established way: 「今天」 and 「昨天」 are calendar days, so
    // a test that read the real clock would assert different strings at 03:00 and at noon.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-10-16T02:00:00Z')) // 10:00 on the 16th in Shanghai
    try {
      const { container } = await showPage('/account/keys', () =>
        json(200, {
          keys: [
            key({ id: 'k1', name: '刚用过', last_used_at: '2026-10-16T01:59:30Z' }),
            key({ id: 'k2', name: '今天用过', last_used_at: '2026-10-16T01:41:00Z' }),
            key({ id: 'k3', name: '昨天用过', last_used_at: '2026-10-14T19:12:00Z' }),
            key({ id: 'k4', name: '很久以前', last_used_at: '2026-10-11T14:04:00Z' }),
            key({ id: 'k5', name: '没动过', last_used_at: null }),
          ],
          policy: POLICY,
        }),
      )

      const said = [...container.querySelectorAll('.key-used-text')].map((node) =>
        text(node).replace(/\s+/g, ' '),
      )
      expect(said).toEqual([
        '最后使用 刚刚',
        '最后使用 今天 09:41',
        '最后使用 昨天 03:12',
        '最后使用 2026/10/11 22:04',
        '最后使用 从未使用',
      ])
    } finally {
      vi.useRealTimers()
    }
  })

  it('counts the list the reader is looking at, search included', async () => {
    const { container } = await showPage('/account/keys', () =>
      json(200, {
        keys: [key(), key({ id: 'k2', name: 'CI 流水线' })],
        policy: POLICY,
      }),
    )

    const counts = () => [...container.querySelectorAll('.stat-number')].map((node) => text(node))
    expect(counts()).toEqual(['2', '0', '0', '0'])

    fireEvent.change(container.querySelector('#key-query') as HTMLInputElement, {
      target: { value: 'CI' },
    })
    await flush()

    // The design's own rule: a card that says 3 and opens a list of 1 is the count arguing with its
    // own destination, so the counts follow the search box.
    expect(counts()).toEqual(['1', '0', '0', '0'])
  })

  it('keeps the search term in the address without reloading', async () => {
    const { container } = await showPage('/account/keys', () =>
      json(200, { keys: [key()], policy: POLICY }),
    )
    const replace = vi.spyOn(window.history, 'replaceState')

    fireEvent.change(container.querySelector('#key-query') as HTMLInputElement, {
      target: { value: '笔记' },
    })
    await flush()

    // **And 活跃 is not written**, because it is what the page shows with no parameter at all: the
    // design's own rule is that a default does not go into an address, and its 活跃 card links to
    // `?page=keys`. Written, one screen had two addresses (`/account/keys` and
    // `/account/keys?filter=active`) that a person could send to somebody and not recognise.
    expect(replace).toHaveBeenCalledWith(null, '', '/account/keys?q=%E7%AC%94%E8%AE%B0')
  })

  it('says which of the three empty lists this is', async () => {
    const none = await showPage('/account/keys', () => json(200, { keys: [], policy: POLICY }))
    expect(none.container.textContent).toContain('暂无密钥。CLI 与 agent 通过密钥访问你的 skill')

    const filtered = await showPage('/account/keys?filter=revoked', () =>
      json(200, { keys: [key()], policy: POLICY }),
    )
    expect(filtered.container.textContent).toContain('该状态下暂无密钥。')

    const unmatched = await showPage('/account/keys', () =>
      json(200, { keys: [key()], policy: POLICY }),
    )
    fireEvent.change(unmatched.container.querySelector('#key-query') as HTMLInputElement, {
      target: { value: '机器' },
    })
    await flush()
    // A sentence about the *search* rather than about the filter: the box has something in it and
    // the term matched nothing, which is not the same as "this state has none".
    expect(unmatched.container.textContent).toContain('没有名称包含「机器」的密钥。')
  })

  it('asks what a revocation costs, in the row, and lets Escape answer no', async () => {
    const { container } = await showPage('/account/keys', () => json(200, LISTING))

    fireEvent.click(container.querySelector('[data-key-action="revoke"]') as Element)
    expect(container.textContent).toContain('撤销后，使用该密钥的客户端将立即失去访问权限。')
    expect(text(container.querySelector('[data-key-confirm]'))).toBe('确认撤销')

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flush()
    await nextFrame()
    expect(container.textContent).not.toContain('撤销后，使用该密钥的客户端将立即失去访问权限。')
    // Focus is back on the button that was pressed rather than dropped at the top of the document.
    expect(document.activeElement).toBe(container.querySelector('[data-key-action="revoke"]'))
  })

  it('revokes by id and re-reads the list rather than editing the row', async () => {
    const { container, requests } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () => new Response(null, { status: 204 }),
      () => json(200, { keys: [key({ revoked_at: '2026-10-15T02:00:00Z' })], policy: POLICY }),
    )

    fireEvent.click(container.querySelector('[data-key-action="revoke"]') as Element)
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()

    const revoke = requested(requests, '/web/keys/k1/revoke', 'POST')
    expect(revoke?.headers['X-XSRF-TOKEN']).toBe('token-one')
    // The state of the row comes from the server's own answer to "when did it stop working", so the
    // page reads the list again instead of writing its own opinion into the row.
    expect(requests.filter((request) => request.path === '/web/keys')).toHaveLength(2)
  })

  it('keeps a copy that lands while the string is being revealed', async () => {
    const { container } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () => json(200, { key: key({ id: 'k9' }), token: 'skm_' + 'a'.repeat(43) }),
      () => json(200, { keys: [key()], policy: POLICY }),
    )

    fireEvent.click(container.querySelector('[data-key-action="rotate"]') as Element)
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()

    // **A clipboard write the test finishes by hand**, so the eye can be pressed while it is in
    // flight: the reveal replaces the moment with a copy, and identifying it by object used to drop
    // the copy — the way out still said 复制后方可完成 about a string already on the clipboard.
    let finish: () => void = () => undefined
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: () =>
          new Promise<void>((resolve) => {
            finish = () => {
              resolve()
            }
          }),
      },
    })
    fireEvent.click(container.querySelector('.secret [data-key-copy]') as Element)
    fireEvent.click(container.querySelector('.secret [aria-label="显示密钥"]') as Element)
    finish()
    await flush()

    const exit = container.querySelector('.key-exit button') as HTMLButtonElement
    expect(text(exit)).toBe('完成')
    expect(container.querySelector('#key-secret')?.classList.contains('is-masked')).toBe(false)
  })

  it('renewing shows the new string once, masked, with the way out gated on copying it', async () => {
    const { container, requests } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () => json(200, { key: key({ id: 'k9' }), token: 'skm_' + 'a'.repeat(43) }),
      () => json(200, { keys: [key()], policy: POLICY }),
    )

    fireEvent.click(container.querySelector('[data-key-action="rotate"]') as Element)
    expect(container.textContent).toContain('续期后，这把密钥立即失效，换上一把新的。')
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()

    expect(requested(requests, '/web/keys/k1/rotate', 'POST')).not.toBeUndefined()
    expect(container.textContent).toContain('密钥已续期')
    expect(text(container.querySelector('#key-secret'))).toBe('skm_' + 'a'.repeat(43))
    expect(
      container.querySelector('#key-secret')?.classList.contains('is-masked'),
    ).toBe(true)

    const exit = container.querySelector('.key-exit button') as HTMLButtonElement
    expect(exit.getAttribute('disabled')).not.toBeNull()
    expect(text(exit)).toBe('复制后方可完成')

    // jsdom has no clipboard at all, so the test provides the one thing it needs to exercise the
    // first of the three paths.
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: () => Promise.resolve() },
    })
    fireEvent.click(container.querySelector('.secret [data-key-copy]') as Element)
    await flush()
    expect(exit.getAttribute('disabled')).toBeNull()
    expect(text(exit)).toBe('完成')

    // And the listing it goes back to is re-read, because the renewal changed a row on it.
    fireEvent.click(exit)
    await flush()
    expect(requests.filter((request) => request.path === '/web/keys')).toHaveLength(2)
  })

  it('says it only selected the string when it could not copy it', async () => {
    // Neither API available: jsdom has no clipboard, and `execCommand` is not a function there. The
    // design's rule is that the button must not claim a copy that did not happen.
    const { container } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () => json(200, { key: key(), token: 'skm_x' }),
    )
    fireEvent.click(container.querySelector('[data-key-action="rotate"]') as Element)
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()

    fireEvent.click(container.querySelector('.secret [data-key-copy]') as Element)
    await flush()

    expect(container.textContent).toContain('已选中，请按你的复制快捷键')

    // **And the way out opens, because 选中 is the string being in the reader's hands** — the button
    // has said what it did and what to press, and holding the reader on this screen after that would
    // be the page asking for something it has no way to check. It stayed gated until 2026-10-15: the
    // design opens it (`done.disabled = false` on the same fallback), and the gate and the leave
    // question are one fact — see `held`.
    const exit = container.querySelector('.key-exit button') as HTMLButtonElement
    expect(exit.getAttribute('disabled')).toBeNull()
    expect(text(exit)).toBe('完成')

    // Which is also why leaving is not asked about any more.
    const crumb = [...container.querySelectorAll('.crumbs a')][1]!
    const replay = vi.fn()
    crumb.addEventListener('click', replay)
    fireEvent.click(crumb)
    expect(container.querySelector('.key-exit .link-button')).toBeNull()
    expect(replay).toHaveBeenCalledTimes(1)
  })

  it('asks before the crumb takes a string that is still only on this screen', async () => {
    // The exits the shell draws stay live on the moment screen on purpose — locking the page would
    // block the reader who wants to put the string somewhere else first — so the screen asks in the
    // block that was already its way out (design §那一刻, 2026-10-15).
    const { container } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () => json(200, { key: key(), token: 'skm_x' }),
    )
    fireEvent.click(container.querySelector('[data-key-action="rotate"]') as Element)
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()

    const crumb = [...container.querySelectorAll('.crumbs a')][1]!
    fireEvent.click(crumb)
    await nextFrame()

    // Still here, with the string: the click was held, not obeyed.
    expect(text(container.querySelector('#key-secret'))).toBe('skm_x')
    expect(text(container.querySelector('.key-exit .hint'))).toBe(
      '这把密钥还没复制——离开后取不回来，只能撤销后重建。',
    )
    expect([...container.querySelectorAll('.key-exit .link-button')].map((node) => text(node))).toEqual([
      '仍然离开',
      '留在这一屏',
    ])
    // The block stops drawing its own button while it is asking — one way out, doing one thing.
    expect(container.querySelector('.key-exit .button')).toBeNull()
    // And the answer that loses something is the one under the fingers.
    expect(document.activeElement?.textContent?.trim()).toBe('仍然离开')
  })

  it('hands the exit and the focus back when the answer is 留在这一屏', async () => {
    const { container } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () => json(200, { key: key(), token: 'skm_x' }),
    )
    fireEvent.click(container.querySelector('[data-key-action="rotate"]') as Element)
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()

    const crumb = [...container.querySelectorAll('.crumbs a')][1]!
    fireEvent.click(crumb)
    fireEvent.keyDown(container.querySelector('.key-exit') as Element, { key: 'Escape' })
    await nextFrame()

    expect(container.querySelector('.key-exit .link-button')).toBeNull()
    const exit = container.querySelector('.key-exit .button') as HTMLButtonElement
    expect(text(exit)).toBe('复制后方可完成')
    expect(exit.getAttribute('disabled')).not.toBeNull()
    // Back to what the reader pressed, not to the button this block drew: that one is disabled while
    // the string is uncopied, and a disabled control cannot take the focus at all.
    expect(document.activeElement).toBe(crumb)
  })

  it('re-runs the gesture that was pressed when the answer is 仍然离开', async () => {
    // **Answering does not do what the control would have done; it presses the control again.** One
    // writer per fact — which matters most for the sign-out button, whose address this page would
    // otherwise have to keep a second copy of.
    const { container } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () => json(200, { key: key(), token: 'skm_x' }),
    )
    fireEvent.click(container.querySelector('[data-key-action="rotate"]') as Element)
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()

    const crumb = [...container.querySelectorAll('.crumbs a')][1]!
    const replay = vi.fn()
    crumb.addEventListener('click', replay)

    fireEvent.click(crumb)
    // Held while the question is up: the reader's own click never reached anything.
    expect(replay).not.toHaveBeenCalled()

    fireEvent.click([...container.querySelectorAll('.key-exit .link-button')][0] as Element)
    expect(replay).toHaveBeenCalledTimes(1)
    expect(container.querySelector('.key-exit .link-button')).toBeNull()
  })

  it('stops showing a failure once the page has been read again', async () => {
    // **A banner is about the request that produced it.** One left by a row action that failed used to
    // survive the next successful read: the list came back correct and the page still said the request
    // had failed, about a screen it had just read successfully.
    const two = { keys: [key(), key({ id: 'k2', name: '家里的机器' })], policy: POLICY }
    const { container } = await showPage(
      '/account/keys',
      () => json(200, two),
      () => json(500, errorBody('internal_error', 'boom')),
      () => new Response(null, { status: 204 }),
      () => json(200, { keys: [key()], policy: POLICY }),
    )

    fireEvent.click([...container.querySelectorAll('[data-key-action="revoke"]')][0] as Element)
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()
    expect(container.querySelector('.banner')).not.toBeNull()

    // The second attempt works, and the page re-reads the list — which is what makes the first
    // banner stale.
    fireEvent.click([...container.querySelectorAll('[data-key-action="revoke"]')][1] as Element)
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()
    expect(container.querySelector('.banner')).toBeNull()
  })

  it('arms the question again when the answer did not actually leave', async () => {
    // **A replayed gesture can fail.** 仍然离开 presses the control the reader pressed, and the
    // sign-out button is the one that can come back with nothing done — the repo's own shell test
    // covers its 403 (a stale CSRF token) — leaving the reader standing here with the string. The
    // flag that stands the guard down is for that one gesture, not for the rest of this screen's
    // life, which is what it covered: every later exit would have left in silence.
    const { container } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () => json(200, { key: key(), token: 'skm_x' }),
    )
    fireEvent.click(container.querySelector('[data-key-action="rotate"]') as Element)
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()

    // The shell's own control, standing in for one whose handler answers and stays put.
    const stuck = document.createElement('button')
    stuck.setAttribute('data-sign-out', '')
    stuck.addEventListener('click', () => undefined)
    document.body.appendChild(stuck)

    stuck.click()
    await flush()
    expect(container.querySelector('.key-exit .link-button')).not.toBeNull()
    fireEvent.click([...container.querySelectorAll('.key-exit .link-button')][0] as Element)
    await flush()

    // Still here, because nothing navigated — so the next exit must be asked about as well.
    fireEvent.click([...container.querySelectorAll('.crumbs a')][1] as Element)
    expect(container.querySelector('.key-exit .link-button')).not.toBeNull()
    stuck.remove()
  })

  it('stops asking once the string has been copied', async () => {
    const { container } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () => json(200, { key: key(), token: 'skm_x' }),
    )
    fireEvent.click(container.querySelector('[data-key-action="rotate"]') as Element)
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()

    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: () => Promise.resolve() },
    })
    fireEvent.click(container.querySelector('.secret [data-key-copy]') as Element)
    await flush()

    const crumb = [...container.querySelectorAll('.crumbs a')][1]!
    const replay = vi.fn()
    crumb.addEventListener('click', replay)
    fireEvent.click(crumb)

    expect(container.querySelector('.key-exit .link-button')).toBeNull()
    expect(replay).toHaveBeenCalledTimes(1)
  })

  it('hides the reveal, and the mask with it', async () => {
    const { container } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () => json(200, { key: key(), token: 'skm_x' }),
    )
    fireEvent.click(container.querySelector('[data-key-action="rotate"]') as Element)
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()

    fireEvent.click(container.querySelector('.secret button[aria-label="显示密钥"]') as Element)

    expect(
      container.querySelector('#key-secret')?.classList.contains('is-masked'),
    ).toBe(false)
  })

  it('asks for write and lets the server fold in read', async () => {
    const { container, requests } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () => json(201, { key: key({ id: 'k7' }), token: 'skm_y' }),
    )

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: 'CI' },
    })
    fireEvent.change(container.querySelector('#key-note') as HTMLTextAreaElement, {
      target: { value: '流水线用' },
    })
    fireEvent.click(container.querySelector('input[value="write"]') as HTMLInputElement)
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    const created = posted(requests, '/web/keys')[0]
    // Only the intent: 「读写」 asks for write, and that write implies read is the server's rule. Two
    // places holding a rule is one place that will forget it.
    expect(created?.body).toMatchObject({ name: 'CI', note: '流水线用', scopes: ['skills:write'] })
    // And the date is the end of the chosen day here rather than midnight UTC, which would be eight
    // hours earlier than the picker said.
    expect(String((created?.body as { expires_at: string }).expires_at)).toMatch(/T15:59:59\.000Z$/)
    expect(String((created?.body as { expires_at: string }).expires_at)).toContain('2027-01-13')
  })

  it('does not ask for a date when the deployment issues keys that never expire', async () => {
    const { container, requests } = await showPage(
      '/account/keys',
      () => json(200, { keys: [], policy: { expires: false, lifetime_days: 90, latest_day: null } }),
      () => json(201, { key: key({ id: 'k8' }), token: 'skm_z' }),
    )

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    expect(container.querySelector('#key-expires')).toBeNull()

    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: '永久' },
    })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    // Null asks for the deployment's default, which is "never" on this one. A form that insisted on
    // a date would send one, and a date wins over the default — so it would undo the switch.
    expect((posted(requests, '/web/keys')[0]?.body as { expires_at: unknown }).expires_at).toBeNull()
  })

  it('drops the search as well as the filter when it leaves the moment', async () => {
    // **The one place on this page that lets go of the list's state** (design §访问凭据). The moment
    // has just created a fact, and either half of that state can hide it: the search term was typed
    // before the key existed. Landing on「0 个密钥」directly after「密钥已创建」is the page telling
    // somebody their key is not there.
    const { container, requests } = await showPage(
      '/account/keys?filter=active&q=%E7%AC%94%E8%AE%B0',
      () => json(200, { keys: [key()], policy: POLICY }),
      () => json(201, { key: key({ id: 'k9', name: 'CI' }), token: 'skm_y' }),
      () => json(200, { keys: [key(), key({ id: 'k9', name: 'CI' })], policy: POLICY }),
    )
    const replace = vi.spyOn(window.history, 'replaceState')

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: 'CI' },
    })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: () => Promise.resolve() },
    })
    fireEvent.click(container.querySelector('.secret [data-key-copy]') as Element)
    await flush()
    fireEvent.click(container.querySelector('.key-exit button') as Element)
    await flush()

    // The address says what the screen says: no filter, no search term.
    expect(replace).toHaveBeenCalledWith(null, '', '/account/keys?filter=all')
    // GETs only: creating one is a POST to the same path, and what is being counted is how many times
    // the list was read.
    const lists = requests.filter(
      (request) => request.path === '/web/keys' && request.method === 'GET',
    )
    expect(lists).toHaveLength(2)
    // And the list it landed on does contain the key it was just told about.
    expect(container.textContent).toContain('CI')
    expect(container.textContent).not.toContain('没有名称包含')
  })

  it('draws no state mark on an active key, and one on the keys that have something to say', async () => {
    // **An active key says nothing at all.** Most rows are active, so a mark on every one of them
    // carries no information; the two that speak are the two something has happened to, and
    // 「两周内到期」 joins them because it is the one thing the console has to say on its own — there
    // is no email to say it.
    const { container } = await showPage('/account/keys?filter=all', () =>
      json(200, {
        keys: [
          key({ id: 'k1', name: '活跃的' }),
          // Relative to now: a fixed date made this row change class (and this test fail) the day
          // it passed. Ten days is inside the console's two-week window.
          key({
            id: 'k2',
            name: '快到期',
            expires_at: new Date(Date.now() + 10 * 24 * 60 * 60 * 1000).toISOString(),
          }),
          key({ id: 'k3', name: '旧的', revoked_at: '2026-10-02T01:00:00Z' }),
        ],
        policy: POLICY,
      }),
    )

    const rows = [...container.querySelectorAll('.keys li')]
    const rowOf = (name: string) =>
      rows.find((row) => text(row.querySelector('.key-name')) === name)
    expect(rowOf('活跃的')?.querySelector('.chip') ?? null).toBeNull()
    expect(rowOf('快到期')?.querySelector('.chip')?.classList.contains('is-soon')).toBe(true)
    expect(text(rowOf('快到期')?.querySelector('.chip-text') ?? null)).toBe('两周内到期')
    expect(rowOf('旧的')?.querySelector('.chip')?.classList.contains('is-revoked')).toBe(true)
  })

  it('says what happened to the key rather than always when it expires', async () => {
    // A revoked key has a different date entirely, and the day it was revoked is the fact somebody
    // looking at the row needs — its expiry would be true and useless. The clock line is the slot
    // that carries it, which a product keeping revocation as a boolean cannot show at all.
    const { container } = await showPage('/account/keys?filter=all', () =>
      json(200, {
        keys: [
          key({ id: 'k1', name: '活跃的' }),
          key({ id: 'k3', name: '旧的', revoked_at: '2026-10-02T01:00:00Z' }),
        ],
        policy: POLICY,
      }),
    )

    const rows = [...container.querySelectorAll('.keys li')]
    expect(text(rows[0]?.querySelector('.key-date') ?? null)).toBe('到期 2027/01/13')
    expect(text(rows[1]?.querySelector('.key-date') ?? null)).toBe('撤销于 2026/10/02')
  })

  it('says a note is missing in words rather than leaving the cell blank', async () => {
    // A blank cell reads like a rendering failure. The reference product's own answer is a grey
    // sentence saying so, and the grey is what separates "nothing was written here" from "there is
    // nothing here to write".
    const { container } = await showPage('/account/keys', () =>
      json(200, { keys: [key({ note: null })], policy: POLICY }),
    )

    const note = container.querySelector('.key-note')
    expect(text(note)).toBe('未填写说明')
    expect(note?.classList.contains('is-empty')).toBe(true)
  })

  it('says a key never expires instead of leaving the expiry cell empty', async () => {
    // A deployment can turn expiry off, and then the server answers `expires_at: null`. `formatDay`
    // returns an empty string for null, so the row read「到期 」with nothing after it — which looks
    // like a broken cell rather than like a choice somebody made.
    const { container } = await showPage('/account/keys', () =>
      json(200, {
        keys: [key({ expires_at: null })],
        policy: { expires: false, lifetime_days: 90, latest_day: null },
      }),
    )
    expect(text(container.querySelector('.key-date'))).toBe('永不过期')
    // **The absence of the broken cell, asked of the cells rather than of the page's text.** The old
    // form was `not.toContain('到期 \n')`, which is a claim about how the template's whitespace
    // happens to fall — reformat the markup and the assertion stops testing anything at all.
    const cells = [...container.querySelectorAll('.key-date, .key-used-text, .chip-text')]
    expect(cells.some((cell) => /^到期\s*$/.test(text(cell)))).toBe(false)

    // The moment screen says it the same way, from the same one place.
    const second = await showPage(
      '/account/keys',
      () =>
        json(200, {
          keys: [key({ expires_at: null })],
          policy: { expires: false, lifetime_days: 90, latest_day: null },
        }),
      () => json(201, { key: key({ id: 'k9', name: '永久', expires_at: null }), token: 'skm_y' }),
    )
    fireEvent.click(second.container.querySelector('[data-key-new]') as Element)
    fireEvent.change(second.container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: '永久' },
    })
    fireEvent.submit(second.container.querySelector('form') as HTMLFormElement)
    await flush()
    expect(second.container.querySelector('.key-body')?.textContent).toContain('永不过期')
  })

  it('says why a renewal was refused, and re-reads the row it was refused about', async () => {
    // Two tabs, key revoked in one of them. The refusal names the key (`field: id`, `issue: revoked`)
    // and that sentence was written and never reached the screen: the banner's generic「提交的内容
    // 有问题，请检查后重试」invites a retry that can only fail the same way.
    const { container, requests } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () =>
        json(
          400,
          errorBody('invalid_request', 'the request was refused', [{ field: 'id', issue: 'revoked' }]),
        ),
      () => json(200, { keys: [key({ revoked_at: '2026-10-15T02:00:00Z' })], policy: POLICY }),
    )

    fireEvent.click(container.querySelector('[data-key-action="rotate"]') as Element)
    fireEvent.click(container.querySelector('[data-key-confirm]') as Element)
    await flush()

    expect(container.textContent).toContain('这把密钥已经被撤销，不能再续期')
    // And the list is read again, because what the refusal says is that this page is holding a row
    // that no longer exists.
    expect(requests.filter((request) => request.path === '/web/keys')).toHaveLength(2)
  })

  it('shows what the server said about the permissions', async () => {
    // The sentence for `empty` existed with no way of reaching the screen: the page read the field
    // names it knew about, and the permission group's own name was not among them.
    const { container } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () =>
        json(
          400,
          errorBody('invalid_scope', 'the request was refused', [{ field: 'scopes', issue: 'empty' }]),
        ),
    )

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: 'CI' },
    })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    expect(container.textContent).toContain('至少要选一项权限')
  })

  it('reads the picker day in the zone the date it sends is pinned to', async () => {
    // The chosen day means "the end of that day in Shanghai" (`expiresAt` sends `+08:00`), so the day
    // the picker offers as its earliest has to be Shanghai's — from a browser whose clock is elsewhere
    // the two differ, and the day the picker offered as legal was already over when it was picked.
    vi.stubEnv('TZ', 'UTC')
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-10-15T20:00:00Z')) // 04:00 on the 16th in Shanghai

    const { container } = await showPage('/account/keys', () => json(200, { keys: [], policy: POLICY }))
    // The picker is on the form, and the form is one of the three screens this page carries.
    fireEvent.click(container.querySelector('[data-key-new]') as Element)

    expect(container.querySelector('#key-expires')?.getAttribute('min')).toBe('2026-10-16')
    // The other end is **the server's day, taken verbatim**: the picker offers no date this deployment
    // would refuse, and it does not depend on this browser's clock agreeing with the server's (ADR
    // 0042 §决定 5). The fixture's `latest_day` is deliberately not what a local computation would
    // produce from the frozen clock — if this page ever went back to computing it, this assertion is
    // where that shows up.
    expect(container.querySelector('#key-expires')?.getAttribute('max')).toBe('2027-01-13')
    expect((container.querySelector('#key-expires') as HTMLInputElement).value).toBe('2027-01-13')

    vi.useRealTimers()
    vi.unstubAllEnvs()
  })

  it("sends the breadcrumb where that screen's own exit goes", async () => {
    // Design §访问凭据: 「面包屑和这个按钮去同一处」. A screen with two ways out that land on two
    // different lists is worse than either of them — the reader has to remember which one they used.
    const list = await showPage('/account/keys?filter=revoked&q=%E6%97%A7', () =>
      json(200, { keys: [key({ revoked_at: '2026-10-02T01:00:00Z' })], policy: POLICY }),
    )
    fireEvent.click(list.container.querySelector('[data-key-new]') as Element)
    // Three segments, in the design's own pair of answers: the area, the page — a link *back*, since
    // the trail's last segment is the action — and then the action. The page's link keeps the list's
    // state, because that is where 取消 goes too. And the first segment is the design's `设置`, which
    // is also what the sidebar files this page under.
    expect(
      [...list.container.querySelectorAll('.crumbs a')].map((node) => node.getAttribute('href')),
    ).toEqual(['/account', '/account/keys?filter=revoked&q=%E6%97%A7'])
    expect(text(list.container.querySelector('.crumbs [aria-current="page"]'))).toBe('新建 API 密钥')

    const moment = await showPage(
      '/account/keys',
      () => json(200, { keys: [key()], policy: POLICY }),
      () => json(201, { key: key({ id: 'k9' }), token: 'skm_y' }),
    )
    fireEvent.click(moment.container.querySelector('[data-key-new]') as Element)
    fireEvent.change(moment.container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: 'CI' },
    })
    fireEvent.submit(moment.container.querySelector('form') as HTMLFormElement)
    await flush()
    // And the moment's exit is unfiltered, like 完成: the key it just made has to be visible.
    expect(
      [...moment.container.querySelectorAll('.crumbs a')].map((node) => node.getAttribute('href')),
    ).toEqual(['/account', '/account/keys?filter=all'])
  })

  it("starts a new form empty, and takes the last attempt's message with it", async () => {
    const { container } = await showPage(
      '/account/keys',
      () => json(200, { keys: [], policy: POLICY }),
      () =>
        json(
          400,
          errorBody('invalid_request', 'the request was refused', [{ field: 'name', issue: 'too_long' }]),
        ),
      () =>
        json(
          400,
          errorBody('invalid_request', 'the request was refused', [{ field: 'role', issue: 'invalid' }]),
        ),
    )

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: '上一个' },
    })
    fireEvent.change(container.querySelector('#key-note') as HTMLTextAreaElement, {
      target: { value: '上一次的说明' },
    })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()
    expect(container.textContent).toContain('长度超出限制')

    // A refusal the form has no field for goes to the banner instead, which is the message that
    // would follow the reader back to the list.
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: '再试' },
    })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()
    expect(container.querySelector('.banner')).not.toBeNull()

    // 取消 leaves both messages behind rather than carrying them into the list.
    fireEvent.click(container.querySelector('.key-form-foot .button.is-quiet') as Element)
    await flush()
    expect(container.textContent).not.toContain('长度超出限制')
    expect(container.querySelector('.banner')).toBeNull()

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    expect((container.querySelector('#key-name') as HTMLInputElement).value).toBe('')
    expect((container.querySelector('#key-note') as HTMLTextAreaElement).value).toBe('')
  })

  it('says what the ceiling is when the date is beyond it', async () => {
    // The deployment's lifetime is a ceiling (ADR 0042), and the server answers `beyond_maximum`. A
    // date input's `max` does not stop typing, so this refusal has to have words of its own — without
    // them the field message is the envelope's own English, which is written for a log.
    const { container } = await showPage(
      '/account/keys',
      () => json(200, { keys: [], policy: POLICY }),
      () =>
        json(
          400,
          errorBody('invalid_expiry', 'the request was refused', [
            { field: 'expires_at', issue: 'beyond_maximum' },
          ]),
        ),
    )

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: 'CI' },
    })
    fireEvent.change(container.querySelector('#key-expires') as HTMLInputElement, {
      target: { value: '2099-01-01' },
    })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    expect(container.textContent).toContain('超过这个部署允许的最长有效期')
  })

  it('closes its own exit while the creation is in flight', async () => {
    // **取消 cannot mean "cancel" once the request is out.** The POST is gone; a button that still
    // said 取消 would take the person back to a list where the key may already exist, having told
    // them nothing — which is the same shape of lie as a row action left live while it is working.
    // (The breadcrumb is a real link, so it navigates: the page is torn down and nothing of this form
    // survives to be confused by.)
    let release: (() => void) | undefined
    const slow = new Promise<Response>((resolve) => {
      release = () =>
        resolve(
          json(
            400,
            errorBody('invalid_request', 'the request was refused', [{ field: 'name', issue: 'too_long' }]),
          ),
        )
    })
    const { container } = await showPage(
      '/account/keys',
      () => json(200, { keys: [], policy: POLICY }),
      () => slow,
    )

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: 'CI' },
    })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    const exit = container.querySelector('.key-form-foot .button.is-quiet') as HTMLButtonElement
    expect(exit.getAttribute('disabled')).not.toBeNull()

    // And the answer still has somewhere to land once it arrives, because the person is still here.
    release?.()
    await flush()
    expect(exit.getAttribute('disabled')).toBeNull()
    expect(container.textContent).toContain('长度超出限制')
  })

  it('stays usable when the listing carries no policy at all', async () => {
    // **The one read of `policy` that threw.** A response without it — an older server, or a bundle
    // from this build against one — failed inside `load()`, and *after* `state` had been set to
    // `ready`: the list rendered and looked right, the exception went to the console and nowhere else,
    // and everything `onMounted` still had to do never happened. What is observable from outside is
    // that last part — the Escape key is bound after `load()` — so that is what this asserts.
    const { container } = await showPage('/account/keys', () => json(200, { keys: [key()] }))
    expect(container.textContent).toContain('笔记本')

    fireEvent.click(container.querySelector('[data-key-action="revoke"]') as Element)
    expect(container.textContent).toContain('撤销后，使用该密钥的客户端将立即失去访问权限。')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flush()
    expect(container.textContent).not.toContain('撤销后，使用该密钥的客户端将立即失去访问权限。')

    // And the form is still reachable — and shows no date field at all, which is the same thing the
    // page does for a deployment that turned expiry off: with no policy there is nothing to say what
    // the ceiling is, and a date invented here is a date the server may refuse.
    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    expect(container.querySelector('#key-name')).not.toBeNull()
    expect(container.querySelector('#key-expires')).toBeNull()
  })

  it('offers a day from the lifetime when the policy has no latest_day', async () => {
    // `latest_day` is how the server publishes its ceiling (ADR 0042 §决定 5), and a policy without it
    // is a server this build is older than. The fallback is the arithmetic the server would have done
    // — and the alternative is worse than a day of slack: no preselection at all, under a hint that
    // says one was made.
    vi.stubEnv('TZ', 'UTC')
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-10-15T20:00:00Z')) // 04:00 on the 16th in Shanghai

    const { container } = await showPage('/account/keys', () =>
      json(200, { keys: [], policy: { expires: true, lifetime_days: 3 } }),
    )
    fireEvent.click(container.querySelector('[data-key-new]') as Element)

    // The Shanghai day of the frozen clock is the 16th, and three days past it is the 19th.
    expect(container.querySelector('#key-expires')?.getAttribute('max')).toBe('2026-10-19')
    expect((container.querySelector('#key-expires') as HTMLInputElement).value).toBe('2026-10-19')
    // Same source as the bound: a form whose preselection its own picker would refuse is the failure
    // this pairing exists to prevent.
    expect(container.textContent).toContain('已按这个部署的上限预选 3 天')

    vi.useRealTimers()
    vi.unstubAllEnvs()
  })

  it('re-reads the picker floor when the form opens on a later day', async () => {
    // The floor is a Shanghai day read from the clock, and a page left open across midnight would
    // otherwise keep offering the day that has just ended — a date the server refuses, which the
    // person learns only after filling the form in. It is refreshed where the rest of this page's
    // clock is: on a load, and every time the form opens.
    vi.stubEnv('TZ', 'UTC')
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-10-15T20:00:00Z')) // 04:00 on the 16th in Shanghai

    const { container } = await showPage('/account/keys', () => json(200, { keys: [], policy: POLICY }))
    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    expect(container.querySelector('#key-expires')?.getAttribute('min')).toBe('2026-10-16')
    fireEvent.click(container.querySelector('.key-form-foot .button.is-quiet') as Element)

    // Past Shanghai's midnight, with the page still open and nothing reloaded in between.
    vi.setSystemTime(new Date('2026-10-16T16:30:00Z')) // 00:30 on the 17th
    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    expect(container.querySelector('#key-expires')?.getAttribute('min')).toBe('2026-10-17')

    vi.useRealTimers()
    vi.unstubAllEnvs()
  })

  it('creates one key when the form is submitted twice', async () => {
    // **The second submission would throw the first one's string away.** Two keys, one screen: the
    // moment shows the later one and the earlier one's only appearance is gone for good. The disabled
    // button does not cover this — an implicit submission (Enter, a script, an extension) never
    // consults it — so the guard is in `submit` and this is what it is for.
    let release: (() => void) | undefined
    const slow = new Promise<Response>((resolve) => {
      release = () => resolve(json(201, { key: key({ id: 'k9' }), token: 'skm_y' }))
    })
    const { container, requests } = await showPage(
      '/account/keys',
      () => json(200, { keys: [], policy: POLICY }),
      () => slow,
    )

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: 'CI' },
    })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    expect(posted(requests, '/web/keys')).toHaveLength(1)
    release?.()
    await flush()
    expect(container.textContent).toContain('skm_y')
  })

  it('refuses the breadcrumb while the creation is in flight', async () => {
    // **The breadcrumb is a way out, so it follows 取消's rule.** It used to be the one exit that
    // stayed live during a submission, and on this screen that is the expensive one: leaving is a real
    // navigation, the page is torn down, and the string the answer carried has no second appearance.
    // Asserted on the event rather than on a navigation jsdom would not perform anyway.
    let release: (() => void) | undefined
    const slow = new Promise<Response>((resolve) => {
      // A refusal rather than a creation, so the form is still on the screen when the answer lands —
      // which is what makes "the way out opens again" something this test can observe.
      release = () =>
        resolve(
          json(
            400,
            errorBody('invalid_request', 'the request was refused', [{ field: 'name', issue: 'too_long' }]),
          ),
        )
    })
    const { container } = await showPage(
      '/account/keys',
      () => json(200, { keys: [], policy: POLICY }),
      () => slow,
    )

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: 'CI' },
    })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    // The trail's middle segment: this page's own way back to the list, and the only crumb that is
    // guarded (`guardCrumb`). The first one goes to 设置, which nothing about a submission closes.
    const crumb = [...container.querySelectorAll('.crumbs a')][1]!
    expect(crumb.getAttribute('class')).toContain('is-blocked')

    const blocked = new MouseEvent('click', { bubbles: true, cancelable: true })
    crumb.dispatchEvent(blocked)
    expect(blocked.defaultPrevented).toBe(true)

    // And it opens again once the answer has landed: the rule is about the moment, not the screen.
    release?.()
    await flush()
    expect(crumb.getAttribute('class') ?? '').not.toContain('is-blocked')

    const later = new MouseEvent('click', { bubbles: true, cancelable: true })
    crumb.dispatchEvent(later)
    expect(later.defaultPrevented).toBe(false)
  })

  it('says what the server refused, on the field it named', async () => {
    const { container } = await showPage(
      '/account/keys',
      () => json(200, LISTING),
      () =>
        json(
          400,
          errorBody('invalid_expiry', 'the request was refused', [
            { field: 'expires_at', issue: 'not_in_the_future' },
          ]),
        ),
    )

    fireEvent.click(container.querySelector('[data-key-new]') as Element)
    fireEvent.change(container.querySelector('#key-name') as HTMLInputElement, {
      target: { value: 'CI' },
    })
    fireEvent.submit(container.querySelector('form') as HTMLFormElement)
    await flush()

    expect(container.textContent).toContain('到期时间要在将来')
  })
})
