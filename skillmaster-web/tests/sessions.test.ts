import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type DOMWrapper } from '@vue/test-utils'
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
 * 登录设备 — the screen where a login somebody else's machine is holding can be ended (ADR 0043).
 *
 * These are about the parts a person would notice going wrong: a row that offers to end the login
 * they are reading the page with, a question whose two answers are not where the button was, an
 * 「取消」 that does not put things back, and a count that disagrees with the list under it.
 *
 * The page is mounted directly rather than through `App.vue`: the route is asserted there, and the
 * shell's navigation has its own test.
 */

function device(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    kind: 'browser',
    device_id: 'd1',
    device: 'Chrome · macOS',
    address: '203.0.113.24',
    logged_in_at: '2026-10-14T01:12:00Z',
    last_seen_at: '2026-10-16T02:00:00Z',
    current: false,
    ...overrides,
  }
}

/** A CLI login — the other kind of row this list holds since v4 (Q6). */
function cliLogin(overrides: Partial<Record<string, unknown>> = {}) {
  return device({
    kind: 'cli',
    device_id: 'a1',
    // What the server builds for this kind: `CLI · ` and the name the person typed.
    device: 'CLI · 我的 MacBook',
    address: '203.0.113.24',
    ...overrides,
  })
}

const MINE = device({ device_id: 'me', current: true, address: '198.51.100.7', device: 'Safari · iPhone' })

let location = stubLocation('/account/sessions')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/account/sessions')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function showPage(...responses: Array<() => Response | Promise<Response>>) {
  vi.resetModules()
  const { default: AccountSessionsPage } = await import('../src/pages/AccountSessionsPage.vue')
  const stub = stubFetch(...responses)
  const wrapper = mount(AccountSessionsPage, { attachTo: document.body })
  await flush()
  return { wrapper, ...stub }
}

/**
 * A row, addressed the way the page addresses it.
 *
 * **Not by looking for the row that does not say 「当前设备」**, which is what this helper did first
 * and why three tests failed: the consequence text on the row being questioned ends in 「当前设备不受
 * 影响」, so the moment a question opened, the row the test was asking about stopped being findable.
 * The attribute is the identity the design gives a row for exactly this reason.
 */
function rowFor(wrapper: ReturnType<typeof mount>, deviceId: string): DOMWrapper<Element> {
  return wrapper.find(`li.session[data-session="${deviceId}"]`)
}

function postPaths(requests: RecordedRequest[]): string[] {
  return requests.filter((request) => request.method === 'POST').map((request) => request.path)
}

describe('the login devices screen', () => {
  it('shows where each login came from, what it is, and the two times, on two lines', async () => {
    // The clock is fixed here for the same reason `time.test.ts` fixes it: the second line says
    // 「刚刚」 or 「今天 HH:MM」 depending on when the suite runs.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-10-16T02:00:00Z')) // 10:00 on the 16th in Shanghai
    try {
      const { wrapper } = await showPage(() =>
        json(200, {
          sessions: [
            MINE,
            // The other device is older and was last used yesterday, which is the shape the design's
            // own second row draws (「最近活动 昨天 18:20」) — two rows saying 「刚刚」 would pin the
            // words without pinning the distinction between them.
            device({ logged_in_at: '2026-10-12T21:04:00Z', last_seen_at: '2026-10-15T10:00:00Z' }),
            // The design's own CLI row, value for value: signed in at 08:41 and active 「刚刚」
            // (`design/skillmaster-控制台演示.html`, the `s2` fixture).
            cliLogin({
              logged_in_at: '2026-10-16T00:41:00Z',
              last_seen_at: '2026-10-16T01:59:30Z',
            }),
          ],
        }),
      )

      // **Two lines, and the second one is a sentence about three things** (2026-10-16): the upper
      // line is the full address, and 什么设备 / 登录于 / 最近活动 share the line under it. Each is
      // read off the element that holds it rather than out of the page's whole text — asserting the
      // page merely *contains* these strings would pass with any two of them swapped between rows.
      const mine = rowFor(wrapper, 'me')
      expect(mine.find('.session-ip').text()).toBe('198.51.100.7')
      // The design's own separator between the three segments (「　·　」 — U+3000, U+00B7, U+3000),
      // byte for byte, and a different one from the half-width `·` a compound label uses
      // (`Chrome · macOS` glues two words into one name).
      expect(mine.find('.session-meta').text()).toBe(
        'Safari · iPhone　·　登录于 2026/10/14 09:12　·　最近活动 刚刚',
      )

      const theirs = rowFor(wrapper, 'd1')
      expect(theirs.find('.session-ip').text()).toBe('203.0.113.24')
      // 登录于 is a date and 最近活动 is the same moment said the way a person says it — the one place
      // the two halves of this line differ (`voice-spec.md` §六 gives the words).
      expect(theirs.find('.session-meta').text()).toBe(
        'Chrome · macOS　·　登录于 2026/10/13 05:04　·　最近活动 昨天 18:00',
      )

      // **The other kind of login is a row like any of them**, differing in one segment: the CLI
      // login's own second line opens with the kind of device, because it has no User-Agent to
      // describe it (v4 Q6). No mark says which kind a row is — this screen's rule is that an active
      // row says nothing about itself.
      const cli = rowFor(wrapper, 'a1')
      expect(cli.find('.session-meta').text()).toBe(
        'CLI · 我的 MacBook　·　登录于 2026/10/16 08:41　·　最近活动 刚刚',
      )
      expect(cli.find('.session-ip').text()).toBe('203.0.113.24')
      expect(cli.text()).not.toContain('当前设备')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('marks the device reading the page and offers it no way to end itself', async () => {
    const { wrapper } = await showPage(() => json(200, { sessions: [MINE, device()] }))

    const rows = wrapper.findAll('li.session')
    expect(rows).toHaveLength(2)

    const mine = rowFor(wrapper, 'me')
    expect(mine.text()).toContain('198.51.100.7')
    // Removed rather than disabled: that button would end the login of whoever is reading the page.
    expect(mine.find('[data-act="kick"]').exists()).toBe(false)

    const theirs = rowFor(wrapper, 'd1')
    expect(theirs.find('[data-act="kick"]').exists()).toBe(true)
    expect(theirs.text()).not.toContain('当前设备')
    wrapper.unmount()
  })

  it('tells a list that would not load once, in the design sentence, and not in a banner too', async () => {
    const { wrapper } = await showPage(() =>
      json(500, errorBody('internal_error', 'The list could not be read.')),
    )

    // The design's own `sessions-failed.png` is one line under the heading — 「读不到时是一句话，而且
    // 只关于这个列表」 (design/README.md §登录设备). A banner as well would be a second sentence about
    // the same failure, and it would be the generic one rather than the one written for this list.
    expect(wrapper.find('.hint').text()).toBe(
      '无法加载登录设备列表，请稍后重试。此处仅表示列表加载失败，不代表账号异常。',
    )
    expect(wrapper.find('.banner').exists()).toBe(false)
    // And nothing of the list itself: no count, no rows, no note.
    expect(wrapper.find('.key-card').exists()).toBe(false)
    expect(wrapper.find('.page-note').exists()).toBe(false)
    wrapper.unmount()
  })

  it('still says it once when a failed write is followed by a failed reload', async () => {
    // The path that made the rule a rule rather than a coincidence: ending a device fails (the
    // network), the page re-reads the list to make itself true again, and that fails too. Both are
    // about the same outage, and the design's failed state is one sentence — so the banner the write
    // set must not stand over it.
    const { wrapper, requests } = await showPage(
      () => json(200, { sessions: [MINE, device()] }),
      () => {
        throw new TypeError('Failed to fetch')
      },
      () => {
        throw new TypeError('Failed to fetch')
      },
    )

    await wrapper.find('li.session[data-session="d1"] [data-act="kick"]').trigger('click')
    await flush()
    await wrapper.find('li.session[data-session="d1"] [data-device-confirm]').trigger('click')
    await flush()

    expect(requests.filter((one) => one.method === 'POST')).toHaveLength(1)
    expect(wrapper.findAll('.page > .banner')).toHaveLength(0)
    const said = wrapper.findAll('.page .hint, .page > .banner')
    expect(said).toHaveLength(1)
    expect(said[0]!.text()).toBe(
      '无法加载登录设备列表，请稍后重试。此处仅表示列表加载失败，不代表账号异常。',
    )
    wrapper.unmount()
  })

  it('stops saying a request failed once the list has come back', async () => {
    // A banner is about the request that failed, and it used to be about that request for ever: the
    // list came back correct, a later revoke succeeded, and the page still said something had not
    // gone through — with no way to dismiss it and nothing on screen that was stale.
    const failed = json(500, errorBody('internal_error', 'The device could not be ended.'))
    const { wrapper } = await showPage(
      () => json(200, { sessions: [MINE, device()] }),
      () => failed.clone(),
      () => json(200, { sessions: [MINE, device()] }),
      () => empty(204),
      () => json(200, { sessions: [MINE] }),
    )

    await rowFor(wrapper, 'd1').find('[data-act="kick"]').trigger('click')
    await flush()
    await rowFor(wrapper, 'd1').find('[data-device-confirm]').trigger('click')
    await flush()

    // The failure is reported, and the sentence is set after the re-read so that the re-read's own
    // success (which clears the banner) cannot swallow it.
    expect(wrapper.find('.banner').text()).toContain('服务暂时不可用，请稍后重试。')
    const afterFailure = rowFor(wrapper, 'd1')
    expect(afterFailure.find('[data-act="kick"]').exists()).toBe(true)
    expect(afterFailure.find('[data-device-confirm]').exists()).toBe(false)

    // And the next request that does go through takes it away.
    await afterFailure.find('[data-act="kick"]').trigger('click')
    await flush()
    await rowFor(wrapper, 'd1').find('[data-device-confirm]').trigger('click')
    await flush()

    expect(wrapper.find('.banner').exists()).toBe(false)
    expect(wrapper.find('li.session[data-session="d1"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('asks before ending every other device, and Escape puts it all back', async () => {
    // The head cell's own question: the count it replaces is the number of rows offering a button, so
    // the sentence and the label carry the same figure as the list under them.
    const { wrapper, requests } = await showPage(() =>
      json(200, { sessions: [MINE, device(), device({ device_id: 'd2' })] }),
    )

    await wrapper.find('[data-others]').trigger('click')
    await flush()

    const head = wrapper.find('.key-head')
    expect(head.find('.key-count').text()).toBe('退出后，其他 2 台设备都需要重新登录；当前设备不受影响。')
    expect(head.find('[data-others-confirm]').text()).toBe('确认退出 2 台')
    expect(head.find('[data-others]').exists()).toBe(false)
    // The answer that ends things is where the button was, and under the fingers.
    expect(document.activeElement).toBe(head.find('[data-others-confirm]').element)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flush()

    // Back to the count and the button, and nothing was sent: 「取消」 has to be a real answer, not a
    // question that closed while the request went out anyway.
    expect(wrapper.find('.key-count').text()).toBe('3 台设备')
    expect(wrapper.find('[data-others]').exists()).toBe(true)
    expect(document.activeElement).toBe(wrapper.find('[data-others]').element)
    expect(postPaths(requests)).toEqual([])
    wrapper.unmount()
  })

  it('will not let Escape answer 取消 after the request has gone out', async () => {
    // A window the design does not have (`askBefore` is synchronous) and this port does. By mouse it
    // is unreachable — both answers are `disabled` — but Escape used to close the question anyway,
    // telling the reader nothing had happened while the device was being ended.
    let release: ((response: Response) => void) | undefined
    const inFlight = new Promise<Response>((resolve) => {
      release = resolve
    })
    const { wrapper, requests } = await showPage(
      () => json(200, { sessions: [MINE, device(), device({ device_id: 'd2' })] }),
      () => inFlight,
      () => json(200, { sessions: [MINE] }),
    )

    await rowFor(wrapper, 'd1').find('[data-act="kick"]').trigger('click')
    await flush()
    await rowFor(wrapper, 'd1').find('[data-device-confirm]').trigger('click')
    await flush()

    expect(rowFor(wrapper, 'd1').find('[data-device-confirm]').exists()).toBe(true)
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flush()

    // Still asked: there is nothing left to cancel once the request is out.
    expect(rowFor(wrapper, 'd1').find('[data-device-confirm]').exists()).toBe(true)
    expect(postPaths(requests)).toEqual(['/web/sessions/d1/revoke'])
    // **And the buttons that would replace this question are disabled too.** They used to be live,
    // so pressing another row's 「退出该设备」 opened a second question whose own answers were disabled
    // and whose Escape was ignored — a question nobody could answer or dismiss until the first
    // request landed. Asserted as `disabled` on both shapes: a row's and the head's.
    expect(rowFor(wrapper, 'd2').find('[data-act="kick"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-others]').attributes('disabled')).toBeDefined()

    release?.(empty(204))
    await flush()
    // And when it lands, the row is gone — the answer that was given, not a cancelled one.
    expect(wrapper.find('li.session[data-session="d1"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('counts in a noun phrase, and says the half the count did not', async () => {
    const alone = await showPage(() => json(200, { sessions: [MINE] }))

    expect(alone.wrapper.text()).toContain('仅当前设备')
    expect(alone.wrapper.text()).toContain('在别处登录过的设备会出现在这里。')
    // Nothing to end, so nothing offering to end it: a button that did nothing would be worse than
    // no button, and the design leaves it out rather than disabling it.
    expect(alone.wrapper.find('[data-others]').exists()).toBe(false)
    alone.wrapper.unmount()

    const three = await showPage(() => json(200, { sessions: [MINE, device(), device({ device_id: 'd2' })] }))

    expect(three.wrapper.text()).toContain('3 台设备')
    // The list's own sentence is gone once there is more than one row to look at.
    expect(three.wrapper.text()).not.toContain('在别处登录过的设备会出现在这里。')
    three.wrapper.unmount()
  })

  it('asks before ending a device, in the row it is about', async () => {
    const { wrapper } = await showPage(() => json(200, { sessions: [MINE, device()] }))

    await rowFor(wrapper, 'd1').find('[data-act="kick"]').trigger('click')
    await flush()

    const asked = rowFor(wrapper, 'd1')
    // The consequence takes the row's own line, and the two answers take the cell the button was in.
    expect(asked.text()).toContain('退出后，该设备需要重新登录；当前设备不受影响。')
    expect(asked.text()).not.toContain('最近活动')
    expect(asked.find('[data-device-confirm]').exists()).toBe(true)
    expect(asked.find('[data-act="kick"]').exists()).toBe(false)
    expect(asked.text()).toContain('取消')
    // The prompt took the row, so the answer that ends something is the one under the fingers.
    expect(document.activeElement).toBe(asked.find('[data-device-confirm]').element)
    wrapper.unmount()
  })

  it('answers 取消 on Escape and puts the reader back where they were', async () => {
    const { wrapper, requests } = await showPage(() => json(200, { sessions: [MINE, device()] }))

    await rowFor(wrapper, 'd1').find('[data-act="kick"]').trigger('click')
    await flush()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flush()

    const back = rowFor(wrapper, 'd1')
    expect(back.find('[data-device-confirm]').exists()).toBe(false)
    expect(back.text()).toContain('最近活动')
    // Back on the button that was pressed, not on `<body>`: answering "no" must not cost somebody
    // their place in the list.
    expect(document.activeElement).toBe(back.find('[data-act="kick"]').element)
    expect(postPaths(requests)).toEqual([])
    wrapper.unmount()
  })

  it('ends the device it named, and re-reads the list rather than editing it', async () => {
    const { wrapper, requests } = await showPage(
      () => json(200, { sessions: [MINE, device()] }),
      () => empty(204),
      () => json(200, { sessions: [MINE] }),
    )

    await rowFor(wrapper, 'd1').find('[data-act="kick"]').trigger('click')
    await flush()
    await rowFor(wrapper, 'd1').find('[data-device-confirm]').trigger('click')
    await flush()

    expect(postPaths(requests)).toEqual(['/web/sessions/d1/revoke'])
    // The server is the truth about what is left: a row edited in the browser would be this page's
    // opinion of a list another tab can change.
    expect(requests.filter((request) => request.method === 'GET')).toHaveLength(2)
    expect(wrapper.findAll('li.session')).toHaveLength(1)
    expect(wrapper.text()).toContain('仅当前设备')
    wrapper.unmount()
  })

  it('ends a CLI login through the route of its own plane', async () => {
    // **Two routes, not one that guesses** (v4 Q6): a browser session is deleted from the session
    // store, and a CLI login is an authorization that gets marked — so the row's `kind` is what picks
    // the address the button posts to. The row itself is the same row, and this screen draws no mark
    // to say which kind it is.
    const { wrapper, requests } = await showPage(
      () => json(200, { sessions: [MINE, cliLogin()] }),
      () => empty(204),
      () => json(200, { sessions: [MINE] }),
    )

    expect(wrapper.text()).toContain('CLI · 我的 MacBook')
    expect(wrapper.text()).toContain('2 台设备')
    await rowFor(wrapper, 'a1').find('[data-act="kick"]').trigger('click')
    await flush()
    await rowFor(wrapper, 'a1').find('[data-device-confirm]').trigger('click')
    await flush()

    expect(postPaths(requests)).toEqual(['/web/cli-sign-ins/a1/revoke'])
    expect(wrapper.find('li.session[data-session="a1"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('仅当前设备')
    wrapper.unmount()
  })

  it('says how many the bulk end is about to affect, and reports nothing it did not do', async () => {
    const { wrapper, requests } = await showPage(
      () => json(200, { sessions: [MINE, device(), device({ device_id: 'd2' })] }),
      () => json(200, { revoked: 2 }),
      () => json(200, { sessions: [MINE] }),
    )

    await wrapper.find('[data-others]').trigger('click')
    await flush()

    // The number in the question is the number of rows offering one, read from the list on screen.
    expect(wrapper.text()).toContain('退出后，其他 2 台设备都需要重新登录；当前设备不受影响。')
    expect(wrapper.find('[data-others-confirm]').text()).toBe('确认退出 2 台')

    await wrapper.find('[data-others-confirm]').trigger('click')
    await flush()

    expect(postPaths(requests)).toEqual(['/web/sessions/revoke-others'])
    expect(wrapper.findAll('li.session')).toHaveLength(1)
    // Nothing else signed in, so the control goes with them.
    expect(wrapper.find('[data-others]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('says the failure is only about this list', async () => {
    const { wrapper } = await showPage(() => json(500, errorBody('internal_error', 'boom')))

    // 怕的人怕的是账号出事, so the page says on the spot that it is not — the key screen's sentence
    // and its reason.
    expect(wrapper.text()).toContain('无法加载登录设备列表，请稍后重试。此处仅表示列表加载失败，不代表账号异常。')
    expect(wrapper.findAll('li.session')).toHaveLength(0)
    wrapper.unmount()
  })

  it('sends a session that has gone to the sign-in form, which knows how to come back', async () => {
    const { wrapper } = await showPage(() => json(401, errorBody('unauthenticated', 'no session')))

    expect(location.assign).toHaveBeenCalledWith('/login?return_to=%2Faccount%2Fsessions')
    wrapper.unmount()
  })

  it('draws no device segment for a client that said nothing about itself', async () => {
    const { wrapper } = await showPage(() =>
      json(200, { sessions: [device({ device: '', current: true })] }),
    )

    // The server sends an empty description when there was no User-Agent, and there is nothing to
    // say — a word of our own for it would be copy invented here. It is dropped from the line rather
    // than drawn as an empty segment: the three segments share one line, so an empty one would leave
    // its separator standing there.
    const meta = wrapper.find('.session-meta').text()
    expect(meta).toMatch(/^登录于 .+　·　最近活动 /)
    expect(wrapper.find('.session-ip').text()).toBe('203.0.113.24')
    wrapper.unmount()
  })

  it('leaves a session with no handle unaddressable rather than offering a broken button', async () => {
    const { wrapper } = await showPage(() =>
      json(200, { sessions: [MINE, device({ device_id: null })] }),
    )

    // A session created before this version carries no device id. It is still shown — a login that
    // cannot be seen is the thing this version exists to end — but the row offers no button, and the
    // bulk action is the way it can still be ended. Found by the absence of the attribute the other
    // rows are addressed by, since that absence is the state under test.
    const unstamped = wrapper
      .findAll('li.session')
      .find((row) => row.attributes('data-session') === undefined)!
    expect(unstamped.find('[data-act="kick"]').exists()).toBe(false)
    expect(unstamped.text()).toContain('203.0.113.24')
    expect(wrapper.find('[data-others]').exists()).toBe(true)
    wrapper.unmount()
  })
})
