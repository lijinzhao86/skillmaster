/**
 * Version names, as an address spells them and as a page shows them.
 *
 * ADR 0033: a version's name is the semver its author declared in `SKILL.md`, and a version whose
 * author declared none has no name at all — it is addressed by its content digest. So every page has
 * to answer the same two questions about a version: what suffix its address carries, and what to
 * call it on screen. Answered here once, because a second answer would eventually disagree with the
 * first about something like the abbreviation.
 */

/**
 * SemVer 2.0.0's official grammar, unchanged, mirroring the server's own validator.
 *
 * A suffix is a version name or a prefixed digest, and one that is neither names nothing — which is
 * why the address parser folds it into "no version" rather than passing it on. Kept the same
 * expression the server uses so that a link this app builds is one the server resolves, and `@3` —
 * the integer alias ADR 0033 retired — is refused on both sides.
 */
const SEMVER =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$/

/** Whether a suffix after `@` names a version: a semver, or a prefixed SHA-256 digest. */
export function isVersionSuffix(suffix: string): boolean {
  return SEMVER.test(suffix) || /^sha256:[0-9a-f]{64}$/.test(suffix)
}

/**
 * A version's address suffix: its declared name, or its digest when it has none.
 *
 * The digest arrives already prefixed (`sha256:…`), so the fallback is the digest itself rather than
 * a prefix added here — the same spelling the API hands out and a URI it mints pins.
 */
export function versionSuffix(version: { name: string | null; digest: string }): string {
  return version.name ?? version.digest
}

/**
 * What to call a version on screen, from its address suffix: its name, or an abbreviated digest.
 *
 * The abbreviation is git's — the first 8 hex characters — and deliberately not one of our own: a
 * person who reads `a1b2c3d4` already reads it as a hash, where `@1` would be the integer alias
 * ADR 0033 retired. Only the digest is abbreviated; a name is the author's own and is shown whole.
 */
export function versionLabel(suffix: string): string {
  return suffix.startsWith('sha256:') ? suffix.slice('sha256:'.length, 'sha256:'.length + 8) : suffix
}
