import type { CSSProperties } from 'react'

/**
 * The file tree's state shapes.
 *
 * They live beside the tree — and beside `files.ts`, which computes what the tree shows — because
 * both the page that owns this state and the component that draws it have to name it. Types only:
 * the state itself is still the page's, passed down as values and setters.
 */

/** One drawn line: a folder or a file, already flattened to the depth it renders at. */
export interface TreeRow {
  path: string
  name: string
  depth: number
  dir: boolean
  expanded: boolean
  /** A folder with nothing under it — drawn but not expandable. */
  empty: boolean
}

/** The new-entry input at the top of the tree, while it is open. */
export interface CreateState {
  kind: 'file' | 'dir'
  value: string
  error: string | null
}

/** The in-place rename input on one row. */
export interface RenameState {
  path: string
  value: string
  error: string | null
}

/** A row's action menu, positioned where its button was pressed. */
export interface MenuState {
  path: string
  style: CSSProperties
}
