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

/**
 * The day alone — for the rows of the credential screen, which say when a key stops working rather
 * than when something happened. Same formatter and same timezone as {@link formatTime}, minus the
 * clock: a date that shifted by a timezone would make "expires on the 13th" mean the 12th to
 * somebody reading it from the other side.
 */
export function formatDay(value: string | null): string {
  if (value === null) {
    return ''
  }
  const at = new Date(value)
  if (Number.isNaN(at.getTime())) {
    return value
  }
  return at.toLocaleDateString('zh-CN', { ...FORMAT, hour: undefined, minute: undefined })
}

export function formatTime(value: string | null): string {
  if (value === null) {
    return ''
  }
  const at = new Date(value)
  return Number.isNaN(at.getTime()) ? value : at.toLocaleString('zh-CN', FORMAT)
}

/** The clock alone, in the same formatter and the same timezone as {@link formatTime}. */
const CLOCK: Intl.DateTimeFormatOptions = {
  ...FORMAT,
  year: undefined,
  month: undefined,
  day: undefined,
}

/**
 * The same moment said the way a person says it — for the columns that answer "is it still in use".
 *
 * `design/voice-spec.md` §六 is the authority here, and it names the shapes: 「刚刚」「今天 09:41」
 * 「昨天 03:12」, and a plain date for anything older. **A date alone is not one of them** — the
 * fallback carries the clock too, in the form the credential screen's own fixture uses for its
 * oldest row (`2026/10/11 22:04`), which is {@link formatTime} itself. So the only thing this adds
 * over that function is the two day words and 「刚刚」.
 *
 * **A day is the reader's day, and this reader is in Shanghai.** "Today" has to be worked out in the
 * same zone the clock is printed in: at 07:00 UTC a browser in London and one here are on different
 * days, so comparing browser-local days would put 「今天」 beside a time that the string next to it
 * dates to yesterday.
 *
 * 「刚刚」 is one minute. The spec names the word and no number, and the number has to live somewhere
 * — a minute is the shortest window in which a timestamp still reads as "now" rather than as a time.
 * It is also the only threshold here nobody can see; the other three are calendar boundaries, which
 * are visible on the screen itself.
 *
 * @param now the moment to compare against. A parameter only so a test can fix the clock instead of
 *        racing the wall — nothing in the app passes it, and the day words would be untestable
 *        without it, since "three hours ago" is 「今天」 or 「昨天」 depending on when the suite runs.
 */
export function formatRelative(value: string | null, now: Date = new Date()): string {
  if (value === null) {
    return ''
  }
  const at = new Date(value)
  if (Number.isNaN(at.getTime())) {
    return value
  }
  const MINUTE = 60_000
  // Ahead of `now` counts as just now: the two clocks are not the same clock, and a server that is a
  // few seconds fast would otherwise print a time in the future.
  if (now.getTime() - at.getTime() < MINUTE) {
    return '刚刚'
  }
  const clock = at.toLocaleTimeString('zh-CN', CLOCK)
  if (dayOf(at) === dayOf(now)) {
    return `今天 ${clock}`
  }
  if (dayOf(at) === dayOf(new Date(now.getTime() - 24 * 60 * MINUTE))) {
    return `昨天 ${clock}`
  }
  return formatTime(value)
}

/**
 * Which calendar day this moment falls on, here — a comparable key, not something shown.
 *
 * `en-CA` because it is the one locale that writes `2026/10/16` as `2026-10-16`, which compares as
 * text; the formatter is a key, so its locale is an implementation detail rather than a display
 * choice. The zone is the display zone, which is the point of the function.
 */
function dayOf(at: Date): string {
  return at.toLocaleDateString('en-CA', { ...FORMAT, hour: undefined, minute: undefined })
}
