import type { Rung, Standing } from './api/types'

/**
 * The ladder, as the design names it (ADR 0047).
 *
 * Copying these five labels rather than deriving them: a rung is a word the product uses everywhere
 * — grants, requests, the visibility hint — and `design/README.md`'s vocabulary is the authority.
 * Ordered low to high, which is what every question below is asked against.
 */
export const LADDER: { name: Rung; label: string }[] = [
  { name: 'invoke', label: '调用' },
  { name: 'browse', label: '浏览' },
  { name: 'write', label: '写入' },
  { name: 'maintain', label: '维护' },
  { name: 'admin', label: '管理' },
]

/**
 * What each rung adds, and what it still lacks — the design's `RUNG_NOTES`.
 *
 * The information page's 申请 block prints one of these under the picker, because the act of asking
 * does not explain itself: somebody at 浏览 sees three words and cannot tell what separates 维护 from
 * 管理 without being told they differ in the half that is still missing.
 */
export const RUNG_NOTES: Partial<Record<Rung, string>> = {
  browse: '可以查看文件清单与已上线的版本，也可以在控制台打开；不可以提交版本。',
  write: '可以提交新版本；上线、修改可见性与管理协作者不属于这一档。',
  maintain: '可以提交并上线版本，也可以丢弃任何人提交的草稿；修改可见性与管理协作者需要「管理」。',
  admin: '可以修改可见性与管理协作者；删除与恢复仅限所有者。',
}

/**
 * Where a standing sits: 0–4 for the ladder, and −1 for the owner, who is above it rather than on it
 * (ADR 0047). −1 sorts before every rung, which answers both questions asked of it: no rung is above
 * the owner, and every rung is above −1.
 */
export function rungIndex(standing: Standing | null): number {
  if (standing === 'owner') {
    return -1
  }
  return LADDER.findIndex((rung) => rung.name === standing)
}

export function rungLabel(standing: Standing | Rung): string {
  return standing === 'owner' ? '所有者' : (LADDER.find((rung) => rung.name === standing)?.label ?? standing)
}

/** Whether a standing reaches a rung. The owner answers yes to every one — ownership is above it. */
export function atLeast(standing: Standing, required: Rung): boolean {
  return standing === 'owner' || rungIndex(standing) >= rungIndex(required)
}

/** The rungs strictly above a standing — what a request can ask for, and what a share can offer. */
export function rungsAbove(standing: Standing | Rung | null): { name: Rung; label: string }[] {
  const index = rungIndex(standing)
  return LADDER.filter((rung) => rungIndex(rung.name) > index)
}

/**
 * The rungs a public skill gives away, named — read off the ladder, so the sentence and the list it
 * precedes cannot disagree.
 */
export function rungsUpTo(publicRung: Rung): string {
  return LADDER.filter((rung) => rungIndex(rung.name) <= rungIndex(publicRung))
    .map((rung) => `「${rung.label}」`)
    .join('、')
}

/**
 * The choices for one row, plus that row's own rung when it falls below the public one — a control
 * has to be able to show what is actually true. A grant made before the skill went public is
 * exactly that case.
 */
export function rungChoices(role: Rung, publicRung: Rung | null): { name: Rung; label: string }[] {
  const offered = rungsAbove(publicRung)
  if (publicRung === null || rungIndex(role) < 0 || rungIndex(role) > rungIndex(publicRung)) {
    return offered
  }
  return [{ name: role, label: rungLabel(role) }, ...offered]
}
