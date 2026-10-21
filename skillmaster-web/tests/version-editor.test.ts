import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { errorBody, flush, json, setCookie, stubFetch, stubLocation } from './support'
import type { AuthoredFile, AuthoredSkill, AuthoredVersion } from '../src/api/types'
import VersionEditorPage from '../src/pages/VersionEditorPage.vue'

/**
 * The editor's own page half (v8 test plan E2–E21).
 *
 * These assert the things the server cannot see: what the head says about the base, when 保存 is
 * available, which banner a refusal becomes, where the leave guard sits, and what the page does with
 * the focus it took. The requests are asserted as the protocol — path, body fields — because a page
 * that saved the right-looking thing to the wrong address would be indistinguishable otherwise.
 */

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
  wrapper: VueWrapper
  requests: ReturnType<typeof stubFetch>['requests']
}> {
  const { requests } = stubFetch(
    () => json(200, skill(published())),
    () => new Response(SKILL_MD, { status: 200, headers: { 'Content-Type': 'text/markdown' } }),
    ...responses,
  )
  const wrapper = mount(VersionEditorPage, {
    props: { namespace: 'dev', name: 'pdf-tools', mode: 'new' },
  })
  await flush()
  await flush()
  return { wrapper, requests }
}

async function showDraft(
  responses: Array<() => Response | Promise<Response>> = [],
  overrides: Partial<AuthoredVersion> = {},
  file?: string,
  attach = false,
): Promise<{ wrapper: VueWrapper; requests: ReturnType<typeof stubFetch>['requests'] }> {
  const version = draft(overrides)
  const { requests } = stubFetch(
    () => json(200, skill(version, { rung: 'maintain' })),
    () => new Response(SKILL_MD, { status: 200, headers: { 'Content-Type': 'text/markdown' } }),
    ...responses,
  )
  const wrapper = mount(VersionEditorPage, {
    props: { namespace: 'dev', name: 'pdf-tools', mode: 'edit', version: 'DRAFT001', file },
    ...(attach ? { attachTo: document.body } : {}),
  })
  await flush()
  await flush()
  return { wrapper, requests }
}

describe('VersionEditorPage · the new version', () => {
  it('opens on the live base with an empty name and no save', async () => {
    const { wrapper } = await showNew()
    expect(wrapper.find('#edMeta').text()).toBe('基于线上版本 @1.0.0　·　4 个文件')
    expect(wrapper.find('#edStates').text()).toContain('尚未保存')
    expect((wrapper.find('#edName').element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('.crumbs-here').text()).toBe('新版本')
    expect(wrapper.find('#edHint').exists()).toBe(true)
    expect(wrapper.find('#edSave').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('enables 保存 only once the text and the name are there, and sends the create', async () => {
    const created = draft({ draft_name: '我的版本' })
    const { wrapper, requests } = await showNew([
      () => json(201, { version: created, files: FILES }),
    ])

    await wrapper.find('#edName').setValue('我的版本')
    await wrapper.find('#edNote').setValue('  one line  ')
    expect(wrapper.find('#edSave').attributes('disabled')).toBeDefined()
    await wrapper.find('#edText').setValue(`${SKILL_MD}changed\n`)
    expect(wrapper.find('#edUnsaved').isVisible()).toBe(true)
    expect(wrapper.find('#edSave').attributes('disabled')).toBeUndefined()

    await wrapper.find('#edSave').trigger('click')
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
    expect(wrapper.find('#edStates').text()).toContain('草稿')
    expect(wrapper.find('.crumbs-here').text()).toBe('编辑')
    // The element is always there; `v-show` is what hides it. `isVisible()` reads jsdom's layout
    // as visible even under `display: none`, so the style is the fact to assert.
    expect(wrapper.find('#edUnsaved').attributes('style')).toContain('display: none')
    wrapper.unmount()
  })

  it('refuses to create anything before the name is filled in', async () => {
    const { wrapper, requests } = await showNew()
    await wrapper.find('#edNewFile').trigger('click')
    await flush()
    const input = wrapper.find('.editor-create-input')
    await input.setValue('references/api.md')
    await input.trigger('keydown', { key: 'Enter' })
    await flush()
    // Detail + file only: the page said why instead of sending an unnamed create.
    expect(requests).toHaveLength(2)
    expect(wrapper.find('#edLabelStatus').text()).toBe('请先填写版本名称。')
    wrapper.unmount()
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
    const wrapper = mount(VersionEditorPage, {
      props: { namespace: 'dev', name: 'pdf-tools', mode: 'new' },
    })
    await flush()
    await flush()
    expect(requests[1]?.path).toBe('/web/skills/dev/pdf-tools?base=none')
    expect(wrapper.find('#edMeta').text()).toBe('空白底稿　·　1 个文件')
    expect(wrapper.findAll('.editor-file')).toHaveLength(1)
    expect(wrapper.find('.editor-file .editor-file-path').text()).toBe('SKILL.md')
    expect((wrapper.find('#edText').element as HTMLTextAreaElement).value).toContain(
      'name: pdf-tools',
    )
    wrapper.unmount()
  })
})

describe('VersionEditorPage · a draft', () => {
  it('writes the file against the digest it read', async () => {
    const after = draft({ digest: 'sha256:next', submitted_by: 'dev' })
    const { wrapper, requests } = await showDraft([
      () => json(200, { version: after, files: FILES }),
    ])
    await wrapper.find('#edText').setValue('changed\n')
    await wrapper.find('#edSave').trigger('click')
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
    expect(wrapper.find('#edUnsaved').isVisible()).toBe(false)
    wrapper.unmount()
  })

  it('turns a stale baseline into the conflict banner and keeps the local text', async () => {
    const { wrapper, requests } = await showDraft([
      () =>
        json(
          409,
          errorBody('digest_mismatch', 'changed', [{ field: 'current_digest', issue: 'sha256:new' }]),
        ),
      () => json(200, skill(draft({ digest: 'sha256:new' }), { rung: 'maintain' })),
      () => json(200, { version: draft({ digest: 'sha256:mine' }), files: FILES }),
    ])
    await wrapper.find('#edText').setValue('mine\n')
    await wrapper.find('#edSave').trigger('click')
    await flush()
    await flush()

    expect(wrapper.find('#edConflict').isVisible()).toBe(true)
    expect((wrapper.find('#edText').element as HTMLTextAreaElement).value).toBe('mine\n')
    expect(wrapper.find('#edError').exists()).toBe(false)

    // Typing after the refusal is part of 「当前内容」 — 覆盖 must send what is in the buffer now.
    await wrapper.find('#edText').setValue('mine later\n')
    await wrapper.find('#edOverwrite').trigger('click')
    await flush()
    await flush()
    expect(wrapper.find('#edConflict').exists()).toBe(false)
    const retry = requests.filter((request) => request.method === 'POST').at(-1)
    expect(retry?.body).toMatchObject({ base_digest: 'sha256:new', text: 'mine later\n' })
    expect((wrapper.find('#edText').element as HTMLTextAreaElement).value).toBe('mine later\n')
    wrapper.unmount()
  })

  it('re-reads a file it had open when 覆盖 advances the baseline', async () => {
    const { wrapper } = await showDraft([
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
    await wrapper.find('[data-path="references"] .editor-file-open').trigger('click')
    await wrapper.find('[data-path="references/notes.md"] .editor-file-open').trigger('click')
    await flush()
    await wrapper.find('[data-path="SKILL.md"] .editor-file-open').trigger('click')

    await wrapper.find('#edText').setValue('mine\n')
    await wrapper.find('#edSave').trigger('click')
    await flush()
    await flush()
    await wrapper.find('#edOverwrite').trigger('click')
    await flush()
    await flush()

    await wrapper.find('[data-path="references/notes.md"] .editor-file-open').trigger('click')
    expect((wrapper.find('#edText').element as HTMLTextAreaElement).value).toBe("A's notes\n")
    expect(wrapper.find('#edUnsaved').attributes('style')).toContain('display: none')
    wrapper.unmount()
  })

  it('names the published version a duplicate belongs to', async () => {
    const { wrapper } = await showDraft([
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
    await wrapper.find('#edText').setValue('same\n')
    await wrapper.find('#edSave').trigger('click')
    await flush()
    await flush()

    expect(wrapper.find('#edDuplicateTitle').text()).toBe('该内容已属于 @1.0.0。')
    expect(wrapper.find('#edDuplicateLink').text()).toBe('查看 @1.0.0')
    expect(wrapper.find('#edDuplicateLink').attributes('href')).toBe(
      '/skills/dev/pdf-tools?tab=versions&version=1.0.0',
    )
    expect(wrapper.find('#edDuplicate').isVisible()).toBe(true)
    wrapper.unmount()
  })

  it('names a draft by its version code and has no number to show', async () => {
    const { wrapper } = await showDraft([
      () =>
        json(
          409,
          errorBody('content_already_exists', 'exists', [
            { field: 'state', issue: 'draft' },
            { field: 'code', issue: 'K3M9QX2P' },
          ]),
        ),
    ])
    await wrapper.find('#edText').setValue('same\n')
    await wrapper.find('#edSave').trigger('click')
    await flush()
    await flush()

    expect(wrapper.find('#edDuplicateTitle').text()).toBe('该内容已属于草稿 K3M9QX2P。')
    expect(wrapper.find('#edDuplicateLink').text()).toBe('查看 K3M9QX2P')
    wrapper.unmount()
  })

  it('shows the binary pane and offers no save for a file it cannot edit', async () => {
    const { wrapper } = await showDraft([], {}, 'assets/manual.pdf')
    expect(wrapper.find('#edBinary').isVisible()).toBe(true)
    expect(wrapper.find('#edCursor').text()).toBe('只读')
    expect(wrapper.find('#edDownload').attributes('href')).toBe(
      '/web/skills/dev/pdf-tools@DRAFT001/files/assets/manual.pdf',
    )
    expect(wrapper.find('#edSave').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('shows 没有这一版 for a draft somebody else submitted and this rung cannot change', async () => {
    const version = draft({ submitted_by: 'somebody-else' })
    stubFetch(
      () => json(200, skill(version, { rung: 'write' })),
      () => new Response(SKILL_MD, { status: 200 }),
    )
    const wrapper = mount(VersionEditorPage, {
      props: { namespace: 'dev', name: 'pdf-tools', mode: 'edit', version: 'DRAFT001' },
    })
    await flush()
    await flush()
    expect(wrapper.find('#edMissing').isVisible()).toBe(true)
    expect(wrapper.find('#edWorkspace').isVisible()).toBe(false)
    wrapper.unmount()
  })
})

describe('VersionEditorPage · the tree', () => {
  it('starts folded, unfolds one folder, and filters as it is typed', async () => {
    const { wrapper } = await showDraft()
    const names = (): string[] =>
      wrapper.findAll('.editor-file-path').map((node) => node.text())
    expect(names()).toEqual(['assets', 'references', 'SKILL.md'])

    await wrapper.find('[data-path="references"] .editor-file-open').trigger('click')
    expect(names()).toEqual(['assets', 'references', 'notes.md', 'split.md', 'SKILL.md'])

    const search = wrapper.find('#edFileQuery')
    await search.setValue('split')
    expect(names()).toEqual(['references', 'split.md'])
    // A filtered ancestor is expanded, not a second click away.
    expect(
      wrapper.find('[data-path="references"] .editor-file-open').attributes('aria-expanded'),
    ).toBe('true')

    await search.setValue('zzz')
    expect(names()).toEqual([])
    expect(wrapper.find('#edEmpty').isVisible()).toBe(true)
    await search.trigger('keydown', { key: 'Escape' })
    expect((search.element as HTMLInputElement).value).toBe('')
    // Back to the tree as it was before typing: `references` stayed open.
    expect(names()).toEqual(['assets', 'references', 'notes.md', 'split.md', 'SKILL.md'])
    wrapper.unmount()
  })

  it('keeps a rename in the row and refuses a path that is already there without a request', async () => {
    const { wrapper, requests } = await showDraft()
    await wrapper.find('[data-path="references"] .editor-file-open').trigger('click')
    await wrapper.find('[data-menu="references/notes.md"]').trigger('click')
    await flush()
    await wrapper.find('.editor-file-pop .menu-item').trigger('click')
    await flush()

    const input = wrapper.find('.editor-rename-input')
    expect((input.element as HTMLInputElement).value).toBe('references/notes.md')
    await input.setValue('references/split.md')
    await input.trigger('keydown', { key: 'Enter' })
    await flush()

    expect(wrapper.find('.editor-field-error').text()).toBe('该路径已存在文件。')
    expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0)
    wrapper.unmount()
  })

  it('opens the folder a renamed file moves into', async () => {
    const renamed = FILES.map((file) =>
      file.relpath === 'references/split.md'
        ? { ...file, relpath: 'guides/split.md' }
        : file,
    )
    const { wrapper } = await showDraft([
      () => json(200, { version: draft({ digest: 'sha256:r' }), files: renamed }),
    ])
    await wrapper.find('[data-path="references"] .editor-file-open').trigger('click')
    await wrapper.find('[data-menu="references/split.md"]').trigger('click')
    await flush()
    await wrapper.find('.editor-file-pop .menu-item').trigger('click')
    await flush()

    const input = wrapper.find('.editor-rename-input')
    await input.setValue('guides/split.md')
    await input.trigger('keydown', { key: 'Enter' })
    await flush()
    await flush()

    expect(
      wrapper.find('[data-path="guides"] .editor-file-open').attributes('aria-expanded'),
    ).toBe('true')
    expect(wrapper.find('[data-path="guides/split.md"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('titles a failed file read as a failed read, not a failed save', async () => {
    const { wrapper } = await showDraft([
      () => json(500, errorBody('internal_error', 'boom')),
    ])
    await wrapper.find('[data-path="references"] .editor-file-open').trigger('click')
    await wrapper.find('[data-path="references/notes.md"] .editor-file-open').trigger('click')
    await flush()
    expect(wrapper.find('#edError').isVisible()).toBe(true)
    expect(wrapper.find('#edErrorTitle').text()).toBe('没能打开这个文件。')
    expect(wrapper.find('#edErrorText').text()).not.toContain('修改后可再次保存')
    wrapper.unmount()
  })

  it('does not draw an empty folder while searching, because a search matches files', async () => {
    const { wrapper } = await showDraft()
    for (const path of ['guides', 'guides/advanced']) {
      await wrapper.find('#edNewFolder').trigger('click')
      await flush()
      const input = wrapper.find('.editor-create-input')
      await input.setValue(path)
      await input.trigger('keydown', { key: 'Enter' })
      await flush()
    }
    await wrapper.find('#edFileQuery').setValue('guides')
    expect(wrapper.findAll('.editor-file-path')).toHaveLength(0)
    expect(wrapper.find('#edEmpty').isVisible()).toBe(true)
    wrapper.unmount()
  })

  it('confirms a delete and names the unsaved text it would take with it', async () => {
    const { wrapper, requests } = await showDraft([], {}, undefined, true)
    await wrapper.find('[data-path="references"] .editor-file-open').trigger('click')
    await wrapper.find('[data-path="references/notes.md"] .editor-file-open').trigger('click')
    await flush()
    await wrapper.find('#edText').setValue('unsaved\n')
    await wrapper.find('[data-menu="references/notes.md"]').trigger('click')
    await flush()
    await wrapper.findAll('.editor-file-pop .menu-item')[1]?.trigger('click')
    await flush()

    expect(wrapper.find('.confirm-title').text()).toBe('删除文件？')
    expect(wrapper.find('.confirm-body').text()).toBe(
      'references/notes.md 将从版本中移除。 该文件未保存的改动也会丢失。',
    )
    await wrapper.find('.confirm-acts .button.is-quiet').trigger('click')
    await flush()
    expect(wrapper.find('.confirm').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.find('[data-menu="references/notes.md"]').element)
    expect(requests.filter((request) => request.method === 'POST')).toHaveLength(0)
    wrapper.unmount()
  })
})

describe('VersionEditorPage · labels and leaving', () => {
  it('writes a name on blur and says so under the field', async () => {
    const { wrapper, requests } = await showDraft([
      () => json(200, { name: '新名字' }),
      () => json(200, { note: '新说明' }),
    ])
    await wrapper.find('#edName').setValue('新名字')
    await wrapper.find('#edName').trigger('blur')
    await flush()
    const rename = requests.find((request) => request.method === 'PATCH')
    expect(rename?.path).toBe('/web/skills/dev/pdf-tools/versions/DRAFT001/name')
    expect(rename?.body).toEqual({ name: '新名字' })
    expect(wrapper.find('#edLabelStatus').text()).toBe('版本名称已保存。')

    await wrapper.find('#edNote').setValue('新说明')
    await wrapper.find('#edNote').trigger('blur')
    await flush()
    expect(wrapper.find('#edLabelStatus').text()).toBe('说明已保存。')
    wrapper.unmount()
  })

  it('shows a failed save as a banner and says the text may be saved again', async () => {
    const { wrapper } = await showDraft([
      () =>
        json(
          400,
          errorBody('invalid_upload', 'too large', [{ field: 'files', issue: 'too_large' }]),
        ),
    ])
    await wrapper.find('#edText').setValue('huge\n')
    await wrapper.find('#edSave').trigger('click')
    await flush()
    await flush()

    expect(wrapper.find('#edError').isVisible()).toBe(true)
    expect(wrapper.find('#edErrorTitle').text()).toBe('保存失败。')
    expect(wrapper.find('#edErrorText').text()).toContain('修改后可再次保存。')
    expect(wrapper.find('#edUnsaved').attributes('style')).not.toContain('display: none')
    expect(wrapper.find('#edConflict').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps what was typed when a label write fails, and says why', async () => {
    const { wrapper } = await showDraft([
      () => {
        throw new TypeError('offline')
      },
    ])
    await wrapper.find('#edName').setValue('没写成的名字')
    await wrapper.find('#edName').trigger('blur')
    await flush()

    expect((wrapper.find('#edName').element as HTMLInputElement).value).toBe('没写成的名字')
    expect(wrapper.find('#edLabelStatus').classes()).toContain('is-danger')
    expect(wrapper.find('#edLabelStatus').text()).toContain('网络')
    wrapper.unmount()
  })

  it('re-reads in place when a delete finds the file already gone', async () => {
    const { wrapper, requests } = await showDraft([
      () => json(404, errorBody('file_not_found', 'gone')),
      // The re-read. The gone file is absent from it, and so is the folder it left empty.
      () => json(200, skill(draft(), { files: FILES.slice(0, 1) })),
    ])
    // Unsaved text in a file that still exists: the reason this path may not reload.
    await wrapper.find('#edText').setValue(`${SKILL_MD}still typing\n`)
    await wrapper.find('[data-path="references"] .editor-file-open').trigger('click')
    await wrapper.find('[data-menu="references/notes.md"]').trigger('click')
    await flush()
    await wrapper.findAll('.editor-file-pop .menu-item')[1]?.trigger('click')
    await flush()
    await wrapper.find('.confirm-acts .button.is-danger').trigger('click')
    await flush()
    await flush()

    expect(requests[2]?.method).toBe('POST')
    expect(requests[3]?.path).toBe('/web/skills/dev/pdf-tools@DRAFT001')
    expect(wrapper.find('[data-path="references"]').exists()).toBe(false)
    expect((wrapper.find('#edText').element as HTMLTextAreaElement).value).toBe(
      `${SKILL_MD}still typing\n`,
    )
    expect(wrapper.find('#edUnsaved').attributes('style')).not.toContain('display: none')
    // Reloading the page would throw away every other file that has unsaved text in it.
    expect(location.reload).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps the page and its unsaved text when the re-read after a delete fails', async () => {
    const { wrapper } = await showDraft([
      () => json(404, errorBody('file_not_found', 'gone')),
      () => json(500, errorBody('internal_error', 'boom')),
    ])
    await wrapper.find('#edText').setValue(`${SKILL_MD}still typing\n`)
    await wrapper.find('[data-path="references"] .editor-file-open').trigger('click')
    await wrapper.find('[data-menu="references/notes.md"]').trigger('click')
    await flush()
    await wrapper.findAll('.editor-file-pop .menu-item')[1]?.trigger('click')
    await flush()
    await wrapper.find('.confirm-acts .button.is-danger').trigger('click')
    await flush()
    await flush()

    expect(wrapper.find('#edErrorTitle').text()).toBe('没能重新读取这一版。')
    expect((wrapper.find('#edText').element as HTMLTextAreaElement).value).toBe(
      `${SKILL_MD}still typing\n`,
    )
    // The failure may not be turned into a reload: that would drop what is unsaved.
    expect(location.reload).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps text typed while a file is still loading', async () => {
    let release!: (response: Response) => void
    const pending = new Promise<Response>((resolve) => {
      release = resolve
    })
    const { wrapper } = await showDraft([() => pending])
    await wrapper.find('[data-path="references"] .editor-file-open').trigger('click')
    await wrapper.find('[data-path="references/notes.md"] .editor-file-open').trigger('click')
    await wrapper.find('#edText').setValue('typed while loading\n')
    release(
      new Response('the server copy\n', {
        status: 200,
        headers: { 'Content-Type': 'text/markdown' },
      }),
    )
    await flush()
    await flush()

    expect((wrapper.find('#edText').element as HTMLTextAreaElement).value).toBe(
      'typed while loading\n',
    )
    expect(wrapper.find('#edUnsaved').attributes('style')).not.toContain('display: none')
    wrapper.unmount()
  })

  it('restores the old name rather than sending an empty one', async () => {
    const { wrapper, requests } = await showDraft()
    await wrapper.find('#edName').setValue('')
    await wrapper.find('#edName').trigger('blur')
    await flush()
    expect((wrapper.find('#edName').element as HTMLInputElement).value).toBe('草稿名')
    expect(wrapper.find('#edLabelStatus').text()).toBe('版本名称不能为空，已恢复原名称。')
    expect(requests.filter((request) => request.method === 'PATCH')).toHaveLength(0)
    wrapper.unmount()
  })

  it('asks before leaving with unsaved text, and 继续编辑 keeps the page', async () => {
    const { wrapper } = await showDraft()
    await wrapper.find('#edText').setValue('unsaved\n')
    await wrapper.find('#edBackCrumb').trigger('click')

    expect(wrapper.find('.confirm-title').text()).toBe('有 1 个文件未保存')
    expect(wrapper.find('.confirm-acts .button.is-quiet').text()).toBe('继续编辑')
    await wrapper.find('.confirm-acts .button.is-quiet').trigger('click')
    await flush()
    expect(wrapper.find('.confirm').exists()).toBe(false)
    expect(location.assign).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('folds the tree to a bar on a narrow screen and opens it again', async () => {
    const original = window.innerWidth
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 400 })
    try {
      const { wrapper } = await showDraft()
      expect(wrapper.find('.editor-tree').classes()).toContain('is-collapsed')
      expect(wrapper.find('#edTreeToggle').attributes('aria-expanded')).toBe('false')
      await wrapper.find('#edTreeToggle').trigger('click')
      expect(wrapper.find('.editor-tree').classes()).not.toContain('is-collapsed')
      expect(wrapper.find('#edTreeToggle').attributes('aria-expanded')).toBe('true')
      wrapper.unmount()
    } finally {
      Object.defineProperty(window, 'innerWidth', { configurable: true, value: original })
    }
  })

  it('saves on Ctrl/⌘+S and closes a menu on Esc with the focus put back', async () => {
    const { wrapper, requests } = await showDraft(
      [() => json(200, { version: draft({ digest: 'sha256:next' }), files: FILES })],
      {},
      undefined,
      true,
    )
    await wrapper.find('#edText').setValue('changed\n')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true }))
    await flush()
    await flush()
    expect(requests.some((request) => request.method === 'POST')).toBe(true)

    await wrapper.find('[data-path="references"] .editor-file-open').trigger('click')
    const trigger = wrapper.find('[data-menu="references/notes.md"]')
    await trigger.trigger('click')
    await flush()
    expect(wrapper.find('.editor-file-pop').exists()).toBe(true)
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flush()
    expect(wrapper.find('.editor-file-pop').exists()).toBe(false)
    expect(document.activeElement).toBe(trigger.element)
    wrapper.unmount()
  })

  it('guards 查看版本详情 the same way it guards the breadcrumb', async () => {
    const { wrapper } = await showDraft()
    // A version is a tab pin on the product's route grammar, not a path suffix.
    expect(wrapper.find('#edView').attributes('href')).toBe(
      '/skills/dev/pdf-tools?tab=versions&version=DRAFT001',
    )
    expect(wrapper.find('#edBackCrumb').attributes('href')).toBe(
      '/skills/dev/pdf-tools?tab=versions',
    )
    await wrapper.find('#edText').setValue('unsaved\n')
    await wrapper.find('#edView').trigger('click')
    expect(wrapper.find('.confirm-title').text()).toBe('有 1 个文件未保存')
    await wrapper.find('.confirm-acts .button.is-quiet').trigger('click')
    await flush()
    expect(wrapper.find('.confirm').exists()).toBe(false)
    expect(location.assign).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('counts labels in code points, so an emoji is one character', async () => {
    const { wrapper } = await showDraft()
    const name = wrapper.find('#edName')
    await name.setValue('😀'.repeat(41))
    expect(Array.from((name.element as HTMLInputElement).value)).toHaveLength(40)

    // At the limit the model would clamp to the same string; the DOM is what has to lose the 41st.
    const field = name.element as HTMLInputElement
    await name.setValue('a'.repeat(40))
    field.value = `${'a'.repeat(40)}b`
    await name.trigger('input')
    expect(field.value).toBe('a'.repeat(40))

    const note = wrapper.find('#edNote')
    await note.setValue('😀'.repeat(201))
    expect(Array.from((note.element as HTMLInputElement).value)).toHaveLength(200)
    wrapper.unmount()
  })

  it('asks the browser once for unsaved text, not again after the page already confirmed', async () => {
    const { wrapper } = await showDraft()
    await wrapper.find('#edText').setValue('unsaved\n')
    const prompted = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(prompted)
    expect(prompted.defaultPrevented).toBe(true)

    await wrapper.find('#edBackCrumb').trigger('click')
    await wrapper.find('.confirm-acts .button.is-danger').trigger('click')
    await flush()
    const confirmed = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(confirmed)
    expect(confirmed.defaultPrevented).toBe(false)
    wrapper.unmount()
  })

  it('closes the delete sheet on Esc as well as on 取消', async () => {
    const { wrapper } = await showDraft()
    await wrapper.find('[data-path="references"] .editor-file-open').trigger('click')
    await wrapper.find('[data-menu="references/notes.md"]').trigger('click')
    await flush()
    await wrapper.findAll('.editor-file-pop .menu-item')[1]?.trigger('click')
    await flush()
    expect(wrapper.find('.confirm').exists()).toBe(true)
    await wrapper.find('.confirm').trigger('keydown', { key: 'Escape' })
    await flush()
    expect(wrapper.find('.confirm').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not stand in the way when nothing is unsaved', async () => {
    const { wrapper } = await showDraft()
    await wrapper.find('#edBackCrumb').trigger('click')
    expect(wrapper.find('.confirm').exists()).toBe(false)
    wrapper.unmount()
  })
})
