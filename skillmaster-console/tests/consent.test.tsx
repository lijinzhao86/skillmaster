import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import { clearCookies, setCookie, stubLocation } from './support'
import ConsentPage from '../src/pages/ConsentPage'

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

function showConsent(search: string) {
  location = stubLocation('/consent', search)
  return render(<ConsentPage />)
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

const inputsOf = (form: Element): Element[] => [...form.querySelectorAll('input')]
const named = (form: Element, name: string): (string | null)[] =>
  inputsOf(form)
    .filter((field) => field.getAttribute('name') === name)
    .map((field) => field.getAttribute('value'))

describe('the consent page', () => {
  it('says what each scope lets the tool do, in words rather than in scope names', () => {
    const { container } = showConsent(query())

    const page = container.textContent ?? ''
    expect(page).toContain('SkillMaster CLI')
    expect(page).toContain('读取你能看到的 skill')
    expect(page).toContain('以你的名义提交新版本、管理共享')
    // **删除 skill is out, and it went out the opposite way from 发布.** 发布 was never true on this
    // plane — the API has no publish route (ADR 0031 §决定 2) — while 删除 *was*, through
    // `DELETE /api/v1/skills/{ns}/{name}`. v4 removed that route (ADR 0046), so the sentence became
    // false the moment the route went; the copy follows the fact.
    expect(page).not.toContain('删除')
    // **And the boundary, which is the half a person cannot infer.**
    expect(page).not.toContain('发布')
    expect(page).toContain('它不能上线')
    // The raw names are identifiers, not explanations, and a page that showed only those would be
    // asking somebody to agree to a string they cannot evaluate.
    expect(container.querySelectorAll('li code')).toHaveLength(0)
  })

  it('says where the credential is ended, and it is not `logout`', () => {
    const { container } = showConsent(query())

    // `logout` has deleted only the local copy since v2 and revokes nothing on the server (ADR 0038
    // §决定 7), so the sentence this page carried promised a revocation that never happens, and did it
    // on the product's only moment of disclosure.
    const page = container.textContent ?? ''
    expect(page).not.toContain('logout')
    expect(page).toContain('重置密码')
  })

  it('says so when a scope is one it cannot describe', () => {
    const { container } = showConsent(query({ scope: 'skills:read skills:destroy' }))

    // Shown, named, and flagged. A silent blank would read as harmless — the one thing an unknown
    // scope must not read as.
    expect(container.textContent).toContain('skills:destroy')
    expect(container.textContent).toContain('不知道这一项的含义')
  })

  it('shows a client it does not recognise as itself, with a warning', () => {
    const { container } = showConsent(query({ client_id: 'some-other-tool' }))

    expect(container.textContent).toContain('some-other-tool')
    expect(container.textContent).toContain('不在已知名单里')
  })

  it('submits the client, the state and the CSRF token, and asks for every scope', () => {
    const { container } = showConsent(query())
    const approve = container.querySelectorAll('form')[0] as Element

    expect(approve.getAttribute('method')).toBe('post')
    expect(approve.getAttribute('action')).toBe('/oauth/authorize')

    expect(named(approve, 'client_id')).toEqual([CLIENT])
    expect(named(approve, 'state')).toEqual([STATE])
    expect(named(approve, '_csrf')).toEqual(['token-one'])
    // One input per scope, not one space-separated value: the submission reads them as a set, and a
    // single joined value would be read as one scope nobody registered.
    expect(named(approve, 'scope')).toEqual(['skills:read', 'skills:write'])
  })

  it('declines by submitting the same request with nothing agreed to', () => {
    const { container } = showConsent(query())
    const decline = container.querySelectorAll('form')[1] as Element

    expect(decline.getAttribute('action')).toBe('/oauth/authorize')
    expect(named(decline, 'scope')).toHaveLength(0)
    // Still carries what identifies the request, or the server could not tell which consent this is.
    const names = inputsOf(decline).map((field) => field.getAttribute('name'))
    expect(names).toContain('client_id')
    expect(names).toContain('state')
  })

  it('offers no form at all when the link is missing what the request needs', () => {
    const { container } = showConsent('?client_id=skillmaster-cli')

    expect(container.querySelectorAll('form')).toHaveLength(0)
    expect(container.textContent).toContain('这个链接不完整')
  })
})
