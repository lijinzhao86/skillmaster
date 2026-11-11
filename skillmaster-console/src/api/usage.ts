import { request } from './client'
import type { ApiResult } from './types'

/**
 * 使用记录（v58）. Everything here is the browser plane; the API plane's `/api/v1/usage` returns
 * the same shapes for the CLI.
 */

export interface UsageResult {
  skill: string
  rank: number
}

export interface UsageInvocation {
  skill: string
  version: string
  at: string
  rank: number
}

export interface UsageSearch {
  kind: 'search'
  /** `activity_event.id` — the row's stable key, not an address. */
  id?: number
  at: string
  query: string
  total: number | null
  returned: number
  results: UsageResult[]
  invocations: UsageInvocation[]
}

export interface UsageDirect {
  kind: 'direct'
  /** `activity_event.id` — the row's stable key, not an address. */
  id?: number
  at: string
  skill: string
  version: string
}

export type UsageInteraction = UsageSearch | UsageDirect

export interface UsagePage {
  items: UsageInteraction[]
  total: number
  page: number
  page_size: number
  window_days: number
  since: string
  until: string
  skill: string | null
  /** Any event at all inside the retention window, filters aside. */
  has_any: boolean
}

export interface UsageStats {
  window_days: number
  since: string
  until: string
  skill: string | null
  calls: number
  searches: number
  converted: number
  missed: number
  skills: number
  latest: string | null
  top: { skill: string; calls: number; last: string }[]
}

export interface UsageUser {
  who: string
  owner: boolean
  role: string
  invoke: number
  submitted: number
  last: string | null
}

export interface SkillUsage {
  skill: string
  window_days: number
  since: string
  until: string
  public: boolean
  totals: { calls: string; accounts: string; joins: number; last_used: string | null }
  users: UsageUser[]
}

export interface UsageMine {
  skill: string
  window_days: number
  since: string
  until: string
  calls: number
  versions: number
  latest: string | null
  by_version: { version: string; state: string; calls: number; last: string | null }[]
}

/** The two instants the server reads, plus the optional address keyword (v60). */
export interface UsageWindow {
  since: string
  until: string
  skill?: string
}

function windowQuery(window: UsageWindow): URLSearchParams {
  const params = new URLSearchParams()
  params.set('since', window.since)
  params.set('until', window.until)
  if (window.skill !== undefined && window.skill !== '') params.set('skill', window.skill)
  return params
}

export function listUsage(
  page: number,
  limit: number,
  window: UsageWindow,
): Promise<ApiResult<UsagePage>> {
  const query = windowQuery(window)
  if (page > 1) query.set('page', String(page))
  query.set('limit', String(limit))
  return request<UsagePage>({ method: 'GET', path: `/web/usage/interactions?${query}` })
}

export function getUsageStats(window: UsageWindow): Promise<ApiResult<UsageStats>> {
  return request<UsageStats>({ method: 'GET', path: `/web/usage/stats?${windowQuery(window)}` })
}

/** The pathname only: callers append `/users`, `/mine`, then the query — never the other way. */
function skillPath(namespace: string, name: string): string {
  return `/web/usage/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`
}

function withWindow(path: string, window: UsageWindow): string {
  return `${path}?${windowQuery(window)}`
}

export function getSkillUsage(
  namespace: string,
  name: string,
  window: UsageWindow,
): Promise<ApiResult<SkillUsage>> {
  return request<SkillUsage>({ method: 'GET', path: withWindow(skillPath(namespace, name), window) })
}

export function getSkillUsageUsers(
  namespace: string,
  name: string,
  window: UsageWindow,
): Promise<ApiResult<{ window_days: number; since: string; until: string; public: boolean; users: UsageUser[] }>> {
  return request({ method: 'GET', path: withWindow(`${skillPath(namespace, name)}/users`, window) })
}

export function getSkillUsageMine(
  namespace: string,
  name: string,
  window: UsageWindow,
): Promise<ApiResult<UsageMine>> {
  return request<UsageMine>({
    method: 'GET',
    path: withWindow(`${skillPath(namespace, name)}/mine`, window),
  })
}
