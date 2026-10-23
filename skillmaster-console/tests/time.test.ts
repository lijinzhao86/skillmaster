import { describe, expect, it } from 'vitest'
import { formatRelative } from '../src/time'

/**
 * The process runs in UTC, and that is part of what is under test.
 *
 * Two of the cases below are about *where* the day turns, and on a machine already set to Shanghai a
 * rule that read the browser's own day would give the same answers as the right one — the suite would
 * pass and prove nothing. This machine is `+0800`, which is how that was noticed. Forcing the zone
 * makes the two rules disagree, so the cases can tell them apart. `process.env.TZ` is honoured when
 * set at runtime (checked), and each test file gets its own environment, so nothing else is moved.
 */
process.env.TZ = 'UTC'

/**
 * The words a moment is said in when the column is about whether something is still in use.
 *
 * `design/voice-spec.md` §六 is the authority, and it gives the vocabulary without the thresholds —
 * 「刚刚」「今天 09:41」「昨天 03:12」, and a date for anything older. So these cases are exactly the
 * places this file had to decide: how short 「刚刚」 is, and where one day ends and the next begins.
 * Both are decisions, so both are written down here rather than left to whatever the code happens to
 * do.
 *
 * **Every case fixes the clock.** "Three hours ago" is 「今天」 or 「昨天」 depending on the hour the
 * suite runs at, which is how a test of day boundaries becomes a test that fails at 01:00 and passes
 * at noon. `now` is a parameter for this reason and for no other.
 */

/** A fixed moment: 2026-10-16 10:00 in Shanghai, which is 02:00 UTC. */
const NOW = new Date('2026-10-16T02:00:00Z')

function ago(millis: number): string {
  return new Date(NOW.getTime() - millis).toISOString()
}

const MINUTE = 60_000
const HOUR = 60 * MINUTE

describe('a moment said the way a person says it', () => {
  it('says 「刚刚」 inside the minute, and a clock time past it', () => {
    expect(formatRelative(ago(0), NOW)).toBe('刚刚')
    expect(formatRelative(ago(59_000), NOW)).toBe('刚刚')
    // The boundary itself is not 「刚刚」: a minute is where the word stops, and the case either side
    // of it is what makes the number a rule rather than a guess.
    expect(formatRelative(ago(MINUTE), NOW)).toBe('今天 09:59')
    expect(formatRelative(ago(5 * MINUTE), NOW)).toBe('今天 09:55')
  })

  it('counts a moment ahead of the clock as just now, not as the future', () => {
    // The server's clock and the browser's are not the same clock. A server a few seconds fast must
    // not print a time that has not happened yet.
    expect(formatRelative(new Date(NOW.getTime() + 3_000).toISOString(), NOW)).toBe('刚刚')
  })

  it('says 「今天」 with a clock time, whatever the hour', () => {
    expect(formatRelative(ago(3 * HOUR), NOW)).toBe('今天 07:00')
    // 00:05 Shanghai — the same calendar day, and the case a formatter that prints 24:30 would fail.
    expect(formatRelative(new Date('2026-10-15T16:05:00Z').toISOString(), NOW)).toBe('今天 00:05')
  })

  it('says 「昨天」 with the clock time, and 「今天」 for the same hour a day earlier', () => {
    expect(formatRelative(new Date('2026-10-15T09:12:00Z').toISOString(), NOW)).toBe('昨天 17:12')
    // 24 hours before `now` is 昨天, not 今天: a difference of a day is not the same question as a
    // difference of 24 hours, and only the calendar can tell them apart.
    expect(formatRelative(ago(24 * HOUR), NOW)).toBe('昨天 10:00')
  })

  it('falls back to the date and the clock for anything older', () => {
    // The form the credential screen's own fixture uses for its oldest row (`2026/10/11 22:04`) —
    // this is `formatTime`, so the two screens agree about what "old" looks like.
    expect(formatRelative(new Date('2026-10-11T14:04:00Z').toISOString(), NOW)).toBe(
      '2026/10/11 22:04',
    )
  })

  it('draws the day boundary in Shanghai, which is where the clock on screen is', () => {
    // 23:30 UTC on the 15th is 07:30 on the 16th here — the same morning as `now`, so 「今天」. Read in
    // the browser's own zone (this machine is not in Shanghai) it would be 「昨天」.
    expect(formatRelative('2026-10-15T23:30:00Z', NOW)).toBe('今天 07:30')
    // And 16:00 UTC on the 15th is midnight on the 16th here: the day turns at 16:00 UTC, not at
    // midnight UTC.
    expect(formatRelative('2026-10-15T16:00:00Z', NOW)).toBe('今天 00:00')
    expect(formatRelative('2026-10-15T15:59:00Z', NOW)).toBe('昨天 23:59')
  })

  it('says nothing for nothing, and shows a value it cannot read as it arrived', () => {
    expect(formatRelative(null, NOW)).toBe('')
    // Not a placeholder: a timestamp nobody can parse is a bug worth seeing.
    expect(formatRelative('not a time', NOW)).toBe('not a time')
  })
})
