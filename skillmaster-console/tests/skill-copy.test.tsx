import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import CopyDialog from '../src/components/skill/CopyDialog'
import SkillPage from '../src/pages/SkillPage'
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
 * 复制 (v54, ADR 0173): who sees the entry, what the sheet posts, and what it does with a refusal
 * and with success.
 *
 * The page-level cases are about the entrance — browse and above, not the owner, a readable
 * published version, online — because that predicate is the design's, not the sheet's. The sheet's
 * own cases are run against the component directly: it is a controlled question, and the page's
 * read chain would only add turns between the click and the assertion.
 */

const LIVE = {
  name: '1.0.0',
  digest: 'sha256:aaa',
  draft_name: '第一版',
  code: 'v1',
  draft_id: null,
  title: '第一版',
  note: '第一版说明',
  submitted_at: '2026-10-05T09:00:00Z',
  submitted_by: 'demo-user',
  state: 'published' as const,
  status: 'live' as const,
  state_at: '2026-10-05T10:00:00Z',
  first_published_at: '2026-10-05T10:00:00Z',
  is_current: true,
  file_count: 1,
  total_bytes: 90,
  revoked_reason: null,
}

const HISTORY = {
  ...LIVE,
  name: '0.9.0',
  digest: 'sha256:bbb',
  draft_name: '零九版',
  code: 'v0',
  is_current: false,
  status: 'history' as const,
  first_published_at: '2026-10-01T10:00:00Z',
}

const DRAFT = {
  ...LIVE,
  name: null,
  digest: 'sha256:ccc',
  draft_name: '第二版',
  code: 'd1',
  draft_id: 'd1',
  state: 'draft' as const,
  status: 'draft' as const,
  state_at: null,
  first_published_at: null,
  is_current: false,
}

const SKILL = {
  namespace: { slug: 'demo-user' },
  name: 'pdf-tools',
  title: 'PDF Tools',
  description: '把 PDF 拆开再拼起来',
  when_to_use: null,
  compatibility: null,
  visibility: 'private',
  public_rung: null,
  offline_at: null,
  rung: 'browse',
  pending_rung: null,
  frontmatter: { name: 'pdf-tools' },
  version: LIVE,
  versions: [DRAFT, LIVE, HISTORY],
  files: [{ relpath: 'SKILL.md', sha256: 'sha256:ddd', size: 90, is_binary: false }],
  resources: { body: '/web/skills/demo-user/pdf-tools@1.0.0/body' },
}

/** A shared entry, an unjoined public one, and the owner's own — the three standings that differ. */
const TAGS = (source: 'created' | 'shared' | null) => ({
  category: null,
  public_tags: [],
  my_tags: { readable: false, reason: 'not_in_library', tags: [] },
  library: {
    source,
    active: true,
    joinable: source === null,
    can_remove: false,
  },
})

const CATEGORIES = { categories: [] }

let location = stubLocation('/')

beforeEach(() => {
  location = stubLocation('/skills/demo-user/pdf-tools')
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function settle(): Promise<void> {
  for (let turn = 0; turn < 6; turn += 1) {
    await flush()
  }
}

async function showSkill(
  skill: Record<string, unknown>,
  source: 'created' | 'shared' | null,
  library?: Record<string, unknown>,
): Promise<HTMLElement> {
  stubFetch(
    () => json(200, skill),
    () => json(200, { ...TAGS(source), library: { ...TAGS(source).library, ...library } }),
    () => json(200, CATEGORIES),
  )
  const { container } = render(
    <SkillPage namespace="demo-user" name="pdf-tools" />,
  )
  await settle()
  return container
}

describe('the copy entrance', () => {
  it('is drawn for a public skill the caller may browse', async () => {
    const container = await showSkill(
      { ...SKILL, visibility: 'public', public_rung: 'browse' },
      null,
    )
    expect(container.querySelector('#skillMore')).not.toBeNull()
    // The copy trigger never competes with the header's one filled button: 入库 stays primary.
    const join = container.querySelector('.skill-join')
    expect(join?.textContent).toContain('入库')
    expect(join?.className).not.toContain('is-quiet')
    expect(container.querySelector('#skillMore')?.className).not.toContain('button')
  })

  it('is drawn for a private skill shared at browse', async () => {
    const container = await showSkill(SKILL, 'shared')
    expect(container.querySelector('#skillMore')).not.toBeNull()
  })

  it('is not drawn for the owner', async () => {
    const container = await showSkill({ ...SKILL, rung: 'owner' }, 'created')
    expect(container.querySelector('#skillMore')).toBeNull()
  })

  it('is not drawn at 调用, offline, or with nothing published', async () => {
    const invoke = await showSkill({ ...SKILL, rung: 'invoke' }, null)
    expect(invoke.querySelector('#skillMore')).toBeNull()

    const offline = await showSkill(
      { ...SKILL, offline_at: '2026-10-10T00:00:00Z' },
      'shared',
    )
    expect(offline.querySelector('#skillMore')).toBeNull()

    const draftOnly = await showSkill({ ...SKILL, versions: [DRAFT] }, 'shared')
    expect(draftOnly.querySelector('#skillMore')).toBeNull()
  })

  it('is not drawn for a dormant library entry', async () => {
    const container = await showSkill(SKILL, 'shared', { active: false, joinable: false })
    expect(container.querySelector('#skillMore')).toBeNull()
  })

  it('opens the sheet from 「⋯ › 复制」', async () => {
    const container = await showSkill(SKILL, 'shared')
    fireEvent.click(container.querySelector('#skillMore') as HTMLButtonElement)
    const item = [...container.querySelectorAll('.menu-item')].find(
      (entry) => (entry.textContent ?? '').trim() === '复制',
    )
    expect(item).not.toBeUndefined()
    expect(container.querySelector('.menu-list.is-actions')).not.toBeNull()
    fireEvent.click(item as HTMLElement)
    await flush()
    expect(container.querySelector('#copyDialog')).not.toBeNull()
    expect(container.querySelector('#copyName')).not.toBeNull()

    // U2 at the page level: the dialog receives only the readable published versions — the live
    // one and history, never the draft.
    const options = [...container.querySelectorAll('#copyVersion option')].map(
      (option) => (option as HTMLOptionElement).value,
    )
    expect(options).toEqual(['1.0.0', '0.9.0'])
    expect((container.querySelector('#copyVersion') as HTMLSelectElement).value).toBe('1.0.0')
  })
})

describe('the information page', () => {
  it('shows where the copy came from, with the digest shortened', async () => {
    const container = await showSkill(
      {
        ...SKILL,
        copied_from: { address: 'demo-user/pdf-tools', version: 'sha256:abcdef0123456789' },
      },
      'shared',
    )
    const fact = container.querySelector('#infoCopiedFrom')
    expect(fact).not.toBeNull()
    expect(fact?.textContent).toContain('demo-user/pdf-tools')
    // The 8-character prefix and the ellipsis are the design's rule for a nameless version; the
    // full digest stays in `skmr skill show`.
    expect(fact?.textContent).toContain('sha256:abcdef01…')
    expect((fact?.querySelector('a') as HTMLAnchorElement).getAttribute('href')).toBe(
      '/skills/demo-user/pdf-tools',
    )
  })
})

describe('the copy sheet', () => {
  const versions = [
    { value: '1.0.0', label: '线上 · 1.0.0' },
    { value: '0.9.0', label: '0.9.0' },
  ]

  function showDialog(onNavigate = vi.fn(), onClose = vi.fn()) {
    const rendered = render(
      <CopyDialog
        open
        sourceNamespace="demo-user"
        sourceName="pdf-tools"
        versions={versions}
        initialVersion="1.0.0"
        onClose={onClose}
        onNavigate={onNavigate}
      />,
    )
    return { ...rendered, onNavigate, onClose }
  }

  const accept = (container: HTMLElement): HTMLButtonElement =>
    [...container.querySelectorAll('.confirm-acts .button')].at(-1) as HTMLButtonElement

  it('prefills the source name and the live version, and posts both', async () => {
    const stub = stubFetch(() =>
      json(201, {
        skill: { namespace: 'you', name: 'pdf-tools' },
        draft: 'd1',
        draft_name: '复制自 demo-user/pdf-tools',
        version: null,
        source: { address: 'demo-user/pdf-tools', version: '1.0.0' },
      }),
    )
    const { container } = showDialog()

    expect((container.querySelector('#copyName') as HTMLInputElement).value).toBe('pdf-tools')
    expect((container.querySelector('#copyVersion') as HTMLSelectElement).value).toBe('1.0.0')
    expect(accept(container).disabled).toBe(false)

    fireEvent.click(accept(container))
    await flush()

    const request = stub.requests[0] as RecordedRequest
    expect(request.path).toBe('/web/skills/demo-user/pdf-tools/copy')
    expect(request.method).toBe('POST')
    expect(request.body).toEqual({ name: 'pdf-tools', version: '1.0.0' })
  })

  it('stays in the sheet on success and offers the two destinations', async () => {
    stubFetch(() =>
      json(201, {
        skill: { namespace: 'you', name: 'pdf-tools-copy' },
        draft: 'd1',
        draft_name: '复制自 demo-user/pdf-tools',
        version: null,
        source: { address: 'demo-user/pdf-tools', version: '1.0.0' },
      }),
    )
    const { container, onNavigate, onClose } = showDialog()

    fireEvent.change(container.querySelector('#copyName') as HTMLInputElement, {
      target: { value: 'pdf-tools-copy' },
    })
    fireEvent.click(accept(container))
    await flush()

    expect((container.querySelector('#copyTitle') as HTMLElement).textContent).toBe('复制成功')
    fireEvent.click(container.querySelector('#copyGo') as HTMLButtonElement)
    expect(onClose).toHaveBeenCalled()
    expect(onNavigate).toHaveBeenCalledWith('/skills/you/pdf-tools-copy')
  })

  it('greys the button on a name conflict until the name changes', async () => {
    stubFetch(() =>
      json(409, errorBody('skill_already_exists', 'a skill named pdf-tools already exists')),
    )
    const { container } = showDialog()

    fireEvent.click(accept(container))
    await flush()

    expect(accept(container).disabled).toBe(true)
    expect((container.querySelector('#copyError') as HTMLElement).textContent).toContain(
      '这个名字已经有了',
    )

    fireEvent.change(container.querySelector('#copyName') as HTMLInputElement, {
      target: { value: 'pdf-tools-copy' },
    })
    expect(accept(container).disabled).toBe(false)
  })

  it('keeps a deleted name refused as well', async () => {
    stubFetch(() =>
      json(409, errorBody('skill_deleted', 'the name belongs to a deleted skill')),
    )
    const { container } = showDialog()

    fireEvent.click(accept(container))
    await flush()

    expect(accept(container).disabled).toBe(true)
    // The design has one conflict state; a deleted name is still a taken name, and the restore
    // entry lives under 「我的 skill › 已删除」 rather than in this sheet.
    expect((container.querySelector('#copyError') as HTMLElement).textContent).toContain(
      '这个名字已经有了',
    )
    // One sentence, not two: the server's refusal and the inline rule would otherwise both draw.
    expect(container.querySelectorAll('.confirm-error')).toHaveLength(1)
  })

  it('refuses the reserved skillmaster prefix before sending', async () => {
    const stub = stubFetch(() => json(201, {}))
    const { container } = showDialog()

    fireEvent.change(container.querySelector('#copyName') as HTMLInputElement, {
      target: { value: 'skillmaster-tools' },
    })

    expect(accept(container).disabled).toBe(true)
    expect((container.querySelector('#copyError') as HTMLElement).textContent).toContain(
      '官方保留',
    )
    expect(stub.requests).toHaveLength(0)
  })
})
