export interface SignalOrigin {
  kind: string
  role: string
  label: string
  locator: string | null
  path: string | null
  url: string | null
  revision: string | null
  checked_at: string | null
  signals: Array<{ key: string; label: string; kind: string; value: string; display: string; url: string | null }>
}

export interface SignalLicense {
  spdx: string
  name: string
  copyright: string | null
  status: string
  url: string | null
  allows: string[]
  requires: string[]
  declared: string | null
  source: string | null
}

/**
 * The `/metadata` use block (v49). **Buckets only**: `bucket` is the 30-day account bucket and
 * `calls_bucket` the cumulative call bucket; neither is an exact count (A6). `joins` is the one
 * public integer, read from the library.
 */
export interface SignalUsage {
  bucket: string
  calls_bucket: string | null
  last_used: string | null
  joins: number
}
