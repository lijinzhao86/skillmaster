import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render } from '@testing-library/react'
import PublishPage from '../src/pages/PublishPage'
import { clearCookies, errorBody, json, setCookie, stubFetch, stubLocation, flush } from './support'

/**
 * 待上线 (v18): the queue, the kind picker that writes back, and the batch that lands one row at a
 * time.
 *
 * What these assert is the protocol and the sentences — which request went out, what the sheet
 * listed, what each row says when it comes back — not a re-implementation of the arithmetic (that
 * is `publish-model.test.ts`'s subject).
 */

let location = stubLocation('/publish')

beforeEach(() => {
  location = stubLocation('/publish')
  setCookie('XSRF-TOKEN', 'token-one')
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  location.restore()
  clearCookies()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function line(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    code: 'K3M9QX2P',
    name: '补测试',
    release_type: 'minor',
    submitted_at: '2026-10-24T10:12:00Z',
    ...overrides,
  }
}

function skill(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    namespace: 'dev',
    name: 'changelog-writer',
    current: { version: '1.0.0', submitted_at: '2026-10-20T08:00:00Z' },
    drafts: [line()],
    ...overrides,
  }
}

function queue(skills: Array<Record<string, unknown>>, truncated = false): Record<string, unknown> {
  return { skills, truncated }
}

/** The batch route's answer: one JSON object per line, exactly as the server writes it. */
function ndjson(lines: Array<Record<string, unknown>>): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      const encoder = new TextEncoder()
      for (const entry of lines) {
        controller.enqueue(encoder.encode(JSON.stringify(entry) + '\n'))
      }
      controller.close()
    },
  })
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'application/x-ndjson' },
  })
}

/**
 * A batch answer the test drives line by line — the only way to look at the sheet *while* it runs,
 * and the only way to break the connection *after* lines have been read.
 *
 * `ndjson()` delivers everything at once, which is a finished batch wearing a running batch's coat.
 * A stream errored in `start()` would lose the lines enqueued before it: `error()` clears the queue,
 * so a cut has to happen after the reader has taken them.
 */
function live(): {
  response: Response
  send: (entry: Record<string, unknown>) => void
  end: () => void
  fail: () => void
} {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(seed) {
      controller = seed
    },
  })
  return {
    response: new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'application/x-ndjson' },
    }),
    send: (entry) => controller.enqueue(encoder.encode(JSON.stringify(entry) + '\n')),
    end: () => controller.close(),
    fail: () => controller.error(new Error('connection lost')),
  }
}

/** The visible acts row: the running placeholder is a second `.confirm-acts` with `hidden`. */
function actsOf(container: HTMLElement): HTMLButtonElement[] {
  const acts = [...container.querySelectorAll('.confirm-acts')].find(
    (row) => !row.hasAttribute('hidden'),
  )
  return acts === undefined ? [] : [...acts.querySelectorAll('button')]
}

async function showQueue(...responses: Array<() => Response>) {
  const { requests } = stubFetch(...responses)
  const view = render(<PublishPage />)
  await flush()
  await flush()
  return { ...view, requests }
}

describe('the queue', () => {
  it('draws one row per draft and counts both drafts and skills', async () => {
    const { container } = await showQueue(() =>
      json(
        200,
        queue([
          skill({
            drafts: [
              line({ code: 'AAAAAAAA', name: '补测试', release_type: 'minor' }),
              line({ code: 'BBBBBBBB', name: '修错别字', release_type: 'patch' }),
            ],
          }),
          skill({ name: 'perf-budget', current: null, drafts: [line({ code: 'CCCCCCCC', name: '初版', release_type: null })] }),
        ]),
      ),
    )

    expect(container.querySelector('.count')?.textContent).toBe('3 份草稿待上线（2 个 skill）')
    // Two drafts of one skill are two rows (the page's subject is the draft, not the skill), and
    // the skill gets a heading only because it has more than one.
    expect(container.querySelectorAll('#publishList > .publish-row')).toHaveLength(4)
    expect(container.querySelector('.publish-row.is-head .chip')?.textContent).toBe('2 份草稿')
    // A skill never published: 1.0.0 whatever the kind says, and no picker.
    expect(container.textContent).toContain('将以 1.0.0 上线（首次上线）')
  })

  it('recomputes the number the moment the kind changes on an already-typed row', async () => {
    const { container, requests } = await showQueue(
      () =>
        json(
          200,
          queue([
            skill({
              current: { version: '0.9.0', submitted_at: '2026-10-01T00:00:00Z' },
              drafts: [line({ name: '重写分类', release_type: 'major' })],
            }),
          ]),
        ),
      () => json(200, { release_type: 'minor' }),
    )

    // The number itself is the picker's trigger: 0.9.0 + major is 1.0.0.
    expect(container.querySelector('.menu-trigger.release-see .menu-trigger-text')?.textContent).toBe(
      '1.0.0',
    )
    fireEvent.click(container.querySelector('.menu-trigger.release-see') as HTMLButtonElement)
    fireEvent.click(container.querySelector('.menu-item[data-value="minor"]') as HTMLButtonElement)
    await flush()
    await flush()

    expect(requests[1]?.method).toBe('PATCH')
    expect(requests[1]?.body).toEqual({ release_type: 'minor' })
    expect(container.querySelector('.menu-trigger.release-see .menu-trigger-text')?.textContent).toBe(
      '0.10.0',
    )
  })

  it('offers no kind picker to a skill that has never been published', async () => {
    const { container } = await showQueue(() =>
      json(
        200,
        queue([
          skill({
            name: 'perf-budget',
            current: null,
            drafts: [line({ code: 'CCCCCCCC', name: '初版', release_type: null })],
          }),
        ]),
      ),
    )

    // **The kind does not take part in a first publish** (ADR 0091): 1.0.0 is fixed, so a picker
    // would be a menu whose answer changes nothing. The row is still tickable as it stands.
    expect(container.textContent).toContain('将以 1.0.0 上线（首次上线）')
    expect(container.querySelector('.release-pick')).toBeNull()
    expect((container.querySelector('.pick') as HTMLInputElement).disabled).toBe(false)
  })

  it('keeps a draft somebody ticked by hand when 全选 fills in the rest', async () => {
    const { container } = await showQueue(() =>
      json(
        200,
        queue([
          skill({
            drafts: [
              line({ code: 'AAAAAAAA', name: '补测试', release_type: 'minor' }),
              line({ code: 'BBBBBBBB', name: '修错别字', release_type: 'patch' }),
            ],
          }),
          skill({ name: 'pdf-tools', drafts: [line({ code: 'CCCCCCCC', name: '补文档' })] }),
        ]),
      ),
    )

    // **The older one on purpose.** Select-all fills in skills that have no decision yet; it does
    // not overrule a decision somebody already made.
    const rows = [...container.querySelectorAll('.publish-row .pick')] as HTMLInputElement[]
    fireEvent.click(rows[1] as HTMLInputElement)
    await flush()
    fireEvent.click(container.querySelector('#publishAll') as HTMLButtonElement)
    await flush()

    expect(rows[1]?.checked).toBe(true)
    // Its sibling is disabled, not silently swapped for the newest one.
    expect(rows[0]?.checked).toBe(false)
    expect(rows[0]?.disabled).toBe(true)
    expect(rows[2]?.checked).toBe(true)
    expect(container.querySelector('.select-count')?.textContent).toBe('已选 2 份')
  })

  it('narrows to the skills that have more than one draft', async () => {
    const { container } = await showQueue(() =>
      json(
        200,
        queue([
          skill({
            drafts: [
              line({ code: 'AAAAAAAA', name: '补测试', release_type: 'minor' }),
              line({ code: 'BBBBBBBB', name: '修错别字', release_type: 'patch' }),
            ],
          }),
          skill({ name: 'pdf-tools', drafts: [line({ code: 'CCCCCCCC', name: '补文档' })] }),
        ]),
      ),
    )

    fireEvent.click(container.querySelectorAll('.filter-select .menu-trigger')[1] as HTMLButtonElement)
    fireEvent.click(container.querySelector('.menu-item[data-value="pick"]') as HTMLButtonElement)
    await flush()

    // One skill with two drafts survives; the single-draft skill leaves, count and all.
    expect(container.querySelectorAll('.publish-row.is-child')).toHaveLength(2)
    expect(container.querySelector('.count')?.textContent).toBe('2 份草稿待上线（1 个 skill）')
    expect(container.textContent).not.toContain('pdf-tools')
  })

  it('says so when the read was truncated', async () => {
    const { container } = await showQueue(() => json(200, queue([skill()], true)))
    expect(container.querySelector('.count-note')?.textContent).toBe('只列出了最近 500 个 skill')
  })

  it('cannot tick a row with no kind, and writes the kind back the moment one is chosen', async () => {
    const { container, requests } = await showQueue(
      () => json(200, queue([skill({ current: { version: '0.1.0', submitted_at: '2026-10-01T00:00:00Z' }, drafts: [line({ name: '加锁表规则', release_type: null })] })])),
      () => json(200, { release_type: 'minor' }),
    )

    const box = container.querySelector('.pick') as HTMLInputElement
    expect(box.disabled).toBe(true)
    expect(container.textContent).toContain('未指定版本类型')

    fireEvent.click(container.querySelector('.release-pick .menu-trigger') as HTMLButtonElement)
    fireEvent.click(container.querySelector('.menu-item[data-value="minor"]') as HTMLButtonElement)
    await flush()
    await flush()

    // **写回服务端，不是客户端状态**：the CLI reads the same field, so a value living only in this
    // tab would let the two surfaces report different things.
    expect(requests[1]?.method).toBe('PATCH')
    expect(requests[1]?.path).toBe('/web/skills/dev/changelog-writer/versions/K3M9QX2P/release-type')
    expect(requests[1]?.body).toEqual({ release_type: 'minor' })
    // The number is the trigger's own text: the picker that chose the kind is gone, because the row
    // now has one and the number is what is left to look at.
    expect(container.querySelector('.menu-trigger.release-see .menu-trigger-text')?.textContent).toBe(
      '0.2.0',
    )
    expect((container.querySelector('.pick') as HTMLInputElement).disabled).toBe(false)
  })

  it('does not repeat the count above the empty state', async () => {
    const { container } = await showQueue(() => json(200, queue([])))
    // voice-spec: an empty state does not restate what its own sentence just said.
    expect(container.querySelector('.count')).toBeNull()
    expect(container.querySelector('.empty')?.textContent).toContain('没有待上线的草稿')
  })

  it('counts the drafts on the page, not every draft the skill has', async () => {
    const { container } = await showQueue(
      () => json(200, queue([
        skill({
          drafts: [
            line({ code: 'AAAAAAAA', name: '补测试', release_type: 'minor' }),
            line({ code: 'BBBBBBBB', name: '补文档', release_type: 'patch' }),
            line({ code: 'CCCCCCCC', name: '修错别字', release_type: 'patch' }),
          ],
        }),
      ])),
    )
    // Three drafts, so a header. The search keeps two of them: the header has to agree with the
    // rows drawn under it — "3 份草稿" over two rows is one sentence contradicting itself.
    expect(container.querySelector('.publish-row.is-head .chip-text')?.textContent).toBe('3 份草稿')

    fireEvent.change(container.querySelector('#publishQuery') as HTMLInputElement, {
      target: { value: '补' },
    })
    await flush()
    expect(container.querySelectorAll('.publish-row.is-child')).toHaveLength(2)
    expect(container.querySelector('.publish-row.is-head .chip-text')?.textContent).toBe('2 份草稿')
  })

  it('selects the newest draft of each skill and never two drafts of one skill', async () => {
    const { container } = await showQueue(() =>
      json(
        200,
        queue([
          skill({
            drafts: [
              line({ code: 'AAAAAAAA', name: '补测试', release_type: 'minor' }),
              line({ code: 'BBBBBBBB', name: '修错别字', release_type: 'patch' }),
            ],
          }),
          skill({ name: 'no-kind', drafts: [line({ code: 'CCCCCCCC', name: '初版', release_type: null })] }),
        ]),
      ),
    )

    fireEvent.click(container.querySelector('#publishAll') as HTMLButtonElement)
    await flush()

    // **一批里同一个 skill 只发一份**：全选挑最新那份，挑不了的那些（缺类型）不进去。
    expect(container.querySelector('.select-count')?.textContent).toBe('已选 1 份')
    expect(container.textContent).toContain('1 个 skill 取最新草稿、1 份未指定版本类型')
    // The second draft of the same skill says why it cannot be ticked.
    const rows = [...container.querySelectorAll('.publish-row.is-child')]
    // The newest draft is the one select-all took; the older one says why it cannot be ticked.
    expect(rows[0]?.querySelector('.pick') as HTMLInputElement).toHaveProperty('checked', true)
    expect(rows[1]?.textContent).toContain('同批已选择该 skill 的另一份草稿')
    expect((rows[1]?.querySelector('.pick') as HTMLInputElement).disabled).toBe(true)
  })
})

describe('the batch', () => {
  const ROWS = [
    skill({
      drafts: [
        line({ code: 'AAAAAAAA', name: '补测试', release_type: 'minor' }),
        line({ code: 'BBBBBBBB', name: '修错别字', release_type: 'patch' }),
      ],
    }),
    skill({
      name: 'pdf-tools',
      current: { version: '1.2.3', submitted_at: '2026-10-01T00:00:00Z' },
      drafts: [line({ code: 'CCCCCCCC', name: '补文档', release_type: 'patch' })],
    }),
  ]

  it('lists what it is about to publish, then lands each row on its own answer', async () => {
    const { container, requests } = await showQueue(
      () => json(200, queue(ROWS)),
      () =>
        ndjson([
          { type: 'start', total: 2 },
          { type: 'row', ok: true, namespace: 'dev', name: 'changelog-writer', version: '1.1.0', draft_name: '补测试', predicted: '1.1.0' },
          { type: 'row', ok: false, namespace: 'dev', name: 'pdf-tools', code: 'version_already_exists', message: 'taken' },
          { type: 'done', ok: 1, failed: 1 },
        ]),
      () => json(200, queue(ROWS)),
    )

    // Select the two newest drafts.
    const boxes = [...container.querySelectorAll('.pick')] as HTMLInputElement[]
    fireEvent.click(boxes[0] as HTMLInputElement)
    fireEvent.click(boxes[2] as HTMLInputElement)
    await flush()

    fireEvent.click(container.querySelector('#publishGo') as HTMLButtonElement)
    await flush()

    // **确认单逐条列名字与版本号**：one line per draft, with the number it would become.
    const lines = [...container.querySelectorAll('.confirm-line')]
    expect(lines.map((row) => row.querySelector('.confirm-line-name')?.textContent)).toEqual([
      'changelog-writer · 「补测试」',
      'pdf-tools · 「补文档」',
    ])
    expect(lines[1]?.querySelector('.confirm-line-version')?.textContent).toBe('1.2.4')

    fireEvent.click(actsOf(container).at(-1) as HTMLButtonElement)
    await flush()
    await flush()

    // The pin is the draft's **bare** code — no `draft:` prefix (ADR 0063) — and the prediction is
    // echoed so the server can contradict it.
    expect(requests[1]?.path).toBe('/web/skills/publish-batch')
    expect(requests[1]?.body).toEqual({
      targets: [
        { namespace: 'dev', name: 'changelog-writer', version: 'AAAAAAAA', predicted: '1.1.0' },
        { namespace: 'dev', name: 'pdf-tools', version: 'CCCCCCCC', predicted: '1.2.4' },
      ],
    })

    // **一条一条地落**：the success shows the number the server assigned, the failure shows why.
    expect(container.querySelector('.confirm-title')?.textContent).toBe('1 份已上线，1 份失败')
    expect(container.querySelector('.confirm-line.is-ok .confirm-line-version')?.textContent).toBe('1.1.0')
    expect(container.querySelector('.confirm-line.is-failed .confirm-line-message')?.textContent).toBe(
      '这个版本号已经被同一 skill 的另一版用了；版本号不能复用。',
    )

    // 完成: the queue is re-read and the sheet closes.
    fireEvent.click(actsOf(container).at(-1) as HTMLButtonElement)
    await flush()
    await flush()
    expect(container.querySelector('.confirm')).toBeNull()
    expect(requests[2]?.path).toBe('/web/publish-queue')
    // The one that failed keeps its reason on its own row after the re-read.
    expect(container.textContent).toContain('这个版本号已经被同一 skill 的另一版用了；版本号不能复用。')
  })

  it('closes on 取消 without sending anything', async () => {
    const { container, requests } = await showQueue(() => json(200, queue(ROWS)))

    fireEvent.click(container.querySelectorAll('.pick')[0] as HTMLInputElement)
    await flush()
    fireEvent.click(container.querySelector('#publishGo') as HTMLButtonElement)
    await flush()
    expect(container.querySelector('.confirm')).not.toBeNull()

    fireEvent.click(actsOf(container)[0] as HTMLButtonElement)
    await flush()

    // Nothing happened, so nothing was sent: the only request is the queue read.
    expect(container.querySelector('.confirm')).toBeNull()
    expect(requests).toHaveLength(1)
  })

  it('breaks the stream mid-batch and counts the rows nobody heard from as 没有结果', async () => {
    // Four skills, each with one draft. The stream reports the first two, then dies: one published,
    // one refused. The last two never happened — and saying they failed would invent a reason.
    const four = [
      skill({ drafts: [line({ code: 'AAAAAAAA', name: '补测试', release_type: 'minor' })] }),
      skill({ name: 'pdf-tools', drafts: [line({ code: 'BBBBBBBB', name: '补文档', release_type: 'patch' })] }),
      skill({ name: 'log-triage', drafts: [line({ code: 'CCCCCCCC', name: '重写分类', release_type: 'major' })] }),
      skill({ name: 'perf-budget', current: null, drafts: [line({ code: 'DDDDDDDD', name: '初版', release_type: null })] }),
    ]
    const stream = live()
    const { container } = await showQueue(
      () => json(200, queue(four)),
      () => stream.response,
    )

    for (const box of [...container.querySelectorAll('.pick')] as HTMLInputElement[]) {
      fireEvent.click(box)
    }
    await flush()
    fireEvent.click(container.querySelector('#publishGo') as HTMLButtonElement)
    await flush()
    fireEvent.click(actsOf(container).at(-1) as HTMLButtonElement)
    await flush()

    stream.send({ type: 'start', total: 4 })
    // **The one row that landed moved its number** (predicted 1.1.0, actual 1.2.0): the settlement
    // must still say so, even though the rest of the batch never reported.
    stream.send({ type: 'row', ok: true, namespace: 'dev', name: 'changelog-writer', version: '1.2.0', draft_name: '补测试', predicted: '1.1.0' })
    stream.send({ type: 'row', ok: false, namespace: 'dev', name: 'pdf-tools', code: 'version_already_exists', message: 'taken' })
    await flush()
    stream.fail()
    await flush()
    await flush()

    // **Three clauses, each its own fact.** A row that said "taken" is a failure, not silence.
    expect(container.querySelector('.confirm-title')?.textContent).toBe(
      '1 份已上线，1 份失败，2 份没有结果',
    )
    expect(
      [...container.querySelectorAll('.confirm-line')].map((row) => row.className),
    ).toEqual([
      'confirm-line is-ok',
      'confirm-line is-failed',
      'confirm-line is-wait',
      'confirm-line is-wait',
    ])
    // The two silent rows keep their preview — they are back to "not yet run", not to a verdict.
    // **And the moved number is still said out loud** (验收 16): a landed row whose number changed
    // is a fact about this batch, not a casualty of the connection.
    expect(container.textContent).toContain(
      '确认之后，changelog-writer 已被上线至 1.1.0，因此这一份的实际版本号为 1.2.0。' +
        '连接中断了：已上线的不会撤回，其余的请重新上线。',
    )
  })

  it('does not call a batch interrupted when only the done line was lost', async () => {
    // Every row reported; the stream then died before `done`. The server did finish the batch, so
    // saying 「连接中断了」 would describe a batch that never ran to the end — it did.
    const stream = live()
    const { container } = await showQueue(
      () => json(200, queue(ROWS)),
      () => stream.response,
    )

    const boxes = [...container.querySelectorAll('.pick')] as HTMLInputElement[]
    fireEvent.click(boxes[0] as HTMLInputElement)
    fireEvent.click(boxes[2] as HTMLInputElement)
    await flush()
    fireEvent.click(container.querySelector('#publishGo') as HTMLButtonElement)
    await flush()
    fireEvent.click(actsOf(container).at(-1) as HTMLButtonElement)
    await flush()

    stream.send({ type: 'start', total: 2 })
    stream.send({ type: 'row', ok: true, namespace: 'dev', name: 'changelog-writer', version: '1.1.0', draft_name: '补测试', predicted: '1.1.0' })
    stream.send({ type: 'row', ok: false, namespace: 'dev', name: 'pdf-tools', code: 'version_already_exists', message: 'taken' })
    await flush()
    // The connection closes without its `done` line — every row was heard.
    stream.end()
    await flush()
    await flush()

    expect(container.querySelector('.confirm-title')?.textContent).toBe('1 份已上线，1 份失败')
    expect(container.textContent).not.toContain('连接中断了')
  })

  it('shows the batch running, refuses Esc, and counts only the rows that came back', async () => {
    const stream = live()
    const { container } = await showQueue(
      () => json(200, queue(ROWS)),
      () => stream.response,
    )

    const boxes = [...container.querySelectorAll('.pick')] as HTMLInputElement[]
    fireEvent.click(boxes[0] as HTMLInputElement)
    fireEvent.click(boxes[2] as HTMLInputElement)
    await flush()
    fireEvent.click(container.querySelector('#publishGo') as HTMLButtonElement)
    await flush()
    fireEvent.click(actsOf(container).at(-1) as HTMLButtonElement)
    await flush()

    // **执行中关不掉**: one row has not come back, and the batch has already started. The event goes
    // on the sheet itself — React 19 delegates at the root container, so an event dispatched on
    // `document` never reaches the dialog's own handler and would prove nothing.
    expect(container.querySelector('.confirm-title')?.textContent).toBe('正在上线 2 份草稿')
    fireEvent.keyDown(container.querySelector('.confirm') as HTMLElement, { key: 'Escape' })
    await flush()
    expect(container.querySelector('.confirm')).not.toBeNull()
    expect(actsOf(container).map((button) => button.textContent)).toEqual(['正在上线…'])
    expect((actsOf(container)[0] as HTMLButtonElement).disabled).toBe(true)

    stream.send({ type: 'start', total: 2 })
    stream.send({ type: 'row', ok: true, namespace: 'dev', name: 'changelog-writer', version: '1.1.0', draft_name: '补测试', predicted: '1.1.0' })
    await flush()
    await flush()

    // **进度只数已经落地的条数**: one came back, one did not.
    expect(container.querySelector('.confirm-progress-count')?.textContent).toBe('1 / 2')
    expect(container.querySelector('.confirm-progress-track')?.getAttribute('aria-valuenow')).toBe('1')

    stream.send({ type: 'row', ok: false, namespace: 'dev', name: 'pdf-tools', code: 'version_already_exists', message: 'taken' })
    stream.send({ type: 'done', ok: 1, failed: 1 })
    stream.end()
    await flush()
    await flush()

    // Settled: the progress bar is gone because there is nothing left to wait for.
    expect(container.querySelector('.confirm-progress')).toBeNull()
    expect(container.querySelector('.confirm-title')?.textContent).toBe('1 份已上线，1 份失败')
  })

  it('says the number moved, and what that did to the drafts still waiting', async () => {
    const { container } = await showQueue(
      () => json(200, queue(ROWS)),
      () =>
        ndjson([
          { type: 'start', total: 1 },
          // Somebody published 1.1.0 in between, so this one landed on 1.2.0.
          { type: 'row', ok: true, namespace: 'dev', name: 'changelog-writer', version: '1.2.0', draft_name: '补测试', predicted: '1.1.0' },
          { type: 'done', ok: 1, failed: 0 },
        ]),
      // The re-read: the skill now live at 1.2.0, with its other draft still waiting.
      () => json(200, queue([
        skill({
          current: { version: '1.2.0', submitted_at: '2026-10-25T10:00:00Z' },
          drafts: [line({ code: 'BBBBBBBB', name: '修错别字', release_type: 'patch' })],
        }),
      ])),
    )

    fireEvent.click(container.querySelectorAll('.pick')[0] as HTMLInputElement)
    await flush()
    fireEvent.click(container.querySelector('#publishGo') as HTMLButtonElement)
    await flush()
    fireEvent.click(actsOf(container).at(-1) as HTMLButtonElement)
    await flush()
    await flush()

    fireEvent.click(actsOf(container).at(-1) as HTMLButtonElement)
    await flush()

    // **基线变了，留在单子上的行也跟着变了**：the remaining draft now computes from 1.2.0, so it
    // says 1.2.1 — not the 1.0.1 it showed before the batch. The number is read from the re-read,
    // which is the only moment it is true.
    expect(container.querySelector('.banner.is-note')?.textContent).toBe(
      '确认之后，changelog-writer 已被上线至 1.1.0，因此这一份的实际版本号为 1.2.0；' +
        '同组还留着的「修错别字」现在以 1.2.1 上线。',
    )
    expect(container.textContent).toContain('1.2.1')
  })

  it('paints a failed type write as a failure, never as the 「号变了」 note', async () => {
    const { container } = await showQueue(
      () => json(200, queue([
        skill({
          current: { version: '0.1.0', submitted_at: '2026-10-01T00:00:00Z' },
          drafts: [line({ name: '加锁表规则', release_type: null })],
        }),
      ])),
      () => json(500, errorBody('internal_error', 'boom')),
    )

    fireEvent.click(container.querySelector('.release-pick .menu-trigger') as HTMLButtonElement)
    fireEvent.click(container.querySelector('.menu-item[data-value="minor"]') as HTMLButtonElement)
    await flush()
    await flush()

    // The note banner explains a number that moved; this is a write that did not happen.
    expect(container.querySelector('.banner.is-note')).toBeNull()
    expect(container.querySelector('.banner[role="alert"]')).not.toBeNull()
  })

  it('is the wide sheet from the confirmation on', async () => {
    const { container } = await showQueue(() => json(200, queue(ROWS)))

    fireEvent.click(container.querySelectorAll('.pick')[0] as HTMLInputElement)
    await flush()
    fireEvent.click(container.querySelector('#publishGo') as HTMLButtonElement)
    await flush()

    // Four columns — a name with its draft name, a kind, a number, a state — do not fit in 420px,
    // so the design widens the sheet as soon as it has a list.
    expect(container.querySelector('.confirm-sheet')?.className).toContain('has-run')
  })

  it('puts the refusal back on the sheet when the request itself is rejected', async () => {
    const { container } = await showQueue(
      () => json(200, queue(ROWS)),
      () => json(400, errorBody('duplicate_target', 'twice')),
    )

    fireEvent.click(container.querySelectorAll('.pick')[0] as HTMLInputElement)
    await flush()
    fireEvent.click(container.querySelector('#publishGo') as HTMLButtonElement)
    await flush()
    fireEvent.click(actsOf(container).at(-1) as HTMLButtonElement)
    await flush()
    await flush()

    // Nothing was published, so the sheet stays where it can be answered again — with the reason.
    expect(container.querySelector('.confirm-error')?.textContent).toBe(
      '同一批里同一个 skill 出现了两次；请刷新页面后重新选择。',
    )
    expect(container.querySelector('.confirm-title')?.textContent).toBe('上线 1 份草稿？')
  })
})
