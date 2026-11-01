import { useState } from 'react'
import { go, query as pageParams, relativeAddress, replaceAddress } from '../address'
import type { AuthoredVersion } from '../api/types'
import {
  filterKeyOf,
  statusOf,
  versionTitle,
  type VersionFilterKey,
  type VersionRoute,
} from '../version'
import { defaultBaseline } from '../versionActions'
import {
  backToListHrefFor,
  listAddressParams,
  selectBaselineAddress,
  type SkillAddressParts,
} from '../pages/skill/skillAddress'

/**
 * The version list's own address: its status filter, its search, and the rows those select.
 *
 * Filter and search are written with `replaceState`, not by navigating: the list is already here,
 * and a round trip per keystroke is what makes a search box feel like a form. Both survive refresh,
 * a copied link and the way back from a version page.
 */
export interface SkillVersionsProps {
  versionRoute: VersionRoute
  visibleVersions: AuthoredVersion[]
  rung: string
  liveVersion: AuthoredVersion | null
  detailRoute: VersionRoute
  parts: SkillAddressParts
}

export function useSkillVersions({
  versionRoute,
  visibleVersions,
  rung,
  liveVersion,
  detailRoute,
  parts,
}: SkillVersionsProps) {
  const [filter, setFilterState] = useState<VersionFilterKey>(versionRoute.filter)
  const [query, setQueryState] = useState(versionRoute.q)

  function writeListAddress(nextFilter: VersionFilterKey, nextQuery: string): void {
    const next = listAddressParams(pageParams(), nextFilter, nextQuery)
    replaceAddress(relativeAddress(next))
  }

  function setFilter(value: VersionFilterKey): void {
    setFilterState(value)
    writeListAddress(value, query)
  }

  function setQuery(value: string): void {
    setQueryState(value)
    writeListAddress(filter, value)
  }

  function clearFilters(): void {
    setFilterState('all')
    setQueryState('')
    writeListAddress('all', '')
  }

  /** The list state the rows carry into their own page, and back. */
  const listQueryString = (() => {
    const next = new URLSearchParams()
    if (filter !== 'all') {
      next.set('filter', filter)
    }
    if (query.trim() !== '') {
      next.set('q', query)
    }
    return next.toString()
  })()

  /**
   * The rows on screen: status filter first, then search. What the search reads is what the row shows
   * — the name, number, note and submitter on the author plane; only the number and note for a
   * caller, whose row never carries the other two.
   */
  const matchedVersions = visibleVersions.filter((candidate) => {
    if (filter !== 'all' && filterKeyOf(statusOf(candidate)) !== filter) {
      return false
    }
    const needle = query.trim().toLowerCase()
    if (needle === '') {
      return true
    }
    const words =
      rung === 'invoke'
        ? [candidate.name ?? '', candidate.note ?? '']
        : [
            versionTitle(candidate, rung),
            candidate.name ?? '',
            candidate.note ?? '',
            candidate.submitted_by ?? '',
          ]
    return words.join(' ').toLowerCase().includes(needle)
  })

  /** Whether a version has a baseline at all — what draws its 「查看改动」 menu item. */
  function hasBaselineFor(candidate: AuthoredVersion): boolean {
    return defaultBaseline(candidate, visibleVersions, liveVersion, rung) !== null
  }

  const backToListHref = backToListHrefFor(parts, pageParams())

  /** A new comparison baseline is a new screen; the address is the state, so it is a real navigation. */
  function selectBaseline(pin: string): void {
    go(selectBaselineAddress(parts, pageParams(), pin, detailRoute))
  }

  return {
    filter,
    query,
    setFilter,
    setQuery,
    clearFilters,
    listQueryString,
    matchedVersions,
    hasBaselineFor,
    backToListHref,
    selectBaseline,
  }
}
