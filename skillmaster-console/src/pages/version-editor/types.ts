/**
 * The shapes the editor's hooks exchange.
 *
 * Only types: state still lives in the hook that owns it, and every hook that reads another one's
 * state does so through these names rather than through an implicit shared scope.
 */

export interface VersionEditorPageProps {
  namespace: string
  name: string
  mode: 'new' | 'edit'
  version?: string
  file?: string
}

/** A link the page is holding back until unsaved work is answered. */
export interface LeaveTarget {
  href: string
  opener: HTMLElement | null
}

/** The sentence a notice banner shows, and the address its action opens. */
export interface DuplicateNotice {
  title: string
  label: string
  href: string
}

/** A hint under the header's fields — `danger` is the red one. */
export interface EditorLabelStatus {
  text: string
  danger: boolean
}

/** Titled failure text: the editor's one way of saying a request did not succeed. */
export type FailureReporter = (code: string, message: string, title?: string) => void

/** The names a load hands the label fields once it knows which version is on screen. */
export type LabelsInit = (name: string, note: string) => void
