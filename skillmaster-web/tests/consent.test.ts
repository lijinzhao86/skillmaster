import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { clearCookies, flush, setCookie, stubLocation } from './support'

/**
 * The consent page: what a person is told, and what their two buttons submit.
 *
 * This page is the product's only moment of disclosure — the design says so, and says the page must
 * state consequences rather than ask "authorize?". The tests below are mostly about that claim, plus
 * the one structural fact that makes declining work: the refusal carries no scopes.
 */

const CLIENT = 'skillmaster-cli'
const STATE = 'consent-state-1'

let location = stubLocation('/consent')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.restoreAllMocks()
})

async function showConsent(search: string): Promise<VueWrapper> {
  location = stubLocation('/consent', search)
  vi.resetModules()
  const { default: ConsentPage } = await import('../src/pages/ConsentPage.vue')
  const wrapper = mount(ConsentPage)
  await flush()
  return wrapper
}

/** What the framework's redirect puts in the query: one space-separated scope parameter. */
function query(overrides: Record<string, string> = {}): string {
  const params = new URLSearchParams({
    client_id: CLIENT,
    state: STATE,
    scope: 'skills:read skills:write',
    ...overrides,
  })
  return `?${params.toString()}`
}

describe('the consent page', () => {
  it('says what each scope lets the tool do, in words rather than in scope names', async () => {
    const wrapper = await showConsent(query())

    const text = wrapper.text()
    expect(text).toContain('SkillMaster CLI')
    expect(text).toContain('读取你能看到的 skill')
    expect(text).toContain('以你的名义发布、更新和删除 skill')
    // The raw names are identifiers, not explanations, and a page that showed only those would be
    // asking somebody to agree to a string they cannot evaluate.
    expect(wrapper.findAll('li code')).toHaveLength(0)
    wrapper.unmount()
  })

  it('says so when a scope is one it cannot describe', async () => {
    const wrapper = await showConsent(query({ scope: 'skills:read skills:destroy' }))

    // Shown, named, and flagged. A silent blank would read as harmless — the one thing an unknown
    // scope must not read as.
    expect(wrapper.text()).toContain('skills:destroy')
    expect(wrapper.text()).toContain('不知道这一项的含义')
    wrapper.unmount()
  })

  it('shows a client it does not recognise as itself, with a warning', async () => {
    const wrapper = await showConsent(query({ client_id: 'some-other-tool' }))

    expect(wrapper.text()).toContain('some-other-tool')
    expect(wrapper.text()).toContain('不在已知名单里')
    wrapper.unmount()
  })

  it('submits the client, the state and the CSRF token, and asks for every scope', async () => {
    const wrapper = await showConsent(query())
    const approve = wrapper.findAll('form')[0]!

    expect(approve.attributes('method')).toBe('post')
    expect(approve.attributes('action')).toBe('/oauth/authorize')

    const fields = approve.findAll('input')
    const byName = (name: string) =>
      fields.filter((field) => field.attributes('name') === name).map((f) => f.attributes('value'))

    expect(byName('client_id')).toEqual([CLIENT])
    expect(byName('state')).toEqual([STATE])
    expect(byName('_csrf')).toEqual(['token-one'])
    // One input per scope, not one space-separated value: the submission reads them as a set, and a
    // single joined value would be read as one scope nobody registered.
    expect(byName('scope')).toEqual(['skills:read', 'skills:write'])
    wrapper.unmount()
  })

  it('declines by submitting the same request with nothing agreed to', async () => {
    const wrapper = await showConsent(query())
    const decline = wrapper.findAll('form')[1]!

    expect(decline.attributes('action')).toBe('/oauth/authorize')
    expect(decline.findAll('input').filter((f) => f.attributes('name') === 'scope')).toHaveLength(0)
    // Still carries what identifies the request, or the server could not tell which consent this is.
    const names = decline.findAll('input').map((f) => f.attributes('name'))
    expect(names).toContain('client_id')
    expect(names).toContain('state')
    wrapper.unmount()
  })

  it('offers no form at all when the link is missing what the request needs', async () => {
    const wrapper = await showConsent('?client_id=skillmaster-cli')

    expect(wrapper.findAll('form')).toHaveLength(0)
    expect(wrapper.text()).toContain('这个链接不完整')
    wrapper.unmount()
  })
})
