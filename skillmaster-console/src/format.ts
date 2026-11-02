/**
 * Numbers as a reader reads them.
 *
 * **One size formatter, because there were four.** The file table, the diff pane, a version's facts
 * and the single-file view each grew their own copy of the same three-way branch, and the copies had
 * drifted into two signatures: two took `number | null` and answered `''` for "the server did not
 * say", two took a plain `number`. Both are one function — a caller with a number passes a number.
 */

/**
 * The unit v39's ceiling sentences are written in (voice-spec §上传与大小的四句话); the
 * values themselves live in the server's limits, not here. Separate from {@link formatSize} on purpose: a ceiling is
 * quoted in the unit the rule is stated in, while a file's own size is shown the way a person
 * reads it.
 */
export function formatMib(bytes: number): string {
  const value = bytes / (1024 * 1024)
  return `${Number.isInteger(value) ? value : value.toFixed(1)} MiB`
}

/** `1.2 MB` / `830.4 KB` / `512 B`; `''` when there is no size to report. */
export function formatSize(bytes: number | null): string {
  if (bytes === null) {
    return ''
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  const kilobytes = bytes / 1024
  return kilobytes < 1024 ? `${kilobytes.toFixed(1)} KB` : `${(kilobytes / 1024).toFixed(1)} MB`
}
