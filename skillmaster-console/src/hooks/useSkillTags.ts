import { useCallback, useRef, useState } from 'react'
import {
  addMyTag,
  addPublicTag,
  clearSkillCategory,
  getSkillTags,
  joinSkill,
  leaveSkill,
  listCategories,
  listMyTags,
  listPublicTags,
  removeMyTag,
  removePublicTag,
  setSkillCategory,
} from '../api/library'
import { failureMessage } from '../api/errors'
import type { ApiResult, CategoryRow, SkillTagGroups } from '../api/types'

/**
 * The information page's three word groups: one read, three vocabulary reads, and the eight writes.
 *
 * Kept out of the page because the page is already the longest one in the console and because the
 * tag layer is a small state machine of its own. **Every write re-reads the groups** rather than
 * patching a local copy: the server computes the counts, the effective/dormant boundary and the
 * four `library` facts, and a second answer on this side would be the first place the two drift.
 *
 * The vocabularies are read from `/web/categories` and `/web/public-tags`; the console carries no
 * copy of either (technical-design.md §传输契约).
 */
export function useSkillTags(
  namespace: string,
  name: string,
  active: boolean,
  onError: (message: string | null) => void,
) {
  const [groups, setGroups] = useState<SkillTagGroups | null>(null)
  const [categories, setCategories] = useState<CategoryRow[]>([])
  const [myTagDefs, setMyTagDefs] = useState<Array<{ id: string; name: string }>>([])
  const [publicTagDefs, setPublicTagDefs] = useState<Array<{ id: string; name: string }>>([])
  const myDefsLoaded = useRef(false)
  const publicDefsLoaded = useRef(false)
  const categoriesAsked = useRef(false)
  const asked = useRef(false)
  /** Whether the banner on screen is this hook's read failure, so a later read may clear it. */
  const readFailure = useRef(false)
  /** Which read put that banner there; only a newer success may take it away. */
  const readFailureGeneration = useRef(0)
  const readGeneration = useRef(0)

  const read = useCallback(async (): Promise<boolean> => {
    const generation = ++readGeneration.current
    const result = await getSkillTags(namespace, name)
    if (result.ok) {
      setGroups(result.data)
      // Only a banner this read put there is this read's to take away. The page's banner is shared
      // with the membership actions, and clearing somebody else's failure would hide it.
      if (readFailure.current && generation > readFailureGeneration.current) {
        readFailure.current = false
        onError(null)
      }
      return true
    }
    // The envelope's own message is written for a log; the console says the code's sentence.
    readFailure.current = true
    readFailureGeneration.current = generation
    onError(failureMessage(result))
    return false
  }, [namespace, name, onError])

  const startIfNeeded = useCallback((): void => {
    if (!active || asked.current) {
      return
    }
    asked.current = true
    void read().then((ok) => {
      // A failed first read can be retried: the effect runs again on the next active toggle or
      // remount, and a one-off 500 should not need a full page reload.
      if (!ok) {
        asked.current = false
      }
    })
    if (categoriesAsked.current) {
      return
    }
    categoriesAsked.current = true
    void (async () => {
      const result = await listCategories()
      if (result.ok) {
        setCategories(result.data.categories)
      }
    })()
  }, [active, read])

  /** Candidate definitions are read the first time a group is opened, not for a read-only view. */
  const loadMyTagDefs = useCallback((): void => {
    if (myDefsLoaded.current) {
      return
    }
    myDefsLoaded.current = true
    void (async () => {
      const result = await listMyTags()
      if (result.ok && Array.isArray(result.data.tags)) {
        setMyTagDefs(result.data.tags.map((tag) => ({ id: tag.id, name: tag.name })))
      }
    })()
  }, [])

  /**
   * **The public candidates are the platform vocabulary**, not the author's own public words:
   * `/web/public-tags` also offers words somebody else wrote, which is what a candidate dropdown is
   * for (technical-design.md §传输契约).
   */
  const loadPublicTagDefs = useCallback((): void => {
    if (publicDefsLoaded.current) {
      return
    }
    publicDefsLoaded.current = true
    void (async () => {
      const result = await listPublicTags()
      if (result.ok && Array.isArray(result.data.tags)) {
        setPublicTagDefs(result.data.tags.map((tag) => ({ id: tag.key, name: tag.name })))
      }
    })()
  }, [])

  const write = useCallback(
    async (action: () => Promise<ApiResult<unknown>>): Promise<boolean> => {
      // Starting a write takes the previous failure off the screen; whatever follows is about this
      // action, not the one that ended a moment ago.
      readFailure.current = false
      onError(null)
      const result = await action()
      if (!result.ok) {
        // This answer is the write's, even if a read failed while it was in flight.
        readFailure.current = false
        onError(failureMessage(result))
        return false
      }
      await read()
      return true
    },
    [onError, read],
  )

  const changeCategory = useCallback(
    (key: string | null) =>
      write(() =>
        key === null
          ? clearSkillCategory(namespace, name)
          : setSkillCategory(namespace, name, key),
      ),
    [write, namespace, name],
  )

  const addPublic = useCallback(
    (tagName: string) =>
      tagName === '' ? Promise.resolve(false) : write(() => addPublicTag(namespace, name, tagName)),
    [write, namespace, name],
  )
  const removePublic = useCallback(
    (tagName: string) => write(() => removePublicTag(namespace, name, tagName)),
    [write, namespace, name],
  )
  const addMine = useCallback(
    (tagName: string) =>
      tagName === '' ? Promise.resolve(false) : write(() => addMyTag(namespace, name, tagName)),
    [write, namespace, name],
  )
  const removeMine = useCallback(
    (tagName: string) => write(() => removeMyTag(namespace, name, tagName)),
    [write, namespace, name],
  )

  /** Adds the reference; the re-read then says 「我加入的」 and offers 移除. */
  const join = useCallback(
    () => write(() => joinSkill(namespace, name)),
    [write, namespace, name],
  )
  /** Removes only the reference. The skill stays public and callable. */
  const leave = useCallback(
    () => write(() => leaveSkill(namespace, name)),
    [write, namespace, name],
  )

  return {
    groups,
    categories,
    myTagDefs,
    publicTagDefs,
    startIfNeeded,
    loadMyTagDefs,
    loadPublicTagDefs,
    changeCategory,
    addPublic,
    removePublic,
    addMine,
    removeMine,
    join,
    leave,
  }
}
