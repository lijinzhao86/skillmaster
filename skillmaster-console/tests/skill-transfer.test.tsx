import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
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
import ImportDialog from '../src/components/skill/ImportDialog'
import NewSkillDialog from '../src/components/skill/NewSkillDialog'
import VersionDetail from '../src/components/version/VersionDetail'
import VersionFilterBar from '../src/components/version/VersionFilterBar'
import VersionRow from '../src/components/version/VersionRow'
import SkillsPage from '../src/pages/SkillsPage'
import SkillPage from '../src/pages/SkillPage'

/**
 * The v9 transfer surfaces: the two menus that grew a second source, the two sheets, the archive
 * link, and the file tab's one new state.
 *
 * What is asserted is the protocol and what is *not* drawn: which address a sheet posts to, which
 * form fields the package travels in, which version a discarded address may point files at (none),
 * and which sentences the design owns (the idempotent replay) are copied whole.
 */

/** The element's text, trimmed; `''` when it is not there (`Wrapper.text()`). */
const text = (element: Element | null): string => (element?.textContent ?? '').trim()

/** The attribute, or `undefined` when it is not there (`Wrapper.attributes()`). */
const attr = (element: Element | null, name: string): string | undefined =>
  element?.getAttribute(name) ?? undefined

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
    const onOpenImport = vi.fn()
    const { container } = render(
      <VersionFilterBar
        versions={[LIVE, DRAFT]}
        filter="all"
        query=""
        rung="write"
        newHref="/skills/demo-user/pdf-tools/new"
        onUpdateFilter={() => undefined}
        onUpdateQuery={() => undefined}
        onOpenImport={onOpenImport}
      />,
    )

    expect(attr(container.querySelector('.version-create'), 'aria-haspopup')).toBe('menu')
    const items = [...container.querySelectorAll('.version-toolbar .menu-item')]
    expect(items.map((item) => text(item))).toEqual(['在线编辑', '导入压缩包'])
    expect(attr(items[0] ?? null, 'href')).toBe('/skills/demo-user/pdf-tools/new')

    if (items[1] !== undefined) {
      fireEvent.click(items[1])
    }
    expect(onOpenImport).toHaveBeenCalledTimes(1)
  })

  it('reaches the second entry with the arrow keys', async () => {
    // The list sits inside the trigger's span; before `.stop` on its keydown, every arrow press
    // also ran the trigger's handler and reopened the menu on its first item.
    const { container } = render(
      <VersionFilterBar
        versions={[LIVE, DRAFT]}
        filter="all"
        query=""
        rung="write"
        newHref="/skills/demo-user/pdf-tools/new"
        onUpdateFilter={() => undefined}
        onUpdateQuery={() => undefined}
        onOpenImport={() => undefined}
      />,
    )

    fireEvent.click(container.querySelector('.version-create') as HTMLElement)
    // The first item's focus happens inside `requestAnimationFrame`, which jsdom runs on its own
    // timer — one macrotask is not enough to reach it.
    await new Promise((resolve) => setTimeout(resolve, 20))
    const items = [...container.querySelectorAll('.version-toolbar .menu-item')]
    expect(document.activeElement).toBe(items[0])

    items[0]?.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    await flush()
    expect(document.activeElement).toBe(items[1])

    // ArrowUp from the first entry leaves the menu, and the trigger has focus again.
    ;(items[0] as HTMLElement | undefined)?.focus()
    items[0]?.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }))
    await flush()
    expect(document.activeElement).toBe(container.querySelector('.version-create'))
  })

  it('draws no entry for a rung that cannot submit', () => {
    const { container } = render(
      <VersionFilterBar
        versions={[LIVE]}
        filter="all"
        query=""
        rung="browse"
        newHref="/skills/demo-user/pdf-tools/new"
        onUpdateFilter={() => undefined}
        onUpdateQuery={() => undefined}
        onOpenImport={() => undefined}
      />,
    )
    expect(container.querySelector('.version-create')).toBeNull()
  })

  it('opens the import and the create sheets from the skills list', async () => {
    const { container } = await showSkills(() => json(200, EMPTY_LIBRARY))
    const items = [...container.querySelectorAll('.menu-list .menu-item')]
    expect(items.map((item) => text(item))).toEqual(['在线编辑', '导入本地 skill'])

    if (items[1] !== undefined) {
      fireEvent.click(items[1])
    }
    await flush()
    expect(text(container.querySelector('#importTitle'))).toBe('导入 skill')

    fireEvent.click(
      container.querySelector('.confirm-acts .button.is-quiet') as HTMLButtonElement,
    )
    await flush()
    if (items[0] !== undefined) {
      fireEvent.click(items[0])
    }
    await flush()
    expect(text(container.querySelector('#newSkillTitle'))).toBe('新建 skill')
  })

  it('draws no group rule when a group has nothing in it', () => {
    // A 浏览 caller sees 查看文件/查看改动 and no lifecycle action — the design skips the empty
    // group rather than drawing a hairline under the last item.
    const { container } = render(
      <VersionRow
        version={LIVE}
        namespace="demo-user"
        name="pdf-tools"
        rung="browse"
        username="demo-user"
        listQuery=""
        hasBaseline
      />,
    )
    expect([...container.querySelectorAll('.menu-group')]).toHaveLength(1)
  })

  it('gives focus back to 新建 skill ▾ when a sheet closes', async () => {
    const { container } = await showSkills(() => json(200, EMPTY_LIBRARY), '/')
    const items = [...container.querySelectorAll('.menu-list .menu-item')]
    if (items[1] !== undefined) {
      fireEvent.click(items[1])
    }
    await flush()
    fireEvent.click(
      container.querySelector('.confirm-acts .button.is-quiet') as HTMLButtonElement,
    )
    await flush()
    // The focus goes back on the next frame (Vue's `nextTick`; React's rAF).
    await new Promise((resolve) => setTimeout(resolve, 20))

    expect(container.querySelector('#importTitle')).toBeNull()
    expect(document.activeElement).toBe(container.querySelector('.menu-button'))
  })

  it('hides the list tabs only when the library itself is empty', async () => {
    // v14: 「库为空 = 没有任何条目」 is a state of its own, and it hides the switching row. A
    // library that has entries but nothing deleted keeps the row, so the way back to 在用 stays.
    const live = await showSkills(() => json(200, EMPTY_LIBRARY))
    expect(live.container.querySelector('.list-tabs')?.hasAttribute('hidden')).toBe(true)

    const deleted = await showSkills(() => json(200, DELETED_LIBRARY), '/?show=deleted')
    expect(deleted.container.querySelector('.list-tabs')?.hasAttribute('hidden')).toBe(false)
  })

  it('is drawn on both lists, and not while the list is loading or failed', async () => {
    // The header is the page's, not the list's: the design gates the button on `ready` alone, so
    // the deleted tab draws it too.
    const deleted = await showSkills(() => json(200, DELETED_LIBRARY), '/?show=deleted')
    expect(deleted.container.querySelector('.menu-button')).not.toBeNull()

    const failed = await showSkills(() => json(500, errorBody('internal_error', 'boom')))
    expect(failed.container.querySelector('.menu-button')).toBeNull()
  })
})

/* --------------------------------------------------------------------- the import sheet */

describe('the import sheet', () => {
  it('sends the package as multipart and lands on the new draft', async () => {
    const stub = stubFetch(() => json(201, submitted()))
    const onNavigate = vi.fn()
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={() => undefined}
        onNavigate={onNavigate}
      />,
    )

    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    expect(attr(accept ?? null, 'disabled')).toBeDefined()

    await chooseFile(container, 'pdf-tools.zip')
    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    expect(attr(accept ?? null, 'disabled')).toBeUndefined()

    if (accept !== undefined) {
      fireEvent.click(accept)
    }
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
    expect(onNavigate).toHaveBeenNthCalledWith(1, 
      '/skills/demo-user/pdf-tools?tab=versions&version=d1',
    )
  })

  it('keeps the sheet open on a replay and says which version already holds it', async () => {
    stubFetch(() =>
      json(200, submitted({ created: false }, { state: 'published', name: '1.0.0', draft_id: null })),
    )
    const onNavigate = vi.fn()
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={() => undefined}
        onNavigate={onNavigate}
      />,
    )

    await chooseFile(container, 'pdf-tools.zip')
    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    expect(onNavigate).not.toHaveBeenCalled()
    const result = container.querySelector('.confirm-result')
    expect(text(result)).toContain('这个 skill 已经有一版同样的内容（@1.0.0）；没有新建。')
    expect(text(result?.querySelector('a') ?? null)).toBe('查看 @1.0.0')
    expect(attr(result?.querySelector('a') ?? null, 'href')).toBe(
      '/skills/demo-user/pdf-tools?tab=versions&version=1.0.0',
    )
  })

  it('says the replayed version can no longer be read, and gives no link to it', async () => {
    stubFetch(() => json(200, submitted({ created: false }, { state: 'discarded', draft_id: 'd9' })))
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={() => undefined}
        onNavigate={() => undefined}
      />,
    )

    await chooseFile(container, 'pdf-tools.zip')
    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    expect(text(container.querySelector('.confirm-result'))).toBe(
      '这个 skill 已经有一版同样的内容；那一版现在读不到。',
    )
    expect(container.querySelector('.confirm-result a')).toBeNull()
  })

  it('writes a draft hit as its bare version code, not as an @-address', async () => {
    stubFetch(() => json(200, submitted({ created: false }, { state: 'draft', draft_id: '7K3M9QX2' })))
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={() => undefined}
        onNavigate={() => undefined}
      />,
    )

    await chooseFile(container, 'pdf-tools.zip')
    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    const result = container.querySelector('.confirm-result')
    expect(text(result)).toContain('这个 skill 已经有一版同样的内容（7K3M9QX2）；没有新建。')
    expect(text(result?.querySelector('a') ?? null)).toBe('查看 7K3M9QX2')
    expect(attr(result?.querySelector('a') ?? null, 'href')).toBe(
      '/skills/demo-user/pdf-tools?tab=versions&version=7K3M9QX2',
    )
  })

  it('links a replayed revoked version only for the rung that can read it', async () => {
    const revoked = { state: 'revoked', name: '1.0.0', draft_id: null }
    stubFetch(
      () => json(200, submitted({ created: false }, revoked)),
      () => json(200, submitted({ created: false }, revoked)),
    )

    const write = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={() => undefined}
        onNavigate={() => undefined}
      />,
    )
    await chooseFile(write.container, 'pdf-tools.zip')
    fireEvent.change(write.container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const writeAccept = [...write.container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (writeAccept !== undefined) {
      fireEvent.click(writeAccept)
    }
    await flush()
    expect(write.container.querySelector('.confirm-result a')).toBeNull()

    const maintain = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="maintain"
        onClose={() => undefined}
        onNavigate={() => undefined}
      />,
    )
    await chooseFile(maintain.container, 'pdf-tools.zip')
    fireEvent.change(maintain.container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const maintainAccept = [...maintain.container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (maintainAccept !== undefined) {
      fireEvent.click(maintainAccept)
    }
    await flush()
    expect(text(maintain.container.querySelector('.confirm-result a'))).toBe('查看 @1.0.0')
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
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={() => undefined}
        onNavigate={() => undefined}
      />,
    )

    await chooseFile(container, 'split.zip')
    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    expect(text(container.querySelector('.confirm-error'))).toContain(
      '地址是 pdf-tools，SKILL.md 里写的是 split。把 SKILL.md 的 name 改成 pdf-tools。',
    )
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
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={() => undefined}
        onNavigate={() => undefined}
      />,
    )

    await chooseFile(container, 'split.zip')
    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    expect(text(container.querySelector('.confirm-error'))).toBe(said)
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
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={() => undefined}
        onNavigate={() => undefined}
      />,
    )

    await chooseFile(container, 'pdf-tools.zip')
    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    expect(text(container.querySelector('.confirm-error'))).toBe('压缩包里没有 SKILL.md。要把它放在压缩包根部。')
  })

  it('turns every unsafe-path issue into the design’s one sentence', async () => {
    for (const issue of ['absolute_path', 'path_traversal', 'empty_path_segment']) {
      stubFetch(() =>
        json(400, errorBody('invalid_upload', 'unsafe path', [{ field: 'a/../b', issue }])),
      )
      const { container } = render(
        <ImportDialog
          open
          mode="version"
          namespace="demo-user"
          name="pdf-tools"
          rung="write"
          onClose={() => undefined}
          onNavigate={() => undefined}
        />,
      )

      await chooseFile(container, 'pdf-tools.zip')
      fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
        target: { value: '第二版' },
      })
      const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
      if (accept !== undefined) {
        fireEvent.click(accept)
      }
      await flush()

      expect(text(container.querySelector('.confirm-error')), issue).toBe(
        '压缩包里的路径不合法：不能有绝对路径、`..` 或空段。',
      )
    }
  })

  it('stops the note at the design’s 200 code points, as the demo does', async () => {
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={() => undefined}
        onNavigate={() => undefined}
      />,
    )

    const note = [...container.querySelectorAll('.confirm-input')].at(1)
    if (note !== undefined) {
      fireEvent.change(note as HTMLInputElement, { target: { value: '好'.repeat(201) } })
    }
    expect((note as HTMLInputElement | undefined)?.value).toBe('好'.repeat(200))
  })

  it('closes on Esc, and disables both buttons while the upload is in flight', async () => {
    let release: ((response: Response) => void) | undefined
    stubFetch(
      () =>
        new Promise<Response>((resolve) => {
          release = resolve
        }),
    )
    const onClose = vi.fn()
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={onClose}
        onNavigate={() => undefined}
      />,
    )

    await chooseFile(container, 'pdf-tools.zip')
    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    const buttons = [...container.querySelectorAll('.confirm-acts .button')]
    expect(buttons.every((button) => attr(button, 'disabled') !== undefined)).toBe(true)
    expect(text(buttons[1] ?? null)).toBe('正在导入…')

    // Esc during the request is the same answer as 取消: nothing happens until it lands.
    fireEvent.keyDown(container.querySelector('.confirm') as HTMLElement, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()

    release?.(json(201, submitted()))
    await flush()
  })

  it('closes on Esc when it is not waiting on anything', async () => {
    const onClose = vi.fn()
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={onClose}
        onNavigate={() => undefined}
      />,
    )
    fireEvent.keyDown(container.querySelector('.confirm') as HTMLElement, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('does not tell an importer to fill in fields the sheet does not have', async () => {
    // The package's own SKILL.md is missing `name`; `required_field_missing` means something else
    // on this sheet, so the server's sentence is shown instead of the create form's.
    const said = "SKILL.md's frontmatter has no 'name'"
    stubFetch(() =>
      json(400, errorBody('invalid_upload', said, [{ field: 'name', issue: 'required_field_missing' }])),
    )
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={() => undefined}
        onNavigate={() => undefined}
      />,
    )

    await chooseFile(container, 'pdf-tools.zip')
    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    expect(text(container.querySelector('.confirm-error'))).toBe(said)
    expect(text(container.querySelector('.confirm-error'))).not.toContain('名字和描述都要填')
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
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={() => undefined}
        onNavigate={() => undefined}
      />,
    )

    await chooseFile(container, 'big.zip')
    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    expect(text(container.querySelector('.confirm-error'))).toBe('压缩包太大，服务器没有接收。压小一点再试。')
  })

  // v33 test-plan W4 / A7: a package refused for its file names points at up to three of them,
  // says what is wrong with each, and then what to do — not one generic sentence.
  it('names the files a package cannot use instead of one generic sentence', async () => {
    stubFetch(() =>
      json(
        400,
        errorBody('invalid_upload', 'the upload has file names that are not usable', [
          { field: '报告:v2.md', issue: 'windows_invalid_char' },
          { field: 'CON.md', issue: 'windows_reserved_name' },
          { field: 'A.md', issue: 'case_collision' },
          { field: 'a.md', issue: 'case_collision' },
          { field: 'notes.md ', issue: 'trailing_space_or_dot' },
        ]),
      ),
    )
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={() => undefined}
        onNavigate={() => undefined}
      />,
    )

    await chooseFile(container, 'pdf-tools.zip')
    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    // Five files, four problems: the collision line names two, so the header counts five; the one
    // file behind the cut is what 「还有」 counts.
    expect(text(container.querySelector('.confirm-error'))).toBe(
      '压缩包里有 5 个文件名不能用在所有系统上，没有导入。' +
        '报告:v2.md：冒号不能作为文件名（Windows）。' +
        'CON.md：CON 是 Windows 保留的设备名。' +
        'A.md 与 a.md：两个名字在 macOS 和 Windows 上会被当成同一个文件。' +
        '还有 1 个。' +
        '改好压缩包再选一次。',
    )
  })

  // A name that could not be decoded has no path to show; its line is the sentence alone, without
  // the colon an empty label would otherwise leave at the front.
  it('draws a nameless refusal without a leading colon', async () => {
    stubFetch(() =>
      json(
        400,
        errorBody('invalid_upload', 'the upload has file names that are not usable', [
          { field: '', issue: 'invalid_zip_name' },
          { field: 'CON.md', issue: 'windows_reserved_name' },
        ]),
      ),
    )
    const { container } = render(
      <ImportDialog
        open
        mode="version"
        namespace="demo-user"
        name="pdf-tools"
        rung="write"
        onClose={() => undefined}
        onNavigate={() => undefined}
      />,
    )

    await chooseFile(container, 'pdf-tools.zip')
    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    expect(text(container.querySelector('.confirm-error'))).toBe(
      '压缩包里有 2 个文件名不能用在所有系统上，没有导入。' +
        '有一个文件名解不出来（压缩包不是 UTF-8 编码）。' +
        'CON.md：CON 是 Windows 保留的设备名。' +
        '改好压缩包再选一次。',
    )
  })

  it('gives the create sheet a network sentence that does not mention a file', async () => {
    stubFetch(() => {
      throw new TypeError('fetch failed')
    })
    const { container } = render(<NewSkillDialog open namespace="demo-user" onClose={() => undefined} onNavigate={() => undefined} />)

    const inputs = [...container.querySelectorAll('.confirm-input')]
    if (inputs[0] !== undefined) {
      fireEvent.change(inputs[0] as HTMLInputElement, { target: { value: 'pdf-tools' } })
    }
    if (inputs[1] !== undefined) {
      fireEvent.change(inputs[1] as HTMLInputElement, { target: { value: '描述' } })
    }
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    const said = text(container.querySelector('.confirm-error'))
    expect(said).toBe('网络请求失败，请检查网络后重试。')
    expect(said).not.toContain('文件没有导入')
  })

  it('offers 去恢复 when the name belongs to a deleted skill', async () => {
    stubFetch(() => json(409, errorBody('skill_deleted', 'that name belongs to a deleted skill')))
    const { container } = render(
      <ImportDialog
        open
        mode="skill"
        namespace=""
        name=""
        rung="owner"
        onClose={() => undefined}
        onNavigate={() => undefined}
      />,
    )

    await chooseFile(container, 'pdf-tools.zip')
    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '第二版' },
    })
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    expect(text(container.querySelector('.confirm-error'))).toContain('这个名字属于一个已删除的 skill。')
    expect(text(container.querySelector('.confirm-error a'))).toBe('去恢复')
    expect(attr(container.querySelector('.confirm-error a'), 'href')).toBe('/skills?show=deleted')
  })
})

/* ------------------------------------------------------------------- the create sheet */

describe('the new-skill sheet', () => {
  it('creates the skill and its first draft, then opens the editor on it', async () => {
    const stub = stubFetch(() => json(201, submitted({ skill_created: true }, { draft_id: 'k1' })))
    const onNavigate = vi.fn()
    const { container } = render(
      <NewSkillDialog open namespace="demo-user" onClose={() => undefined} onNavigate={onNavigate} />,
    )

    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    expect(attr(accept ?? null, 'disabled')).toBeDefined()

    const inputs = [...container.querySelectorAll('.confirm-input')]
    expect((inputs[2] as HTMLInputElement | undefined)?.value).toBe('第一版')
    if (inputs[0] !== undefined) {
      fireEvent.change(inputs[0] as HTMLInputElement, { target: { value: 'pdf-tools' } })
    }
    if (inputs[1] !== undefined) {
      fireEvent.change(inputs[1] as HTMLInputElement, { target: { value: '把 PDF 拆开再拼起来' } })
    }
    expect(attr(accept ?? null, 'disabled')).toBeUndefined()

    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    const request = stub.requests[0] as RecordedRequest
    expect(request.path).toBe('/web/skills')
    expect(request.body).toEqual({
      name: 'pdf-tools',
      description: '把 PDF 拆开再拼起来',
      version_name: '第一版',
    })
    expect(onNavigate).toHaveBeenNthCalledWith(1, 
      '/skills/demo-user/pdf-tools@k1/edit',
    )
  })

  it('leads to the skill that already has the name', async () => {
    stubFetch(() => json(409, errorBody('skill_already_exists', 'that name is taken')))
    const { container } = render(
      <NewSkillDialog open namespace="demo-user" onClose={() => undefined} onNavigate={() => undefined} />,
    )

    const inputs = [...container.querySelectorAll('.confirm-input')]
    if (inputs[0] !== undefined) {
      fireEvent.change(inputs[0] as HTMLInputElement, { target: { value: 'pdf-tools' } })
    }
    if (inputs[1] !== undefined) {
      fireEvent.change(inputs[1] as HTMLInputElement, { target: { value: '把 PDF 拆开再拼起来' } })
    }
    const accept = [...container.querySelectorAll('.confirm-acts .button')].at(-1)
    if (accept !== undefined) {
      fireEvent.click(accept)
    }
    await flush()

    expect(text(container.querySelector('.confirm-error'))).toContain('这个名字已经有了。')
    expect(text(container.querySelector('.confirm-error a'))).toBe('查看 pdf-tools')
    expect(attr(container.querySelector('.confirm-error a'), 'href')).toBe(
      '/skills/demo-user/pdf-tools',
    )
  })
})

/* ------------------------------------------------------------------ the download link */

describe('the archive link', () => {
  it('sits between 查看文件 and 编辑文件, and is one GET the browser makes itself', () => {
    stubLocation('/skills/demo-user/pdf-tools', '?tab=versions&version=d1')
    const { container } = render(
      <VersionDetail
        namespace="demo-user"
        name="pdf-tools"
        version={DRAFT}
        versions={[LIVE, DRAFT]}
        rung="write"
        username="demo-user"
        live={LIVE}
        route={{ version: 'd1', fq: '', list: false, filter: 'all', q: '' }}
        onSelectBaseline={() => undefined}
      />,
    )

    const acts = [...container.querySelectorAll('.vp-head-act a')]
    expect(acts.map((item) => text(item))).toEqual(['查看文件', '下载', '编辑文件'])
    expect(attr(acts[1] ?? null, 'href')).toBe('/web/skills/demo-user/pdf-tools@d1/archive')
    expect(attr(acts[1] ?? null, 'target')).toBeUndefined()
  })

  it('is absent exactly where 查看文件 is absent', () => {
    stubLocation('/skills/demo-user/pdf-tools', '?tab=versions&version=d9')
    const { container } = render(
      <VersionDetail
        namespace="demo-user"
        name="pdf-tools"
        version={DISCARDED}
        versions={[LIVE, DISCARDED]}
        rung="maintain"
        username="demo-user"
        live={LIVE}
        route={{ version: 'd9', fq: '', list: false, filter: 'all', q: '' }}
        onSelectBaseline={() => undefined}
      />,
    )
    expect(container.textContent).not.toContain('下载')
    expect(container.textContent).not.toContain('查看文件')
  })
})

/* ------------------------------------------- the file table's actions (2026-10-21) */

describe('the file table’s actions', () => {
  it('puts 下载 and 新建版本 at the row’s right end, download first and as a direct link', async () => {
    const { container } = await showSkill(SKILL, { query: '?tab=files' })

    const actions = container.querySelector('.skill-version-actions')
    expect([...(actions?.querySelectorAll('.button') ?? [])].map((node) => text(node))).toEqual([
      '下载',
      '新建版本',
    ])

    const download = actions?.querySelector('a.button') ?? null
    expect(attr(download, 'href')).toBe(
      '/web/skills/demo-user/pdf-tools@1.0.0/archive',
    )
    expect(download?.classList.contains('is-quiet')).toBe(true)

    // Only the text: no plus, no caret. `aria-haspopup` is what still says it opens a menu.
    const create = actions?.querySelector('button.menu-button') ?? null
    expect(text(create)).toBe('新建版本')
    expect(create?.querySelector('.button-caret')).toBeNull()
    expect(create?.querySelector('svg')).toBeNull()
    expect(create?.classList.contains('is-compact')).toBe(true)
    expect(attr(create, 'aria-haspopup')).toBe('menu')
  })

  it('takes the version on screen, not the live one', async () => {
    // A pinned draft is what the files belong to, so the package is that draft's — not @1.0.0.
    const { container } = await showSkill(SKILL, { query: '?tab=files', version: 'd1' })

    expect([...container.querySelectorAll('.files li')]).not.toHaveLength(0)
    expect(attr(container.querySelector('.skill-version-actions a.button'), 'href')).toBe(
      '/web/skills/demo-user/pdf-tools@d1/archive',
    )
  })

  it('offers no download when the pinned version has no files on screen', async () => {
    const discarded = { ...SKILL, versions: [LIVE, DISCARDED], version: DISCARDED }
    const { container } = await showSkill(discarded, { query: '?tab=files', version: 'd9' })

    // The pinned row is an identity, not a snapshot: the file area is empty, so there is no package.
    expect(container.querySelector('.skill-version-actions a.button')).toBeNull()
    // 新建版本 belongs to the rung, not to the pin, so `maintain` keeps it.
    expect(container.querySelector('.skill-version-actions button.menu-button')).not.toBeNull()
  })

  it('draws no 新建版本 for a rung that cannot write', async () => {
    const { container } = await showSkill({ ...SKILL, rung: 'browse' }, { query: '?tab=files' })

    expect(attr(container.querySelector('.skill-version-actions a.button'), 'href')).toBe(
      '/web/skills/demo-user/pdf-tools@1.0.0/archive',
    )
    expect(container.querySelector('.skill-version-actions button.menu-button')).toBeNull()
  })

  it('opens the same menu, and gives focus back when the sheet it opened closes', async () => {
    const { container } = await showSkill(SKILL, { query: '?tab=files' })

    const trigger = container.querySelector('.skill-version-actions .menu-button') as HTMLElement
    fireEvent.click(trigger)
    // The list is always in the DOM; `aria-expanded` is what proves the trigger actually opened it.
    expect(attr(trigger, 'aria-expanded')).toBe('true')
    expect(
      [...container.querySelectorAll('.skill-version-actions .menu-item')].map((item) => text(item)),
    ).toEqual(['在线编辑', '导入压缩包'])

    const menuItems = [...container.querySelectorAll('.skill-version-actions .menu-item')]
    if (menuItems[1] !== undefined) {
      fireEvent.click(menuItems[1])
    }
    await flush()
    // The sheet is the page's own: its title says which entry this was.
    expect(text(container.querySelector('.confirm .confirm-title'))).toBe('导入新版本')

    fireEvent.keyDown(container.querySelector('.confirm') as HTMLElement, { key: 'Escape' })
    await flush()
    // The design returns focus to whichever trigger opened the sheet; rAF runs after flush().
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(document.activeElement).toBe(
      container.querySelector('.skill-version-actions .menu-button'),
    )
  })
})

/* --------------------------------------------------------------- the file tab, discarded */

describe('the file tab with a discarded pin', () => {
  it('draws no files, does not fall back to the live version, and pins the row disabled', async () => {
    const skill = { ...SKILL, versions: [LIVE, DISCARDED], version: DISCARDED }
    const { container } = await showSkill(skill, { query: '?tab=files', version: 'd9' })

    // No fallback: the live version has two files and none of them is drawn.
    expect([...container.querySelectorAll('.files li')]).toHaveLength(0)
    expect(container.querySelector('.file-open')).toBeNull()

    const option = container.querySelector('.version-picker [role="option"][data-value="d9"]')
    expect(attr(option, 'disabled')).toBeDefined()
    expect(attr(option, 'aria-selected')).toBe('true')
    expect(text(container.querySelector('.version-picker .menu-trigger-text'))).toBe('废弃版')

    // The head still names the version whose files are missing, and links back to it.
    const commit = container.querySelector('.commit-version')
    expect(text(commit)).toContain('废弃版')
    expect(attr(commit, 'href')).toBe(
      '/skills/demo-user/pdf-tools?tab=versions&version=d9',
    )
  })

  it('still draws a readable version’s files', async () => {
    const { container } = await showSkill(SKILL, { query: '?tab=files', version: '1.0.0' })

    expect([...container.querySelectorAll('.files li')].map((item) => text(item))).toEqual([
      expect.stringContaining('references/'),
      expect.stringContaining('SKILL.md'),
    ])
    expect(
      attr(container.querySelector('.version-picker [role="option"][data-value="1.0.0"]'), 'disabled'),
    ).toBeUndefined()
  })
})

/* -------------------------------------------------------------------------- helpers */

async function chooseFile(container: HTMLElement, filename: string): Promise<void> {
  const input = container.querySelector('input[type="file"]') as HTMLInputElement
  const file = new File(['PK'], filename, { type: 'application/zip' })
  fireEvent.change(input, { target: { files: [file] } })
}

/** The two list shapes these tests need: nothing in the library, and entries but nothing deleted. */
const EMPTY_LIBRARY = {
  skills: [],
  page: 1,
  page_size: 10,
  total_entries: 0,
  effective_entries: 0,
  filtered_total: 0,
}
const DELETED_LIBRARY = { ...EMPTY_LIBRARY, total_entries: 5, effective_entries: 5 }

async function showSkills(
  listResponse: () => Response,
  url = '/',
): Promise<ReturnType<typeof render>> {
  const [path, query] = url.split('?')
  stubLocation(path ?? '/', query === undefined ? '' : `?${query}`)
  stubFetch(listResponse)
  const view = render(<SkillsPage />)
  await flush()
  return view
}

async function showSkill(
  skill: Record<string, unknown>,
  options: { query?: string; version?: string } = {},
): Promise<{ container: HTMLElement; requests: RecordedRequest[] }> {
  stubLocation('/skills/demo-user/pdf-tools', options.query ?? '')
  const stub = stubFetch(() => json(200, skill))
  const { container } = render(
    <SkillPage
      namespace="demo-user"
      name="pdf-tools"
      {...(options.version === undefined ? {} : { version: options.version })}
    />,
  )
  await flush()
  return { container, requests: stub.requests }
}
