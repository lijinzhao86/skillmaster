import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCookies, errorBody, json, setCookie, stubLocation, stubMyWhenToUse } from './support'
import { SKILL, showSkill, text } from './skill-page-harness'

/**
 * A server that does not know the optional sentence's route yet (v59 iteration 0001).
 *
 * The page has already resolved the skill when it reads `GET …/my-when-to-use`, so a 404 here can
 * only mean the route is missing — the shape of a rolled-back server behind a newer console. That
 * is not a statement about the skill, and the page must not say 「这个地址找不到」 over a skill it
 * is currently drawing. A real failure still earns the banner.
 */

beforeEach(() => {
  stubLocation('/')
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
  stubMyWhenToUse(json(404, errorBody('skill_not_found', 'no such skill')))
})

afterEach(() => {
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('one skill reading my when-to-use', () => {
  it('treats a 404 on the optional read as the empty state, not a page banner', async () => {
    const { container } = await showSkill(SKILL)

    expect(container.querySelector('.banner')).toBeNull()
    expect(text(container.querySelector('#infoMyWhen'))).toContain('搜索时先按你的说法找')
    expect(container.querySelector('#infoMyWhenEmpty')).not.toBeNull()
  })

  it('still draws the banner for a failure that is not a missing route', async () => {
    stubMyWhenToUse(json(500, errorBody('internal_error', 'boom')))
    const { container } = await showSkill(SKILL)

    expect(container.querySelector('.banner')).not.toBeNull()
  })
})
