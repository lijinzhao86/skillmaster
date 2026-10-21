import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
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
import ImportDialog from '../src/components/skill/ImportDialog.vue'
import NewSkillDialog from '../src/components/skill/NewSkillDialog.vue'
import VersionDetail from '../src/components/version/VersionDetail.vue'
import VersionFilterBar from '../src/components/version/VersionFilterBar.vue'
import VersionRow from '../src/components/version/VersionRow.vue'

/**
 * The v9 transfer surfaces: the two menus that grew a second source, the two sheets, the archive
 * link, and the file tab's one new state.
 *
 * What is asserted is the protocol and what is *not* drawn: which address a sheet posts to, which
 * form fields the package travels in, which version a discarded address may point files at (none),
 * and which sentences the design owns (the idempotent replay) are copied whole.
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
  file_count: 2,
  total_bytes: 120,
  revoked_reason: null,
}

const DRAFT = {
  name: null,
  digest: 'sha256:bbb',
  draft_name: '第二版',
  code: 'd1',
  draft_id: 'd1',
  title: '第二版',
  note: '第二版说明',
  submitted_at: '2026-10-06T09:00:00Z',
  submitted_by: 'demo-user',
  state: 'draft' as const,
  status: 'draft' as const,
  state_at: null,
  first_published_at: null,
  is_current: false,
  file_count: 2,
  total_bytes: 120,
  revoked_reason: null,
}

/** A discarded draft: still addressable by its version code, and it has no files to show. */
const DISCARDED = {
  ...DRAFT,
  digest: 'sha256:ccc',
  draft_name: '废弃版',
  code: 'd9',
  draft_id: 'd9',
  state: 'discarded' as const,
  status: 'discarded' as const,
  state_at: '2026-10-07T09:00:00Z',
}

const FILES = [
  { relpath: 'SKILL.md', sha256: 'sha256:ddd', size: 90, is_binary: false },
  { relpath: 'references/checklist.md', sha256: 'sha256:eee', size: 30, is_binary: false },
]

const SKILL = {
  namespace: { slug: 'demo-user' },
  name: 'pdf-tools',
  title: 'PDF Tools',
  description: '把 PDF 拆开再拼起来',
  visibility: 'private',
  public_rung: null,
  offline_at: null,
  rung: 'maintain',
  pending_rung: null,
  frontmatter: { name: 'pdf-tools' },
  version: LIVE,
  versions: [LIVE, DRAFT],
  files: FILES,
  resources: null,
}

/** The `SubmitResponse` both sheets read. */
function submitted(overrides: Record<string, unknown> = {}, version: Record<string, unknown> = {}) {
  return {
    namespace: 'demo-user',
    name: 'pdf-tools',
    created: true,
    skill_created: false,
    version: {
      name: null,
      draft_name: '第二版',
      note: null,
      draft_id: 'd1',
      digest: 'sha256:bbb',
      file_count: 2,
      total_bytes: 120,
      submitted_at: '2026-10-06T09:00:00Z',
      state: 'draft',
      ...version,
    },
    ...overrides,
  }
}

beforeEach(() => {
  stubLocation('/')
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/* ------------------------------------------------------------------- the two menus */

describe('新建 ▾ menus', () => {
  it('offers the two sources beside 新建版本, and one of them is the page’s sheet', async () => {
    const wrapper = mount(VersionFilterBar, {
      props: {
        versions: [LIVE, DRAFT],
        filter: 'all' as const,
        query: '',
        rung: 'write',
        newHref: '/skills/demo-user/pdf-tools/new',
      },
    })

    expect(wrapper.find('.version-create').attributes('aria-haspopup')).toBe('menu')
    const items = wrapper.findAll('.version-toolbar .menu-item')
    expect(items.map((item) => item.text())).toEqual(['在线编辑', '导入压缩包'])
    expect(items[0]?.attributes('href')).toBe('/skills/demo-user/pdf-tools/new')

    await items[1]?.trigger('click')
    expect(wrapper.emitted('open-import')).toHaveLength(1)
    wrapper.unmount()
  })

  it('reaches the second entry with the arrow keys', async () => {
    // The list sits inside the trigger's span; before `.stop` on its keydown, every arrow press
    // also ran the trigger's handler and reopened the menu on its first item.
    const wrapper = mount(VersionFilterBar, {
      props: {
        versions: [LIVE, DRAFT],
        filter: 'all' as const,
        query: '',
        rung: 'write',
        newHref: '/skills/demo-user/pdf-tools/new',
      },
      attachTo: document.body,
    })

    await wrapper.find('.version-create').trigger('click')
    // The first item's focus happens inside `requestAnimationFrame`, which jsdom runs on its own
    // timer — one macrotask is not enough to reach it.
    await new Promise((resolve) => setTimeout(resolve, 20))
    const items = wrapper.findAll('.version-toolbar .menu-item')
    expect(document.activeElement).toBe(items[0]?.element)

    items[0]?.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    await flush()
    expect(document.activeElement).toBe(items[1]?.element)

    // ArrowUp from the first entry leaves the menu, and the trigger has focus again.
    ;(items[0]?.element as HTMLElement).focus()
    items[0]?.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }))
    await flush()
    expect(document.activeElement).toBe(wrapper.find('.version-create').element)
    wrapper.unmount()
  })

  it('draws no entry for a rung that cannot submit', () => {
    const wrapper = mount(VersionFilterBar, {
      props: {
        versions: [LIVE],
        filter: 'all' as const,
        query: '',
        rung: 'browse',
        newHref: '/skills/demo-user/pdf-tools/new',
      },
    })
    expect(wrapper.find('.version-create').exists()).toBe(false)
    wrapper.unmount()
  })

  it('opens the import and the create sheets from the skills list', async () => {
    const wrapper = await showSkills(() => json(200, { skills: [] }))
    const items = wrapper.findAll('.menu-list .menu-item')
    expect(items.map((item) => item.text())).toEqual(['在线编辑', '导入本地 skill'])

    await items[1]?.trigger('click')
    await flush()
    expect(wrapper.find('#importTitle').text()).toBe('导入 skill')

    await wrapper.find('.confirm-acts .button.is-quiet').trigger('click')
    await flush()
    await items[0]?.trigger('click')
    await flush()
    expect(wrapper.find('#newSkillTitle').text()).toBe('新建 skill')
    wrapper.unmount()
  })

  it('draws no group rule when a group has nothing in it', () => {
    // A 浏览 caller sees 查看文件/查看改动 and no lifecycle action — the design skips the empty
    // group rather than drawing a hairline under the last item.
    const wrapper = mount(VersionRow, {
      props: {
        version: LIVE,
        namespace: 'demo-user',
        name: 'pdf-tools',
        rung: 'browse',
        username: 'demo-user',
        listQuery: '',
        hasBaseline: true,
      },
    })
    expect(wrapper.findAll('.menu-group')).toHaveLength(1)
    wrapper.unmount()
  })

  it('gives focus back to 新建 skill ▾ when a sheet closes', async () => {
    const wrapper = await showSkills(() => json(200, { skills: [] }), '/', true)
    await wrapper.findAll('.menu-list .menu-item')[1]?.trigger('click')
    await flush()
    await wrapper.find('.confirm-acts .button.is-quiet').trigger('click')
    await flush()

    expect(wrapper.find('#importTitle').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.find('.menu-button').element)
    wrapper.unmount()
  })

  it('hides the list tabs on an empty live list, and keeps them on 已删除', async () => {
    // The design's `empty` state hides the switching row; the deleted list keeps it, because
    // hiding it there would leave no way back to 在用.
    const live = await showSkills(() => json(200, { skills: [] }))
    expect(live.find('.list-tabs').exists()).toBe(false)
    live.unmount()

    const deleted = await showSkills(() => json(200, { skills: [] }), '/?show=deleted')
    expect(deleted.find('.list-tabs').exists()).toBe(true)
    deleted.unmount()
  })

  it('is drawn on both lists, and not while the list is loading or failed', async () => {
    // The header is the page's, not the list's: the design gates the button on `ready` alone, so
    // the deleted tab draws it too.
    const deleted = await showSkills(() => json(200, { skills: [] }), '/?show=deleted')
    expect(deleted.find('.menu-button').exists()).toBe(true)
    deleted.unmount()

    const failed = await showSkills(() => json(500, errorBody('internal_error', 'boom')))
    expect(failed.find('.menu-button').exists()).toBe(false)
    failed.unmount()
  })
})

/* --------------------------------------------------------------------- the import sheet */

describe('the import sheet', () => {
  it('sends the package as multipart and lands on the new draft', async () => {
    const stub = stubFetch(() => json(201, submitted()))
    const wrapper = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
    })

    const accept = wrapper.findAll('.confirm-acts .button').at(-1)
    expect(accept?.attributes('disabled')).toBeDefined()

    await chooseFile(wrapper, 'pdf-tools.zip')
    await wrapper.find('.confirm-input').setValue('第二版')
    expect(accept?.attributes('disabled')).toBeUndefined()

    await accept?.trigger('click')
    await flush()

    const request = stub.requests[0] as RecordedRequest
    expect(request.path).toBe('/web/skills/demo-user/pdf-tools/versions/import')
    expect(request.method).toBe('POST')
    const form = request.body as FormData
    expect(form).toBeInstanceOf(FormData)
    expect((form.get('file') as File).name).toBe('pdf-tools.zip')
    expect(form.get('name')).toBe('第二版')
    // An empty note is not sent at all — a blank field is a missing one, not a fallback.
    expect(form.get('note')).toBeNull()
    // The **version page**, not the language-less `@pin` path: that one opens 信息.
    expect(wrapper.emitted('navigate')?.[0]).toEqual([
      '/skills/demo-user/pdf-tools?tab=versions&version=d1',
    ])
    wrapper.unmount()
  })

  it('keeps the sheet open on a replay and says which version already holds it', async () => {
    stubFetch(() =>
      json(200, submitted({ created: false }, { state: 'published', name: '1.0.0', draft_id: null })),
    )
    const wrapper = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
    })

    await chooseFile(wrapper, 'pdf-tools.zip')
    await wrapper.find('.confirm-input').setValue('第二版')
    await wrapper.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()

    expect(wrapper.emitted('navigate')).toBeUndefined()
    const result = wrapper.find('.confirm-result')
    expect(result.text()).toContain('这个 skill 已经有一版同样的内容（@1.0.0）；没有新建。')
    expect(result.find('a').text()).toBe('查看 @1.0.0')
    expect(result.find('a').attributes('href')).toBe(
      '/skills/demo-user/pdf-tools?tab=versions&version=1.0.0',
    )
    wrapper.unmount()
  })

  it('says the replayed version can no longer be read, and gives no link to it', async () => {
    stubFetch(() => json(200, submitted({ created: false }, { state: 'discarded', draft_id: 'd9' })))
    const wrapper = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
    })

    await chooseFile(wrapper, 'pdf-tools.zip')
    await wrapper.find('.confirm-input').setValue('第二版')
    await wrapper.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()

    expect(wrapper.find('.confirm-result').text()).toBe(
      '这个 skill 已经有一版同样的内容；那一版现在读不到。',
    )
    expect(wrapper.find('.confirm-result a').exists()).toBe(false)
    wrapper.unmount()
  })

  it('writes a draft hit as its bare version code, not as an @-address', async () => {
    stubFetch(() => json(200, submitted({ created: false }, { state: 'draft', draft_id: '7K3M9QX2' })))
    const wrapper = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
    })

    await chooseFile(wrapper, 'pdf-tools.zip')
    await wrapper.find('.confirm-input').setValue('第二版')
    await wrapper.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()

    const result = wrapper.find('.confirm-result')
    expect(result.text()).toContain('这个 skill 已经有一版同样的内容（7K3M9QX2）；没有新建。')
    expect(result.find('a').text()).toBe('查看 7K3M9QX2')
    expect(result.find('a').attributes('href')).toBe(
      '/skills/demo-user/pdf-tools?tab=versions&version=7K3M9QX2',
    )
    wrapper.unmount()
  })

  it('links a replayed revoked version only for the rung that can read it', async () => {
    const revoked = { state: 'revoked', name: '1.0.0', draft_id: null }
    stubFetch(
      () => json(200, submitted({ created: false }, revoked)),
      () => json(200, submitted({ created: false }, revoked)),
    )

    const write = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
    })
    await chooseFile(write, 'pdf-tools.zip')
    await write.find('.confirm-input').setValue('第二版')
    await write.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()
    expect(write.find('.confirm-result a').exists()).toBe(false)
    write.unmount()

    const maintain = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'maintain' },
    })
    await chooseFile(maintain, 'pdf-tools.zip')
    await maintain.find('.confirm-input').setValue('第二版')
    await maintain.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()
    expect(maintain.find('.confirm-result a').text()).toBe('查看 @1.0.0')
    maintain.unmount()
  })

  it('names both sides when the package’s name does not match the address', async () => {
    stubFetch(() =>
      json(
        400,
        errorBody('invalid_upload', 'the upload was refused', [
          { field: 'pdf-tools', issue: 'name_mismatch' },
          { field: 'address_name', issue: 'pdf-tools' },
          { field: 'content_name', issue: 'split' },
        ]),
      ),
    )
    const wrapper = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
    })

    await chooseFile(wrapper, 'split.zip')
    await wrapper.find('.confirm-input').setValue('第二版')
    await wrapper.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()

    expect(wrapper.find('.confirm-error').text()).toContain(
      '地址是 pdf-tools，SKILL.md 里写的是 split。把 SKILL.md 的 name 改成 pdf-tools。',
    )
    wrapper.unmount()
  })

  it('shows the server’s own reason for a refusal the design has no sentence for yet', async () => {
    // Q7: the folder-name mismatch has no sentence in the design. Falling back to this file's
    // generic `invalid_upload` line would say "size or frontmatter", which is not what happened.
    const said = "the skill's directory is named 'pdf-tools' but its frontmatter says name: split"
    stubFetch(() =>
      json(
        400,
        errorBody('invalid_upload', said, [
          { field: 'name', issue: 'name_does_not_match_directory' },
        ]),
      ),
    )
    const wrapper = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
    })

    await chooseFile(wrapper, 'split.zip')
    await wrapper.find('.confirm-input').setValue('第二版')
    await wrapper.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()

    expect(wrapper.find('.confirm-error').text()).toBe(said)
    wrapper.unmount()
  })

  it('uses the design’s own sentence when the package has no root SKILL.md', async () => {
    // The design draws this refusal as a fixed sentence on its import-error screen, so it is not a
    // Q7 code: the server's English line must never reach the sheet.
    stubFetch(() =>
      json(
        400,
        errorBody('invalid_upload', 'the skill has no SKILL.md at its root', [
          { field: 'SKILL.md', issue: 'missing_skill_md' },
        ]),
      ),
    )
    const wrapper = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
    })

    await chooseFile(wrapper, 'pdf-tools.zip')
    await wrapper.find('.confirm-input').setValue('第二版')
    await wrapper.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()

    expect(wrapper.find('.confirm-error').text()).toBe('压缩包里没有 SKILL.md。要把它放在压缩包根部。')
    wrapper.unmount()
  })

  it('turns every unsafe-path issue into the design’s one sentence', async () => {
    for (const issue of ['absolute_path', 'path_traversal', 'empty_path_segment']) {
      stubFetch(() =>
        json(400, errorBody('invalid_upload', 'unsafe path', [{ field: 'a/../b', issue }])),
      )
      const wrapper = mount(ImportDialog, {
        props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
      })

      await chooseFile(wrapper, 'pdf-tools.zip')
      await wrapper.find('.confirm-input').setValue('第二版')
      await wrapper.findAll('.confirm-acts .button').at(-1)?.trigger('click')
      await flush()

      expect(wrapper.find('.confirm-error').text(), issue).toBe(
        '压缩包里的路径不合法：不能有绝对路径、`..` 或空段。',
      )
      wrapper.unmount()
    }
  })

  it('stops the note at the design’s 200 code points, as the demo does', async () => {
    const wrapper = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
    })

    const note = wrapper.findAll('.confirm-input').at(1)
    await note?.setValue('好'.repeat(201))
    expect((note?.element as HTMLInputElement).value).toBe('好'.repeat(200))
    wrapper.unmount()
  })

  it('closes on Esc, and disables both buttons while the upload is in flight', async () => {
    let release: ((response: Response) => void) | undefined
    stubFetch(
      () =>
        new Promise<Response>((resolve) => {
          release = resolve
        }),
    )
    const wrapper = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
      attachTo: document.body,
    })

    await chooseFile(wrapper, 'pdf-tools.zip')
    await wrapper.find('.confirm-input').setValue('第二版')
    await wrapper.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()

    const buttons = wrapper.findAll('.confirm-acts .button')
    expect(buttons.every((button) => button.attributes('disabled') !== undefined)).toBe(true)
    expect(buttons[1]?.text()).toBe('正在导入…')

    // Esc during the request is the same answer as 取消: nothing happens until it lands.
    await wrapper.find('.confirm').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toBeUndefined()

    release?.(json(201, submitted()))
    await flush()
    wrapper.unmount()
  })

  it('closes on Esc when it is not waiting on anything', async () => {
    const wrapper = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
    })
    await wrapper.find('.confirm').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('does not tell an importer to fill in fields the sheet does not have', async () => {
    // The package's own SKILL.md is missing `name`; `required_field_missing` means something else
    // on this sheet, so the server's sentence is shown instead of the create form's.
    const said = "SKILL.md's frontmatter has no 'name'"
    stubFetch(() =>
      json(400, errorBody('invalid_upload', said, [{ field: 'name', issue: 'required_field_missing' }])),
    )
    const wrapper = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
    })

    await chooseFile(wrapper, 'pdf-tools.zip')
    await wrapper.find('.confirm-input').setValue('第二版')
    await wrapper.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()

    expect(wrapper.find('.confirm-error').text()).toBe(said)
    expect(wrapper.find('.confirm-error').text()).not.toContain('名字和描述都要填')
    wrapper.unmount()
  })

  it('shows the size sentence for the multipart limit, which arrives as a detail', async () => {
    stubFetch(() =>
      json(
        413,
        errorBody('invalid_upload', 'the request body is too large to be accepted', [
          { field: 'file', issue: 'body_too_large' },
        ]),
      ),
    )
    const wrapper = mount(ImportDialog, {
      props: { open: true, mode: 'version', namespace: 'demo-user', name: 'pdf-tools', rung: 'write' },
    })

    await chooseFile(wrapper, 'big.zip')
    await wrapper.find('.confirm-input').setValue('第二版')
    await wrapper.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()

    expect(wrapper.find('.confirm-error').text()).toBe('压缩包太大，服务器没有接收。压小一点再试。')
    wrapper.unmount()
  })

  it('gives the create sheet a network sentence that does not mention a file', async () => {
    stubFetch(() => {
      throw new TypeError('fetch failed')
    })
    const wrapper = mount(NewSkillDialog, { props: { open: true, namespace: 'demo-user' } })

    const inputs = wrapper.findAll('.confirm-input')
    await inputs[0]?.setValue('pdf-tools')
    await inputs[1]?.setValue('描述')
    await wrapper.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()

    const text = wrapper.find('.confirm-error').text()
    expect(text).toBe('网络请求失败，请检查网络后重试。')
    expect(text).not.toContain('文件没有导入')
    wrapper.unmount()
  })

  it('offers 去恢复 when the name belongs to a deleted skill', async () => {
    stubFetch(() => json(409, errorBody('skill_deleted', 'that name belongs to a deleted skill')))
    const wrapper = mount(ImportDialog, {
      props: { open: true, mode: 'skill', namespace: '', name: '', rung: 'owner' },
    })

    await chooseFile(wrapper, 'pdf-tools.zip')
    await wrapper.find('.confirm-input').setValue('第二版')
    await wrapper.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()

    expect(wrapper.find('.confirm-error').text()).toContain('这个名字属于一个已删除的 skill。')
    expect(wrapper.find('.confirm-error a').text()).toBe('去恢复')
    expect(wrapper.find('.confirm-error a').attributes('href')).toBe('/skills?show=deleted')
    wrapper.unmount()
  })
})

/* ------------------------------------------------------------------- the create sheet */

describe('the new-skill sheet', () => {
  it('creates the skill and its first draft, then opens the editor on it', async () => {
    const stub = stubFetch(() => json(201, submitted({ skill_created: true }, { draft_id: 'k1' })))
    const wrapper = mount(NewSkillDialog, { props: { open: true, namespace: 'demo-user' } })

    const accept = wrapper.findAll('.confirm-acts .button').at(-1)
    expect(accept?.attributes('disabled')).toBeDefined()

    const inputs = wrapper.findAll('.confirm-input')
    expect((inputs[2]?.element as HTMLInputElement).value).toBe('第一版')
    await inputs[0]?.setValue('pdf-tools')
    await inputs[1]?.setValue('把 PDF 拆开再拼起来')
    expect(accept?.attributes('disabled')).toBeUndefined()

    await accept?.trigger('click')
    await flush()

    const request = stub.requests[0] as RecordedRequest
    expect(request.path).toBe('/web/skills')
    expect(request.body).toEqual({
      name: 'pdf-tools',
      description: '把 PDF 拆开再拼起来',
      version_name: '第一版',
    })
    expect(wrapper.emitted('navigate')?.[0]).toEqual([
      '/skills/demo-user/pdf-tools@k1/edit',
    ])
    wrapper.unmount()
  })

  it('leads to the skill that already has the name', async () => {
    stubFetch(() => json(409, errorBody('skill_already_exists', 'that name is taken')))
    const wrapper = mount(NewSkillDialog, { props: { open: true, namespace: 'demo-user' } })

    const inputs = wrapper.findAll('.confirm-input')
    await inputs[0]?.setValue('pdf-tools')
    await inputs[1]?.setValue('把 PDF 拆开再拼起来')
    await wrapper.findAll('.confirm-acts .button').at(-1)?.trigger('click')
    await flush()

    expect(wrapper.find('.confirm-error').text()).toContain('这个名字已经有了。')
    expect(wrapper.find('.confirm-error a').text()).toBe('查看 pdf-tools')
    expect(wrapper.find('.confirm-error a').attributes('href')).toBe(
      '/skills/demo-user/pdf-tools',
    )
    wrapper.unmount()
  })
})

/* ------------------------------------------------------------------ the download link */

describe('the archive link', () => {
  it('sits between 查看文件 and 编辑文件, and is one GET the browser makes itself', () => {
    stubLocation('/skills/demo-user/pdf-tools', '?tab=versions&version=d1')
    const wrapper = mount(VersionDetail, {
      props: {
        namespace: 'demo-user',
        name: 'pdf-tools',
        version: DRAFT,
        versions: [LIVE, DRAFT],
        rung: 'write',
        username: 'demo-user',
        live: LIVE,
        route: { version: 'd1', fq: '', list: false, filter: 'all', q: '' },
      },
    })

    const acts = wrapper.findAll('.vp-head-act a')
    expect(acts.map((item) => item.text())).toEqual(['查看文件', '下载', '编辑文件'])
    expect(acts[1]?.attributes('href')).toBe('/web/skills/demo-user/pdf-tools@d1/archive')
    expect(acts[1]?.attributes('target')).toBeUndefined()
    wrapper.unmount()
  })

  it('is absent exactly where 查看文件 is absent', () => {
    stubLocation('/skills/demo-user/pdf-tools', '?tab=versions&version=d9')
    const wrapper = mount(VersionDetail, {
      props: {
        namespace: 'demo-user',
        name: 'pdf-tools',
        version: DISCARDED,
        versions: [LIVE, DISCARDED],
        rung: 'maintain',
        username: 'demo-user',
        live: LIVE,
        route: { version: 'd9', fq: '', list: false, filter: 'all', q: '' },
      },
    })
    expect(wrapper.text()).not.toContain('下载')
    expect(wrapper.text()).not.toContain('查看文件')
    wrapper.unmount()
  })
})

/* ------------------------------------------- the file table's actions (2026-10-21) */

describe('the file table’s actions', () => {
  it('puts 下载 and 新建版本 at the row’s right end, download first and as a direct link', async () => {
    const { wrapper } = await showSkill(SKILL, { query: '?tab=files' })

    const actions = wrapper.find('.skill-version-actions')
    expect(actions.findAll('.button').map((node) => node.text())).toEqual(['下载', '新建版本'])

    const download = actions.find('a.button')
    expect(download.attributes('href')).toBe(
      '/web/skills/demo-user/pdf-tools@1.0.0/archive',
    )
    expect(download.classes()).toContain('is-quiet')

    // Only the text: no plus, no caret. `aria-haspopup` is what still says it opens a menu.
    const create = actions.find('button.menu-button')
    expect(create.text()).toBe('新建版本')
    expect(create.find('.button-caret').exists()).toBe(false)
    expect(create.find('svg').exists()).toBe(false)
    expect(create.classes()).toContain('is-compact')
    expect(create.attributes('aria-haspopup')).toBe('menu')
    wrapper.unmount()
  })

  it('takes the version on screen, not the live one', async () => {
    // A pinned draft is what the files belong to, so the package is that draft's — not @1.0.0.
    const { wrapper } = await showSkill(SKILL, { query: '?tab=files', version: 'd1' })

    expect(wrapper.findAll('.files li')).not.toHaveLength(0)
    expect(wrapper.find('.skill-version-actions a.button').attributes('href')).toBe(
      '/web/skills/demo-user/pdf-tools@d1/archive',
    )
    wrapper.unmount()
  })

  it('offers no download when the pinned version has no files on screen', async () => {
    const discarded = { ...SKILL, versions: [LIVE, DISCARDED], version: DISCARDED }
    const { wrapper } = await showSkill(discarded, { query: '?tab=files', version: 'd9' })

    // The pinned row is an identity, not a snapshot: the file area is empty, so there is no package.
    expect(wrapper.find('.skill-version-actions a.button').exists()).toBe(false)
    // 新建版本 belongs to the rung, not to the pin, so `maintain` keeps it.
    expect(wrapper.find('.skill-version-actions button.menu-button').exists()).toBe(true)
    wrapper.unmount()
  })

  it('draws no 新建版本 for a rung that cannot write', async () => {
    const { wrapper } = await showSkill({ ...SKILL, rung: 'browse' }, { query: '?tab=files' })

    expect(wrapper.find('.skill-version-actions a.button').attributes('href')).toBe(
      '/web/skills/demo-user/pdf-tools@1.0.0/archive',
    )
    expect(wrapper.find('.skill-version-actions button.menu-button').exists()).toBe(false)
    wrapper.unmount()
  })

  it('opens the same menu, and gives focus back when the sheet it opened closes', async () => {
    const { wrapper } = await showSkill(SKILL, { query: '?tab=files', attach: true })

    const trigger = wrapper.find('.skill-version-actions .menu-button')
    await trigger.trigger('click')
    // The list is always in the DOM; `aria-expanded` is what proves the trigger actually opened it.
    expect(trigger.attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('.skill-version-actions .menu-item').map((item) => item.text())).toEqual([
      '在线编辑',
      '导入压缩包',
    ])

    await wrapper.findAll('.skill-version-actions .menu-item')[1]?.trigger('click')
    await flush()
    // The sheet is the page's own: its title says which entry this was.
    expect(wrapper.find('.confirm .confirm-title').text()).toBe('导入新版本')

    await wrapper.find('.confirm').trigger('keydown', { key: 'Escape' })
    await flush()
    // The design returns focus to whichever trigger opened the sheet; rAF runs after flush().
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(document.activeElement).toBe(wrapper.find('.skill-version-actions .menu-button').element)
    wrapper.unmount()
  })
})

/* --------------------------------------------------------------- the file tab, discarded */

describe('the file tab with a discarded pin', () => {
  it('draws no files, does not fall back to the live version, and pins the row disabled', async () => {
    const skill = { ...SKILL, versions: [LIVE, DISCARDED], version: DISCARDED }
    const { wrapper } = await showSkill(skill, { query: '?tab=files', version: 'd9' })

    // No fallback: the live version has two files and none of them is drawn.
    expect(wrapper.findAll('.files li')).toHaveLength(0)
    expect(wrapper.find('.file-open').exists()).toBe(false)

    const option = wrapper.find('.version-picker [role="option"][data-value="d9"]')
    expect(option.attributes('disabled')).toBeDefined()
    expect(option.attributes('aria-selected')).toBe('true')
    expect(wrapper.find('.version-picker .menu-trigger-text').text()).toBe('废弃版')

    // The head still names the version whose files are missing, and links back to it.
    const commit = wrapper.find('.commit-version')
    expect(commit.text()).toContain('废弃版')
    expect(commit.attributes('href')).toBe(
      '/skills/demo-user/pdf-tools?tab=versions&version=d9',
    )
    wrapper.unmount()
  })

  it('still draws a readable version’s files', async () => {
    const { wrapper } = await showSkill(SKILL, { query: '?tab=files', version: '1.0.0' })

    expect(wrapper.findAll('.files li').map((item) => item.text())).toEqual([
      expect.stringContaining('references/'),
      expect.stringContaining('SKILL.md'),
    ])
    expect(wrapper.find('.version-picker [role="option"][data-value="1.0.0"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })
})

/* -------------------------------------------------------------------------- helpers */

async function chooseFile(wrapper: VueWrapper, filename: string): Promise<void> {
  const input = wrapper.find('input[type="file"]')
  const file = new File(['PK'], filename, { type: 'application/zip' })
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
}

async function showSkills(
  listResponse: () => Response,
  url = '/',
  attach = false,
): Promise<VueWrapper> {
  const [path, query] = url.split('?')
  stubLocation(path ?? '/', query === undefined ? '' : `?${query}`)
  stubFetch(listResponse)
  vi.resetModules()
  const { default: SkillsPage } = await import('../src/pages/SkillsPage.vue')
  const wrapper = attach ? mount(SkillsPage, { attachTo: document.body }) : mount(SkillsPage)
  await flush()
  return wrapper
}

async function showSkill(
  skill: Record<string, unknown>,
  options: { query?: string; version?: string; attach?: boolean } = {},
): Promise<{ wrapper: VueWrapper; requests: RecordedRequest[] }> {
  stubLocation('/skills/demo-user/pdf-tools', options.query ?? '')
  const stub = stubFetch(() => json(200, skill))
  vi.resetModules()
  const { default: SkillPage } = await import('../src/pages/SkillPage.vue')
  const wrapper = mount(SkillPage, {
    props: {
      namespace: 'demo-user',
      name: 'pdf-tools',
      ...(options.version === undefined ? {} : { version: options.version }),
    },
    ...(options.attach === true ? { attachTo: document.body } : {}),
  })
  await flush()
  return { wrapper, requests: stub.requests }
}
