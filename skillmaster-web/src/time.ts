/**
 * Server timestamps, shown to a person.
 *
 * The wire carries RFC3339 UTC — §4.1 says so, and it is the only thing two machines can agree on.
 * Everything on screen that says when something happened goes through here, so that the answer to
 * "what time is it there" is written down once rather than decided per page.
 *
 * `zh-CN` with `hour12: false`, in Shanghai: this product's first readers are here, and a date
 * shown in a browser's locale would be a different string for each of them. That is the right
 * default for a site with users everywhere and the wrong one for a v1 whose users are not — the
 * decision is one line, and this comment is what it costs to revisit later.
 *
 * A value that cannot be parsed is returned as it arrived rather than replaced with a placeholder:
 * a timestamp nobody can read is a bug worth seeing, and "—" would hide it.
 */
const FORMAT: Intl.DateTimeFormatOptions = {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
  timeZone: 'Asia/Shanghai',
}

export function formatTime(value: string | null): string {
  if (value === null) {
    return ''
  }
  const at = new Date(value)
  return Number.isNaN(at.getTime()) ? value : at.toLocaleString('zh-CN', FORMAT)
}
