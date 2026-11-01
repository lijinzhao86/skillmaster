/**
 * The public entry for the version editor.
 *
 * The page is now composed in `./version-editor/`; this file keeps the address every caller and
 * test already imports (`../pages/VersionEditorPage`) unchanged.
 */
export { default } from './version-editor/VersionEditorPage'
export type { VersionEditorPageProps } from './version-editor/types'
