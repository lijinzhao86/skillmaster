import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import AccountSecretsPage from '../src/pages/AccountSecretsPage'
import SecretsPane from '../src/components/skill/SecretsPane'
import { clearCookies, empty, flush, json, setCookie, stubFetch, stubLocation } from './support'

/**
 * 权限 › 服务凭据 (v52).
 *
 * The things a person would notice going wrong: a value echoed back, a share created somewhere
 * other than the console's own plane, a delete that does not say what it takes away, and an address
 * that names no row silently acting on the first one.
 */

const SECRET = {
  id: 'sec_7f3a',
  name: '知乎 cookie',
  per_day: 500,
  used: 500,
  created_at: '2026-11-01T00:00:00Z',
  rotated_at: null,
  last_used_at: '2026-11-07T09:00:00Z',
  bindings: [{ skill: 'dev/zhihu', env: 'ZHIHU_COOKIE' }],
}

const LIBRARY = {
  skills: [
    {
      id: 's1',
      namespace: 'dev',
      name: 'zhihu',
      description: '',
      when_to_use: null,
      source: null,
      state: 'live',
      version: '1.0.0',
      updated_at: null,
      deleted_at: null,
      category: null,
    },
  ],
  page: 1,
  page_size: 10,
  total_entries: 1,
  effective_entries: 1,
  filtered_total: 1,
}

/** The two reads every load makes, in the order the page issues them. */
const loading = (items: unknown[] = [SECRET]) => [
  () => json(200, { items }),
  () => json(200, LIBRARY),
]

let location = stubLocation('/account/secrets')

beforeEach(() => {
  setCookie('XSRF-TOKEN', 'token-one')
  location = stubLocation('/account/secrets')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
})

describe('the service-credential list', () => {
  it('shows each credential with its bindings, its usage and the capped marker', async () => {
    stubFetch(...loading())
    render(<AccountSecretsPage />)

    await waitFor(() => expect(screen.getByText('知乎 cookie')).toBeTruthy())
    expect(screen.getByText('dev/zhihu')).toBeTruthy()
    expect(screen.getByText('ZHIHU_COOKIE')).toBeTruthy()
    expect(screen.getByText('500 / 500')).toBeTruthy()
    expect(screen.getByText('已达上限')).toBeTruthy()
    // Nothing on this screen carries a value, and there is no control that would ask for one.
    expect(document.body.textContent).not.toContain('test-cookie-value')
  })

  it('says the list failed without implying the skill stopped working', async () => {
    stubFetch(() => json(500, { error: { code: 'internal_error', message: 'boom', details: [] } }),
      () => json(200, LIBRARY))
    render(<AccountSecretsPage />)

    await waitFor(() => expect(screen.getByText(/没有读到服务凭据列表/)).toBeTruthy())
    expect(screen.getByText(/不代表 skill 运行出了问题/)).toBeTruthy()
  })

  it('asks before deleting, and says what the deletion takes with it', async () => {
    stubFetch(...loading())
    render(<AccountSecretsPage />)
    await waitFor(() => expect(screen.getByText('知乎 cookie')).toBeTruthy())

    fireEvent.click(screen.getByText('删除'))

    expect(screen.getByText('删除「知乎 cookie」？')).toBeTruthy()
    expect(document.body.textContent).toContain('当前最多 7 天')
    expect(document.body.textContent).toContain('共享给别人的也一起失效')
  })
})

describe('the share screen', () => {
  it('creates the grant on the console plane, and only after the confirmation', async () => {
    location = stubLocation('/account/secrets', '?state=share&secret=sec_7f3a')
    const { requests } = stubFetch(
      ...loading(),
      // The collaborators of the skill the share is being made on.
      () => json(200, { grants: [{ handle: 'hexu', role: 'invoke', created_at: '2026-11-01T00:00:00Z' }], total: 1, page: 1, page_size: 20 }),
      // The grants this credential already has.
      () => json(200, { items: [] }),
      () => json(201, { items: [{ user: 'hexu', skill: 'dev/zhihu', env: 'ZHIHU_COOKIE', created_at: '2026-11-08T00:00:00Z' }] }),
      // The reload after the write.
      () => json(200, { items: [SECRET] }),
      () => json(200, { items: [{ user: 'hexu', skill: 'dev/zhihu', env: 'ZHIHU_COOKIE', created_at: '2026-11-08T00:00:00Z' }] }),
    )
    render(<AccountSecretsPage />)

    // The risk is on the screen before anything is clicked — that is the point of it.
    await waitFor(() => expect(screen.getByText(/也能提取明文/)).toBeTruthy())
    await waitFor(() => expect(screen.getByText('hexu')).toBeTruthy())

    fireEvent.change(screen.getByLabelText('共享给'), { target: { value: 'hexu' } })
    // By id: 「共享」 is also the crumb's label for this screen, and the button is the one that acts.
    fireEvent.click(document.getElementById('secretShareGo') as HTMLElement)
    expect(screen.getByText('共享「知乎 cookie」给 hexu？')).toBeTruthy()

    fireEvent.click(screen.getByText('确认共享'))
    await flush()

    const write = requests.find((entry) => entry.method === 'POST')
    // **The console's plane is the only one that can create a grant** (ADR 0081's first exception):
    // the API plane answers 405 on the same path.
    expect(write?.path).toContain('/web/secrets/sec_7f3a/shares')
    expect(write?.body).toEqual({ skill: 'dev/zhihu', user: 'hexu' })
  })

  it('opens on the share screen the page action addressed, with the skill and person already chosen', async () => {
    // `skmr secret share <id> --skill <地址> --user <人名>` prints exactly this address; landing one
    // screen short would leave the person choosing a row themselves — and choosing the wrong row
    // here shares a value somebody can then extract.
    location = stubLocation('/account/secrets', '?state=share&secret=sec_7f3a&share=dev/zhihu&user=hexu')
    stubFetch(
      () => json(200, { items: [SECRET] }),
      () => json(200, LIBRARY),
      () => json(200, { grants: [{ handle: 'hexu', role: 'invoke', created_at: '2026-11-01T00:00:00Z' }], total: 1, page: 1, page_size: 10 }),
      () => json(200, { items: [] }),
    )
    render(<AccountSecretsPage />)

    await waitFor(() => expect(screen.getByText(/也能提取明文/)).toBeTruthy())
    // The person is preselected from the address, and the option exists once the collaborator list
    // for that skill arrives — a controlled select cannot show a value it has no option for.
    await waitFor(() =>
      expect((document.getElementById('secretWho') as HTMLSelectElement).value).toBe('hexu'))
    expect((document.getElementById('secretShareSkill') as HTMLSelectElement).value).toBe('dev/zhihu')
  })

  it('goes back to the list when the address names no credential, rather than acting on the first', async () => {
    location = stubLocation('/account/secrets', '?state=share&secret=sec_gone')
    stubFetch(...loading())
    render(<AccountSecretsPage />)

    await waitFor(() => expect(screen.getByText('知乎 cookie')).toBeTruthy())
    expect(screen.getByText(/地址里那条凭据不在这里/)).toBeTruthy()
    // The list is still here, and no share screen was opened on a row nobody asked for.
    expect(screen.queryByText('共享「知乎 cookie」')).toBeNull()
  })
})

describe('the skill settings pane', () => {
  it('offers a credential that is not bound to this skill yet', async () => {
    // **The server's `?skill=` filter keeps only what is already bound here** — asking with it made
    // this dropdown permanently empty, so the pane can never be the place a binding is made.
    const unbound = { ...SECRET, id: 'sec_unbound', bindings: [{ skill: 'dev/other', env: 'X' }] }
    stubFetch(
      () => json(200, { items: [unbound] }),
      () => json(200, { items: [] }),
    )
    render(<SecretsPane hidden={false} skill="dev/zhihu" enabled />)

    await waitFor(() => expect(screen.getByText('知乎 cookie')).toBeTruthy())
    const pick = document.getElementById('credPick') as HTMLSelectElement
    expect(pick.textContent).toContain('知乎 cookie')
  })

  it('lists who the credential is already shared with, and offers 撤销', async () => {
    stubFetch(
      () => json(200, { items: [SECRET] }),
      () => json(200, { items: [] }),
      () => json(200, {
        items: [{ user: 'hexu', skill: 'dev/zhihu', env: 'ZHIHU_COOKIE', created_at: '2026-11-08T00:00:00Z' }],
      }),
    )
    render(<SecretsPane hidden={false} skill="dev/zhihu" enabled />)

    await waitFor(() => expect(screen.getByText(/已共享给：/)).toBeTruthy())
    expect(screen.getByText('hexu')).toBeTruthy()
    expect(screen.getByText('撤销')).toBeTruthy()
  })
})
