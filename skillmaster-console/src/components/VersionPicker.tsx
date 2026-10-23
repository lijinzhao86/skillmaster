import MenuSelect from './MenuSelect'
import type { MenuGroup, MenuOption } from './MenuSelect'
import type { AuthoredVersion } from '../api/types'
import { statusOf, statusText, statusTone, versionSuffix, versionTitle } from '../version'

/**
 * Which version the page is showing — the branch box, for versions.
 *
 * **A `MenuSelect`, not a native `<select>`** (v6): `@Version` alone cannot say whether it is live
 * or history, and the design wants the option to be 「名称 + 版本号 + 状态 tag」 and to be searchable.
 * `MenuSelect` is the page's one dropdown mechanism, so this component only maps versions into it.
 *
 * **What is offered is what the caller passed.** The file tab lists every version this rung can
 * read (live, drafts for `write`, history and revoked for `maintain`); the comparison baseline adds
 * drafts; rollback passes only history. A discarded version is never offered as a choice; v9 shows
 * one only when the address pins it, and then as a disabled row (see below). Ordering is the
 * caller's too — a picker that re-sorted would make the comparison default disagree with the
 * rollback default.
 *
 * **A pinned version this rung cannot read is a disabled option**, so the control does not lie about
 * what is on screen. It appears only if the caller passes it in `versions` (the page does when the
 * address pins one); `disabledValues` says which values are display-only.
 *
 * The component emits rather than navigates: where a version is shown is the page's business, and a
 * component that built its own URLs would have to be told which page it is on.
 *
 * **What changed in the port.** The `select` event is the `onSelect` prop, and `withDefaults` is an
 * object destructuring default.
 */
export interface VersionPickerProps {
  versions: AuthoredVersion[]
  selected: string
  ariaLabel?: string
  /** Values that are on screen but not choices — the disabled row the control has to show. */
  disabledValues?: string[]
  /** The closed button's text, when the page needs more than the version's own title. */
  triggerText?: string
  /** The previous published version's pin — that option carries the 「上一版」 tag. */
  previousPin?: string
  onSelect: (value: string) => void
}

export default function VersionPicker({
  versions,
  selected,
  ariaLabel = '选择版本',
  disabledValues = [],
  triggerText,
  previousPin,
  onSelect,
}: VersionPickerProps) {
  // A rung that can read no version at all gets 「未上线」, not an empty control that looks broken
  // (design 文件栏, only-draft skill read by `browse`).
  const options: MenuOption[] =
    versions.length === 0
      ? [{ value: '', label: '未上线', disabled: true }]
      : versions.map((version) => {
          const status = statusOf(version)
          const pin = versionSuffix(version)
          return {
            value: pin,
            label: versionTitle(version, 'browse'),
            ...(version.name !== null && version.draft_name != null
              ? { number: `@${version.name}` }
              : {}),
            tag: statusText(status),
            tagTone: statusTone(status),
            ...(pin === previousPin ? { meta: '上一版' } : {}),
            disabled: disabledValues.includes(pin),
            searchText: statusText(status),
          }
        })

  const groups: MenuGroup[] = [{ options }]

  const disabled = versions.length === 0

  const triggerLabel = ((): string => {
    if (versions.length === 0) {
      return '未上线'
    }
    if (triggerText !== undefined) {
      return triggerText
    }
    const found = options.find((option) => option.value === selected)
    return found === undefined ? selected : found.label
  })()

  return (
    <label className="version-picker">
      <span className="visually-hidden">{ariaLabel}</span>
      <MenuSelect
        groups={groups}
        value={selected}
        ariaLabel={ariaLabel}
        triggerText={triggerLabel}
        triggerClass="version-picker-trigger"
        disabled={disabled}
        search
        searchPlaceholder="按名称或版本号筛选"
        emptyText="没有匹配的版本。"
        onSelect={onSelect}
      />
    </label>
  )
}
