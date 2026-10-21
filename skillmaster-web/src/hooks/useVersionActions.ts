import { useState } from 'react'
import { go, query, relativeAddress } from '../address'
import { codeMessage } from '../api/errors'
import { discardVersion, publishVersion, restoreVersion, revokeVersion } from '../api/skills'
import type { ApiResult } from '../api/types'

/**
 * The four writes, each followed by a re-read of the page.
 *
 * **A reload rather than a patched copy**: the server is the only thing that knows what the pointer,
 * the statuses and the derived counts became. The design's own demo updates in place because it has
 * no server; the product's answer to "what happened" is the page the next GET renders.
 *
 * **What changed in the port.** `pending` and `error` are `useState` values; the returned writes are
 * plain functions, as they were in Vue's setup scope. This file is the hook half of the Vue
 * composable — the pure rules live in `versionActions.ts` and are re-exported here, so both
 * `./useVersionActions` and `./versionActions` remain importable paths for them.
 */
export * from '../versionActions'

export function useVersionActions(namespace: string, name: string) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  /**
   * @param after what the next screen is. The default re-reads the same address; a write that
   *              changes what that address names has to be handed the new one instead.
   */
  async function run<T>(
    call: Promise<ApiResult<T>>,
    after: (data: T) => void = () => window.location.reload(),
  ): Promise<boolean> {
    setPending(true)
    setError(null)
    const result = await call
    setPending(false)
    if (!result.ok) {
      setError(codeMessage(result.code, result.message))
      return false
    }
    after(result.data)
    return true
  }

  /** The same page, naming another version. A relative query keeps path and tab. */
  function navigateVersion(suffix: string): void {
    const params = query()
    params.set('version', suffix)
    // The handoff's preselection (v11) has been consumed by the publish that just happened; the
    // published row has no publish form for it to seed anyway.
    params.delete('bump')
    go(relativeAddress(params))
  }

  /** The version list, for a write whose row leaves the caller's view (`write` discarding). */
  function backToList(): void {
    const params = query()
    for (const key of ['version', 'sub', 'bump', 'from', 'to', 'fq', 'file', 'list']) {
      params.delete(key)
    }
    params.set('tab', 'versions')
    go(relativeAddress(params))
  }

  return {
    pending,
    error,
    // A page clears the previous refusal as it opens another action (Vue's page wrote to the same
    // `ref`); a state value is read-only here, so the setter has to be handed out.
    setError,
    publish: (version: string, releaseName?: string, stay = false) =>
      run(publishVersion(namespace, name, version, releaseName), (data) => {
        // **A rollback stays on this page.** It publishes a history version as the target, so the
        // response's `release_name` is a number that already existed — navigating to it would move
        // the reader off the page whose status just changed (design: 「页面就地更新（回滚之后这一版
        // 从线上变成历史版本…）」).
        if (stay) {
          window.location.reload()
          return
        }
        // **A published draft has a new address.** its bare row id names it only while it is a draft;
        // after publish its number is the name, and reloading the old address answered 「没有这一版」
        // on the screen the publish itself had just produced. The server reports the assigned number
        // in `release_name`.
        if (data.release_name !== null && data.release_name !== undefined) {
          navigateVersion(data.release_name)
        } else {
          // No number reported: the row's old address no longer names it (a published row answers to
          // its number, not its code — ADR 0063), so the list is the one landing that certainly
          // still shows it.
          backToList()
        }
      }),
    discard: (version: string) =>
      // A `write` caller's own discarded draft leaves their visible set; the list is the one landing
      // that is right for every rung.
      run(discardVersion(namespace, name, version), backToList),
    revoke: (version: string, reason?: string) =>
      run(revokeVersion(namespace, name, version, reason)),
    restore: (version: string) => run(restoreVersion(namespace, name, version)),
  }
}
