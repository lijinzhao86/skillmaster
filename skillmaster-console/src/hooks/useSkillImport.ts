import { useRef, useState } from 'react'
import type { CommandMenuHandle } from '../components/CommandMenu'
import type { VersionFilterBarHandle } from '../components/version/VersionFilterBar'
import { versionCreateItems } from '../components/version/createMenu'
import { baseAddressFor, type SkillAddressParts } from '../pages/skill/skillAddress'

/**
 * The import sheet's one piece of state, and the two 「新建版本」 menus that can open it.
 *
 * Closing returns focus to whichever menu opened the sheet (design §导入与下载: 「焦点回到打开它的
 * 那颗」), which is why the origin is remembered rather than assumed.
 */
export interface SkillImportProps {
  parts: SkillAddressParts
}

export function useSkillImport({ parts }: SkillImportProps) {
  /* The import sheet: one file, a name, a note; success lands on the new draft's version page. */
  const [importOpen, setImportOpen] = useState(false)
  /** The version list's 「新建版本 ▾」 — where focus goes when the sheet it opened closes. */
  const filterBar = useRef<VersionFilterBarHandle | null>(null)
  /** The file table's own 「新建版本」 (2026-10-21): the same menu, in the other tab. */
  const filesMenu = useRef<CommandMenuHandle | null>(null)
  /** Which 「新建版本」 opened the sheet, so focus goes back to that one. */
  const importOrigin = useRef<'filter' | 'files'>('filter')

  /** The version list's 「新建版本 ▾」: its 导入压缩包 item opens the sheet. */
  function openImportFromFilter(): void {
    importOrigin.current = 'filter'
    setImportOpen(true)
  }

  /** The file table's 「新建版本」: the same menu, opening the same sheet (2026-10-21). */
  function openImportFromFiles(): void {
    importOrigin.current = 'files'
    setImportOpen(true)
  }

  /** The file table's 「新建版本」 items: built by the same function as the version list's menu. */
  const filesCreateItems = versionCreateItems(
    `${baseAddressFor(parts, null)}/new`,
    openImportFromFiles,
  )

  /**
   * Closing the import sheet returns focus to the menu that opened it (design §导入与下载: 「焦点回到
   * 打开它的那颗」). Wait a frame so the sheet is gone before the button takes focus back — and the
   * button is whichever of the two 「新建版本」s it came from.
   */
  function closeImport(): void {
    setImportOpen(false)
    requestAnimationFrame(() => {
      if (importOrigin.current === 'files') {
        filesMenu.current?.focusTrigger()
      } else {
        filterBar.current?.focusCreate()
      }
    })
  }

  return {
    importOpen,
    filterBar,
    filesMenu,
    filesCreateItems,
    openImportFromFilter,
    openImportFromFiles,
    closeImport,
  }
}
