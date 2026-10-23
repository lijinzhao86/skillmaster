import { useCallback, useState } from 'react'
import { codeMessage } from '../api/errors'
import { getSkill } from '../api/skills'
import { go } from '../address'
import { loginPath } from '../login'
import type { AuthoredSkill } from '../api/types'

/**
 * Loading one of the reader's own skills, and the four things that can come back.
 *
 * Three pages show a skill — its content, one of its files, and its comparison against live — and
 * all three have to answer the same questions in the same way: who is asking (a 401 sends them to
 * sign in, with the address to come back to), is there such a skill (a 404 is an ordinary answer,
 * not an error), and did something else go wrong (everything else is a banner).
 *
 * `missing` and `failed` are separate states because they are separate claims. Collapsing them
 * would print "没有这个 skill" at somebody whose network dropped, which is a statement about their
 * skills that this page has no way to make.
 *
 * **What changed in the port.** The three refs are three `useState` values, and `load` is wrapped
 * in `useCallback`: a page calls it from an effect, and Vue's setup-scope function was stable by
 * construction — an unstable one would re-run that effect on every render.
 *
 * Two additions the port needed. `setBanner` is returned because this hook owns the banner and the
 * page writes to it when an action is refused — Vue's page held the same `ref` and assigned to it.
 * And `load` **answers with the state it reached and the skill it read**, because a caller that
 * reads `state` or `skill` after awaiting it would be reading the render the effect closed over:
 * React commits the `setSkill` after the awaiting continuation resumes, where Vue's ref was already
 * new. Found by a settings-page test that expects the queue-count request to follow the detail read.
 */
export function useSkillDetail() {
  const [skill, setSkill] = useState<AuthoredSkill | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'missing' | 'failed'>('loading')
  const [banner, setBanner] = useState<string | null>(null)

  /** @param version omitted follows the pointer, or the newest submission when nothing is live */
  const load = useCallback(
    async (
      namespace: string,
      name: string,
      version?: string,
    ): Promise<{ state: 'ready' | 'missing' | 'failed' | 'unauthenticated'; skill: AuthoredSkill | null }> => {
      const result = await getSkill(namespace, name, version)
      if (result.ok) {
        setSkill(result.data)
        setState('ready')
        return { state: 'ready', skill: result.data }
      }
      if (result.code === 'unauthenticated') {
        go(loginPath())
        return { state: 'unauthenticated', skill: null }
      }
      if (result.status === 404) {
        setState('missing')
        return { state: 'missing', skill: null }
      }
      setState('failed')
      setBanner(codeMessage(result.code, result.message))
      return { state: 'failed', skill: null }
    },
    [],
  )

  return { skill, state, banner, setBanner, load }
}
