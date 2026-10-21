<script setup lang="ts">
import { onBeforeUnmount, ref, type ComponentPublicInstance } from 'vue'

/**
 * The command menu the design draws in three places (v9): the version row's 「更多操作」, and the
 * two 「新建 ▾」 triggers.
 *
 * **One behaviour, extracted rather than copied.** The row menu already had positioning, keyboard
 * handling and the outside-click/scroll/resize rules; a second and third copy would be three places
 * for the arrow keys to drift. The trigger is a slot because its face differs — an icon button on a
 * row, a labelled primary button in a toolbar — while everything behind it is the same question.
 *
 * **Positioned fixed, from the trigger's box.** The list can open upward when the viewport's bottom
 * is close (the row menu near the end of a long list), and it never leaves the viewport's sides.
 * Scroll always closes it: a menu that moves under the pointer while the page scrolls is a menu
 * somebody clicks the wrong thing in. Resize follows the design's two rules — the row menu closes,
 * the 「新建 ▾」 menus reposition — which is what the `resize` prop selects.
 */
export interface CommandMenuItem {
  label: string
  /** A navigation entry. */
  href?: string
  /** A command entry: opening a dialog, say. Exactly one of `href` / `onSelect` is set. */
  onSelect?: () => void
}

const props = defineProps<{
  /** One array per group; groups are drawn as separate blocks, as the row menu's are. */
  groups: CommandMenuItem[][]
  /** Read aloud for the list. Named `menuLabel` so it cannot be mistaken for a plain attribute. */
  menuLabel: string
  /**
   * What a window resize does to an open menu.
   *
   * The design specifies both, for the two kinds of menu it draws. The row menu closes: it belongs
   * to one line of a long list, and the row may have moved. The 「新建 ▾」 menus reposition: their
   * anchor is a fixed button in the page's head, and the design's own comment says so
   * (「Resizing repositions, scrolling closes」).
   */
  resize?: 'close' | 'reposition'
}>()

const open = ref(false)
const anchor = ref<HTMLElement | null>(null)
const list = ref<HTMLDivElement | null>(null)
const menuStyle = ref<Record<string, string>>({})
const links = ref<HTMLElement[]>([])

function position(): void {
  const box = anchor.value
  const menu = list.value
  if (box === null || menu === null) {
    return
  }
  const rect = box.getBoundingClientRect()
  const width = menu.getBoundingClientRect().width
  const height = menu.getBoundingClientRect().height
  menuStyle.value = {
    left: `${Math.round(
      Math.min(Math.max(8, rect.right - width), Math.max(8, window.innerWidth - width - 8)),
    )}px`,
    top: `${Math.round(
      rect.bottom + 6 + height > window.innerHeight - 8 ? rect.top - 6 - height : rect.bottom + 6,
    )}px`,
  }
}

function onOutside(event: MouseEvent): void {
  const target = event.target as Node
  if (anchor.value?.contains(target) !== true && list.value?.contains(target) !== true) {
    close(false)
  }
}

function close(refocus: boolean): void {
  if (!open.value) {
    return
  }
  open.value = false
  document.removeEventListener('mousedown', onOutside, true)
  window.removeEventListener('scroll', closeOnViewportChange, true)
  window.removeEventListener('resize', onResize)
  if (refocus) {
    focusTrigger()
  }
}

function closeOnViewportChange(): void {
  close(false)
}

function onResize(): void {
  if (props.resize === 'reposition') {
    position()
  } else {
    close(false)
  }
}

/** Puts focus back on the control this menu hangs from — the way out of an open menu, and the way
 *  back from a sheet it opened. */
function focusTrigger(): void {
  anchor.value?.querySelector<HTMLElement>('button, a')?.focus()
}

defineExpose({ focusTrigger })

function show(): void {
  close(false)
  open.value = true
  document.addEventListener('mousedown', onOutside, true)
  window.addEventListener('scroll', closeOnViewportChange, true)
  window.addEventListener('resize', onResize)
  requestAnimationFrame(() => {
    position()
    links.value[0]?.focus()
  })
}

function toggle(): void {
  if (open.value) {
    close(true)
  } else {
    show()
  }
}

function onTriggerKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    show()
  } else if (event.key === 'Escape') {
    close(true)
  }
}

function onMenuKeydown(event: KeyboardEvent): void {
  const index = links.value.indexOf(document.activeElement as HTMLElement)
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    links.value[Math.min(index + 1, links.value.length - 1)]?.focus()
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    if (index <= 0) {
      close(true)
    } else {
      links.value[index - 1]?.focus()
    }
  } else if (event.key === 'Home') {
    event.preventDefault()
    links.value[0]?.focus()
  } else if (event.key === 'End') {
    event.preventDefault()
    links.value.at(-1)?.focus()
  } else if (event.key === 'Escape') {
    event.preventDefault()
    close(true)
  } else if (event.key === 'Tab') {
    close(false)
  }
}

function setLink(
  index: number,
): (el: Element | ComponentPublicInstance | null) => void {
  return (el) => {
    if (el instanceof HTMLElement) {
      links.value[index] = el
    }
  }
}

function nextIndex(groupIndex: number, itemIndex: number): number {
  let index = itemIndex
  for (let group = 0; group < groupIndex; group++) {
    index += props.groups[group]?.length ?? 0
  }
  return index
}

function pick(item: CommandMenuItem): void {
  close(false)
  item.onSelect?.()
}

onBeforeUnmount(() => close(false))
</script>

<template>
  <span ref="anchor" class="menu-anchor" @keydown="onTriggerKeydown">
    <slot name="trigger" :open="open" :toggle="toggle" />
    <!-- Always in the DOM, hidden while closed — the same rule the row menu kept and `MenuSelect`
         states outright: "what may I choose" is readable to a screen reader (and to the checks)
         rather than existing only while the menu is open.

         **`.stop` on the keydown is load-bearing.** The list is inside the anchor span, so without
         it every arrow key also ran the trigger's handler, which reopened the menu on its first
         item — the second item could not be reached by keyboard at all. -->
    <div
      ref="list"
      class="menu-list"
      role="menu"
      :hidden="!open"
      :aria-label="props.menuLabel"
      :style="menuStyle"
      @keydown.stop="onMenuKeydown"
    >
      <template v-for="(group, groupIndex) in props.groups" :key="groupIndex">
        <div v-if="group.length > 0" class="menu-group" role="group">
          <template v-for="item in group" :key="item.label">
            <a
              v-if="item.href !== undefined"
              :ref="setLink(nextIndex(groupIndex, group.indexOf(item)))"
              class="menu-item"
              role="menuitem"
              tabindex="-1"
              :href="item.href"
            >
              {{ item.label }}
            </a>
            <button
              v-else
              :ref="setLink(nextIndex(groupIndex, group.indexOf(item)))"
              type="button"
              class="menu-item"
              role="menuitem"
              tabindex="-1"
              @click="pick(item)"
            >
              {{ item.label }}
            </button>
          </template>
        </div>
      </template>
    </div>
  </span>
</template>
