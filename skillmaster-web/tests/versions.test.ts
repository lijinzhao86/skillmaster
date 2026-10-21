import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { flush, json, setCookie, stubFetch, stubLocation } from './support'
import type { AuthoredVersion, DiffFile } from '../src/api/types'
import { answersPin, fileAnchorId, isVersionSuffix, parseVersionRoute, versionSuffix, versionTitle } from '../src/version'
import VersionPicker from '../src/components/VersionPicker.vue'
import DiffFileBlock from '../src/components/DiffFileBlock.vue'
import VersionRow from '../src/components/version/VersionRow.vue'
import VersionFacts from '../src/components/version/VersionFacts.vue'
import VersionStatusBar from '../src/components/version/VersionStatusBar.vue'
import VersionActionPanel from '../src/components/version/VersionActionPanel.vue'
import VersionDiffPane from '../src/components/version/VersionDiffPane.vue'

/**
 * The v6 version surface, component by component.
 *
 * The assertions follow the design's own contract: the five statuses come from `status`; a draft is
 * addressed by `draft:<id>`; the list only navigates; every state-changing action opens one
 * confirmation sheet; a file's `hunks: null` has a reason (binary, too large, not fetched yet).
 */

function version(overrides: Partial<AuthoredVersion> = {}): AuthoredVersion {
  return {
    name: '1.2.3',
    digest: 'sha256:aaa',
    draft_name: 'split 范围语法',
    draft_id: null,
    title: 'split',
    note: '把 split 的范围语法写成文档。',
    submitted_at: '2026-10-07T10:12:00Z',
    submitted_by: 'dev',
    state: 'published',
    status: 'live',
    state_at: '2026-10-07T14:32:00Z',
    first_published_at: '2026-10-07T14:32:00Z',
    is_current: true,
    file_count: 3,
    total_bytes: 5734,
    revoked_reason: null,
    ...overrides,
  }
}

const DRAFT = version({
  name: null,
  digest: 'sha256:bbb',
  draft_id: 'd1',
  draft_name: 'compress 补文档',
  note: '把 compress 的用法补进正文。',
  submitted_by: 'yuhan',
  state: 'draft',
  status: 'draft',
  state_at: null,
  first_published_at: null,
  is_current: false,
})

const HISTORY = version({
  name: '1.2.2',
  digest: 'sha256:ccc',
  draft_name: 'split 拆页',
  note: '拆分时保留原文件的元数据。',
  status: 'history',
  state_at: '2026-09-28T11:05:00Z',
  first_published_at: '2026-09-28T11:05:00Z',
  is_current: false,
})

let location = stubLocation('/')

beforeEach(() => {
  location = stubLocation('/')
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/* --------------------------------------------------------------- the address */

describe('version pins and the version route', () => {
  it('addresses a row by its state: row id for a draft, name or digest once published', () => {
    expect(isVersionSuffix('01JKMNPQ')).toBe(true)
    expect(isVersionSuffix('01JKMNPQRSTVWXYZ0123456789')).toBe(true)
    // A 26-character ULID's first character is part of its timestamp and stops at 7; a shape that
    // only looks like one is not an address (the server draws the same line).
    expect(isVersionSuffix('Z0123456789ABCDEFGHJKMNPQR')).toBe(false)
    // The v6 `draft:` prefix is an input spelling the CLI keeps; the console's grammar is bare.
    expect(isVersionSuffix('draft:01JKMNPQ')).toBe(false)
    expect(isVersionSuffix('draft:')).toBe(false)

    const draft = { name: null, digest: 'sha256:aaa', state: 'draft' as const }
    expect(versionSuffix({ ...draft, code: 'K3M9QX2P' })).toBe('K3M9QX2P')
    expect(versionSuffix({ ...draft, draft_id: 'd1' })).toBe('d1')
    // A discarded row is addressed exactly as the draft it was.
    expect(versionSuffix({ ...draft, state: 'discarded' as const, code: 'K3M9QX2P' })).toBe('K3M9QX2P')

    // **The API sends `code` on every row**, so the field cannot decide the address. A published
    // version answers to its number (or its digest when it has none); its row id is only displayed.
    const published = { name: '1.2.3', digest: 'sha256:aaa', state: 'published' as const }
    expect(versionSuffix({ ...published, code: 'K3M9QX2P' })).toBe('1.2.3')
    expect(versionSuffix({ ...published, name: null, code: 'K3M9QX2P' })).toBe('sha256:aaa')
  })

  it('a published version answers to its number and digest; a draft to its row id', () => {
    const published = { name: '1.2.3', digest: 'sha256:abc', state: 'published', draft_id: null, code: 'K3M9QX2P' }
    expect(answersPin(published, '1.2.3')).toBe(true)
    expect(answersPin(published, 'sha256:abc')).toBe(true)
    expect(answersPin(published, '1.2.4')).toBe(false)
    // The version code is displayed on an author's page; it is not an address a published row
    // answers to (ADR 0063).
    expect(answersPin(published, 'K3M9QX2P')).toBe(false)

    const draft = { name: null, digest: 'sha256:abc', state: 'draft', draft_id: 'd1' }
    expect(answersPin(draft, 'd1')).toBe(true)
    expect(answersPin(draft, 'draft:d1')).toBe(false)
    expect(answersPin(draft, 'sha256:abc')).toBe(false)
  })

  it('names an old published row by its digest and leaves a nameless draft blank', () => {
    const digest = `sha256:${'0123456789abcdef'.repeat(4)}`
    expect(
      versionTitle({ name: null, draft_name: null, draft_id: null, digest }, 'browse'),
    ).toBe('@01234567')
    expect(
      versionTitle({ name: null, draft_name: null, draft_id: 'd1', digest }, 'browse'),
    ).toBe('')
  })

  it('parses the version tab’s own parameters and falls back on unknown values', () => {
    expect(
      parseVersionRoute(
        '?tab=versions&version=d1&sub=diff&from=1.2.2&fq=ref&file=SKILL.md&list=1&filter=draft&q=compress',
      ),
    ).toEqual({
      version: 'd1',
      sub: 'diff',
      from: '1.2.2',
      fq: 'ref',
      file: 'SKILL.md',
      list: true,
      filter: 'draft',
      q: 'compress',
    })
    // Settings' `sub` is not this route's; an unknown pane is simply absent.
    // An unrecognised version is kept: the single-version page answers 「没有这一版」, and dropping
    // the value here would land the reader on the list instead (v7 TD §网页).
    expect(parseVersionRoute('?sub=general&version=nope&filter=zzz')).toEqual({
      version: 'nope',
      fq: '',
      list: false,
      filter: 'all',
      q: '',
    })
  })
})

/* --------------------------------------------------------------- the picker */

describe('VersionPicker', () => {
  it('draws name + number + status and keeps an unreadable pinned row disabled', () => {
    const revoked = version({
      name: '1.2.1',
      digest: 'sha256:ddd',
      draft_name: 'compress 收进 skill',
      status: 'revoked',
      state: 'revoked',
      state_at: '2026-09-20T11:30:00Z',
      is_current: false,
    })
    const wrapper = mount(VersionPicker, {
      props: {
        versions: [version(), DRAFT, revoked],
        selected: '1.2.3',
        disabledValues: ['1.2.1'],
      },
    })

    expect(wrapper.find('.menu-trigger-text').text()).toBe('split 范围语法')
    expect(wrapper.findAll('.menu-label').map((node) => node.text())).toEqual([
      'split 范围语法',
      'compress 补文档',
      'compress 收进 skill',
    ])
    expect(wrapper.findAll('.menu-number').map((node) => node.text())).toEqual(['@1.2.3', '@1.2.1'])
    expect(wrapper.findAll('.menu-tag').map((node) => node.text())).toEqual([
      '线上',
      '草稿',
      '已下架',
    ])
    const revokedOption = wrapper.findAll('.menu-item')[2]
    expect(revokedOption?.attributes('aria-disabled')).toBe('true')
    expect((revokedOption?.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('filters by name and by version number, and emits the chosen pin', async () => {
    const wrapper = mount(VersionPicker, { props: { versions: [version(), DRAFT], selected: '1.2.3' } })

    await wrapper.find('.menu-search-input').setValue('1.2.')
    const visible = wrapper.findAll('.menu-item').filter((item) => !(item.element as HTMLElement).hidden)
    expect(visible).toHaveLength(1)
    expect(visible[0]?.text()).toContain('split 范围语法')

    await wrapper.find('.menu-search-input').setValue('compress')
    const byName = wrapper.findAll('.menu-item').filter((item) => !(item.element as HTMLElement).hidden)
    expect(byName).toHaveLength(1)
    expect(byName[0]?.text()).toContain('compress 补文档')

    await byName[0]!.trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['d1'])
  })
})

/* --------------------------------------------------------------- one file's diff */

describe('DiffFileBlock', () => {
  function file(overrides: Partial<DiffFile> = {}): DiffFile {
    return {
      relpath: 'SKILL.md',
      status: 'modified',
      binary: false,
      size: 90,
      added: 1,
      removed: 1,
      hunks: [{ header: '@@ -1,2 +1,2 @@', lines: [' # PDF', '-old', '+new'] }],
      ...overrides,
    }
  }

  it('paints added and removed lines from their own prefix', () => {
    const wrapper = mount(DiffFileBlock, { props: { file: file() } })

    expect(wrapper.find('.is-add .diff-text').text()).toBe('+new')
    expect(wrapper.find('.is-del .diff-text').text()).toBe('-old')
    expect(wrapper.find('.diff-stats').text()).toBe('+1 −1')
  })

  it('says why a binary or oversized file has no body, and never +0 −0', () => {
    const binary = mount(DiffFileBlock, {
      props: { file: file({ binary: true, added: null, removed: null, hunks: null }) },
    })
    expect(binary.find('.diff-pending').text()).toContain('二进制文件，不显示内容。')
    expect(binary.find('.diff-stats').text()).toBe('—')

    const big = mount(DiffFileBlock, {
      props: {
        file: file({ relpath: 'assets/export.csv', size: 1468006, added: null, removed: null, hunks: null }),
      },
    })
    expect(big.find('.diff-pending').text()).toContain('这个文件太大，没有展开（1.4 MB）。')
    expect(big.find('.diff-stats').text()).toBe('—')
  })

  it('draws 400 lines first and 500 more per press', async () => {
    const lines = Array.from({ length: 600 }, (_, index) => ` line ${index}`)
    const wrapper = mount(DiffFileBlock, {
      props: {
        file: file({ hunks: [{ header: '@@ -1,600 +1,600 @@', lines }] }),
        initialLimit: 400,
      },
    })

    // One `<pre>` per file, one row per line — the design's contiguous block.
    expect(wrapper.findAll('.diff-lines').length).toBe(1)
    expect(wrapper.findAll('.diff-lines > span').length).toBe(400)
    expect(wrapper.find('.diff-more').text()).toContain('还有 200 行未显示')
    await wrapper.find('.diff-more .link-button').trigger('click')
    expect(wrapper.findAll('.diff-lines > span').length).toBe(600)
    expect(wrapper.find('.diff-more').exists()).toBe(false)
  })
})

/* --------------------------------------------------------------- the row and status */

describe('VersionRow', () => {
  it('makes the whole row the name link and its menu only links', () => {
    const wrapper = mount(VersionRow, {
      props: {
        version: DRAFT,
        namespace: 'dev',
        name: 'pdf-tools',
        rung: 'owner',
        username: 'dev',
        listQuery: 'filter=draft',
        hasBaseline: true,
      },
    })

    const link = wrapper.find('.version-name')
    expect(link.attributes('href')).toBe(
      '/skills/dev/pdf-tools?tab=versions&version=d1&filter=draft',
    )
    expect(link.attributes('aria-label')).toBe('打开 compress 补文档')
    expect(wrapper.find('.version-number').text()).toBe('—')
    expect(wrapper.findAll('.version-menu .menu-item').map((item) => item.attributes('href'))).toEqual([
      '/skills/dev/pdf-tools@d1?tab=files',
      '/skills/dev/pdf-tools@d1/edit',
      '/skills/dev/pdf-tools?tab=versions&version=d1&filter=draft&sub=diff',
      '/skills/dev/pdf-tools?tab=versions&version=d1&filter=draft&sub=actions',
      '/skills/dev/pdf-tools?tab=versions&version=d1&filter=draft&sub=actions',
    ])
    expect(wrapper.findAll('.version-menu .menu-item').map((item) => item.text())).toEqual([
      '查看文件',
      '编辑',
      '查看改动',
      '上线',
      '丢弃',
    ])
    // Navigation and lifecycle actions are two groups, separated by a rule (design `.menu-group`).
    expect(wrapper.findAll('.version-menu .menu-group')).toHaveLength(2)
  })

  it('gives a caller the version number as its identity, no menu, and only the publish time', () => {
    const wrapper = mount(VersionRow, {
      props: {
        version: version({ first_published_at: '2026-10-07T14:32:00Z' }),
        namespace: 'dev',
        name: 'pdf-tools',
        rung: 'invoke',
        username: null,
        listQuery: '',
        hasBaseline: false,
      },
    })

    expect(wrapper.find('.version-name').text()).toBe('@1.2.3')
    expect(wrapper.find('.version-name').classes()).toContain('is-mono')
    expect(wrapper.find('.version-number').exists()).toBe(false)
    expect(wrapper.find('.version-meta').text()).toContain('上线于')
    expect(wrapper.find('.version-menu').exists()).toBe(false)
  })

  it('sends a published row to its number even though the row carries a version code', () => {
    const wrapper = mount(VersionRow, {
      props: {
        version: version({ code: 'K3M9QX2P' }),
        namespace: 'dev',
        name: 'pdf-tools',
        rung: 'owner',
        username: 'dev',
        listQuery: '',
        hasBaseline: true,
      },
    })
    expect(wrapper.find('.version-name').attributes('href')).toContain('version=1.2.3')
    expect(wrapper.find('.version-name').attributes('href')).not.toContain('K3M9QX2P')
  })

  it('puts the code after the note and copies it without navigating the row', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: () => Promise.resolve() },
    })
    const wrapper = mount(VersionRow, {
      props: {
        version: DRAFT,
        namespace: 'dev',
        name: 'pdf-tools',
        rung: 'write',
        username: 'dev',
        listQuery: '',
        hasBaseline: false,
      },
    })

    const sub = wrapper.find('.version-sub')
    expect(sub.find('.version-note').exists()).toBe(true)
    expect(sub.find('.version-code-slot .version-code').text()).toContain('d1')
    const html = sub.html()
    expect(html.indexOf('version-note')).toBeLessThan(html.indexOf('version-code-slot'))
    expect(html.indexOf('version-code-slot')).toBeLessThan(html.indexOf('version-meta'))

    await sub.find('.version-code .vp-copy').trigger('click')
    await flush()
    expect(wrapper.find('.version-code .vp-copy').classes()).toContain('is-done')
    expect(wrapper.find('.version-sub .visually-hidden').text()).toBe('已复制')
    // Clicking the code's button does not follow the row's link. The stacking itself (`z-index`)
    // is a rendered property jsdom does not apply; T-WEB-05 is the check for that half.
    expect(wrapper.find('.version-name').attributes('href')).toContain('version=d1')
    Reflect.deleteProperty(navigator, 'clipboard')
  })

  it('keeps the copy failure sentence instead of clearing it after a moment', async () => {
    // jsdom has neither clipboard nor execCommand, so the fallback fails and the button must say so.
    // The design only resets the success state; clearing the failure would remove the explanation.
    vi.useFakeTimers()
    const wrapper = mount(VersionRow, {
      props: {
        version: DRAFT,
        namespace: 'dev',
        name: 'pdf-tools',
        rung: 'write',
        username: 'dev',
        listQuery: '',
        hasBaseline: false,
      },
    })
    try {
      const button = wrapper.find('.version-code .vp-copy')
      await button.trigger('click')
      expect(button.attributes('title')).toBe('复制失败，请手动选择')
      vi.advanceTimersByTime(2000)
      await Promise.resolve()
      expect(button.attributes('title')).toBe('复制失败，请手动选择')
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })

  it('draws no menu for a discarded version', () => {
    const wrapper = mount(VersionRow, {
      props: {
        version: version({ state: 'discarded', status: 'discarded', is_current: false }),
        namespace: 'dev',
        name: 'pdf-tools',
        rung: 'owner',
        username: 'dev',
        listQuery: '',
        hasBaseline: false,
      },
    })
    expect(wrapper.find('.version-menu').exists()).toBe(false)
  })
})

describe('VersionFacts', () => {
  it('puts the version code on the author plane, third, after the number', () => {
    const wrapper = mount(VersionFacts, {
      props: { version: DRAFT, namespace: 'dev', name: 'pdf-tools', rung: 'owner' },
    })
    expect(wrapper.findAll('.vp-facts-list dt').map((dt) => dt.text())).toEqual([
      '名称', '版本号', '版本码', '状态', '提交', '首次上线', '内容摘要', '文件', '地址',
    ])
    const facts = wrapper.findAll('.vp-facts-list dd')
    expect(facts[2]?.text()).toContain('d1')
    expect(facts[2]?.find('.vp-copy').exists()).toBe(true)
    expect(wrapper.find('.vp-facts-list').text()).toContain('dev/pdf-tools@d1')
  })

  it('gives a caller four rows and no version code', () => {
    const wrapper = mount(VersionFacts, {
      props: { version: version({ code: 'K3M9QX2P' }), namespace: 'dev', name: 'pdf-tools', rung: 'invoke' },
    })
    expect(wrapper.findAll('.vp-facts-list dt').map((dt) => dt.text())).toEqual([
      '版本号', '状态', '首次上线', '地址',
    ])
    expect(wrapper.find('.vp-facts-list').text()).not.toContain('版本码')
    // A published row's address is its number, never its row id (ADR 0063).
    expect(wrapper.find('.vp-facts-list').text()).toContain('dev/pdf-tools@1.2.3')
    expect(wrapper.find('.vp-facts-list').text()).not.toContain('K3M9QX2P')
  })
})

describe('VersionStatusBar', () => {
  it('says the consequence of each state, and puts a delist reason on its own line', () => {
    const draft = mount(VersionStatusBar, {
      props: { version: DRAFT, live: version(), rung: 'write' },
    })
    expect(draft.text()).toContain('这个版本还没有上线')
    expect(draft.text()).toContain('上线需要「维护」档位。')

    const revoked = mount(VersionStatusBar, {
      props: {
        version: version({
          state: 'revoked',
          status: 'revoked',
          is_current: false,
          revoked_reason: '引用了已经删除的临时脚本',
        }),
        live: version(),
        rung: 'maintain',
      },
    })
    expect(revoked.text()).toContain('指定这个版本的调用会失败（410）')
    expect(revoked.find('.vp-status-reason').text()).toBe('原因：引用了已经删除的临时脚本')

    const live = mount(VersionStatusBar, {
      props: { version: version(), live: version(), rung: 'maintain' },
    })
    expect(live.find('.vp-status').exists()).toBe(false)
  })
})

/* --------------------------------------------------------------- the action panel */

describe('VersionActionPanel', () => {
  async function mountPanel(target: AuthoredVersion, rung = 'owner', live: AuthoredVersion | null = version()) {
    const wrapper = mount(VersionActionPanel, {
      props: {
        namespace: 'dev',
        name: 'pdf-tools',
        version: target,
        versions: [target, live, HISTORY].filter((entry): entry is AuthoredVersion => entry !== null),
        rung,
        username: 'dev',
        live,
      },
    })
    return wrapper
  }

  it('publishes a draft with the suggested patch number, then confirms it', async () => {
    const post = stubFetch(() =>
      json(200, {
        version: 'd1',
        release_name: '1.2.4',
        state: 'published',
        live_at: null,
        changed: true,
      }),
    )
    const wrapper = await mountPanel(DRAFT)

    expect(wrapper.findAll('.choice').map((choice) => choice.text())).toEqual([
      '只是修正修好了问题，调用方式不变 · 1.2.4',
      '新增能力多了新东西，旧调用照旧能用 · 1.3.0',
      '旧调用会失效旧的调用要改，才能继续用 · 2.0.0',
    ])
    const publish = wrapper.findAll('.button').find((button) => button.text().startsWith('上线 @'))
    expect(publish?.text()).toBe('上线 @1.2.4')
    await publish!.trigger('click')

    expect(wrapper.find('.confirm-title').text()).toBe('上线 compress 补文档？')
    expect(wrapper.find('.confirm-body').text()).toContain('名称与说明在这一刻锁定')
    // Not a dangerous action: the confirm button is not red.
    expect(wrapper.find('.confirm-acts .button:not(.is-quiet)').classes()).not.toContain('is-danger')

    await wrapper.find('.confirm-acts .button:not(.is-quiet)').trigger('click')
    await flush()

    expect(post.requests[0]?.path).toBe('/web/skills/dev/pdf-tools/publish')
    expect(post.requests[0]?.body).toEqual({ version: 'd1', release_name: '1.2.4' })
    // The published row's address is its number: reloading `d1` answered 「没有这一版」.
    expect(location.assign).toHaveBeenCalledWith('?version=1.2.4')
    expect(location.reload).not.toHaveBeenCalled()
  })

  it('republishes a history version without a release name, and confirms the rollback', async () => {
    const post = stubFetch(() => json(200, { version: '1.2.2', state: 'published', live_at: null, changed: true }))
    const wrapper = await mountPanel(HISTORY)

    // **A two-step in place, not a sheet** (design README: history 上线 and 重新上架 expand
    // 「确认后立即生效。取消 / 确认…」; the sheet is for 回滚, 上线草稿, 丢弃 and dangerous actions).
    const button = wrapper.findAll('.button').find((entry) => entry.text() === '上线 split 拆页')
    await button!.trigger('click')
    expect(wrapper.find('.confirm-title').exists()).toBe(false)
    expect(wrapper.text()).toContain('确认后立即生效。')
    expect(post.requests).toHaveLength(0)

    const confirm = wrapper.findAll('.button').find((entry) => entry.text() === '确认上线')
    await confirm!.trigger('click')
    await flush()

    expect(post.requests[0]?.body).toEqual({ version: '1.2.2' })
  })

  it('rolls back from a live version to a history candidate', async () => {
    const post = stubFetch(() => json(200, { version: '1.2.2', state: 'published', live_at: null, changed: true }))
    const wrapper = await mountPanel(version())

    expect(wrapper.find('.vp-action .menu-trigger-text').text()).toBe('split 拆页')
    await wrapper.findAll('.button').find((entry) => entry.text() === '回滚')!.trigger('click')
    await wrapper.find('.confirm-acts .button:not(.is-quiet)').trigger('click')
    await flush()

    expect(post.requests[0]?.body).toEqual({ version: '1.2.2' })
  })

  // **A history re-publish moves the pointer to a number that already exists**, so navigating to
  // `release_name` would take the reader off the page whose status just changed. The design keeps
  // this page and re-renders it (the version becomes live); the composable reloads in place.
  it('keeps the current page open after a rollback', async () => {
    const post = stubFetch(() => json(200, { version: '1.2.2', state: 'published', live_at: null, changed: true }))
    const wrapper = await mountPanel(version())

    await wrapper.findAll('.button').find((entry) => entry.text() === '回滚')!.trigger('click')
    await wrapper.find('.confirm-acts .button:not(.is-quiet)').trigger('click')
    await flush()

    expect(post.requests[0]?.body).toEqual({ version: '1.2.2' })
    expect(location.reload).toHaveBeenCalled()
    expect(location.assign).not.toHaveBeenCalled()
  })

  it('restores a revoked version through the inline two-step', async () => {
    const post = stubFetch(() => json(200, { version: '1.2.1', state: 'revoked', live_at: null, changed: true }))
    const revoked = version({ name: '1.2.1', digest: 'sha256:ddd', status: 'revoked', is_current: false })
    const wrapper = await mountPanel(revoked)

    await wrapper.findAll('.button').find((entry) => entry.text() === '重新上架')!.trigger('click')
    // The sheet is not used here: the first press only expands the confirmation in place.
    expect(wrapper.find('.confirm-title').exists()).toBe(false)
    expect(wrapper.text()).toContain('确认后立即生效。')
    expect(post.requests).toHaveLength(0)

    await wrapper.findAll('.button').find((entry) => entry.text() === '确认重新上架')!.trigger('click')
    await flush()

    // The pin is the path here, not a body field: restore names the version it restores.
    expect(post.requests[0]?.path).toBe('/web/skills/dev/pdf-tools/versions/1.2.1/restore')
  })

  // `1.2.3+build.5` is SemVer 2.0.0 and the server accepts it; the client's own check rejected it
  // with a red border and no sentence.
  it('accepts build metadata in a release name', async () => {
    const post = stubFetch(() => json(200, { version: '1.2.4+build.5', state: 'published', live_at: null, changed: true }))
    const wrapper = await mountPanel(DRAFT)

    await wrapper.find('.confirm-input').setValue('1.2.4+build.5')
    await wrapper.findAll('.button').find((entry) => entry.text().startsWith('上线 @'))!.trigger('click')

    expect(wrapper.find('.confirm-title').exists()).toBe(true)
    await wrapper.find('.confirm-acts .button:not(.is-quiet)').trigger('click')
    await flush()
    expect(post.requests[0]?.body).toEqual({ version: 'd1', release_name: '1.2.4+build.5' })
  })

  it('discards a draft with an outlined button and a “cannot be recovered” sheet', async () => {
    const post = stubFetch(() => json(200, { version: 'd1', state: 'discarded', live_at: null, changed: true }))
    const wrapper = await mountPanel(DRAFT)

    const discard = wrapper.findAll('.button').find((entry) => entry.text() === '丢弃')
    expect(discard?.classes()).toContain('is-quiet')
    await discard!.trigger('click')
    expect(wrapper.find('.confirm-body').text().startsWith('丢弃后无法找回')).toBe(true)
    await wrapper.find('.confirm-acts .button:not(.is-quiet)').trigger('click')
    await flush()

    expect(post.requests[0]?.path).toBe('/web/skills/dev/pdf-tools/discard')
    expect(post.requests[0]?.body).toEqual({ version: 'd1' })
  })

  it('delists a history version with an optional reason', async () => {
    const post = stubFetch(() => json(200, { version: '1.2.2', state: 'revoked', live_at: null, changed: true }))
    const wrapper = await mountPanel(HISTORY)

    const revoke = wrapper.findAll('.danger-acts .button').find((entry) => entry.text() === '下架')
    await revoke!.trigger('click')
    expect(wrapper.find('.confirm-acts .button:not(.is-quiet)').classes()).toContain('is-danger')
    // The reason is optional: the confirm button is enabled while the sheet is empty.
    expect((wrapper.find('.confirm-acts .button:not(.is-quiet)').element as HTMLButtonElement).disabled).toBe(false)
    await wrapper.find('.confirm-input').setValue('有安全问题')
    await wrapper.find('.confirm-acts .button:not(.is-quiet)').trigger('click')
    await flush()

    expect(post.requests[0]?.path).toBe('/web/skills/dev/pdf-tools/versions/1.2.2/revoke')
    expect(post.requests[0]?.body).toEqual({ reason: '有安全问题' })
  })
})

/* --------------------------------------------------------------- the 改动 pane */

it('gives two paths that sanitise alike different in-page anchors', () => {
  // `a-b.md` and `a_b.md` both sanitised to `a_b_md`; a tree click then scrolled to whichever block
  // came first.
  expect(fileAnchorId('a-b.md')).not.toBe(fileAnchorId('a_b.md'))
})

describe('VersionDiffPane', () => {
  const changed: DiffFile = {
    relpath: 'SKILL.md',
    status: 'modified',
    binary: false,
    size: 90,
    added: 1,
    removed: 1,
    hunks: [{ header: '@@ -1 +1 @@', lines: ['-old', '+new'] }],
  }
  const pending: DiffFile = {
    relpath: 'references/split.md',
    status: 'modified',
    binary: false,
    size: 120,
    added: 2,
    removed: 0,
    hunks: null,
  }

  function diffResponse(files: DiffFile[] = [changed, pending]) {
    return () => json(200, { from: '1.2.2', to: '1.2.3', truncated: true, next: null, files })
  }

  async function mountPane(options: { list?: boolean; file?: string } = {}): Promise<VueWrapper> {
    stubFetch(diffResponse())
    const wrapper = mount(VersionDiffPane, {
      props: {
        namespace: 'dev',
        name: 'pdf-tools',
        version: version(),
        versions: [version(), HISTORY],
        rung: 'maintain',
        fq: '',
        list: options.list ?? false,
        ...(options.file === undefined ? {} : { file: options.file }),
        live: version(),
      },
    })
    await flush()
    return wrapper
  }

  it('draws the list and both file blocks, leaving an unloaded body pending', async () => {
    const wrapper = await mountPane()

    expect(wrapper.findAll('.vp-tree-item')).toHaveLength(2)
    expect(wrapper.findAll('.vp-file')).toHaveLength(2)
    expect(wrapper.find('.vp-file-count').text()).toBe('2 个文件')
    // The list's numstat is there before the body.
    expect(wrapper.findAll('.vp-tree-item')[1]?.text()).toContain('+2')
    expect(wrapper.text()).toContain('进入视野后加载')
    wrapper.unmount()
  })

  it('asks once on mount and does not follow next: bodies come per file', async () => {
    // `next` names the next batch of bodies, not a page of the list. Following it fetched every
    // file's content up front; the stub answers exactly one request, so a second one fails loudly.
    const fetch = stubFetch(() =>
      json(200, { from: '1.2.2', to: '1.2.3', truncated: true, next: '0', files: [changed, pending] }),
    )
    const wrapper = mount(VersionDiffPane, {
      props: {
        namespace: 'dev',
        name: 'pdf-tools',
        version: version(),
        versions: [version(), HISTORY],
        rung: 'maintain',
        fq: '',
        list: false,
        live: version(),
      },
    })
    await flush()

    expect(fetch.requests).toHaveLength(1)
    expect(fetch.requests[0]?.path).not.toContain('cursor=')
    wrapper.unmount()
  })

  it('sums the files it can count instead of blanking the total for a binary file', async () => {
    stubFetch(() =>
      json(200, {
        from: '1.2.2',
        to: '1.2.3',
        truncated: false,
        next: null,
        files: [changed, { ...pending, binary: true, added: null, removed: null, hunks: null }],
      }),
    )
    const wrapper = mount(VersionDiffPane, {
      props: {
        namespace: 'dev',
        name: 'pdf-tools',
        version: version(),
        versions: [version(), HISTORY],
        rung: 'maintain',
        fq: '',
        list: false,
        live: version(),
      },
    })
    await flush()

    const summary = wrapper.emitted('summary')?.at(-1)?.[0] as { added: number | null }
    expect(summary.added).toBe(1)
    wrapper.unmount()
  })

  it('narrows only the tree when the path filter is typed, and writes fq to the address', async () => {
    const replace = vi.spyOn(window.history, 'replaceState')
    const wrapper = await mountPane()

    await wrapper.find('.vp-tree-search input').setValue('references')
    await flush()

    expect(wrapper.findAll('.vp-tree-item')).toHaveLength(1)
    // The diff body does not disappear.
    expect(wrapper.findAll('.vp-file')).toHaveLength(2)
    expect(String(replace.mock.calls.at(-1)?.[2])).toContain('fq=references')
    wrapper.unmount()
  })

  it('“只看文件列表” keeps the tree and drops every body', async () => {
    const wrapper = await mountPane({ list: true })

    expect(wrapper.find('.vp-file').exists()).toBe(false)
    expect(wrapper.findAll('.vp-tree-item')).toHaveLength(2)
    expect(wrapper.text()).toContain('只看文件列表。点左边的文件')
    wrapper.unmount()
  })

  it('“逐个查看” navigates to the state without `list`, so single-file mode loads its file', async () => {
    const wrapper = await mountPane({ list: true })

    await wrapper.findAll('.link-button').find((entry) => entry.text() === '逐个查看')!.trigger('click')

    // Flipping the local switch left single-file mode with no `file=` and no load; the design's
    // own toggle is an address change, which re-mounts the pane and runs the load path.
    expect(location.assign).toHaveBeenCalledWith('?')
    wrapper.unmount()
  })

  it('reports no total when the load failed, instead of claiming +0 −0', async () => {
    stubFetch(() => json(500, { error: { code: 'internal_error', message: 'boom' } }))
    const wrapper = mount(VersionDiffPane, {
      props: {
        namespace: 'dev',
        name: 'pdf-tools',
        version: version(),
        versions: [version(), HISTORY],
        rung: 'maintain',
        fq: '',
        list: false,
        live: version(),
      },
    })
    await flush()

    expect(wrapper.emitted('summary')?.at(-1)?.[0]).toBeNull()
    wrapper.unmount()
  })

  it('over 100 files draws one file at a time and fetches that one', async () => {
    const files = Array.from({ length: 101 }, (_, index) => ({
      ...pending,
      relpath: `references/${index}.md`,
      hunks: null,
    }))
    stubFetch(diffResponse(files), diffResponse([{ ...pending, relpath: 'references/100.md', hunks: [] }]))
    const wrapper = mount(VersionDiffPane, {
      props: {
        namespace: 'dev',
        name: 'pdf-tools',
        version: version(),
        versions: [version(), HISTORY],
        rung: 'maintain',
        fq: '',
        file: 'references/100.md',
        list: false,
        live: version(),
      },
    })
    await flush()

    expect(wrapper.findAll('.vp-file')).toHaveLength(1)
    expect(wrapper.find('.vp-file-path').text()).toBe('references/100.md')
    // The 124th-file rule: only that file's body is requested.
    wrapper.unmount()
  })
})

/* --------------------------------------------------------------- route fallback */

describe('the single-version page', () => {
  it('offers 操作 to a write-only reader on their own draft', async () => {
    stubLocation('/skills/dev/pdf-tools', '?tab=versions&version=d1&sub=actions')
    stubFetch(() => json(200, { from: '1.2.3', to: 'd1', truncated: false, next: null, files: [] }))
    const { default: VersionDetail } = await import('../src/components/version/VersionDetail.vue')
    const live = version()
    const wrapper = mount(VersionDetail, {
      props: {
        namespace: 'dev',
        name: 'pdf-tools',
        version: DRAFT,
        versions: [DRAFT, live],
        rung: 'write',
        username: 'yuhan',
        live,
        route: parseVersionRoute('?tab=versions&version=d1&sub=actions'),
      },
    })
    await flush()

    // A write-only reader may discard their own draft; that is an action, so 操作 exists.
    expect(wrapper.find('.vp-tab.is-current').text()).toContain('操作')
    wrapper.unmount()
  })

  it('offers 编辑文件 only on a draft this caller may change', async () => {
    stubLocation('/skills/dev/pdf-tools', '?tab=versions&version=d1')
    const { default: VersionDetail } = await import('../src/components/version/VersionDetail.vue')
    const live = version()

    const mine = mount(VersionDetail, {
      props: {
        namespace: 'dev',
        name: 'pdf-tools',
        version: DRAFT,
        versions: [DRAFT, live],
        rung: 'write',
        username: 'yuhan',
        live,
        route: parseVersionRoute('?tab=versions&version=d1'),
      },
    })
    const edit = mine.findAll('a').find((node) => node.text() === '编辑文件')
    expect(edit?.attributes('href')).toBe('/skills/dev/pdf-tools@d1/edit')
    mine.unmount()

    // Somebody else's draft: `write` may read it, not change it.
    const theirs = mount(VersionDetail, {
      props: {
        namespace: 'dev',
        name: 'pdf-tools',
        version: DRAFT,
        versions: [DRAFT, live],
        rung: 'write',
        username: 'somebody-else',
        live,
        route: parseVersionRoute('?tab=versions&version=d1'),
      },
    })
    expect(theirs.findAll('a').some((node) => node.text() === '编辑文件')).toBe(false)
    theirs.unmount()

    // A published version's content is frozen.
    const publishedPage = mount(VersionDetail, {
      props: {
        namespace: 'dev',
        name: 'pdf-tools',
        version: live,
        versions: [live],
        rung: 'owner',
        username: 'dev',
        live,
        route: parseVersionRoute('?tab=versions&version=1.2.3'),
      },
    })
    expect(publishedPage.findAll('a').some((node) => node.text() === '编辑文件')).toBe(false)
    publishedPage.unmount()
  })

  it('falls back to 概览 for a discarded version, which has no action pane', async () => {
    const discarded = version({ state: 'discarded', status: 'discarded', is_current: false })
    const live = version()
    stubLocation('/skills/dev/pdf-tools', '?tab=versions&version=1.2.3&sub=actions')
    stubFetch(() => json(200, { from: '1.2.3', to: '1.2.3', truncated: false, next: null, files: [] }))
    const { default: VersionDetail } = await import('../src/components/version/VersionDetail.vue')
    const wrapper = mount(VersionDetail, {
      props: {
        namespace: 'dev',
        name: 'pdf-tools',
        version: discarded,
        versions: [discarded, live],
        rung: 'owner',
        username: 'dev',
        live,
        route: parseVersionRoute('?tab=versions&version=1.2.3&sub=actions'),
      },
    })
    await flush()

    expect(wrapper.find('.vp-tab.is-current').text()).toContain('概览')
    wrapper.unmount()
  })
})
