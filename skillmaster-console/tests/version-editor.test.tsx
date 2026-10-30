import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import { errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'
import type { AuthoredFile, AuthoredSkill, AuthoredVersion } from '../src/api/types'
import VersionEditorPage from '../src/pages/VersionEditorPage'

/**
 * The editor's own page half (v8 test plan E2–E21).
 *
 * These assert the things the server cannot see: what the head says about the base, when 保存 is
 * available, which banner a refusal becomes, where the leave guard sits, and what the page does with
 * the focus it took. The requests are asserted as the protocol — path, body fields — because a page
 * that saved the right-looking thing to the wrong address would be indistinguishable otherwise.
 */

/** The element's text, trimmed; `''` when it is not there (`Wrapper.text()`). */
const text = (element: Element | null): string => (element?.textContent ?? '').trim()

/** The attribute, or `undefined` when it is not there (`Wrapper.attributes()`). */
const attr = (element: Element | null, name: string): string | undefined =>
  element?.getAttribute(name) ?? undefined

/** The inline style, or `''` when there is none — what the assertions below look through. */
const styleOf = (element: Element | null): string => element?.getAttribute('style') ?? ''

/**
 * `Wrapper.isVisible()`: not hidden by the `hidden` attribute or an inline style on the element or
 * any ancestor. jsdom applies no stylesheet in this suite, so inline style is the whole fact — the
 * same one the Vue tests read.
 */
function isVisible(element: Element | null): boolean {
  for (let node = element; node !== null; node = node.parentElement) {
    if (node instanceof HTMLElement && node.hidden) {
      return false
    }
    const style = getComputedStyle(node)
    if (
      style.display === 'none' ||
      style.visibility === 'hidden' ||
      style.visibility === 'collapse' ||
      style.opacity === '0'
    ) {
      return false
    }
  }
  return element !== null
}

const FILES: AuthoredFile[] = [
  { relpath: 'SKILL.md', sha256: 'sha256:a', size: 40, is_binary: false },
  { relpath: 'references/notes.md', sha256: 'sha256:b', size: 10, is_binary: false },
  { relpath: 'references/split.md', sha256: 'sha256:c', size: 9, is_binary: false },
  { relpath: 'assets/manual.pdf', sha256: 'sha256:d', size: 12, is_binary: true },
]

const SKILL_MD = '---\nname: pdf-tools\ndescription: d\n---\nbody\n'

function published(overrides: Partial<AuthoredVersion> = {}): AuthoredVersion {
  return {
    name: '1.0.0',
    digest: 'sha256:live',
    draft_name: '线上那版',
    code: 'LIVE0001',
    draft_id: null,
    title: 'PDF Tools',
    note: null,
    submitted_at: '2026-10-01T10:00:00Z',
    submitted_by: 'dev',
    state: 'published',
    status: 'live',
    state_at: '2026-10-01T10:05:00Z',
    first_published_at: '2026-10-01T10:05:00Z',
    is_current: true,
    file_count: FILES.length,
    total_bytes: 71,
    revoked_reason: null,
    ...overrides,
  }
}

function draft(overrides: Partial<AuthoredVersion> = {}): AuthoredVersion {
  return published({
    name: null,
    digest: 'sha256:draft',
    draft_name: '草稿名',
    code: 'DRAFT001',
    draft_id: 'DRAFT001',
    note: '一句说明',
    submitted_by: 'dev',
    state: 'draft',
    status: 'draft',
    state_at: null,
    first_published_at: null,
    is_current: false,
    ...overrides,
  })
}

function skill(version: AuthoredVersion | null, overrides: Partial<AuthoredSkill> = {}): AuthoredSkill {
  return {
    namespace: { slug: 'dev' },
    name: 'pdf-tools',
    title: 'PDF Tools',
    description: 'row description',
    visibility: 'private',
    public_rung: null,
    offline_at: null,
    rung: 'maintain',
    pending_rung: null,
    frontmatter: {},
    version,
    versions: version === null ? [] : [version],
    files: version === null ? [] : FILES,
    resources: null,
    ...overrides,
  }
}

let location = stubLocation('/')

beforeEach(() => {
  location = stubLocation('/skills/dev/pdf-tools/new')
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function showNew(responses: Array<() => Response | Promise<Response>> = []): Promise<{
  container: HTMLElement
  requests: ReturnType<typeof stubFetch>['requests']
}> {
  const { requests } = stubFetch(
    () => json(200, skill(published())),
    () => new Response(SKILL_MD, { status: 200, headers: { 'Content-Type': 'text/markdown' } }),
    ...responses,
  )
  const { container } = render(
    <VersionEditorPage namespace="dev" name="pdf-tools" mode="new" />,
  )
  await flush()
  await flush()
  return { container, requests }
}

async function showDraft(
  responses: Array<() => Response | Promise<Response>> = [],
  overrides: Partial<AuthoredVersion> = {},
  file?: string,
): Promise<{ container: HTMLElement; requests: ReturnType<typeof stubFetch>['requests'] }> {
  const version = draft(overrides)
  const { requests } = stubFetch(
    () => json(200, skill(version, { rung: 'maintain' })),
    () => new Response(SKILL_MD, { status: 200, headers: { 'Content-Type': 'text/markdown' } }),
    ...responses,
  )
  const { container } = render(
    <VersionEditorPage namespace="dev" name="pdf-tools" mode="edit" version="DRAFT001" file={file} />,
  )
  await flush()
  await flush()
  return { container, requests }
}

describe('VersionEditorPage · the new version', () => {
  it('opens on the live base with an empty name and no save', async () => {
    const { container } = await showNew()
    expect(text(container.querySelector('#edMeta'))).toBe('基于线上版本 @1.0.0　·　4 个文件')
    expect(text(container.querySelector('#edStates'))).toContain('尚未保存')
    expect((container.querySelector('#edName') as HTMLInputElement).value).toBe('')
    expect(text(container.querySelector('.crumbs-here'))).toBe('新版本')
    expect(container.querySelector('#edHint')).not.toBeNull()
    expect(attr(container.querySelector('#edSave'), 'disabled')).toBeDefined()
  })

  it('enables 保存 only once the text and the name are there, and sends the create', async () => {
    const created = draft({ draft_name: '我的版本' })
    const { container, requests } = await showNew([
      () => json(201, { version: created, files: FILES }),
    ])

    fireEvent.change(container.querySelector('#edName') as HTMLInputElement, {
      target: { value: '我的版本' },
    })
    fireEvent.change(container.querySelector('#edNote') as HTMLInputElement, {
      target: { value: '  one line  ' },
    })
    expect(attr(container.querySelector('#edSave'), 'disabled')).toBeDefined()
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: `${SKILL_MD}changed\n` },
    })
    expect(isVisible(container.querySelector('#edUnsaved'))).toBe(true)
    expect(attr(container.querySelector('#edSave'), 'disabled')).toBeUndefined()

    fireEvent.click(container.querySelector('#edSave') as HTMLButtonElement)
    await flush()
    await flush()

    const post = requests.find((request) => request.method === 'POST')
    expect(post?.path).toBe('/web/skills/dev/pdf-tools/versions')
    expect(post?.body).toMatchObject({
      name: '我的版本',
      note: 'one line',
      from: '1.0.0',
      from_digest: 'sha256:live',
      edit: { op: 'write', path: 'SKILL.md', text: `${SKILL_MD}changed\n` },
    })
    expect(text(container.querySelector('#edStates'))).toContain('草稿')
    expect(text(container.querySelector('.crumbs-here'))).toBe('编辑')
    // The element is always there; `v-show` is what hides it. `isVisible()` reads jsdom's layout
    // as visible even under `display: none`, so the style is the fact to assert.
    expect(styleOf(container.querySelector('#edUnsaved'))).toContain('display: none')
  })

  it('refuses to create anything before the name is filled in', async () => {
    const { container, requests } = await showNew()
    fireEvent.click(container.querySelector('#edNewFile') as HTMLButtonElement)
    await flush()
    const input = container.querySelector('.editor-create-input') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'references/api.md' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await flush()
    // Detail + file only: the page said why instead of sending an unnamed create.
    expect(requests).toHaveLength(2)
    expect(text(container.querySelector('#edLabelStatus'))).toBe('请先填写版本名称。')
  })

  it('shows the blank base when nothing readable can be a base', async () => {
    const { requests } = stubFetch(
      () => json(200, skill(null, { rung: 'maintain' })),
      () =>
        json(
          200,
          skill(null, {
            rung: 'maintain',
            description: 'row description',
          }),
        ),
    )
    const { container } = render(
      <VersionEditorPage namespace="dev" name="pdf-tools" mode="new" />,
    )
    await flush()
    await flush()
    expect(requests[1]?.path).toBe('/web/skills/dev/pdf-tools?base=none')
    expect(text(container.querySelector('#edMeta'))).toBe('空白底稿　·　1 个文件')
    expect([...container.querySelectorAll('.editor-file')]).toHaveLength(1)
    expect(text(container.querySelector('.editor-file .editor-file-path'))).toBe('SKILL.md')
    expect((container.querySelector('#edText') as HTMLTextAreaElement).value).toContain(
      'name: pdf-tools',
    )
  })
})

describe('VersionEditorPage · a draft', () => {
  it('writes the file against the digest it read', async () => {
    const after = draft({ digest: 'sha256:next', submitted_by: 'dev' })
    const { container, requests } = await showDraft([
      () => json(200, { version: after, files: FILES }),
    ])
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: 'changed\n' },
    })
    fireEvent.click(container.querySelector('#edSave') as HTMLButtonElement)
    await flush()
    await flush()

    const post = requests.find((request) => request.method === 'POST')
    expect(post?.path).toBe('/web/skills/dev/pdf-tools/versions/DRAFT001/edits')
    expect(post?.body).toEqual({
      base_digest: 'sha256:draft',
      op: 'write',
      path: 'SKILL.md',
      text: 'changed\n',
    })
    expect(isVisible(container.querySelector('#edUnsaved'))).toBe(false)
  })

  it('turns a stale baseline into the conflict banner and keeps the local text', async () => {
    const { container, requests } = await showDraft([
      () =>
        json(
          409,
          errorBody('digest_mismatch', 'changed', [{ field: 'current_digest', issue: 'sha256:new' }]),
        ),
      () => json(200, skill(draft({ digest: 'sha256:new' }), { rung: 'maintain' })),
      () => json(200, { version: draft({ digest: 'sha256:mine' }), files: FILES }),
    ])
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: 'mine\n' },
    })
    fireEvent.click(container.querySelector('#edSave') as HTMLButtonElement)
    await flush()
    await flush()

    expect(isVisible(container.querySelector('#edConflict'))).toBe(true)
    expect((container.querySelector('#edText') as HTMLTextAreaElement).value).toBe('mine\n')
    expect(container.querySelector('#edError')).toBeNull()

    // Typing after the refusal is part of 「当前内容」 — 覆盖 must send what is in the buffer now.
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: 'mine later\n' },
    })
    fireEvent.click(container.querySelector('#edOverwrite') as HTMLButtonElement)
    await flush()
    await flush()
    expect(container.querySelector('#edConflict')).toBeNull()
    const retry = requests.filter((request) => request.method === 'POST').at(-1)
    expect(retry?.body).toMatchObject({ base_digest: 'sha256:new', text: 'mine later\n' })
    expect((container.querySelector('#edText') as HTMLTextAreaElement).value).toBe('mine later\n')
  })

  it('re-reads a file it had open when 覆盖 advances the baseline', async () => {
    const { container } = await showDraft([
      // The page's own first read of notes, before anything else happened.
      () => new Response('old notes\n', { status: 200, headers: { 'Content-Type': 'text/markdown' } }),
      // The failed save of SKILL.md…
      () =>
        json(
          409,
          errorBody('digest_mismatch', 'changed', [{ field: 'current_digest', issue: 'sha256:new' }]),
        ),
      // …the baseline re-read…
      () => json(200, skill(draft({ digest: 'sha256:new' }), { rung: 'maintain' })),
      // …the accepted overwrite…
      () => json(200, { version: draft({ digest: 'sha256:mine' }), files: FILES }),
      // …and the file somebody else had changed while this page held a stale copy.
      () => new Response("A's notes\n", { status: 200, headers: { 'Content-Type': 'text/markdown' } }),
    ])
    // Open notes first, so it is in the page's buffers, then come back to SKILL.md.
    fireEvent.click(
      container.querySelector('[data-path="references"] .editor-file-open') as HTMLElement,
    )
    fireEvent.click(
      container.querySelector('[data-path="references/notes.md"] .editor-file-open') as HTMLElement,
    )
    await flush()
    fireEvent.click(
      container.querySelector('[data-path="SKILL.md"] .editor-file-open') as HTMLElement,
    )

    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: 'mine\n' },
    })
    fireEvent.click(container.querySelector('#edSave') as HTMLButtonElement)
    await flush()
    await flush()
    fireEvent.click(container.querySelector('#edOverwrite') as HTMLButtonElement)
    await flush()
    await flush()

    fireEvent.click(
      container.querySelector('[data-path="references/notes.md"] .editor-file-open') as HTMLElement,
    )
    expect((container.querySelector('#edText') as HTMLTextAreaElement).value).toBe("A's notes\n")
    expect(styleOf(container.querySelector('#edUnsaved'))).toContain('display: none')
  })

  it('names the published version a duplicate belongs to', async () => {
    const { container } = await showDraft([
      () =>
        json(
          409,
          errorBody('content_already_exists', 'exists', [
            { field: 'state', issue: 'published' },
            { field: 'pin', issue: '1.0.0' },
            { field: 'code', issue: 'LIVE0001' },
          ]),
        ),
    ])
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: 'same\n' },
    })
    fireEvent.click(container.querySelector('#edSave') as HTMLButtonElement)
    await flush()
    await flush()

    expect(text(container.querySelector('#edDuplicateTitle'))).toBe('该内容已属于 @1.0.0。')
    expect(text(container.querySelector('#edDuplicateLink'))).toBe('查看 @1.0.0')
    expect(attr(container.querySelector('#edDuplicateLink'), 'href')).toBe(
      '/skills/dev/pdf-tools?tab=versions&version=1.0.0',
    )
    expect(isVisible(container.querySelector('#edDuplicate'))).toBe(true)
  })

  it('names a draft by its version code and has no number to show', async () => {
    const { container } = await showDraft([
      () =>
        json(
          409,
          errorBody('content_already_exists', 'exists', [
            { field: 'state', issue: 'draft' },
            { field: 'code', issue: 'K3M9QX2P' },
          ]),
        ),
    ])
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: 'same\n' },
    })
    fireEvent.click(container.querySelector('#edSave') as HTMLButtonElement)
    await flush()
    await flush()

    expect(text(container.querySelector('#edDuplicateTitle'))).toBe('该内容已属于草稿 K3M9QX2P。')
    expect(text(container.querySelector('#edDuplicateLink'))).toBe('查看 K3M9QX2P')
  })

  it('shows the binary pane and offers no save for a file it cannot edit', async () => {
    const { container } = await showDraft([], {}, 'assets/manual.pdf')
    expect(isVisible(container.querySelector('#edBinary'))).toBe(true)
    expect(text(container.querySelector('#edCursor'))).toBe('只读')
    expect(attr(container.querySelector('#edDownload'), 'href')).toBe(
      '/web/skills/dev/pdf-tools@DRAFT001/files/assets/manual.pdf',
    )
    expect(attr(container.querySelector('#edSave'), 'disabled')).toBeDefined()
  })

  it('shows 没有这一版 for a draft somebody else submitted and this rung cannot change', async () => {
    const version = draft({ submitted_by: 'somebody-else' })
    stubFetch(
      () => json(200, skill(version, { rung: 'write' })),
      () => new Response(SKILL_MD, { status: 200 }),
    )
    const { container } = render(
      <VersionEditorPage namespace="dev" name="pdf-tools" mode="edit" version="DRAFT001" />,
    )
    await flush()
    await flush()
    expect(isVisible(container.querySelector('#edMissing'))).toBe(true)
    expect(isVisible(container.querySelector('#edWorkspace'))).toBe(false)
  })
})

describe('VersionEditorPage · the tree', () => {
  it('starts folded, unfolds one folder, and filters as it is typed', async () => {
    const { container } = await showDraft()
    const names = (): string[] =>
      [...container.querySelectorAll('.editor-file-path')].map((node) => text(node))
    expect(names()).toEqual(['assets', 'references', 'SKILL.md'])

    fireEvent.click(
      container.querySelector('[data-path="references"] .editor-file-open') as HTMLElement,
    )
    expect(names()).toEqual(['assets', 'references', 'notes.md', 'split.md', 'SKILL.md'])

    const search = container.querySelector('#edFileQuery') as HTMLInputElement
    fireEvent.change(search, { target: { value: 'split' } })
    expect(names()).toEqual(['references', 'split.md'])
    // A filtered ancestor is expanded, not a second click away.
    expect(
      attr(container.querySelector('[data-path="references"] .editor-file-open'), 'aria-expanded'),
    ).toBe('true')

    fireEvent.change(search, { target: { value: 'zzz' } })
    expect(names()).toEqual([])
    expect(isVisible(container.querySelector('#edEmpty'))).toBe(true)
    fireEvent.keyDown(search, { key: 'Escape' })
    expect(search.value).toBe('')
    // Back to the tree as it was before typing: `references` stayed open.
    expect(names()).toEqual(['assets', 'references', 'notes.md', 'split.md', 'SKILL.md'])
  })

  it('keeps a rename in the row and refuses a path that is already there without a request', async () => {
    const { container, requests } = await showDraft()
    fireEvent.click(
      container.querySelector('[data-path="references"] .editor-file-open') as HTMLElement,
    )
    fireEvent.click(container.querySelector('[data-menu="references/notes.md"]') as HTMLElement)
    await flush()
    fireEvent.click(container.querySelector('.editor-file-pop .menu-item') as HTMLElement)
    await flush()

    const input = container.querySelector('.editor-rename-input') as HTMLInputElement
    expect(input.value).toBe('references/notes.md')
    fireEvent.change(input, { target: { value: 'references/split.md' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await flush()

    expect(text(container.querySelector('.editor-field-error'))).toBe('该路径已存在文件。')
    expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0)
  })

  // v33 test-plan W1–W3: a name macOS or Windows cannot restore is refused while it is still in
  // the input — no request, one sentence, the input and the focus stay put.
  it('refuses a new file whose name Windows treats as a device, without a request', async () => {
    const { container, requests } = await showDraft()
    fireEvent.click(container.querySelector('#edNewFile') as HTMLButtonElement)
    await flush()
    const input = container.querySelector('.editor-create-input') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'CON.md' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await flush()

    expect(text(container.querySelector('.editor-field-error'))).toBe('CON 是 Windows 保留的设备名。')
    expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0)
    // The input keeps what was typed: the next keystroke is the fix. (Focus is the design's rule
    // too, but jsdom's activeElement is not the browser's — the page test does not pin it.)
    expect((container.querySelector('.editor-create-input') as HTMLInputElement).value).toBe('CON.md')
  })

  it('refuses a new file that is only a case away from one that is already there', async () => {
    const { container, requests } = await showDraft()
    fireEvent.click(container.querySelector('#edNewFile') as HTMLButtonElement)
    await flush()
    const input = container.querySelector('.editor-create-input') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'references/SPLIT.md' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await flush()

    expect(text(container.querySelector('.editor-field-error'))).toBe(
      '与已有的 references/split.md 在 macOS 和 Windows 上会被当成同一个文件。',
    )
    expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0)
  })

  it('refuses a rename that would land on another spelling of an existing file', async () => {
    const { container, requests } = await showDraft()
    fireEvent.click(
      container.querySelector('[data-path="references"] .editor-file-open') as HTMLElement,
    )
    fireEvent.click(container.querySelector('[data-menu="references/notes.md"]') as HTMLElement)
    await flush()
    fireEvent.click(container.querySelector('.editor-file-pop .menu-item') as HTMLElement)
    await flush()

    const input = container.querySelector('.editor-rename-input') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'references/SPLIT.md' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await flush()

    expect(text(container.querySelector('.editor-field-error'))).toBe(
      '与已有的 references/split.md 在 macOS 和 Windows 上会被当成同一个文件。',
    )
    expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0)
  })

  it('opens the folder a renamed file moves into', async () => {
    const renamed = FILES.map((file) =>
      file.relpath === 'references/split.md'
        ? { ...file, relpath: 'guides/split.md' }
        : file,
    )
    const { container } = await showDraft([
      () => json(200, { version: draft({ digest: 'sha256:r' }), files: renamed }),
    ])
    fireEvent.click(
      container.querySelector('[data-path="references"] .editor-file-open') as HTMLElement,
    )
    fireEvent.click(container.querySelector('[data-menu="references/split.md"]') as HTMLElement)
    await flush()
    fireEvent.click(container.querySelector('.editor-file-pop .menu-item') as HTMLElement)
    await flush()

    const input = container.querySelector('.editor-rename-input') as HTMLInputElement
    fireEvent.change(input, { target: { value: 'guides/split.md' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    await flush()
    await flush()

    expect(
      attr(container.querySelector('[data-path="guides"] .editor-file-open'), 'aria-expanded'),
    ).toBe('true')
    expect(container.querySelector('[data-path="guides/split.md"]')).not.toBeNull()
  })

  it('titles a failed file read as a failed read, not a failed save', async () => {
    const { container } = await showDraft([
      () => json(500, errorBody('internal_error', 'boom')),
    ])
    fireEvent.click(
      container.querySelector('[data-path="references"] .editor-file-open') as HTMLElement,
    )
    fireEvent.click(
      container.querySelector('[data-path="references/notes.md"] .editor-file-open') as HTMLElement,
    )
    await flush()
    expect(isVisible(container.querySelector('#edError'))).toBe(true)
    expect(text(container.querySelector('#edErrorTitle'))).toBe('没能打开这个文件。')
    expect(text(container.querySelector('#edErrorText'))).not.toContain('修改后可再次保存')
  })

  it('does not draw an empty folder while searching, because a search matches files', async () => {
    const { container } = await showDraft()
    for (const path of ['guides', 'guides/advanced']) {
      fireEvent.click(container.querySelector('#edNewFolder') as HTMLButtonElement)
      await flush()
      const input = container.querySelector('.editor-create-input') as HTMLInputElement
      fireEvent.change(input, { target: { value: path } })
      fireEvent.keyDown(input, { key: 'Enter' })
      await flush()
    }
    fireEvent.change(container.querySelector('#edFileQuery') as HTMLInputElement, {
      target: { value: 'guides' },
    })
    expect([...container.querySelectorAll('.editor-file-path')]).toHaveLength(0)
    expect(isVisible(container.querySelector('#edEmpty'))).toBe(true)
  })

  it('confirms a delete and names the unsaved text it would take with it', async () => {
    const { container, requests } = await showDraft([], {}, undefined)
    fireEvent.click(
      container.querySelector('[data-path="references"] .editor-file-open') as HTMLElement,
    )
    fireEvent.click(
      container.querySelector('[data-path="references/notes.md"] .editor-file-open') as HTMLElement,
    )
    await flush()
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: 'unsaved\n' },
    })
    fireEvent.click(container.querySelector('[data-menu="references/notes.md"]') as HTMLElement)
    await flush()
    const deleteItem = [...container.querySelectorAll('.editor-file-pop .menu-item')][1]
    if (deleteItem !== undefined) {
      fireEvent.click(deleteItem)
    }
    await flush()

    expect(text(container.querySelector('.confirm-title'))).toBe('删除文件？')
    expect(text(container.querySelector('.confirm-body'))).toBe(
      'references/notes.md 将从版本中移除。 该文件未保存的改动也会丢失。',
    )
    fireEvent.click(
      container.querySelector('.confirm-acts .button.is-quiet') as HTMLButtonElement,
    )
    await flush()
    // 取消 puts the focus back on the next frame (Vue's `nextTick`; React's rAF).
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(container.querySelector('.confirm')).toBeNull()
    expect(document.activeElement).toBe(
      container.querySelector('[data-menu="references/notes.md"]'),
    )
    expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0)
  })
})

describe('VersionEditorPage · labels and leaving', () => {
  it('writes a name on blur and says so under the field', async () => {
    const { container, requests } = await showDraft([
      () => json(200, { name: '新名字' }),
      () => json(200, { note: '新说明' }),
    ])
    const name = container.querySelector('#edName') as HTMLInputElement
    fireEvent.change(name, { target: { value: '新名字' } })
    fireEvent.blur(name)
    await flush()
    const rename = requests.find((request) => request.method === 'PATCH')
    expect(rename?.path).toBe('/web/skills/dev/pdf-tools/versions/DRAFT001/name')
    expect(rename?.body).toEqual({ name: '新名字' })
    expect(text(container.querySelector('#edLabelStatus'))).toBe('版本名称已保存。')

    const note = container.querySelector('#edNote') as HTMLInputElement
    fireEvent.change(note, { target: { value: '新说明' } })
    fireEvent.blur(note)
    await flush()
    expect(text(container.querySelector('#edLabelStatus'))).toBe('说明已保存。')
  })

  it('shows a failed save as a banner and says the text may be saved again', async () => {
    const { container } = await showDraft([
      () =>
        json(
          400,
          errorBody('invalid_upload', 'too large', [{ field: 'files', issue: 'too_large' }]),
        ),
    ])
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: 'huge\n' },
    })
    fireEvent.click(container.querySelector('#edSave') as HTMLButtonElement)
    await flush()
    await flush()

    expect(isVisible(container.querySelector('#edError'))).toBe(true)
    expect(text(container.querySelector('#edErrorTitle'))).toBe('保存失败。')
    expect(text(container.querySelector('#edErrorText'))).toContain('修改后可再次保存。')
    expect(styleOf(container.querySelector('#edUnsaved'))).not.toContain('display: none')
    expect(container.querySelector('#edConflict')).toBeNull()
  })

  it('keeps what was typed when a label write fails, and says why', async () => {
    const { container } = await showDraft([
      () => {
        throw new TypeError('offline')
      },
    ])
    const name = container.querySelector('#edName') as HTMLInputElement
    fireEvent.change(name, { target: { value: '没写成的名字' } })
    fireEvent.blur(name)
    await flush()

    expect((container.querySelector('#edName') as HTMLInputElement).value).toBe('没写成的名字')
    expect(
      container.querySelector('#edLabelStatus')?.classList.contains('is-danger'),
    ).toBe(true)
    expect(text(container.querySelector('#edLabelStatus'))).toContain('网络')
  })

  it('re-reads in place when a delete finds the file already gone', async () => {
    const { container, requests } = await showDraft([
      () => json(404, errorBody('file_not_found', 'gone')),
      // The re-read. The gone file is absent from it, and so is the folder it left empty.
      () => json(200, skill(draft(), { files: FILES.slice(0, 1) })),
    ])
    // Unsaved text in a file that still exists: the reason this path may not reload.
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: `${SKILL_MD}still typing\n` },
    })
    fireEvent.click(
      container.querySelector('[data-path="references"] .editor-file-open') as HTMLElement,
    )
    fireEvent.click(container.querySelector('[data-menu="references/notes.md"]') as HTMLElement)
    await flush()
    const deleteItem = [...container.querySelectorAll('.editor-file-pop .menu-item')][1]
    if (deleteItem !== undefined) {
      fireEvent.click(deleteItem)
    }
    await flush()
    fireEvent.click(container.querySelector('.confirm-acts .button.is-danger') as HTMLButtonElement)
    await flush()
    await flush()

    expect(requests[2]?.method).toBe('POST')
    expect(requests[3]?.path).toBe('/web/skills/dev/pdf-tools@DRAFT001')
    expect(container.querySelector('[data-path="references"]')).toBeNull()
    expect((container.querySelector('#edText') as HTMLTextAreaElement).value).toBe(
      `${SKILL_MD}still typing\n`,
    )
    expect(styleOf(container.querySelector('#edUnsaved'))).not.toContain('display: none')
    // Reloading the page would throw away every other file that has unsaved text in it.
    expect(location.reload).not.toHaveBeenCalled()
  })

  it('keeps the page and its unsaved text when the re-read after a delete fails', async () => {
    const { container } = await showDraft([
      () => json(404, errorBody('file_not_found', 'gone')),
      () => json(500, errorBody('internal_error', 'boom')),
    ])
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: `${SKILL_MD}still typing\n` },
    })
    fireEvent.click(
      container.querySelector('[data-path="references"] .editor-file-open') as HTMLElement,
    )
    fireEvent.click(container.querySelector('[data-menu="references/notes.md"]') as HTMLElement)
    await flush()
    const deleteItem = [...container.querySelectorAll('.editor-file-pop .menu-item')][1]
    if (deleteItem !== undefined) {
      fireEvent.click(deleteItem)
    }
    await flush()
    fireEvent.click(container.querySelector('.confirm-acts .button.is-danger') as HTMLButtonElement)
    await flush()
    await flush()

    expect(text(container.querySelector('#edErrorTitle'))).toBe('没能重新读取这一版。')
    expect((container.querySelector('#edText') as HTMLTextAreaElement).value).toBe(
      `${SKILL_MD}still typing\n`,
    )
    // The failure may not be turned into a reload: that would drop what is unsaved.
    expect(location.reload).not.toHaveBeenCalled()
  })

  it('keeps text typed while a file is still loading', async () => {
    let release!: (response: Response) => void
    const pending = new Promise<Response>((resolve) => {
      release = resolve
    })
    const { container } = await showDraft([() => pending])
    fireEvent.click(
      container.querySelector('[data-path="references"] .editor-file-open') as HTMLElement,
    )
    fireEvent.click(
      container.querySelector('[data-path="references/notes.md"] .editor-file-open') as HTMLElement,
    )
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: 'typed while loading\n' },
    })
    release(
      new Response('the server copy\n', {
        status: 200,
        headers: { 'Content-Type': 'text/markdown' },
      }),
    )
    await flush()
    await flush()

    expect((container.querySelector('#edText') as HTMLTextAreaElement).value).toBe(
      'typed while loading\n',
    )
    expect(styleOf(container.querySelector('#edUnsaved'))).not.toContain('display: none')
  })

  it('restores the old name rather than sending an empty one', async () => {
    const { container, requests } = await showDraft()
    const name = container.querySelector('#edName') as HTMLInputElement
    fireEvent.change(name, { target: { value: '' } })
    fireEvent.blur(name)
    await flush()
    expect((container.querySelector('#edName') as HTMLInputElement).value).toBe('草稿名')
    expect(text(container.querySelector('#edLabelStatus'))).toBe('版本名称不能为空，已恢复原名称。')
    expect(requests.filter((request) => request.method === 'PATCH')).toHaveLength(0)
  })

  it('asks before leaving with unsaved text, and 继续编辑 keeps the page', async () => {
    const { container } = await showDraft()
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: 'unsaved\n' },
    })
    fireEvent.click(container.querySelector('#edBackCrumb') as HTMLAnchorElement)

    expect(text(container.querySelector('.confirm-title'))).toBe('有 1 个文件未保存')
    expect(text(container.querySelector('.confirm-acts .button.is-quiet'))).toBe('继续编辑')
    fireEvent.click(
      container.querySelector('.confirm-acts .button.is-quiet') as HTMLButtonElement,
    )
    await flush()
    expect(container.querySelector('.confirm')).toBeNull()
    expect(location.assign).not.toHaveBeenCalled()
  })

  it('folds the tree to a bar on a narrow screen and opens it again', async () => {
    const original = window.innerWidth
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 400 })
    try {
      const { container } = await showDraft()
      expect(
        container.querySelector('.editor-tree')?.classList.contains('is-collapsed'),
      ).toBe(true)
      expect(attr(container.querySelector('#edTreeToggle'), 'aria-expanded')).toBe('false')
      fireEvent.click(container.querySelector('#edTreeToggle') as HTMLButtonElement)
      expect(
        container.querySelector('.editor-tree')?.classList.contains('is-collapsed'),
      ).toBe(false)
      expect(attr(container.querySelector('#edTreeToggle'), 'aria-expanded')).toBe('true')
    } finally {
      Object.defineProperty(window, 'innerWidth', { configurable: true, value: original })
    }
  })

  it('saves on Ctrl/⌘+S and closes a menu on Esc with the focus put back', async () => {
    const { container, requests } = await showDraft(
      [() => json(200, { version: draft({ digest: 'sha256:next' }), files: FILES })],
      {},
      undefined,
    )
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: 'changed\n' },
    })
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true }))
    await flush()
    await flush()
    expect(requests.some((request) => request.method === 'POST')).toBe(true)

    fireEvent.click(
      container.querySelector('[data-path="references"] .editor-file-open') as HTMLElement,
    )
    const trigger = container.querySelector('[data-menu="references/notes.md"]') as HTMLElement
    fireEvent.click(trigger)
    await flush()
    expect(container.querySelector('.editor-file-pop')).not.toBeNull()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flush()
    // The focus goes back on the next frame (Vue's `nextTick`; React's rAF).
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(container.querySelector('.editor-file-pop')).toBeNull()
    expect(document.activeElement).toBe(trigger)
  })

  it('guards 查看版本详情 the same way it guards the breadcrumb', async () => {
    const { container } = await showDraft()
    // A version is a tab pin on the product's route grammar, not a path suffix.
    expect(attr(container.querySelector('#edView'), 'href')).toBe(
      '/skills/dev/pdf-tools?tab=versions&version=DRAFT001',
    )
    expect(attr(container.querySelector('#edBackCrumb'), 'href')).toBe(
      '/skills/dev/pdf-tools?tab=versions',
    )
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: 'unsaved\n' },
    })
    fireEvent.click(container.querySelector('#edView') as HTMLAnchorElement)
    expect(text(container.querySelector('.confirm-title'))).toBe('有 1 个文件未保存')
    fireEvent.click(
      container.querySelector('.confirm-acts .button.is-quiet') as HTMLButtonElement,
    )
    await flush()
    expect(container.querySelector('.confirm')).toBeNull()
    expect(location.assign).not.toHaveBeenCalled()
  })

  it('counts labels in code points, so an emoji is one character', async () => {
    const { container } = await showDraft()
    const name = container.querySelector('#edName') as HTMLInputElement
    fireEvent.change(name, { target: { value: '😀'.repeat(41) } })
    expect(Array.from(name.value)).toHaveLength(40)

    // At the limit the model would clamp to the same string; the DOM is what has to lose the 41st.
    fireEvent.change(name, { target: { value: 'a'.repeat(40) } })
    fireEvent.input(name, { target: { value: `${'a'.repeat(40)}b` } })
    expect(name.value).toBe('a'.repeat(40))

    const note = container.querySelector('#edNote') as HTMLInputElement
    fireEvent.change(note, { target: { value: '😀'.repeat(201) } })
    expect(Array.from(note.value)).toHaveLength(200)
  })

  it('asks the browser once for unsaved text, not again after the page already confirmed', async () => {
    const { container } = await showDraft()
    fireEvent.change(container.querySelector('#edText') as HTMLTextAreaElement, {
      target: { value: 'unsaved\n' },
    })
    const prompted = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(prompted)
    expect(prompted.defaultPrevented).toBe(true)

    fireEvent.click(container.querySelector('#edBackCrumb') as HTMLAnchorElement)
    fireEvent.click(container.querySelector('.confirm-acts .button.is-danger') as HTMLButtonElement)
    await flush()
    const confirmed = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(confirmed)
    expect(confirmed.defaultPrevented).toBe(false)
  })

  it('closes the delete sheet on Esc as well as on 取消', async () => {
    const { container } = await showDraft()
    fireEvent.click(
      container.querySelector('[data-path="references"] .editor-file-open') as HTMLElement,
    )
    fireEvent.click(container.querySelector('[data-menu="references/notes.md"]') as HTMLElement)
    await flush()
    const deleteItem = [...container.querySelectorAll('.editor-file-pop .menu-item')][1]
    if (deleteItem !== undefined) {
      fireEvent.click(deleteItem)
    }
    await flush()
    expect(container.querySelector('.confirm')).not.toBeNull()
    fireEvent.keyDown(container.querySelector('.confirm') as HTMLElement, { key: 'Escape' })
    await flush()
    expect(container.querySelector('.confirm')).toBeNull()
  })

  it('does not stand in the way when nothing is unsaved', async () => {
    const { container } = await showDraft()
    fireEvent.click(container.querySelector('#edBackCrumb') as HTMLAnchorElement)
    expect(container.querySelector('.confirm')).toBeNull()
  })
})
