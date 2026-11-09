/**
 * The four sections of one skill, as words and as marks.
 *
 * The page's route state machine reads these; the header draws them. Kept out of both so the list
 * of tabs and the list of settings sub-pages have one spelling each.
 */

export type Tab = 'info' | 'files' | 'versions' | 'settings'

export const TABS = ['info', 'files', 'versions', 'settings'] as const

/** Each top-level tab's mark, drawn once in the page's own symbol sheet (design 2026-10-20). */
export const TAB_ICONS: Record<Tab, string> = {
  info: 'infoMark',
  files: 'fileMark',
  versions: 'versionMark',
  settings: 'settingsMark',
}

// v52 adds 服务凭据 between 协作者 and 详细信息: it is about what this skill may reach, which puts
// it beside the people it is shared with rather than among the skill's own facts.
export const SUBPAGES = ['general', 'collaborators', 'secrets', 'details'] as const
export type Subpage = (typeof SUBPAGES)[number]
