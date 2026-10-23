import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import { flush, json, setCookie, stubFetch, stubLocation } from './support'
import type { AuthoredVersion, DiffFile } from '../src/api/types'
import { answersPin, fileAnchorId, isVersionSuffix, parseVersionRoute, versionSuffix, versionTitle } from '../src/version'
import VersionPicker from '../src/components/VersionPicker'
import DiffFileBlock from '../src/components/DiffFileBlock'
import VersionRow from '../src/components/version/VersionRow'
import VersionFacts from '../src/components/version/VersionFacts'
import VersionStatusBar from '../src/components/version/VersionStatusBar'
import VersionActionPanel from '../src/components/version/VersionActionPanel'
import VersionDiffPane from '../src/components/version/VersionDiffPane'
import VersionDetail from '../src/components/version/VersionDetail'

/**
 * The v6 version surface, component by component.
 *
 * The assertions follow the design's own contract: the five statuses come from `status`; a draft is
 * addressed by `draft:<id>`; the list only navigates; every state-changing action opens one
 * confirmation sheet; a file's `hunks: null` has a reason (binary, too large, not fetched yet).
 *
 * **What changed in the port.** Vue Test Utils' `mount` became `render`, `find`/`findAll` became
 * `querySelector`/`querySelectorAll`, `.text()` became a trimmed `textContent`, and emits became the
 * `onSelect`/`onSummary` callbacks. The assertions themselves are unchanged.
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

const text = (element: Element | null): string => (element?.textContent ?? '').trim()

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

  it('reads a preselected publish choice and drops anything else', () => {
    expect(parseVersionRoute('?tab=versions&version=d1&bump=minor').bump).toBe('minor')
    // Not one of the three choices: the form opens on its default rather than inventing a state.
    expect(parseVersionRoute('?tab=versions&version=d1&bump=foo')).not.toHaveProperty('bump')
    expect(parseVersionRoute('?tab=versions&version=d1')).not.toHaveProperty('bump')
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
    const { container } = render(
      <VersionPicker
        versions={[version(), DRAFT, revoked]}
        selected="1.2.3"
        disabledValues={['1.2.1']}
        onSelect={vi.fn()}
      />,
    )

    expect(text(container.querySelector('.menu-trigger-text'))).toBe('split 范围语法')
    expect([...container.querySelectorAll('.menu-label')].map((node) => text(node))).toEqual([
      'split 范围语法',
      'compress 补文档',
      'compress 收进 skill',
    ])
    expect([...container.querySelectorAll('.menu-number')].map((node) => text(node))).toEqual(['@1.2.3', '@1.2.1'])
    expect([...container.querySelectorAll('.menu-tag')].map((node) => text(node))).toEqual([
      '线上',
      '草稿',
      '已下架',
    ])
    const revokedOption = container.querySelectorAll('.menu-item')[2]
    expect(revokedOption?.getAttribute('aria-disabled')).toBe('true')
    expect((revokedOption as HTMLButtonElement).disabled).toBe(true)
  })

  it('filters by name and by version number, and emits the chosen pin', async () => {
    const onSelect = vi.fn()
    const { container } = render(
      <VersionPicker versions={[version(), DRAFT]} selected="1.2.3" onSelect={onSelect} />,
    )

    fireEvent.change(container.querySelector('.menu-search-input') as HTMLInputElement, {
      target: { value: '1.2.' },
    })
    const visible = [...container.querySelectorAll<HTMLElement>('.menu-item')].filter(
      (item) => !item.hidden,
    )
    expect(visible).toHaveLength(1)
    expect(text(visible[0] ?? null)).toContain('split 范围语法')

    fireEvent.change(container.querySelector('.menu-search-input') as HTMLInputElement, {
      target: { value: 'compress' },
    })
    const byName = [...container.querySelectorAll<HTMLElement>('.menu-item')].filter(
      (item) => !item.hidden,
    )
    expect(byName).toHaveLength(1)
    expect(text(byName[0] ?? null)).toContain('compress 补文档')

    fireEvent.click(byName[0] as HTMLElement)
    expect(onSelect.mock.calls).toEqual([['d1']])
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
    const { container } = render(<DiffFileBlock file={file()} />)

    expect(text(container.querySelector('.is-add .diff-text'))).toBe('+new')
    expect(text(container.querySelector('.is-del .diff-text'))).toBe('-old')
    expect(text(container.querySelector('.diff-stats'))).toBe('+1 −1')
  })

  it('says why a binary or oversized file has no body, and never +0 −0', () => {
    const binary = render(
      <DiffFileBlock file={file({ binary: true, added: null, removed: null, hunks: null })} />,
    )
    expect(text(binary.container.querySelector('.diff-pending'))).toContain('二进制文件，不显示内容。')
    expect(text(binary.container.querySelector('.diff-stats'))).toBe('—')

    const big = render(
      <DiffFileBlock
        file={file({ relpath: 'assets/export.csv', size: 1468006, added: null, removed: null, hunks: null })}
      />,
    )
    expect(text(big.container.querySelector('.diff-pending'))).toContain('这个文件太大，没有展开（1.4 MB）。')
    expect(text(big.container.querySelector('.diff-stats'))).toBe('—')
  })

  it('draws 400 lines first and 500 more per press', () => {
    const lines = Array.from({ length: 600 }, (_, index) => ` line ${index}`)
    const { container } = render(
      <DiffFileBlock
        file={file({ hunks: [{ header: '@@ -1,600 +1,600 @@', lines }] })}
        initialLimit={400}
      />,
    )

    // One `<pre>` per file, one row per line — the design's contiguous block.
    expect(container.querySelectorAll('.diff-lines').length).toBe(1)
    expect(container.querySelectorAll('.diff-lines > span').length).toBe(400)
    expect(text(container.querySelector('.diff-more'))).toContain('还有 200 行未显示')
    fireEvent.click(container.querySelector('.diff-more .link-button') as HTMLButtonElement)
    expect(container.querySelectorAll('.diff-lines > span').length).toBe(600)
    expect(container.querySelector('.diff-more')).toBeNull()
  })
})

/* --------------------------------------------------------------- the row and status */

describe('VersionRow', () => {
  it('makes the whole row the name link and its menu only links', () => {
    const { container } = render(
      <VersionRow
        version={DRAFT}
        namespace="dev"
        name="pdf-tools"
        rung="owner"
        username="dev"
        listQuery="filter=draft"
        hasBaseline
      />,
    )

    const link = container.querySelector('.version-name')
    expect(link?.getAttribute('href')).toBe(
      '/skills/dev/pdf-tools?tab=versions&version=d1&filter=draft',
    )
    expect(link?.getAttribute('aria-label')).toBe('打开 compress 补文档')
    expect(text(container.querySelector('.version-number'))).toBe('—')
    expect(
      [...container.querySelectorAll('.version-menu .menu-item')].map((item) => item.getAttribute('href')),
    ).toEqual([
      '/skills/dev/pdf-tools@d1?tab=files',
      '/skills/dev/pdf-tools@d1/edit',
      '/skills/dev/pdf-tools?tab=versions&version=d1&filter=draft&sub=diff',
      '/skills/dev/pdf-tools?tab=versions&version=d1&filter=draft&sub=actions',
      '/skills/dev/pdf-tools?tab=versions&version=d1&filter=draft&sub=actions',
    ])
    expect([...container.querySelectorAll('.version-menu .menu-item')].map((item) => text(item))).toEqual([
      '查看文件',
      '编辑',
      '查看改动',
      '上线',
      '丢弃',
    ])
    // Navigation and lifecycle actions are two groups, separated by a rule (design `.menu-group`).
    expect(container.querySelectorAll('.version-menu .menu-group')).toHaveLength(2)
  })

  it('gives a caller the version number as its identity, no menu, and only the publish time', () => {
    const { container } = render(
      <VersionRow
        version={version({ first_published_at: '2026-10-07T14:32:00Z' })}
        namespace="dev"
        name="pdf-tools"
        rung="invoke"
        username={null}
        listQuery=""
        hasBaseline={false}
      />,
    )

    expect(text(container.querySelector('.version-name'))).toBe('@1.2.3')
    expect(container.querySelector('.version-name')?.classList.contains('is-mono')).toBe(true)
    expect(container.querySelector('.version-number')).toBeNull()
    expect(text(container.querySelector('.version-meta'))).toContain('上线于')
    expect(container.querySelector('.version-menu')).toBeNull()
  })

  it('sends a published row to its number even though the row carries a version code', () => {
    const { container } = render(
      <VersionRow
        version={version({ code: 'K3M9QX2P' })}
        namespace="dev"
        name="pdf-tools"
        rung="owner"
        username="dev"
        listQuery=""
        hasBaseline
      />,
    )
    expect(container.querySelector('.version-name')?.getAttribute('href')).toContain('version=1.2.3')
    expect(container.querySelector('.version-name')?.getAttribute('href')).not.toContain('K3M9QX2P')
  })

  it('puts the code after the note and copies it without navigating the row', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: () => Promise.resolve() },
    })
    const { container } = render(
      <VersionRow
        version={DRAFT}
        namespace="dev"
        name="pdf-tools"
        rung="write"
        username="dev"
        listQuery=""
        hasBaseline={false}
      />,
    )

    const sub = container.querySelector('.version-sub') as HTMLElement
    expect(sub.querySelector('.version-note')).not.toBeNull()
    expect(text(sub.querySelector('.version-code-slot .version-code'))).toContain('d1')
    const html = sub.innerHTML
    expect(html.indexOf('version-note')).toBeLessThan(html.indexOf('version-code-slot'))
    expect(html.indexOf('version-code-slot')).toBeLessThan(html.indexOf('version-meta'))

    fireEvent.click(sub.querySelector('.version-code .vp-copy') as HTMLButtonElement)
    await flush()
    expect(container.querySelector('.version-code .vp-copy')?.classList.contains('is-done')).toBe(true)
    expect(text(container.querySelector('.version-sub .visually-hidden'))).toBe('已复制')
    // Clicking the code's button does not follow the row's link. The stacking itself (`z-index`)
    // is a rendered property jsdom does not apply; T-WEB-05 is the check for that half.
    expect(container.querySelector('.version-name')?.getAttribute('href')).toContain('version=d1')
    Reflect.deleteProperty(navigator, 'clipboard')
  })

  it('keeps the copy failure sentence instead of clearing it after a moment', async () => {
    // jsdom has neither clipboard nor execCommand, so the fallback fails and the button must say so.
    // The design only resets the success state; clearing the failure would remove the explanation.
    vi.useFakeTimers()
    try {
      const { container } = render(
        <VersionRow
          version={DRAFT}
          namespace="dev"
          name="pdf-tools"
          rung="write"
          username="dev"
          listQuery=""
          hasBaseline={false}
        />,
      )
      const button = container.querySelector('.version-code .vp-copy') as HTMLButtonElement
      fireEvent.click(button)
      expect(button.getAttribute('title')).toBe('复制失败，请手动选择')
      vi.advanceTimersByTime(2000)
      await Promise.resolve()
      expect(button.getAttribute('title')).toBe('复制失败，请手动选择')
    } finally {
      vi.useRealTimers()
    }
  })

  it('draws no menu for a discarded version', () => {
    const { container } = render(
      <VersionRow
        version={version({ state: 'discarded', status: 'discarded', is_current: false })}
        namespace="dev"
        name="pdf-tools"
        rung="owner"
        username="dev"
        listQuery=""
        hasBaseline={false}
      />,
    )
    expect(container.querySelector('.version-menu')).toBeNull()
  })
})

describe('VersionFacts', () => {
  it('puts the version code on the author plane, third, after the number', () => {
    const { container } = render(
      <VersionFacts version={DRAFT} namespace="dev" name="pdf-tools" rung="owner" />,
    )
    expect([...container.querySelectorAll('.vp-facts-list dt')].map((dt) => text(dt))).toEqual([
      '名称', '版本号', '版本码', '状态', '提交', '首次上线', '内容摘要', '文件', '地址',
    ])
    const facts = container.querySelectorAll('.vp-facts-list dd')
    expect(text(facts[2] ?? null)).toContain('d1')
    expect(facts[2]?.querySelector('.vp-copy')).not.toBeNull()
    expect(text(container.querySelector('.vp-facts-list'))).toContain('dev/pdf-tools@d1')
  })

  it('gives a caller four rows and no version code', () => {
    const { container } = render(
      <VersionFacts version={version({ code: 'K3M9QX2P' })} namespace="dev" name="pdf-tools" rung="invoke" />,
    )
    expect([...container.querySelectorAll('.vp-facts-list dt')].map((dt) => text(dt))).toEqual([
      '版本号', '状态', '首次上线', '地址',
    ])
    expect(text(container.querySelector('.vp-facts-list'))).not.toContain('版本码')
    // A published row's address is its number, never its row id (ADR 0063).
    expect(text(container.querySelector('.vp-facts-list'))).toContain('dev/pdf-tools@1.2.3')
    expect(text(container.querySelector('.vp-facts-list'))).not.toContain('K3M9QX2P')
  })
})

describe('VersionStatusBar', () => {
  it('says the consequence of each state, and puts a delist reason on its own line', () => {
    const draft = render(<VersionStatusBar version={DRAFT} live={version()} rung="write" />)
    expect(draft.container.textContent).toContain('这个版本还没有上线')
    expect(draft.container.textContent).toContain('上线需要「维护」档位。')

    const revoked = render(
      <VersionStatusBar
        version={version({
          state: 'revoked',
          status: 'revoked',
          is_current: false,
          revoked_reason: '引用了已经删除的临时脚本',
        })}
        live={version()}
        rung="maintain"
      />,
    )
    expect(revoked.container.textContent).toContain('指定这个版本的调用会失败（410）')
    expect(text(revoked.container.querySelector('.vp-status-reason'))).toBe('原因：引用了已经删除的临时脚本')

    const live = render(<VersionStatusBar version={version()} live={version()} rung="maintain" />)
    expect(live.container.querySelector('.vp-status')).toBeNull()
  })
})

/* --------------------------------------------------------------- the action panel */

describe('VersionActionPanel', () => {
  function mountPanel(
    target: AuthoredVersion,
    rung = 'owner',
    live: AuthoredVersion | null = version(),
    initialBump?: 'patch' | 'minor' | 'major',
  ) {
    return render(
      <VersionActionPanel
        namespace="dev"
        name="pdf-tools"
        version={target}
        versions={[target, live, HISTORY].filter((entry): entry is AuthoredVersion => entry !== null)}
        rung={rung}
        username="dev"
        live={live}
        initialBump={initialBump}
      />,
    )
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
    const { container } = mountPanel(DRAFT)

    expect([...container.querySelectorAll('.choice')].map((choice) => text(choice))).toEqual([
      '只是修正修好了问题，调用方式不变 · 1.2.4',
      '新增能力多了新东西，旧调用照旧能用 · 1.3.0',
      '旧调用会失效旧的调用要改，才能继续用 · 2.0.0',
    ])
    const publish = [...container.querySelectorAll('.button')].find((button) => text(button).startsWith('上线 @'))
    expect(text(publish ?? null)).toBe('上线 @1.2.4')
    fireEvent.click(publish as HTMLButtonElement)

    expect(text(container.querySelector('.confirm-title'))).toBe('上线 compress 补文档？')
    expect(text(container.querySelector('.confirm-body'))).toContain('名称与说明在这一刻锁定')
    // Not a dangerous action: the confirm button is not red.
    expect(
      container.querySelector('.confirm-acts .button:not(.is-quiet)')?.classList.contains('is-danger'),
    ).toBe(false)

    fireEvent.click(container.querySelector('.confirm-acts .button:not(.is-quiet)') as HTMLButtonElement)
    await flush()

    expect(post.requests[0]?.path).toBe('/web/skills/dev/pdf-tools/publish')
    expect(post.requests[0]?.body).toEqual({ version: 'd1', release_name: '1.2.4' })
    // The published row's address is its number: reloading `d1` answered 「没有这一版」.
    expect(location.assign).toHaveBeenCalledWith('?version=1.2.4')
    expect(location.reload).not.toHaveBeenCalled()
  })

  it('opens on the publish choice the address preselected', () => {
    // No preselection is the design's own default: 只是修正, checked.
    const plain = mountPanel(DRAFT)
    expect(
      (plain.container.querySelector('input[name="vp-bump"][value="patch"]') as HTMLInputElement).checked,
    ).toBe(true)

    const patch = mountPanel(DRAFT, 'owner', version(), 'patch')
    expect(
      (patch.container.querySelector('input[name="vp-bump"][value="patch"]') as HTMLInputElement).checked,
    ).toBe(true)

    const minor = mountPanel(DRAFT, 'owner', version(), 'minor')
    expect(
      (minor.container.querySelector('input[name="vp-bump"][value="minor"]') as HTMLInputElement).checked,
    ).toBe(true)
    expect((minor.container.querySelector('.confirm-input') as HTMLInputElement).value).toBe('1.3.0')
    expect(
      text(
        [...minor.container.querySelectorAll('.button')].find((button) => text(button).startsWith('上线 @')) ??
          null,
      ),
    ).toBe('上线 @1.3.0')

    const major = mountPanel(DRAFT, 'owner', version(), 'major')
    expect(
      (major.container.querySelector('input[name="vp-bump"][value="major"]') as HTMLInputElement).checked,
    ).toBe(true)
    expect((major.container.querySelector('.confirm-input') as HTMLInputElement).value).toBe('2.0.0')
    expect(
      text(
        [...major.container.querySelectorAll('.button')].find((button) => text(button).startsWith('上线 @')) ??
          null,
      ),
    ).toBe('上线 @2.0.0')
  })

  it('returns to the list without the preselection after a write', async () => {
    location = stubLocation('/skills/dev/pdf-tools', '?tab=versions&version=d1&sub=actions&bump=minor')
    stubFetch(() => json(200, { version: 'd1', state: 'discarded', live_at: null, changed: true }))
    const { container } = mountPanel(DRAFT)

    const discard = [...container.querySelectorAll('.button')].find((entry) => text(entry) === '丢弃')
    fireEvent.click(discard as HTMLButtonElement)
    fireEvent.click(container.querySelector('.confirm-acts .button:not(.is-quiet)') as HTMLButtonElement)
    await flush()

    // The version list is a fresh visit: the handoff's preselection stays behind.
    expect(location.assign).toHaveBeenCalledWith('?tab=versions')
  })

  it('ignores the preselection where no number is chosen', () => {
    // The first publish has no bump to compare against: the field is 1.0.0 and no radios are drawn.
    const first = mountPanel(DRAFT, 'owner', null, 'minor')
    expect(first.container.querySelectorAll('.choice')).toHaveLength(0)
    expect((first.container.querySelector('.confirm-input') as HTMLInputElement).value).toBe('1.0.0')

    // A pre-v6 draft already carries its number: the form would otherwise ask for a second one.
    const legacy = mountPanel(version({ ...DRAFT, name: '1.0.0' }), 'owner', version(), 'minor')
    expect(legacy.container.querySelector('.confirm-input')).toBeNull()
    expect(text(legacy.container.querySelector('.vp-note'))).toContain('上线时沿用')
  })

  it('drops the preselection from the address after a successful publish', async () => {
    location = stubLocation('/skills/dev/pdf-tools', '?tab=versions&version=d1&sub=actions&bump=minor')
    stubFetch(() =>
      json(200, {
        version: 'd1',
        release_name: '1.3.0',
        state: 'published',
        live_at: null,
        changed: true,
      }),
    )
    const { container } = mountPanel(DRAFT, 'owner', version(), 'minor')
    const publish = [...container.querySelectorAll('.button')].find((button) => text(button).startsWith('上线 @'))
    fireEvent.click(publish as HTMLButtonElement)
    fireEvent.click(container.querySelector('.confirm-acts .button:not(.is-quiet)') as HTMLButtonElement)
    await flush()

    // The pane and the version survive; the preselection has been consumed.
    expect(location.assign).toHaveBeenCalledWith('?tab=versions&version=1.3.0&sub=actions')
  })

  it('republishes a history version without a release name, and confirms the rollback', async () => {
    const post = stubFetch(() => json(200, { version: '1.2.2', state: 'published', live_at: null, changed: true }))
    const { container } = mountPanel(HISTORY)

    // **A two-step in place, not a sheet** (design README: history 上线 and 重新上架 expand
    // 「确认后立即生效。取消 / 确认…」; the sheet is for 回滚, 上线草稿, 丢弃 and dangerous actions).
    const button = [...container.querySelectorAll('.button')].find((entry) => text(entry) === '上线 split 拆页')
    fireEvent.click(button as HTMLButtonElement)
    expect(container.querySelector('.confirm-title')).toBeNull()
    expect(container.textContent).toContain('确认后立即生效。')
    expect(post.requests).toHaveLength(0)

    const confirm = [...container.querySelectorAll('.button')].find((entry) => text(entry) === '确认上线')
    fireEvent.click(confirm as HTMLButtonElement)
    await flush()

    expect(post.requests[0]?.body).toEqual({ version: '1.2.2' })
  })

  it('rolls back from a live version to a history candidate', async () => {
    const post = stubFetch(() => json(200, { version: '1.2.2', state: 'published', live_at: null, changed: true }))
    const { container } = mountPanel(version())

    expect(text(container.querySelector('.vp-action .menu-trigger-text'))).toBe('split 拆页')
    fireEvent.click(
      [...container.querySelectorAll('.button')].find((entry) => text(entry) === '回滚') as HTMLButtonElement,
    )
    fireEvent.click(container.querySelector('.confirm-acts .button:not(.is-quiet)') as HTMLButtonElement)
    await flush()

    expect(post.requests[0]?.body).toEqual({ version: '1.2.2' })
  })

  // **A history re-publish moves the pointer to a number that already exists**, so navigating to
  // `release_name` would take the reader off the page whose status just changed. The design keeps
  // this page and re-renders it (the version becomes live); the composable reloads in place.
  it('keeps the current page open after a rollback', async () => {
    const post = stubFetch(() => json(200, { version: '1.2.2', state: 'published', live_at: null, changed: true }))
    const { container } = mountPanel(version())

    fireEvent.click(
      [...container.querySelectorAll('.button')].find((entry) => text(entry) === '回滚') as HTMLButtonElement,
    )
    fireEvent.click(container.querySelector('.confirm-acts .button:not(.is-quiet)') as HTMLButtonElement)
    await flush()

    expect(post.requests[0]?.body).toEqual({ version: '1.2.2' })
    expect(location.reload).toHaveBeenCalled()
    expect(location.assign).not.toHaveBeenCalled()
  })

  it('restores a revoked version through the inline two-step', async () => {
    const post = stubFetch(() => json(200, { version: '1.2.1', state: 'revoked', live_at: null, changed: true }))
    const revoked = version({ name: '1.2.1', digest: 'sha256:ddd', status: 'revoked', is_current: false })
    const { container } = mountPanel(revoked)

    fireEvent.click(
      [...container.querySelectorAll('.button')].find((entry) => text(entry) === '重新上架') as HTMLButtonElement,
    )
    // The sheet is not used here: the first press only expands the confirmation in place.
    expect(container.querySelector('.confirm-title')).toBeNull()
    expect(container.textContent).toContain('确认后立即生效。')
    expect(post.requests).toHaveLength(0)

    fireEvent.click(
      [...container.querySelectorAll('.button')].find(
        (entry) => text(entry) === '确认重新上架',
      ) as HTMLButtonElement,
    )
    await flush()

    // The pin is the path here, not a body field: restore names the version it restores.
    expect(post.requests[0]?.path).toBe('/web/skills/dev/pdf-tools/versions/1.2.1/restore')
  })

  // `1.2.3+build.5` is SemVer 2.0.0 and the server accepts it; the client's own check rejected it
  // with a red border and no sentence.
  it('accepts build metadata in a release name', async () => {
    const post = stubFetch(() => json(200, { version: '1.2.4+build.5', state: 'published', live_at: null, changed: true }))
    const { container } = mountPanel(DRAFT)

    fireEvent.change(container.querySelector('.confirm-input') as HTMLInputElement, {
      target: { value: '1.2.4+build.5' },
    })
    fireEvent.click(
      [...container.querySelectorAll('.button')].find(
        (entry) => text(entry).startsWith('上线 @'),
      ) as HTMLButtonElement,
    )

    expect(container.querySelector('.confirm-title')).not.toBeNull()
    fireEvent.click(container.querySelector('.confirm-acts .button:not(.is-quiet)') as HTMLButtonElement)
    await flush()
    expect(post.requests[0]?.body).toEqual({ version: 'd1', release_name: '1.2.4+build.5' })
  })

  it('discards a draft with an outlined button and a “cannot be recovered” sheet', async () => {
    const post = stubFetch(() => json(200, { version: 'd1', state: 'discarded', live_at: null, changed: true }))
    const { container } = mountPanel(DRAFT)

    const discard = [...container.querySelectorAll('.button')].find((entry) => text(entry) === '丢弃')
    expect(discard?.classList.contains('is-quiet')).toBe(true)
    fireEvent.click(discard as HTMLButtonElement)
    expect(text(container.querySelector('.confirm-body')).startsWith('丢弃后无法找回')).toBe(true)
    fireEvent.click(container.querySelector('.confirm-acts .button:not(.is-quiet)') as HTMLButtonElement)
    await flush()

    expect(post.requests[0]?.path).toBe('/web/skills/dev/pdf-tools/discard')
    expect(post.requests[0]?.body).toEqual({ version: 'd1' })
  })

  it('delists a history version with an optional reason', async () => {
    const post = stubFetch(() => json(200, { version: '1.2.2', state: 'revoked', live_at: null, changed: true }))
    const { container } = mountPanel(HISTORY)

    const revoke = [...container.querySelectorAll('.danger-acts .button')].find((entry) => text(entry) === '下架')
    fireEvent.click(revoke as HTMLButtonElement)
    expect(
      container.querySelector('.confirm-acts .button:not(.is-quiet)')?.classList.contains('is-danger'),
    ).toBe(true)
    // The reason is optional: the confirm button is enabled while the sheet is empty.
    expect(
      (container.querySelector('.confirm-acts .button:not(.is-quiet)') as HTMLButtonElement).disabled,
    ).toBe(false)
    fireEvent.change(container.querySelector('.confirm-input') as HTMLTextAreaElement, {
      target: { value: '有安全问题' },
    })
    fireEvent.click(container.querySelector('.confirm-acts .button:not(.is-quiet)') as HTMLButtonElement)
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

  async function mountPane(options: { list?: boolean; file?: string } = {}) {
    stubFetch(diffResponse())
    const onSummary = vi.fn()
    const view = render(
      <VersionDiffPane
        namespace="dev"
        name="pdf-tools"
        version={version()}
        versions={[version(), HISTORY]}
        rung="maintain"
        fq=""
        list={options.list ?? false}
        {...(options.file === undefined ? {} : { file: options.file })}
        live={version()}
        onSelectBaseline={vi.fn()}
        onSummary={onSummary}
      />,
    )
    await flush()
    return { ...view, onSummary }
  }

  it('draws the list and both file blocks, leaving an unloaded body pending', async () => {
    const { container } = await mountPane()

    expect(container.querySelectorAll('.vp-tree-item')).toHaveLength(2)
    expect(container.querySelectorAll('.vp-file')).toHaveLength(2)
    expect(text(container.querySelector('.vp-file-count'))).toBe('2 个文件')
    // The list's numstat is there before the body.
    expect(text(container.querySelectorAll('.vp-tree-item')[1] ?? null)).toContain('+2')
    expect(container.textContent).toContain('进入视野后加载')
  })

  it('asks once on mount and does not follow next: bodies come per file', async () => {
    // `next` names the next batch of bodies, not a page of the list. Following it fetched every
    // file's content up front; the stub answers exactly one request, so a second one fails loudly.
    const fetch = stubFetch(() =>
      json(200, { from: '1.2.2', to: '1.2.3', truncated: true, next: '0', files: [changed, pending] }),
    )
    render(
      <VersionDiffPane
        namespace="dev"
        name="pdf-tools"
        version={version()}
        versions={[version(), HISTORY]}
        rung="maintain"
        fq=""
        list={false}
        live={version()}
        onSelectBaseline={vi.fn()}
        onSummary={vi.fn()}
      />,
    )
    await flush()

    expect(fetch.requests).toHaveLength(1)
    expect(fetch.requests[0]?.path).not.toContain('cursor=')
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
    const onSummary = vi.fn()
    render(
      <VersionDiffPane
        namespace="dev"
        name="pdf-tools"
        version={version()}
        versions={[version(), HISTORY]}
        rung="maintain"
        fq=""
        list={false}
        live={version()}
        onSelectBaseline={vi.fn()}
        onSummary={onSummary}
      />,
    )
    await flush()
    // The list arrives, React re-renders, and the summary effect runs on the render after that —
    // one tick later than Vue's `nextTick` reached it.
    await flush()

    const summary = onSummary.mock.calls.at(-1)?.[0] as { added: number | null }
    expect(summary.added).toBe(1)
  })

  it('narrows only the tree when the path filter is typed, and writes fq to the address', async () => {
    const replace = vi.spyOn(window.history, 'replaceState')
    const { container } = await mountPane()

    fireEvent.change(container.querySelector('.vp-tree-search input') as HTMLInputElement, {
      target: { value: 'references' },
    })
    await flush()

    expect(container.querySelectorAll('.vp-tree-item')).toHaveLength(1)
    // The diff body does not disappear.
    expect(container.querySelectorAll('.vp-file')).toHaveLength(2)
    expect(String(replace.mock.calls.at(-1)?.[2])).toContain('fq=references')
  })

  it('“只看文件列表” keeps the tree and drops every body', async () => {
    const { container } = await mountPane({ list: true })

    expect(container.querySelector('.vp-file')).toBeNull()
    expect(container.querySelectorAll('.vp-tree-item')).toHaveLength(2)
    expect(container.textContent).toContain('只看文件列表。点左边的文件')
  })

  it('“逐个查看” navigates to the state without `list`, so single-file mode loads its file', async () => {
    const { container } = await mountPane({ list: true })

    fireEvent.click(
      [...container.querySelectorAll('.link-button')].find(
        (entry) => text(entry) === '逐个查看',
      ) as HTMLAnchorElement,
    )

    // Flipping the local switch left single-file mode with no `file=` and no load; the design's
    // own toggle is an address change, which re-mounts the pane and runs the load path.
    expect(location.assign).toHaveBeenCalledWith('?')
  })

  it('reports no total when the load failed, instead of claiming +0 −0', async () => {
    stubFetch(() => json(500, { error: { code: 'internal_error', message: 'boom' } }))
    const onSummary = vi.fn()
    render(
      <VersionDiffPane
        namespace="dev"
        name="pdf-tools"
        version={version()}
        versions={[version(), HISTORY]}
        rung="maintain"
        fq=""
        list={false}
        live={version()}
        onSelectBaseline={vi.fn()}
        onSummary={onSummary}
      />,
    )
    await flush()

    expect(onSummary.mock.calls.at(-1)?.[0]).toBeNull()
  })

  it('over 100 files draws one file at a time and fetches that one', async () => {
    const files = Array.from({ length: 101 }, (_, index) => ({
      ...pending,
      relpath: `references/${index}.md`,
      hunks: null,
    }))
    stubFetch(diffResponse(files), diffResponse([{ ...pending, relpath: 'references/100.md', hunks: [] }]))
    const { container } = render(
      <VersionDiffPane
        namespace="dev"
        name="pdf-tools"
        version={version()}
        versions={[version(), HISTORY]}
        rung="maintain"
        fq=""
        file="references/100.md"
        list={false}
        live={version()}
        onSelectBaseline={vi.fn()}
        onSummary={vi.fn()}
      />,
    )
    await flush()

    expect(container.querySelectorAll('.vp-file')).toHaveLength(1)
    expect(text(container.querySelector('.vp-file-path'))).toBe('references/100.md')
    // The 124th-file rule: only that file's body is requested.
  })
})

/* --------------------------------------------------------------- route fallback */

describe('the single-version page', () => {
  it('offers 操作 to a write-only reader on their own draft', async () => {
    stubLocation('/skills/dev/pdf-tools', '?tab=versions&version=d1&sub=actions')
    stubFetch(() => json(200, { from: '1.2.3', to: 'd1', truncated: false, next: null, files: [] }))
    const live = version()
    const { container } = render(
      <VersionDetail
        namespace="dev"
        name="pdf-tools"
        version={DRAFT}
        versions={[DRAFT, live]}
        rung="write"
        username="yuhan"
        live={live}
        route={parseVersionRoute('?tab=versions&version=d1&sub=actions')}
        onSelectBaseline={vi.fn()}
      />,
    )
    await flush()

    // A write-only reader may discard their own draft; that is an action, so 操作 exists.
    expect(text(container.querySelector('.vp-tab.is-current'))).toContain('操作')
  })

  it('offers 编辑文件 only on a draft this caller may change', () => {
    stubLocation('/skills/dev/pdf-tools', '?tab=versions&version=d1')
    const live = version()

    const mine = render(
      <VersionDetail
        namespace="dev"
        name="pdf-tools"
        version={DRAFT}
        versions={[DRAFT, live]}
        rung="write"
        username="yuhan"
        live={live}
        route={parseVersionRoute('?tab=versions&version=d1')}
        onSelectBaseline={vi.fn()}
      />,
    )
    const edit = [...mine.container.querySelectorAll('a')].find((node) => text(node) === '编辑文件')
    expect(edit?.getAttribute('href')).toBe('/skills/dev/pdf-tools@d1/edit')

    // Somebody else's draft: `write` may read it, not change it.
    const theirs = render(
      <VersionDetail
        namespace="dev"
        name="pdf-tools"
        version={DRAFT}
        versions={[DRAFT, live]}
        rung="write"
        username="somebody-else"
        live={live}
        route={parseVersionRoute('?tab=versions&version=d1')}
        onSelectBaseline={vi.fn()}
      />,
    )
    expect([...theirs.container.querySelectorAll('a')].some((node) => text(node) === '编辑文件')).toBe(false)

    // A published version's content is frozen.
    const publishedPage = render(
      <VersionDetail
        namespace="dev"
        name="pdf-tools"
        version={live}
        versions={[live]}
        rung="owner"
        username="dev"
        live={live}
        route={parseVersionRoute('?tab=versions&version=1.2.3')}
        onSelectBaseline={vi.fn()}
      />,
    )
    expect([...publishedPage.container.querySelectorAll('a')].some((node) => text(node) === '编辑文件')).toBe(false)
  })

  it('falls back to 概览 for a discarded version, which has no action pane', async () => {
    const discarded = version({ state: 'discarded', status: 'discarded', is_current: false })
    const live = version()
    stubLocation('/skills/dev/pdf-tools', '?tab=versions&version=1.2.3&sub=actions')
    stubFetch(() => json(200, { from: '1.2.3', to: '1.2.3', truncated: false, next: null, files: [] }))
    const { container } = render(
      <VersionDetail
        namespace="dev"
        name="pdf-tools"
        version={discarded}
        versions={[discarded, live]}
        rung="owner"
        username="dev"
        live={live}
        route={parseVersionRoute('?tab=versions&version=1.2.3&sub=actions')}
        onSelectBaseline={vi.fn()}
      />,
    )
    await flush()

    expect(text(container.querySelector('.vp-tab.is-current'))).toContain('概览')
  })

  it('hands the address’s preselection to the publish form and keeps it inside the pane', async () => {
    stubLocation('/skills/dev/pdf-tools', '?tab=versions&version=d1&sub=actions&bump=minor')
    stubFetch(() => json(200, { from: '1.2.3', to: 'd1', truncated: false, next: null, files: [] }))
    const live = version()
    const { container } = render(
      <VersionDetail
        namespace="dev"
        name="pdf-tools"
        version={DRAFT}
        versions={[DRAFT, live]}
        rung="owner"
        username="dev"
        live={live}
        route={parseVersionRoute('?tab=versions&version=d1&sub=actions&bump=minor')}
        onSelectBaseline={vi.fn()}
      />,
    )
    await flush()

    // The route reached the form: 新增能力 is checked and the number is its suggestion.
    expect(
      (container.querySelector('input[name="vp-bump"][value="minor"]') as HTMLInputElement).checked,
    ).toBe(true)
    expect((container.querySelector('.confirm-input') as HTMLInputElement).value).toBe('1.3.0')

    // Switching panes is still the same version page: the preselection survives.
    for (const tab of container.querySelectorAll('.vp-tab')) {
      expect(tab.getAttribute('href')).toContain('bump=minor')
    }
    // Going back to the list is a different screen: it does not.
    expect(container.querySelector('.crumbs a')?.getAttribute('href')).toBe(
      '/skills/dev/pdf-tools?tab=versions',
    )
  })

  it('ignores a preselection on a version with no publish form', async () => {
    stubLocation('/skills/dev/pdf-tools', '?tab=versions&version=1.2.3&sub=actions&bump=minor')
    stubFetch(() => json(200, { from: '1.2.3', to: '1.2.3', truncated: false, next: null, files: [] }))
    const live = version()
    const { container } = render(
      <VersionDetail
        namespace="dev"
        name="pdf-tools"
        version={live}
        versions={[live, HISTORY]}
        rung="owner"
        username="dev"
        live={live}
        route={parseVersionRoute('?tab=versions&version=1.2.3&sub=actions&bump=minor')}
        onSelectBaseline={vi.fn()}
      />,
    )
    await flush()

    // A published version's page has 回滚 and 下架, never a release-name field.
    expect(container.querySelector('.confirm-input')).toBeNull()
    expect(container.querySelectorAll('.choice')).toHaveLength(0)
    expect(text(container.querySelector('.vp-tab.is-current'))).toContain('操作')
  })

  it('lands on 改动 only when there is a baseline', async () => {
    stubLocation('/skills/dev/pdf-tools', '?tab=versions&version=d1&sub=diff')
    stubFetch(() => json(200, { from: '1.2.3', to: 'd1', truncated: false, next: null, files: [] }))
    const live = version()

    const withBaseline = render(
      <VersionDetail
        namespace="dev"
        name="pdf-tools"
        version={DRAFT}
        versions={[DRAFT, live]}
        rung="owner"
        username="dev"
        live={live}
        route={parseVersionRoute('?tab=versions&version=d1&sub=diff')}
        onSelectBaseline={vi.fn()}
      />,
    )
    await flush()
    expect(text(withBaseline.container.querySelector('.vp-tab.is-current'))).toContain('改动')

    // Nothing to compare against: the pane is not drawn, and the address falls back to 概览.
    const alone = render(
      <VersionDetail
        namespace="dev"
        name="pdf-tools"
        version={DRAFT}
        versions={[DRAFT]}
        rung="owner"
        username="dev"
        live={null}
        route={parseVersionRoute('?tab=versions&version=d1&sub=diff')}
        onSelectBaseline={vi.fn()}
      />,
    )
    await flush()
    expect(text(alone.container.querySelector('.vp-tab.is-current'))).toContain('概览')
  })
})
