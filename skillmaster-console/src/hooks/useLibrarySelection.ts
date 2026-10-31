import { useEffect, useState } from 'react'
import type { SkillCard } from '../api/types'

/**
 * **Which skill the preview pane is showing — and when the address stops naming one.**
 *
 * The pane previews *this skill*, not *this page* (2026-11-01, the user's call): open something on
 * page 1, page to 2, and it stays open. Only a page's rows come back from the server, so the card has
 * to be remembered; changing a filter drops it, because that skill is not in the list any more.
 *
 * Three things make that harder than it reads, and each one is a bug this hook exists to keep out:
 *
 * - **The address can name a skill this page does not hold.** Refreshing, or being sent
 *   `?p=2&sel=dev/changelog-writer` where that skill is on page 1, used to close the pane: the row
 *   was not among the rows that came back, so the card looked gone and the address was cleaned.
 *   `sel` is a promise about the **filtered list**, not about the page (2026-11-01, the user's call:
 *   ask the server for that one card), so the hook asks `probe` before concluding anything.
 * - **A filter change leaves one frame where `data` is still the old page's.** In that frame the
 *   selected row is still in `rows`, so the old card would be re-registered under the *new* filters
 *   and would then survive the filter that was supposed to drop it — the address kept a `sel` the
 *   screen no longer honoured, and a reload of that address drew a different screen. `fresh` is how
 *   the hook knows the rows belong to the filters it was handed.
 * - **A page past the end answers with an empty page** and the page number is then corrected. That
 *   empty answer is not evidence about the selection: dropping on it lost the preview of a skill
 *   that was one click away. `outOfRange` suspends the verdict until the corrected page arrives.
 *
 * It returns the card the pane should draw, or `null` for "nothing is selected" — including the
 * cases that also clear the address, through `onDrop`.
 */
export function useLibrarySelection({
  sel,
  rows,
  fresh,
  outOfRange,
  listKey,
  probe,
  onDrop,
}: {
  /** The address's `sel`, `namespace/name`. */
  sel: string | null
  /** The rows of the page that is drawn. */
  rows: SkillCard[]
  /** Whether `rows` were read under the filters `listKey` names. */
  fresh: boolean
  /** The answer was a page past the end; the corrected page is on its way. */
  outOfRange: boolean
  /** The filters, as one string: a remembered card only applies to the filters it was opened with. */
  listKey: string
  /**
   * Reads one card by address from the same filtered list, for an address that names a skill on
   * another page. `'unknown'` means **the question could not be answered** — a search term is active,
   * the read failed, or the lookup window ran out — and the address is left exactly as it is: losing
   * a `sel` to a timeout is worse than keeping one whose pane is shut.
   */
  probe?: (address: string) => Promise<SkillCard | null | 'unknown'>
  /** The selection is gone: clear it from the address. */
  onDrop: () => void
}): SkillCard | null {
  const [remembered, setRemembered] = useState<{ key: string; card: SkillCard } | null>(null)
  /** `listKey\0sel` once that pair has been asked about; `${sel}` never equals it by construction. */
  const [asked, setAsked] = useState<string | null>(null)
  /** `listKey\0sel` pairs whose lookup came back "cannot tell" — never dropped, on this screen. */
  const [unanswerable, setUnanswerable] = useState<string | null>(null)
  const [asking, setAsking] = useState(false)

  const addressOf = (card: SkillCard) => `${card.namespace}/${card.name}`
  /* **The row itself wins, stale rows included.** Its text and the pane's are then the same string by
     construction — and while a filter change is in flight the old rows are still on screen, so
     reading them keeps the pane from blinking out and back on every keystroke. Nothing is *believed*
     from them: `fresh` gates the memory and the verdict, not what is drawn. */
  const selectedInRows = sel === null ? null : (rows.find((row) => addressOf(row) === sel) ?? null)
  const rememberedHit =
    sel !== null &&
    remembered !== null &&
    remembered.key === listKey &&
    addressOf(remembered.card) === sel
      ? remembered
      : null
  const selected = selectedInRows ?? rememberedHit?.card ?? null

  /* **Only a fresh page's row is remembered.** Under stale rows this would file the old card under
     the new filters — see the header. */
  useEffect(() => {
    if (fresh && selectedInRows !== null) {
      setRemembered({ key: listKey, card: selectedInRows })
    }
  }, [fresh, selectedInRows, listKey])

  const target = sel === null ? null : `${listKey}\u0000${sel}`
  const canAsk = probe !== undefined && fresh && !outOfRange && sel !== null && selected === null

  /* **Ask about an address this page cannot answer**, once per filters + address. */
  useEffect(() => {
    if (!canAsk || target === null || asked === target) {
      return
    }
    let cancelled = false
    setAsking(true)
    void (async () => {
      const answer = await probe(sel)
      if (cancelled) {
        return
      }
      setAsking(false)
      setAsked(target)
      if (answer === 'unknown') {
        /* **Answered "cannot tell"**: nothing is dropped for this pair, now or later. */
        setUnanswerable(target)
      } else if (answer !== null) {
        setRemembered({ key: listKey, card: answer })
      }
    })()
    /* **`asking` is released on the way out too.** A cancelled lookup (the filters or the address
       changed under it) used to leave it `true`; only `asked` kept that from pinning the verdict. */
    return () => {
      cancelled = true
      setAsking(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canAsk, target, asked, listKey, sel])

  /* **The verdict waits for the answer.** Nothing is dropped while a lookup is on its way, while the
     page correction is pending, or before the question for this pair has been asked. */
  useEffect(() => {
    if (sel === null || !fresh || outOfRange || selected !== null || asking) {
      return
    }
    if (probe !== undefined && asked !== target) {
      return
    }
    if (unanswerable === target) {
      return
    }
    onDrop()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sel, fresh, outOfRange, selected, asking, asked, target, unanswerable])

  return selected
}
