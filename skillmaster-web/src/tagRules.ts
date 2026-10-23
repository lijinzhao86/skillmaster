/**
 * The tag rules the console enforces before a request leaves it.
 *
 * **The server is the only place normalization is stored.** These helpers exist to give the row an
 * error before a round trip and to match a typed name against what is already on screen; a name that
 * passes here can still be refused (NFKC and the category collision are the server's call), and that
 * refusal is shown in the same place.
 */

/** Characters, counted by code point: one Chinese character is one, not two UTF-16 units. */
export const MAX_TAG_NAME = 24
export const MAX_TAG_DESCRIPTION = 200
export const MAX_TAGS_PER_ACCOUNT = 100
export const MAX_TAGS_PER_SKILL = 20
export const MAX_PUBLIC_TAGS_PER_SKILL = 10

/** A code-point slice, so truncating at 24 never splits a surrogate pair. */
export function slicePoints(value: string, limit: number): string {
  return Array.from(value).slice(0, limit).join('')
}

export function pointLength(value: string): number {
  return Array.from(value).length
}

/**
 * The key a typed name is compared by: trimmed, inner runs of whitespace collapsed, case-folded.
 *
 * The server does the same and then NFKC-folds it; the console's copy is deliberately narrower
 * because it is only a pre-check — it never decides what is stored.
 */
export function tagKey(value: string): string {
  return value.trim().replace(/\s+/g, ' ').toLowerCase()
}

/** The name as the console shows it before the server answers: trimmed, inner spaces collapsed. */
export function normalizeTagName(value: string): string {
  return value.trim().replace(/\s+/g, ' ')
}
