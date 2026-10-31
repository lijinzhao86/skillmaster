import type { Rung, SkillSource } from './api/types'
import { LADDER } from './rungs'

/** The three words the console uses for where a library row came from (prd.md §来源标签). */
export const SOURCE_LABEL: Record<SkillSource, string> = {
  created: '我创建的',
  shared: '共享给我的',
  joined: '我加入的',
}

export function sourceLabel(source: SkillSource | null): string {
  return source === null ? '' : SOURCE_LABEL[source]
}

/**
 * The rung a row's chip shows (v35) — the same words the permission page uses (`voice-spec.md`
 * §名词表). Derived from `rungs.ts`: the ladder's words are already written down there once.
 * The owner has no entry on purpose: the console leaves the chip off for them, because owning a
 * skill is already said by the namespace going unprefixed.
 */
export const STANDING_LABEL = Object.fromEntries(
  LADDER.map((rung) => [rung.name, rung.label]),
) as Record<Rung, string>
