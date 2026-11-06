import type { SkillCard } from './types'

/**
 * `GET /web/discover`: the public results. Two shapes, told apart by whether the paging fields came
 * back (v19).
 *
 * **A keyword is a search by meaning**: at most ten rows, no page, no total — similarity is not a
 * page boundary. **A category alone is the literal search**, paged at six, and carries the total the
 * design's numbered pager needs. A degraded semantic search also falls back to the paged shape.
 */
export interface DiscoverResults {
  skills: SkillCard[]
  /** Absent on a semantic answer: there is no page 2 of "most similar". */
  p?: number
  page_size?: number
  total?: number
  /**
   * Why this is not the answer that was asked for: a filter word that names nothing, or semantic
   * search was unavailable (and either these rows came from the literal search, or there were
   * none). **Not** "nothing was similar enough" — that empty state is the page's own sentence.
   * Absent on an ordinary answer.
   */
  note?: string
}

/** `GET /web/discover/recommended`: one batch, and the cursor for the next one (null at the end). */
export interface RecommendedPage {
  skills: SkillCard[]
  next_cursor: string | null
}

