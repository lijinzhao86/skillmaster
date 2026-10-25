import { describe, expect, it } from 'vitest'
import {
  draftLabel,
  group,
  movedSentence,
  noteSentence,
  needsNumber,
  needsType,
  previewOf,
  rowsOf,
  selectable,
  settledBody,
  sheetLines,
} from '../src/components/publish/publishModel'
import type { PendingDraft } from '../src/components/publish/publishModel'
import type { PublishQueue } from '../src/api/types'

/**
 * 待上线's arithmetic: **ADR 0091's rules, and nothing else**.
 *
 * The number a draft would become is what the whole page is about, and it is computed in one place
 * so the row, the confirmation sheet and the select-all cannot disagree. These tests are statements
 * about that one place.
 */

function draft(overrides: Partial<PendingDraft> = {}): PendingDraft {
  return {
    id: 'dev/pdf-tools@K3M9QX2P',
    namespace: 'dev',
    skill: 'pdf-tools',
    code: 'K3M9QX2P',
    draft: '补文档',
    live: '1.2.3',
    submitted: '2026/10/25 09:12',
    release: 'minor',
    ...overrides,
  }
}

function skill(
  name: string,
  current: { version: string | null; submitted_at: string } | null,
  drafts: Array<{ code: string; name: string | null; release_type: 'major' | 'minor' | 'patch' | null; submitted_at: string }>,
): Record<string, unknown> {
  return { namespace: 'dev', name, current, drafts }
}

describe('the number a draft would become', () => {
  it('moves the digit the kind names and drops build metadata', () => {
    expect(previewOf(draft({ live: '1.2.3', release: 'major' }))).toBe('2.0.0')
    expect(previewOf(draft({ live: '1.2.3', release: 'minor' }))).toBe('1.3.0')
    expect(previewOf(draft({ live: '1.2.3', release: 'patch' }))).toBe('1.2.4')
    expect(previewOf(draft({ live: '1.2.3+build.7', release: 'patch' }))).toBe('1.2.4')
  })

  it('is 1.0.0 for a skill that has never been published, whatever the kind says', () => {
    expect(previewOf(draft({ live: null, release: 'major' }))).toBe('1.0.0')
    expect(previewOf(draft({ live: null, release: null }))).toBe('1.0.0')
  })

  it('cannot be computed without a kind, and says so as a missing kind', () => {
    const row = draft({ live: '1.2.3', release: null })
    expect(previewOf(row)).toBeNull()
    expect(needsType(row)).toBe(true)
    expect(needsNumber(row)).toBe(false)
  })

  it('refuses a prerelease baseline — and that is a missing number, not a missing kind', () => {
    // **The kind does not help**: `2.0.0-rc.2` has no release digit to increment, so choosing
    // another kind still yields nothing. The row must offer no picker and say "指定版本号".
    const row = draft({ live: '2.0.0-rc.2', release: null })
    expect(previewOf(row)).toBeNull()
    expect(needsNumber(row)).toBe(true)
    expect(needsType(row)).toBe(false)
  })

  it('refuses a live version that carries no number at all (a historical row)', () => {
    const row = draft({ live: '', release: 'patch' })
    expect(previewOf(row)).toBeNull()
    expect(needsNumber(row)).toBe(true)
    expect(needsType(row)).toBe(false)
    expect(selectable(row)).toBe(false)
  })

  it('refuses a baseline that is not a version at all — and that is a missing number too', () => {
    // **`Number.parseInt` would have said `2.0.0` for `1a.0.0`.** The server answers every one of
    // these with `release_type_needs_explicit_name` (TD §算号), so the page must not preview a number
    // the server will refuse, and must not offer a kind picker that cannot help.
    for (const live of ['1a.0.0', '1.0', 'v1.0.0', 'latest']) {
      const row = draft({ live, release: 'major' })
      expect(previewOf(row), live).toBeNull()
      expect(needsNumber(row), live).toBe(true)
      expect(needsType(row), live).toBe(false)
      expect(selectable(row), live).toBe(false)
    }
    // Build metadata stays usable: the hyphen in it is not a prerelease marker.
    expect(previewOf(draft({ live: '1.0.0+build-7', release: 'patch' }))).toBe('1.0.1')
  })
})

describe('the queue as rows', () => {
  it('maps the three states of a live version', () => {
    const queue = {
      truncated: false,
      skills: [
        skill('never', null, [
          { code: 'AAAAAAAA', name: '初版', release_type: null, submitted_at: '2026-10-25T09:40:00Z' },
        ]),
        skill('no-number', { version: '', submitted_at: '2026-10-01T00:00:00Z' }, [
          { code: 'BBBBBBBB', name: '补一节', release_type: 'patch', submitted_at: '2026-10-24T10:02:00Z' },
        ]),
        skill('live', { version: '1.6.2', submitted_at: '2026-10-01T00:00:00Z' }, [
          { code: 'CCCCCCCC', name: '', release_type: 'patch', submitted_at: '2026-10-20T08:31:00Z' },
        ]),
      ],
    } as unknown as PublishQueue

    const rows = rowsOf(queue)
    expect(rows.map((row) => row.live)).toEqual([null, '', '1.6.2'])
    // **An empty name is its own fact**: the page says 未命名 rather than drawing nothing.
    expect(draftLabel(rows[2]!)).toBe('「未命名」')
  })

  it('keeps the server order and groups only adjacent drafts', () => {
    // The order is the server's (TD §接口); sorting again here would make two orders exist.
    const rows = [
      draft({ id: 'a/one@1', skill: 'one' }),
      draft({ id: 'a/two@1', skill: 'two' }),
      draft({ id: 'a/two@2', skill: 'two' }),
      draft({ id: 'a/three@1', skill: 'three' }),
    ]
    expect(group(rows).map((entry) => `${entry.skill}:${entry.drafts.length}`)).toEqual([
      'one:1',
      'two:2',
      'three:1',
    ])
  })
})

describe('what the sheet and the settlement say', () => {
  it('lists a draft by name, its kind, and what it becomes', () => {
    expect(sheetLines([draft({ live: null, release: null })])).toEqual([
      { name: 'pdf-tools · 「补文档」', version: '1.0.0', note: '首次上线' },
    ])
    expect(sheetLines([draft()])[0]).toEqual({
      name: 'pdf-tools · 「补文档」',
      version: '1.3.0',
      note: '次版本',
    })
  })

  it('says which skill moved, to what, and what this one actually became', () => {
    // **预演不是承诺**（PRD 验收 16）. voice-spec §四 wants 时间、对象、事实 — 「有人抢先上线了更
    // 高的版本」 names none of them, and is the sentence it rejects.
    expect(
      movedSentence([
        { namespace: 'dev', skill: 'changelog-writer', predicted: '1.1.0', actual: '1.2.0' },
      ]),
    ).toBe('确认之后，changelog-writer 已被上线至 1.1.0，因此这一份的实际版本号为 1.2.0。')
  })

  it('says what the moved number does to the drafts still waiting', () => {
    // The design's sentence, generalised: the batch published one draft, and the group's remaining
    // draft now computes from the version this batch put live — not from the version it saw before.
    const remaining = [draft({ id: 'dev/changelog-writer@BBBBBBBB', skill: 'changelog-writer',
      draft: '修错别字', live: '1.2.0', release: 'patch' })]
    expect(
      noteSentence(
        [{ namespace: 'dev', skill: 'changelog-writer', predicted: '1.1.0', actual: '1.2.0' }],
        remaining,
      ),
    ).toBe('确认之后，changelog-writer 已被上线至 1.1.0，因此这一份的实际版本号为 1.2.0；' +
        '同组还留着的「修错别字」现在以 1.2.1 上线。')
  })

  it('says only the first half when nothing of that skill is left', () => {
    expect(
      noteSentence(
        [{ namespace: 'dev', skill: 'changelog-writer', predicted: '1.1.0', actual: '1.2.0' }],
        [],
      ),
    ).toBe('确认之后，changelog-writer 已被上线至 1.1.0，因此这一份的实际版本号为 1.2.0。')
  })

  it('does not speak for an identically named skill in another namespace', () => {
    // **The bar is namespace and name together.** `yuhan` also has a `changelog-writer`; its drafts
    // were not in this batch, and a banner naming them would describe a number this page never saw.
    const other = draft({ id: 'yuhan/changelog-writer@DDDDDDDD', namespace: 'yuhan',
      skill: 'changelog-writer', draft: '别人的草稿', live: '1.2.0', release: 'patch' })
    expect(
      noteSentence(
        [{ namespace: 'dev', skill: 'changelog-writer', predicted: '1.1.0', actual: '1.2.0' }],
        [other],
      ),
    ).toBe('确认之后，changelog-writer 已被上线至 1.1.0，因此这一份的实际版本号为 1.2.0。')
  })

  it('does not repeat the count the title already carries', () => {
    expect(settledBody([], 0)).toBe('以上草稿已从列表移除。')
    expect(settledBody([], 1)).toBe('失败的草稿未从列表移除，原因见对应行。')
  })
})
