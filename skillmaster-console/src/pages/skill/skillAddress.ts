/**
 * The addresses one skill's sections write.
 *
 * Framework-free on purpose: these are strings, and the route hook is the only thing that decides
 * when to navigate with one. The rules themselves are the page's — they used to live inside it —
 * and they are here so the version list, the file table and the settings links cannot each spell
 * the same address a little differently.
 */

import { addressFor } from '../../address'
import type { AuthoredVersion } from '../../api/types'
import { versionSuffix, type VersionFilterKey, type VersionRoute } from '../../version'
import type { Subpage, Tab } from './skillRoute'

export interface SkillAddressParts {
  namespace: string
  name: string
  pathVersion?: string
}

/** `null` means "no version pin at all"; `undefined` keeps the path's pin when there is one. */
export function baseAddressFor(parts: SkillAddressParts, pin?: string | null): string {
  const suffix =
    pin === null
      ? ''
      : pin === undefined
        ? parts.pathVersion === undefined
          ? ''
          : `@${parts.pathVersion}`
        : `@${pin}`
  return `/skills/${encodeURIComponent(parts.namespace)}/${encodeURIComponent(parts.name)}${suffix}`
}

/**
 * A section's own address.
 *
 * The version tab's `version` / `sub` / `from` / `fq` / `file` / `list` belong to that tab; carrying
 * them to 信息 or 设置 would be an address naming two screens. The list's `filter` / `q` are kept
 * when going back to 版本, because returning from a version page should land on the same list.
 *
 * **Only the files tab carries a version in the path**; 信息 / 版本 / 设置 are skill-level screens,
 * and an `@1.2.3` on them would name a version the screen does not show.
 */
export function tabHrefFor(parts: SkillAddressParts, params: URLSearchParams, key: Tab): string {
  const next = new URLSearchParams(params)
  next.delete('p')
  for (const name of ['version', 'sub', 'bump', 'from', 'to', 'fq', 'file', 'list']) {
    next.delete(name)
  }
  if (key !== 'versions') {
    next.delete('filter')
    next.delete('q')
  }
  // A directory belongs to the files section. Carrying it to another tab would be an address that
  // names two screens, and the reader would have to know which one wins.
  if (key !== 'files') {
    next.delete('path')
  }
  if (key === 'info') {
    next.delete('tab')
  } else {
    next.set('tab', key)
  }
  if (key !== 'settings') {
    next.delete('sub')
  }
  const queryString = next.toString()
  const base = key === 'files' ? baseAddressFor(parts) : baseAddressFor(parts, null)
  return `${base}${queryString === '' ? '' : `?${queryString}`}`
}

export function subHrefFor(
  parts: SkillAddressParts,
  params: URLSearchParams,
  key: Subpage,
): string {
  const next = new URLSearchParams(params)
  for (const name of ['version', 'bump', 'from', 'fq', 'file', 'list']) {
    next.delete(name)
  }
  next.set('tab', 'settings')
  next.set('sub', key)
  next.delete('path')
  return `${baseAddressFor(parts, null)}?${next.toString()}`
}

/** A directory's own level, as an address: the listing is one level at a time. */
export function dirHrefFor(
  parts: SkillAddressParts,
  params: URLSearchParams,
  dir: string,
  pin: string | undefined,
): string {
  const query = new URLSearchParams(params)
  query.set('tab', 'files')
  for (const name of ['p', 'q', 'version', 'sub', 'bump', 'from', 'fq', 'file', 'list']) {
    query.delete(name)
  }
  if (dir === '') {
    query.delete('path')
  } else {
    query.set('path', dir)
  }
  return `${baseAddressFor(parts, pin)}?${query.toString()}`
}

export function fileHrefFor(
  parts: SkillAddressParts,
  file: string,
  pin: string | undefined,
): string {
  const encoded = file.split('/').map(encodeURIComponent).join('/')
  return `${baseAddressFor(parts, pin)}/files/${encoded}`
}

/** The way back from a version page to the list it was opened from. */
export function backToListHrefFor(parts: SkillAddressParts, params: URLSearchParams): string {
  const next = new URLSearchParams(params)
  for (const name of ['version', 'sub', 'bump', 'from', 'to', 'fq', 'file', 'list']) {
    next.delete(name)
  }
  next.set('tab', 'versions')
  return addressFor(baseAddressFor(parts, null), next)
}

/** A new comparison baseline is a new screen; the address is the state, so it is a real navigation. */
export function selectBaselineAddress(
  parts: SkillAddressParts,
  params: URLSearchParams,
  pin: string,
  detailRoute: VersionRoute,
): string {
  const next = new URLSearchParams(params)
  next.set('tab', 'versions')
  next.set('sub', 'diff')
  next.set('from', pin)
  // The legacy `/diff` alias's `to=` names the target the old address carried; the canonical form
  // carries it in `version`, so the two are not both kept.
  next.delete('to')
  if (detailRoute.version !== undefined) {
    next.set('version', detailRoute.version)
  }
  return addressFor(baseAddressFor(parts, null), next)
}

/**
 * The list's address. Filter and search are written with `replaceState`, not by navigating: the
 * list is already here, and a round trip per keystroke is what makes a search box feel like a form.
 * Both survive refresh, a copied link and the way back from a version page.
 */
export function listAddressParams(
  params: URLSearchParams,
  filter: VersionFilterKey,
  query: string,
): URLSearchParams {
  const next = new URLSearchParams(params)
  next.set('tab', 'versions')
  for (const name of ['version', 'sub', 'bump', 'from', 'to', 'fq', 'file', 'list']) {
    next.delete(name)
  }
  if (filter === 'all') {
    next.delete('filter')
  } else {
    next.set('filter', filter)
  }
  if (query.trim() === '') {
    next.delete('q')
  } else {
    next.set('q', query)
  }
  return next
}

/** Which version the files on screen belong to, as the link back to that version's own page. */
export function commitVersionHrefFor(
  parts: SkillAddressParts,
  fileTabVersion: AuthoredVersion | null,
): string {
  return fileTabVersion === null
    ? baseAddressFor(parts, null)
    : `${baseAddressFor(parts, null)}?tab=versions&version=${encodeURIComponent(versionSuffix(fileTabVersion))}`
}

/**
 * 「下载」 in the file table's head (2026-10-21, design §一个 skill).
 *
 * The same archive route and the same condition as the version page's 下载: whoever can read the
 * files on screen can take that whole version. `shownVersion` is what decides — when it is null
 * (a pinned discarded version, or nothing readable at all) there are no files, so there is nothing
 * to download; the pinned row in the header is an identity, not a snapshot.
 */
export function fileDownloadHrefFor(
  parts: SkillAddressParts,
  shownVersion: AuthoredVersion | null,
): string | null {
  return shownVersion === null
    ? null
    : `/web/skills/${encodeURIComponent(parts.namespace)}/${encodeURIComponent(parts.name)}@${encodeURIComponent(versionSuffix(shownVersion))}/archive`
}
