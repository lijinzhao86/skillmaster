import { useRef, useState } from 'react'
import type {
  ChangeEvent,
  KeyboardEvent as ReactKeyboardEvent,
} from 'react'
import type { EditorDocument } from './useEditorDocument'

/**
 * The text pane's local facts: where the caret is, where it is scrolled to, and the buffer writes
 * the textarea itself makes.
 *
 * The scroll position is a ref, not state — a state would re-render the whole page on every scroll
 * — and the gutter follows it directly, which is the one thing the pane cannot do from props.
 */
export function useEditorCaret(doc: EditorDocument) {
  const scrollTop = useRef(0)
  const [caret, setCaret] = useState({ line: 1, column: 1 })
  const textarea = useRef<HTMLTextAreaElement | null>(null)
  const gutter = useRef<HTMLDivElement | null>(null)

  function onTextKeydown(event: ReactKeyboardEvent<HTMLTextAreaElement>): void {
    if (event.key !== 'Tab') return
    // Tab is indentation here, not a way out; two spaces, matching the design's own editor.
    event.preventDefault()
    const element = textarea.current
    if (element === null) return
    element.setRangeText('  ', element.selectionStart, element.selectionEnd, 'end')
    doc.writeBuffer(doc.currentRef.current, element.value)
    onCaret()
  }

  function onTextInput(event: ChangeEvent<HTMLTextAreaElement>): void {
    doc.writeBuffer(doc.currentRef.current, event.target.value)
    onCaret()
  }

  function onTextScroll(): void {
    scrollTop.current = textarea.current?.scrollTop ?? 0
    if (gutter.current !== null) gutter.current.scrollTop = scrollTop.current
  }

  function onCaret(): void {
    const element = textarea.current
    if (element === null) return
    const before = element.value.slice(0, element.selectionStart)
    const lines = before.split('\n')
    setCaret({
      line: lines.length,
      column: (lines[lines.length - 1] ?? '').length + 1,
    })
  }

  return {
    caret,
    setCaret,
    textarea,
    gutter,
    onTextInput,
    onTextScroll,
    onTextKeydown,
    onCaret,
  }
}

export type EditorCaret = ReturnType<typeof useEditorCaret>
