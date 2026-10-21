/*
 * The rules this file states are the version page's own — which actions a rung is offered,
 * what it may read, and which version is the baseline. They were the top half of
 * `useVersionActions.ts` in the Vue implementation, and they are framework-free: v12's P3 needs
 * them for the list page's import sheet (`canMaintain` / `canWrite`) and P4's `SkillPage` needs
 * the rest, so the file is split rather than copied.
 */
import type { AuthoredVersion } from './api/types'
import { statusOf } from './version'

/** `maintain`, or `write` on one's own draft — the rule shared by rename, note and discard. */
export function canEditDraft(
  version: AuthoredVersion,
  rung: string,
  username: string | null,
): boolean {
  const maintain = rung === 'owner' || rung === 'admin' || rung === 'maintain'
  const write = rung === 'owner' || rung === 'admin' || rung === 'maintain' || rung === 'write'
  const mine = username !== null && version.submitted_by === username
  return statusOf(version) === 'draft' && (maintain || (write && mine))
}

/** Whether this rung may publish / roll back / revoke / restore. */
export function canMaintain(rung: string): boolean {
  return rung === 'owner' || rung === 'admin' || rung === 'maintain'
}

/** Whether this rung may write (submit, rename, note, discard one's own). */
export function canWrite(rung: string): boolean {
  return canMaintain(rung) || rung === 'write'
}

/**
 * The state-changing actions this version offers this rung, in the design's order.
 *
 * The list is what keeps the row's 「更多操作」 menu and the version page's 操作 tab saying the same
 * thing. `publish` covers both a draft's first publish and a historical version's re-publish; the
 * page decides which name to show (上线 in both cases).
 */
export function versionActions(
  version: AuthoredVersion,
  rung: string,
  username: string | null,
  hasBaseline: boolean,
): Array<'files' | 'diff' | 'publish' | 'rollback' | 'revoke' | 'restore' | 'discard'> {
  const actions: Array<'files' | 'diff' | 'publish' | 'rollback' | 'revoke' | 'restore' | 'discard'> =
    []
  const status = statusOf(version)
  const browse = rung !== 'invoke'
  // A discarded version is not offered for reading — the design draws no files for it, whoever is
  // looking (v9 TD §接口 8). Its row and bytes still exist (ADR 0031), so this is a decision rather
  // than an impossibility. A draft is readable from `write` up: the design gives both read entries
  // the same gate as 查看文件.
  const readable = status !== 'discarded' && (status !== 'draft' || canWrite(rung))
  if (browse && readable) {
    actions.push('files')
  }
  if (browse && readable && hasBaseline) {
    actions.push('diff')
  }
  if (!canMaintain(rung)) {
    if (status === 'draft' && canEditDraft(version, rung, username)) {
      actions.push('discard')
    }
    return actions
  }
  switch (status) {
    case 'draft':
      actions.push('publish')
      if (canEditDraft(version, rung, username)) {
        actions.push('discard')
      }
      break
    case 'live':
      actions.push('rollback')
      break
    case 'history':
      actions.push('publish', 'revoke')
      break
    case 'revoked':
      actions.push('restore')
      break
    default:
      break
  }
  return actions
}

/** Whether the 操作 tab exists at all for this version and rung. */
export function hasVersionActions(
  version: AuthoredVersion,
  rung: string,
  username: string | null,
): boolean {
  return versionActions(version, rung, username, false).some(
    (action) => action !== 'files' && action !== 'diff',
  )
}

/** Whether this rung may read a version at all: drafts need `write`, revoked/discarded `maintain`. */
export function readableBy(version: AuthoredVersion, rung: string): boolean {
  const status = statusOf(version)
  if (status === 'draft') {
    return canWrite(rung)
  }
  if (status === 'revoked' || status === 'discarded') {
    return canMaintain(rung)
  }
  return rung !== 'invoke'
}

/**
 * The previous published version, by first-publish time — the order rollback defaults to.
 *
 * `versions` is the caller's visible set. A previous version this rung cannot see is not a baseline
 * (TD §改动): drawing it would put a version the page cannot open into the comparison.
 */
export function previousPublished(
  version: AuthoredVersion,
  versions: AuthoredVersion[],
): AuthoredVersion | null {
  const target = version.first_published_at
  if (target === null) {
    return null
  }
  return (
    versions
      .filter(
        (candidate) =>
          candidate !== version &&
          (statusOf(candidate) === 'history' || statusOf(candidate) === 'revoked') &&
          candidate.first_published_at !== null &&
          candidate.first_published_at < target,
      )
      .sort((a, b) => (b.first_published_at ?? '').localeCompare(a.first_published_at ?? ''))[0] ??
    null
  )
}

/** The default comparison baseline: previous, else live — or null when neither is visible. */
export function defaultBaseline(
  version: AuthoredVersion,
  versions: AuthoredVersion[],
  live: AuthoredVersion | null,
  rung: string,
): AuthoredVersion | null {
  if (rung === 'invoke') {
    return null
  }
  const visible = versions.filter((candidate) => candidate !== version && readableBy(candidate, rung))
  const previous = previousPublished(version, visible)
  if (previous !== null) {
    return previous
  }
  return live !== null && live !== version && visible.includes(live) ? live : null
}

/** Whether 下架 is drawn (history only — the current version cannot be delisted). */
export function canRevoke(version: AuthoredVersion, rung: string): boolean {
  return canMaintain(rung) && statusOf(version) === 'history'
}

/**
 * The four writes, each followed by a re-read of the page.
 *
 * **A reload rather than a patched copy**: the server is the only thing that knows what the pointer,
 * the statuses and the derived counts became. The design's own demo updates in place because it has
 * no server; the product's answer to "what happened" is the page the next GET renders.
 */
