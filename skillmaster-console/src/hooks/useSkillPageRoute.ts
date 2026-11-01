import { useEffect, useRef, useState } from 'react'
import { query as pageParams } from '../address'
import { atLeast } from '../rungs'
import { parseVersionRoute, type VersionRoute } from '../version'
import type { AuthoredSkill, Standing } from '../api/types'
import {
  baseAddressFor,
  subHrefFor,
  tabHrefFor,
  type SkillAddressParts,
} from '../pages/skill/skillAddress'
import {
  SUBPAGES,
  TABS,
  type Subpage,
  type Tab,
} from '../pages/skill/skillRoute'

/**
 * Which section of one skill is open, and every address that section writes.
 *
 * The address is read once, in `useState` initialisers, exactly where Vue's `setup` read it:
 * `?tab=`, the version route and the settings sub-page are the page's opening state, and the only
 * writes afterwards are `replaceState` for the version list's filter. The rung the server sent is
 * what decides which sections exist; a section it cannot reach is not drawn at all.
 */
export interface SkillPageRouteProps {
  namespace: string
  name: string
  pathVersion?: string
  relpath?: string
  from?: string
  to?: string
  skill: AuthoredSkill | null
}

export function useSkillPageRoute({
  namespace,
  name,
  pathVersion,
  relpath,
  from,
  to,
  skill,
}: SkillPageRouteProps) {
  const params = useRef(pageParams()).current
  const versionRoute = useRef<VersionRoute>(parseVersionRoute(window.location.search)).current
  /** `/skills/…/diff?from=&to=` is the legacy alias for `?tab=versions&version=<to>&sub=diff&from=`. */
  const legacyDiff = from !== undefined || to !== undefined

  const rung: Standing = skill === null ? 'invoke' : skill.rung
  const canBrowse = skill !== null && atLeast(rung, 'browse')
  const canWrite = skill !== null && atLeast(rung, 'write')
  const canMaintain = skill !== null && atLeast(rung, 'maintain')
  const canAdmin = skill !== null && atLeast(rung, 'admin')

  /** The sections this rung can reach. 设置 is last, which is also the least frequent reason to come. */
  const tabs: Array<{ key: Tab; label: string }> = [{ key: 'info', label: '信息' }]
  if (canBrowse) {
    tabs.push({ key: 'files', label: '文件' })
  }
  tabs.push({ key: 'versions', label: '版本' })
  if (canAdmin) {
    tabs.push({ key: 'settings', label: '设置' })
  }

  const requested = params.get('tab')

  /**
   * Which section is open: what the address asked for when it names one of the four, otherwise the
   * file or the comparison when the address names those — and 信息 when it names neither.
   *
   * **An explicit `tab` wins.** The design settles the same order (`openTabs.indexOf(askTab) >= 0 ?
   * askTab : (namedFiles ? 'files' : openTabs[0])`): a directory or a file is the address's *default*
   * destination, not an override of a section it also names. Inverting that trapped the reader — a
   * tab link kept `path` in the query, the page reloaded, and every tab landed back on 文件.
   */
  const [tab, setTab] = useState<Tab>(
    requested !== null && (TABS as readonly string[]).includes(requested)
      ? (requested as Tab)
      : relpath !== undefined || params.get('path') !== null
        ? 'files'
        : legacyDiff
          ? 'versions'
          : 'info',
  )

  // A rung that cannot reach the section the address names lands on 信息 instead of on nothing.
  const tabsKey = tabs.map((entry) => entry.key).join(',')
  const tabsSettled = useRef(false)
  useEffect(() => {
    if (!tabsSettled.current) {
      tabsSettled.current = true
      return
    }
    if (!tabs.some((entry) => entry.key === tab)) {
      setTab('info')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabsKey])

  /**
   * The settings sub-page, read once from the address. Not state: its three items are real links, and
   * the only other way this changes is a full navigation — which re-reads the address.
   */
  const requestedSub = params.get('sub')
  const sub: Subpage =
    tab === 'settings' && requestedSub !== null && (SUBPAGES as readonly string[]).includes(requestedSub)
      ? (requestedSub as Subpage)
      : 'general'

  const parts: SkillAddressParts = { namespace, name, pathVersion }

  function baseAddress(pin?: string | null): string {
    return baseAddressFor(parts, pin)
  }

  function tabHref(key: Tab): string {
    return tabHrefFor(parts, params, key)
  }

  function subHref(key: Subpage): string {
    return subHrefFor(parts, params, key)
  }

  return {
    params,
    versionRoute,
    legacyDiff,
    rung,
    canBrowse,
    canWrite,
    canMaintain,
    canAdmin,
    tabs,
    tab,
    sub,
    parts,
    baseAddress,
    tabHref,
    subHref,
  }
}
