import type { SkillSource } from './api/types'

/** The three words the console uses for where a library row came from (prd.md §来源标签). */
export const SOURCE_LABEL: Record<SkillSource, string> = {
  created: '我创建的',
  shared: '共享给我的',
  joined: '我加入的',
}

export function sourceLabel(source: SkillSource | null): string {
  return source === null ? '' : SOURCE_LABEL[source]
}
