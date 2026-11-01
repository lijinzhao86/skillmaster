/**
 * Which versions a page may show, and which one each part of the page is showing.
 *
 * Every function here is a reading of the server's answer, not a state of its own: the page holds
 * the skill, and these say what follows from it. Framework-free because the same questions are
 * asked by the list, the file tab and the header, and a second answer would eventually disagree.
 */

import type { AuthoredSkill, AuthoredVersion } from '../../api/types'
import { answersPin, statusOf, versionLabel, versionSuffix, type VersionRoute } from '../../version'
import { readableBy } from '../../versionActions'

/** The version consumers get. Null is an ordinary state: a skill nobody has published from yet. */
export function liveVersionOf(skill: AuthoredSkill | null): AuthoredVersion | null {
  return (
    skill?.versions.find(
      (candidate) =>
        statusOf(candidate) === 'live' ||
        (candidate.is_current && candidate.state === 'published'),
    ) ?? null
  )
}

/**
 * The list's rows: drafts need `write`; revoked and discarded need `maintain`. Published versions
 * are readable to anybody who reaches the author plane. One filter, read by the rows, the chips and
 * the tab's count, so they cannot disagree.
 */
export function visibleVersionsOf(
  allVersions: AuthoredVersion[],
  canWrite: boolean,
  canMaintain: boolean,
): AuthoredVersion[] {
  return allVersions.filter((candidate) => {
    const status = statusOf(candidate)
    if (status === 'draft') {
      return canWrite
    }
    if (status === 'revoked' || status === 'discarded') {
      return canMaintain
    }
    return true
  })
}

/**
 * The count the tab wears and the file tab repeats: every visible version that is not a draft and
 * was not discarded — live + history + revoked (TD §计数与列表同源).
 */
export function countedVersionsOf(visibleVersions: AuthoredVersion[]): AuthoredVersion[] {
  return visibleVersions.filter((candidate) => {
    const status = statusOf(candidate)
    return status !== 'draft' && status !== 'discarded'
  })
}

/**
 * The version tab's pin: the query's `version`, or the legacy `/diff` alias's `to`, falling back to
 * the live version when the alias named none.
 */
export function detailRouteOf(
  legacyDiff: boolean,
  versionRoute: VersionRoute,
  to: string | undefined,
  pathVersion: string | undefined,
  liveVersion: AuthoredVersion | null,
  from: string | undefined,
): VersionRoute {
  if (!legacyDiff) {
    return versionRoute
  }
  const target = to ?? pathVersion ?? liveVersion?.name ?? undefined
  return {
    ...versionRoute,
    ...(target === undefined ? {} : { version: target }),
    sub: 'diff',
    ...(from === undefined ? {} : { from }),
  }
}

/** The version the single-version page shows, or null when the pin names none this rung can see. */
export function detailVersionOf(
  detailRoute: VersionRoute,
  visibleVersions: AuthoredVersion[],
): AuthoredVersion | null {
  const pin = detailRoute.version
  if (pin === undefined) {
    return null
  }
  return visibleVersions.find((candidate) => answersPin(candidate, pin)) ?? null
}

/** The version the information page names in its rail — the one consumers get, or none yet. */
export function liveLabelOf(liveVersion: AuthoredVersion | null): string {
  return liveVersion === null ? '未上线' : `@${versionLabel(versionSuffix(liveVersion))}`
}

export interface FileVersionState {
  /** The row the address pinned in the path (`/name@pin?tab=files`), readable or not. */
  pinnedRow: AuthoredVersion | null
  /** Whether the address pins a discarded version — the one pin whose files are never drawn. */
  pinnedIsDiscarded: boolean
  /** The version the file tab is showing, or null when it has no readable snapshot. */
  shownVersion: AuthoredVersion | null
  /** The row the file tab's head speaks for — the version on screen, or the pinned discarded row. */
  fileTabVersion: AuthoredVersion | null
  /** What the file tab's picker offers, in the design's order. */
  pickerVersions: AuthoredVersion[]
  /** The picker values that are drawn disabled. */
  disabledPickerValues: string[]
}

/**
 * The file tab's four version questions, answered together so the picker, the head and the listing
 * cannot name different versions.
 *
 * **A pinned discarded version does not fall back.** Falling back to the live version drew the live
 * files under a discarded address, so the picker said one version and the files said another; v9
 * leaves the file area empty instead, and the head still names the pinned row (TD §接口 8).
 */
export function fileVersionStateOf(
  allVersions: AuthoredVersion[],
  visibleVersions: AuthoredVersion[],
  pathVersion: string | undefined,
  liveVersion: AuthoredVersion | null,
  rung: string,
): FileVersionState {
  const pinnedRow = (() => {
    if (pathVersion === undefined) {
      return null
    }
    return allVersions.find((candidate) => answersPin(candidate, pathVersion)) ?? null
  })()

  const pinnedIsDiscarded = pinnedRow !== null && statusOf(pinnedRow) === 'discarded'

  const pinnedFileVersion = (() => {
    if (pathVersion === undefined) {
      return null
    }
    const found = visibleVersions.find((candidate) => answersPin(candidate, pathVersion))
    return found !== undefined && statusOf(found) !== 'discarded' ? found : null
  })()

  const shownVersion = (() => {
    if (pinnedFileVersion !== null) {
      return pinnedFileVersion
    }
    if (pinnedIsDiscarded) {
      return null
    }
    if (liveVersion !== null) {
      return liveVersion
    }
    return (
      visibleVersions
        .filter(
          (candidate) =>
            statusOf(candidate) !== 'discarded' && statusOf(candidate) !== 'revoked',
        )
        .sort((a, b) => b.submitted_at.localeCompare(a.submitted_at))[0] ?? null
    )
  })()

  const fileTabVersion = shownVersion ?? (pinnedIsDiscarded ? pinnedRow : null)

  const pickerVersions = (() => {
    const readable = visibleVersions.filter((candidate) => statusOf(candidate) !== 'discarded')
    const byTime = (a: AuthoredVersion, b: AuthoredVersion): number =>
      b.submitted_at.localeCompare(a.submitted_at)
    const ordered: AuthoredVersion[] = []
    if (liveVersion !== null) {
      ordered.push(liveVersion)
    }
    readable
      .filter((candidate) => statusOf(candidate) === 'draft')
      .sort(byTime)
      .forEach((candidate) => ordered.push(candidate))
    readable
      .filter((candidate) => statusOf(candidate) !== 'draft' && candidate !== liveVersion)
      .sort(byTime)
      .forEach((candidate) => ordered.push(candidate))
    // The address may pin a version this rung cannot read (the server usually 404s first); if the
    // object is here, show it disabled rather than silently switching the screen to another version.
    const pinned = allVersions.find(
      (candidate) => pathVersion !== undefined && answersPin(candidate, pathVersion),
    )
    if (pinned !== undefined && !ordered.includes(pinned)) {
      ordered.push(pinned)
    }
    return ordered
  })()

  const disabledPickerValues = pickerVersions
    .filter((candidate) => statusOf(candidate) === 'discarded' || !readableBy(candidate, rung))
    .map((candidate) => versionSuffix(candidate))

  return {
    pinnedRow,
    pinnedIsDiscarded,
    shownVersion,
    fileTabVersion,
    pickerVersions,
    disabledPickerValues,
  }
}
