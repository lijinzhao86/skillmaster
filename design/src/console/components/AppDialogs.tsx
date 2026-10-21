/**
 * The sheets that live outside any one page.
 *
 * They are siblings of the frame rather than children of a page because a sheet covers whatever is
 * behind it, and the same sheet is opened from more than one place: 公开 · 浏览 is confirmed on the
 * settings page, the danger sheet serves every destructive action in the console, and the import
 * sheet is reached from both the skill list and the version list.
 *
 * Their open state lives in `dialogs/store.ts`, the way the old demo kept it in globals — a page
 * asks for a sheet, it does not own one.
 */

import DangerConfirm from './dialogs/DangerConfirm'
import ImportDialog from './dialogs/ImportDialog'
import NewSkillDialog from './dialogs/NewSkillDialog'
import PublicBrowseConfirm from './dialogs/PublicBrowseConfirm'

export default function AppDialogs() {
  return (
    <>
      <PublicBrowseConfirm />
      <DangerConfirm />
      <ImportDialog />
      <NewSkillDialog />
    </>
  )
}
