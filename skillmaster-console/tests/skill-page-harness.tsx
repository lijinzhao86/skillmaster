/**
 * The harness a test needs to render `SkillPage`'s 信息 tab: the page's reads answered in order,
 * the settling loop, and the empty word-group answer. Split out of `skills.test.tsx` because that
 * file is on the size baseline (may only shrink) and v59 added to it.
 */
import { render } from '@testing-library/react'
import SkillPage from '../src/pages/SkillPage'
import { flush, json, stubFetch, stubLocation, type RecordedRequest } from './support'

export const text = (element: Element | null | undefined): string => (element?.textContent ?? '').trim()

/**
 * Lets a chain of reads settle.
 *
 * Vue's `flush()` was one microtask turn, which was enough for a ref update to reach the DOM. React
 * commits an effect's state change on its own scheduler turn, and this page reads in chains (the
 * detail, then a child component's own list, then the count); one `setTimeout(0)` only covers the
 * first of them. Several turns, each `flush()`'s own, keep the same intent without asserting on how
 * many turns a render takes.
 */
export async function settle(): Promise<void> {
  for (let turn = 0; turn < 6; turn += 1) {
    await flush()
  }
}


/**
 * Renders the skill page with the reads it makes on the way in already answered: the detail, then
 * the file when the address names one, then the comparison when it names one.
 */
/**
 * The three word groups the information page reads (v14). An empty, read-only answer is enough for
 * every test that is about the rest of the page; the tag tests below supply their own.
 */
export const EMPTY_TAGS = {
  category: null,
  public_tags: [],
  my_tags: { readable: false, reason: 'not_in_library', tags: [] },
  /* An entry the author owns: active, and neither joinable nor removable. */
  library: { source: 'created', active: true, joinable: false, can_remove: false },
}

/** `GET /web/categories`: the information page's category menu reads it. */
export const CATEGORY_VOCABULARY = {
  categories: [
    { key: 'doc', name: '文档与演示', state: 'enabled', public_total: 4 },
    { key: 'data', name: '数据与分析', state: 'enabled', public_total: 7 },
    { key: 'ops', name: '运维与云', state: 'stopped', public_total: 2 },
  ],
}

export async function showSkill(
  skill: Record<string, unknown>,
  options: {
    query?: string
    relpath?: string
    file?: () => Response
    diff?: () => Response
    version?: string
    tags?: () => Response
  } = {},
): Promise<{ container: HTMLElement; requests: RecordedRequest[] }> {
  stubLocation('/skills/demo-user/pdf-tools', options.query ?? '')
  const query = options.query ?? ''
  const responders: Array<() => Response> = [() => json(200, skill)]
  /* A comparison is read by a child panel, whose effect runs before the page's own; the file and
     the word groups are the other way round. */
  if (options.diff !== undefined) {
    responders.push(options.diff)
  }
  /* The word groups and the vocabulary are read on every tab (the page head's join/remove action
     lives there), before the files section's own reads. */
  responders.push(options.tags ?? (() => json(200, EMPTY_TAGS)))
  responders.push(() => json(200, CATEGORY_VOCABULARY))
  if (options.relpath !== undefined) {
    responders.push(options.file ?? (() => new Response('# 标题\n\n正文\n', { status: 200 })))
  }
  if (skill.rung === 'owner' || skill.rung === 'admin') {
    // The settings nav's count: "somebody is waiting" has two homes, and this is one.
    responders.push(() => json(200, { requests: [], total: 0 }))
  }
  const stub = stubFetch(...responders)
  const { container } = render(
    <SkillPage
      namespace="demo-user"
      name="pdf-tools"
      version={options.version}
      relpath={options.relpath}
    />,
  )
  await settle()
  return { container, requests: stub.requests }
}

/** The fixtures the page renders; shared with tests that are about one read. */
export const LIVE_VERSION = {
  name: '1.0.0',
  digest: 'sha256:aaa',
  draft_name: '第一版',
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

export const DRAFT_VERSION = {
  name: null,
  digest: 'sha256:bbb',
  draft_name: '第二版',
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
  file_count: 1,
  total_bytes: 90,
  revoked_reason: null,
}

export const SKILL = {
  namespace: { slug: 'demo-user' },
  name: 'pdf-tools',
  title: 'PDF Tools',
  description: '把 PDF 拆开再拼起来',
  // The server lifts these two from the frontmatter (TD 附录 A); the page no longer parses it.
  when_to_use: '需要拆分或合并 PDF 时',
  compatibility: '需要 ghostscript',
  visibility: 'private',
  public_rung: null,
  offline_at: null,
  rung: 'owner',
  pending_rung: null,
  frontmatter: {
    name: 'pdf-tools',
    compatibility: '需要 ghostscript',
    metadata: { when_to_use: '需要拆分或合并 PDF 时' },
  },
  version: DRAFT_VERSION,
  versions: [DRAFT_VERSION, LIVE_VERSION],
  files: [{ relpath: 'SKILL.md', sha256: 'sha256:ddd', size: 90, is_binary: false }],
  resources: { body: '/web/skills/demo-user/pdf-tools@2.0.0/body' },
}
