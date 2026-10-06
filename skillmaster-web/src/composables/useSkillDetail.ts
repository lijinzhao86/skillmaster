import { ref } from 'vue'
import { codeMessage } from '../api/errors'
import { getSkill } from '../api/skills'
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
 */
export function useSkillDetail() {
  const skill = ref<AuthoredSkill | null>(null)
  const state = ref<'loading' | 'ready' | 'missing' | 'failed'>('loading')
  const banner = ref<string | null>(null)

  /** @param version omitted follows the pointer, or the newest submission when nothing is live */
  async function load(namespace: string, name: string, version?: number): Promise<void> {
    const result = await getSkill(namespace, name, version)
    if (result.ok) {
      skill.value = result.data
      state.value = 'ready'
      return
    }
    if (result.code === 'unauthenticated') {
      window.location.assign(loginPath())
      return
    }
    if (result.status === 404) {
      state.value = 'missing'
      return
    }
    state.value = 'failed'
    banner.value = codeMessage(result.code, result.message)
  }

  return { skill, state, banner, load }
}
