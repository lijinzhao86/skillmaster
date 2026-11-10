import { useCallback, useRef, useState } from 'react'
import {
  clearMyWhenToUse,
  getMyWhenToUse,
  setMyWhenToUse as putMyWhenToUse,
} from '../api/library'
import { failureMessage } from '../api/errors'

/**
 * The caller's own sentence on one skill (v59).
 *
 * **Read once, only while the library entry is effective.** The server refuses the read outside
 * the library, and a dormant entry is not writable; the page asks this hook to start only when
 * those facts say so.
 *
 * The write is local rather than a re-read: the response is the same sentence the server now
 * stores (it normalizes whitespace), and a second read would only confirm what this call already
 * knows. A refusal is field-level when it is about the text, and the page's banner otherwise.
 */
export function useSkillMyWhenToUse(
  namespace: string,
  name: string,
  active: boolean,
  onError: (message: string | null) => void,
) {
  /** null while not read yet; '' is the empty state and a string is the sentence. */
  const [myWhenToUse, setSentence] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const asked = useRef(false)

  const read = useCallback(async (): Promise<boolean> => {
    const result = await getMyWhenToUse(namespace, name)
    if (result.ok) {
      setSentence(result.data.when_to_use ?? '')
      return true
    }
    onError(failureMessage(result))
    return false
  }, [namespace, name, onError])

  const startIfNeeded = useCallback((): void => {
    if (!active || asked.current) {
      return
    }
    asked.current = true
    void read().then((ok) => {
      if (!ok) {
        asked.current = false
      }
    })
  }, [active, read])

  const write = useCallback(
    async (action: () => ReturnType<typeof putMyWhenToUse>, next: string) => {
      setSaving(true)
      setError(null)
      onError(null)
      const result = await action()
      setSaving(false)
      if (!result.ok) {
        // The two refusals this text can earn are about the text itself; anything else (a lost
        // session, a skill that left the library) belongs on the page's banner.
        if (
          result.code === 'my_when_to_use_too_long' ||
          result.code === 'my_when_to_use_empty'
        ) {
          setError(failureMessage(result))
        } else {
          onError(failureMessage(result))
        }
        return false
      }
      setSentence(next)
      return true
    },
    [onError],
  )

  const save = useCallback(
    (text: string) => write(() => putMyWhenToUse(namespace, name, text), text),
    [write, namespace, name],
  )

  const clear = useCallback(
    () => write(() => clearMyWhenToUse(namespace, name), ''),
    [write, namespace, name],
  )

  return { myWhenToUse, error, saving, startIfNeeded, save, clear }
}
