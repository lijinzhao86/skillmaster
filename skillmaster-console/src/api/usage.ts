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
}

export interface UsageStats {
  window_days: number
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
  public: boolean
  totals: { calls: string; accounts: string; joins: number; last_used: string | null }
  users: UsageUser[]
}

export interface UsageMine {
  skill: string
  window_days: number
  calls: number
  versions: number
  latest: string | null
  by_version: { version: string; state: string; calls: number; last: string | null }[]
}

export function listUsage(page = 1, limit = 10): Promise<ApiResult<UsagePage>> {
  const query = new URLSearchParams()
  if (page > 1) query.set('page', String(page))
  query.set('limit', String(limit))
  return request<UsagePage>({ method: 'GET', path: `/web/usage/interactions?${query}` })
}

export function getUsageStats(): Promise<ApiResult<UsageStats>> {
  return request<UsageStats>({ method: 'GET', path: '/web/usage/stats' })
}

function skillPath(namespace: string, name: string): string {
  return `/web/usage/skills/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`
}

export function getSkillUsage(namespace: string, name: string): Promise<ApiResult<SkillUsage>> {
  return request<SkillUsage>({ method: 'GET', path: skillPath(namespace, name) })
}

export function getSkillUsageUsers(
  namespace: string,
  name: string,
): Promise<ApiResult<{ window_days: number; users: UsageUser[] }>> {
  return request({ method: 'GET', path: `${skillPath(namespace, name)}/users` })
}

export function getSkillUsageMine(
  namespace: string,
  name: string,
): Promise<ApiResult<UsageMine>> {
  return request<UsageMine>({ method: 'GET', path: `${skillPath(namespace, name)}/mine` })
}
