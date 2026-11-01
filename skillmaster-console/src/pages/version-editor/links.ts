/**
 * The addresses this editor links to, built from the route grammar in one place.
 *
 * **A version is a tab pin, not a path suffix** (the product's route grammar): `/skills/ns/name`
 * opens 信息, and `?tab=versions&version=<pin>` is the version page. The editor's `@<pin>/edit` is
 * the one address that carries a suffix, because it is a page of its own.
 */

export function skillHrefOf(namespace: string, name: string): string {
  return `/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`
}

export function versionsHrefOf(skillHref: string): string {
  return `${skillHref}?tab=versions`
}

export function versionHrefOf(skillHref: string, pin: string): string {
  return `${skillHref}?tab=versions&version=${encodeURIComponent(pin)}`
}

/**
 * The **server's** file address, not the page's.
 *
 * `下载文件` has to return the bytes, and `/skills/.../files/...` is the SPA's file tab — for a
 * binary it renders a sentence telling the reader to use the CLI. The raw endpoint lives on the
 * session plane, one segment per path.
 */
export function fileHrefOf(
  namespace: string,
  name: string,
  readSuffix: string | undefined,
  path: string,
): string {
  const suffix = readSuffix === undefined ? '' : `@${encodeURIComponent(readSuffix)}`
  return `/web/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}${suffix}/files/${path.split('/').map(encodeURIComponent).join('/')}`
}
