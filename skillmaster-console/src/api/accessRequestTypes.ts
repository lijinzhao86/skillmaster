import type { Rung } from './types'

/** One account waiting for a rung (ADR 0052). A request grants nothing until it is approved. */
export interface AccessRequest {
  handle: string
  wanted_role: Rung
  created_at: string
}

export interface AccessRequests {
  requests: AccessRequest[]
  total: number
}
