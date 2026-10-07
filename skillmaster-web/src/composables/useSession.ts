import { readonly, ref } from 'vue'
import { currentSession } from '../api/account'
import { codeMessage } from '../api/errors'
import type { Account } from '../api/types'

const account = ref<Account | null>(null)
const loaded = ref(false)
const problem = ref<string | null>(null)

/**
 * Who is signed in.
 *
 * Module-level state rather than a store: there is one fact shared across pages, and it is refetched
 * on every page load anyway — a full reload is what happens after signing in, which is also what
 * picks up the session and CSRF cookies the server has just replaced.
 *
 * It doubles as the app's bootstrap, and that is not a side effect to be tidied away: the server
 * only writes the CSRF cookie while answering a request, so *some* GET has to go through before the
 * first POST can carry a token. `App.vue` waits for this before rendering anything.
 */
export function useSession() {
  async function load(): Promise<void> {
    const result = await currentSession()
    // The value is checked, not just `ok`: a success with no data is a shape the client can hand
    // back, and reading the account off `undefined` would set `account` to it while leaving
    // `problem` null — falling through to "还没有登录", which is the claim this composable exists
    // not to make.
    // `!= null` rather than `!== undefined`: a 200 whose body is the JSON literal `null` produces
    // `null`, which `!== undefined` lets through — setting `account` to nothing while leaving
    // `problem` null, which falls through to exactly the claim this composable exists not to make.
    const found: Account | null | undefined = result.ok ? result.data : undefined
    if (found != null) {
      account.value = found
      problem.value = null
    } else {
      account.value = null
      // A 401 is the ordinary answer for a visitor who is not signed in, not a failure to report.
      // Anything else — a 500, a network failure — is a question this page could not ask, and the
      // answer to that is not "还没有登录": saying so would be a claim about the session that was
      // never made.
      problem.value = result.ok
        ? '服务返回了无法识别的内容，请刷新页面重试。'
        : result.code === 'unauthenticated'
          ? null
          : codeMessage(result.code, result.message)
    }
    loaded.value = true
  }

  return {
    account: readonly(account),
    loaded: readonly(loaded),
    problem: readonly(problem),
    load,
  }
}
