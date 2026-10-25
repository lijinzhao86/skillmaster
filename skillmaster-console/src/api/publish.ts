import { CSRF_HEADER, csrfToken, failureOf, request } from './client'
import type {
  ApiResult,
  PublishDone,
  PublishQueue,
  PublishRowResult,
  PublishTarget,
  ReleaseType,
} from './types'

/**
 * 待上线 (v18): one read that lists every waiting draft, two writes that act on them.
 *
 * **The queue is one request, not one per skill** (TD §接口): a page somebody simply opens must not
 * cost five hundred round trips, and the shape it returns is already the shape the table draws —
 * grouped by skill, each skill's drafts newest first. The page does not re-sort what it receives.
 */

/** `GET /web/publish-queue` — every draft waiting on a skill this caller may publish from. */
export function readPublishQueue(): Promise<ApiResult<PublishQueue>> {
  return request<PublishQueue>({ method: 'GET', path: '/web/publish-queue' })
}

/**
 * `PATCH …/versions/{version}/release-type`: declare, change, or (`null`) clear a draft's type.
 *
 * **Written the moment it is chosen, never held in page state.** The type is what the *next* publish
 * computes from, and the console and the CLI are two ways to set it; a value that only lived in this
 * tab would let the CLI report one thing while the page showed another.
 */
export function setDraftReleaseType(
  namespace: string,
  name: string,
  version: string,
  releaseType: ReleaseType | null,
): Promise<ApiResult<{ release_type: ReleaseType | null }>> {
  return request<{ release_type: ReleaseType | null }>({
    method: 'PATCH',
    path: `/web/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}` +
      `/versions/${encodeURIComponent(version)}/release-type`,
    body: { release_type: releaseType },
  })
}

/**
 * `POST /web/skills/publish-batch` — publishes the batch and reports **one row at a time**.
 *
 * **NDJSON read as it arrives, because the sheet says "this one landed" while the others are still
 * going** (TD §接口). Reading the body whole would turn the sheet back into one spinner that ends
 * with every row changing colour at the same instant — the difference between watching a batch and
 * watching a result.
 *
 * **No timeout on the request.** The other calls in this app give up after fifteen seconds because a
 * form that waits for ever is a form somebody reloads; this one is a batch of up to five hundred and
 * the honest answer to a slow one is to keep watching. What it does *not* do is pretend: a stream
 * that stops before its `done` line is reported as incomplete, never as finished.
 */
export async function publishBatch(
  targets: PublishTarget[],
  onRow: (row: PublishRowResult) => void,
): Promise<ApiResult<PublishDone>> {
  const headers: Record<string, string> = {
    Accept: 'application/x-ndjson',
    'Content-Type': 'application/json',
  }
  const token = csrfToken()
  if (token !== null) {
    headers[CSRF_HEADER] = token
  }

  let response: Response
  try {
    response = await fetch('/web/skills/publish-batch', {
      method: 'POST',
      headers,
      credentials: 'same-origin',
      body: JSON.stringify({ targets }),
    })
  } catch (cause) {
    console.error('the batch publish could not be started', cause)
    return {
      ok: false,
      status: 0,
      code: 'network_error',
      message: '网络请求失败，请检查网络后重试。',
      details: [],
    }
  }

  if (!response.ok) {
    // The request as a whole was refused (no session, an empty batch, `duplicate_target`). Nothing
    // has been published, so this is an ordinary answer with a body — read it and stop.
    return failureOf(
      response.status,
      await response.text().catch(() => ''),
      response.headers.get('Retry-After'),
      '/web/skills/publish-batch',
    )
  }
  if (response.body === null) {
    return incomplete()
  }

  // A line is only complete once its newline has arrived: the reader hands back whatever chunk the
  // connection produced, which may end in the middle of a JSON object or (rarely) of a character.
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let done: PublishDone | null = null
  try {
    for (;;) {
      const { value, done: ended } = await reader.read()
      if (ended) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      let newline = buffer.indexOf('\n')
      while (newline >= 0) {
        const line = buffer.slice(0, newline)
        buffer = buffer.slice(newline + 1)
        const parsed = parseLine(line)
        if (parsed?.type === 'row') {
          onRow(parsed)
        } else if (parsed?.type === 'done') {
          done = parsed
        }
        newline = buffer.indexOf('\n')
      }
    }
  } catch (cause) {
    // The connection stopped mid-batch. Rows already written committed in their own transactions, so
    // the caller keeps them — but the batch did not finish, and saying otherwise would be a lie.
    console.error('the batch publish stream stopped', cause)
    return incomplete()
  }

  if (done === null) {
    return incomplete()
  }
  return { ok: true, status: response.status, data: done }
}

/** A stream that ended before its `done` line. The rows that did arrive are the caller's to keep. */
function incomplete(): ApiResult<never> {
  return {
    ok: false,
    status: 0,
    code: 'stream_incomplete',
    message: '这一批没有全部走完：连接中断了，已上线的那些不会再撤回。',
    details: [],
  }
}

/** One NDJSON line, or null when it is blank or not a line this page knows. */
function parseLine(line: string): PublishRowResult | PublishDone | null {
  if (line.trim() === '') {
    return null
  }
  try {
    const parsed: unknown = JSON.parse(line)
    if (typeof parsed !== 'object' || parsed === null || !('type' in parsed)) {
      return null
    }
    return parsed as PublishRowResult | PublishDone
  } catch {
    // A line that does not parse is a broken transport, not a row: the rows that did parse are
    // still true, and the missing `done` below is what the caller sees.
    console.error('a batch line was not JSON: %s', line)
    return null
  }
}
