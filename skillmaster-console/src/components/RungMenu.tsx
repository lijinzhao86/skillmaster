import MenuSelect from './MenuSelect'
import type { MenuGroup } from './MenuSelect'

/**
 * The rung picker: a thin shell over `MenuSelect` (v6).
 *
 * It was the original home of the dropdown mechanism; the version pickers needed the same thing, so
 * the mechanism moved to `MenuSelect` and this component now only maps a list of rungs into the
 * generic option shape. Its contract is unchanged: `options` / `modelValue` in, `update:modelValue`
 * out.
 *
 * **What changed in the port.** `v-model` is `value` + `onChange` here, so the two halves of that
 * contract are two props rather than a prop and an event.
 */
export interface RungOption {
  name: string
  label: string
}

export interface RungMenuProps {
  options: RungOption[]
  /** The chosen rung's `name`. */
  value: string
  /** Read aloud; the visible label, when there is one, is wired to it by the caller. */
  label?: string
  /** The id of a visible label element — the design's `aria-labelledby`, used for 申请档位. */
  labelledby?: string
  onChange: (value: string) => void
}

export default function RungMenu({ options, value, label, labelledby, onChange }: RungMenuProps) {
  const groups: MenuGroup[] = [
    {
      options: options.map((option) => ({ value: option.name, label: option.label })),
    },
  ]

  return (
    <MenuSelect
      groups={groups}
      value={value}
      ariaLabel={label}
      labelledby={labelledby}
      onSelect={onChange}
    />
  )
}
