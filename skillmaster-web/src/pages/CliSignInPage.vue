<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { refuseCliSignIn, startCliSignIn } from '../api/cliSignIns'
import { codeMessage } from '../api/errors'
import { useSession } from '../composables/useSession'
import { loginPath } from '../login'

/**
 * 授权请求 — the screen `skillmaster login` hands the browser to (v4).
 *
 * **A copy of the design's consent fragment, not a rewrite of it.**
 * `design/partials/auth-overlay.html` carries the screen part by part — the markup, the class names
 * and every number in the styles — and what stands here is that screen with the demo's scaffolding
 * left behind (its `?page=` pseudo-router, its fixtures, its one-file machinery). The fragment's
 * styles are scoped under `.dialog` and stay scoped there (`styles.css`), which is what keeps them
 * from reaching another page's `.field` and `.hint`.
 *
 * **It is a login, not a key.** What the person is agreeing to is that this machine gets to act as
 * them until the login runs out — the same two rulers a browser session has (24 hours idle, 7 days
 * absolute), and the same screen it shows up on. Deliberately not an API key: a key is a long-lived
 * credential somebody issued to themselves, and folding the two together would make 「key 是长期凭证」
 * stop being true on the very screen that lists them.
 *
 * **It is a page, not a dialog.** The CLI opened this address; there is no site under it, so the card
 * is the whole page (the design's `.overlay.is-standalone`), and no control closes it — the design's
 * own rule for a dialog with nothing behind it. (The demo's screenshot shows a × because the demo
 * hosts this screen over its own homepage, which is the one thing this page does not have.)
 *
 * **Nothing here is a parameter this page invents.** `client_id`, `state`, `scope` and
 * `code_challenge` all came from the address the CLI opened, and the server checks every one of them
 * again: the client id only selects a registered row, the scopes are intersected with what that row
 * is registered for, and the challenge is stored to be proved at the exchange. What the person
 * supplies is the name, and the server refuses a blank one even though the button below will not
 * submit one.
 */
const query = new URLSearchParams(window.location.search)

const clientId = query.get('client_id')
const state = query.get('state')
const codeChallenge = query.get('code_challenge')

/**
 * Every scope the address asked for, one flat list.
 *
 * The redirect puts them in one space-separated parameter, and a repeated parameter is joined
 * rather than dropped — a client that sent two of them asked for both, and this list is what the
 * person is being asked to agree to.
 */
const scopes = computed(() =>
  query
    .getAll('scope')
    .flatMap((value) => value.split(/\s+/))
    .filter((value) => value !== ''),
)

/** The body `POST /web/cli-sign-ins` takes: the name, and the four read off the address. */
const scopeParameter = computed(() => scopes.value.join(' '))

/**
 * What each scope actually lets the tool do.
 *
 * Written as consequences rather than as scope names, because this screen is the product's only
 * moment of disclosure: somebody who typed `skillmaster login` has not thereby learned that
 * `skills:write` means 「以你的身份提交新版本、管理草稿与共享」.
 *
 * **Neither 「发布」 nor 「删除」 appears here, and that is a decision rather than an omission.** A
 * credential on this plane can never put a version live (ADR 0031 §决定 2), and since v4 it cannot
 * delete a skill either — deleting is a browser act now (ADR 0046). The list is the only thing on this
 * screen holding that line: the boundary sentence that used to spell both out was removed on
 * 2026-10-16, because a consent screen that enumerates what a tool cannot do reads as a list of things
 * to worry about. A scope with no entry here is shown raw **and said to be undescribed** — a blank
 * would read as "harmless", which is the one thing an unknown scope must not read as.
 */
const SCOPE_MEANINGS: Record<string, string> = {
  'skills:read': '读取你可见的全部 skill，包括未公开的',
  'skills:write': '以你的身份提交新版本、管理草稿与共享',
}

/**
 * Who is asking.
 *
 * The friendly names live here, and that has a cost: a name typed into this file can rot. So an id
 * this map does not know is shown **as itself**, with a warning — inventing a friendly name for a
 * client nobody has heard of would be the lie the warning exists to prevent.
 */
const CLIENT_NAMES: Record<string, string> = {
  'skillmaster-cli': 'SkillMaster CLI',
}

const clientName = computed(() => (clientId === null ? null : (CLIENT_NAMES[clientId] ?? null)))

/**
 * Whether this address cannot produce a login at all.
 *
 * All three are required of the address, and the design's sentence below names all three — an address
 * with a hole in any one of them cannot be submitted. The challenge is what the code is created with
 * (`cli_sign_in_code.code_challenge` is NOT NULL), so it can be the only thing missing; the demo
 * cannot show that case, since it does no PKCE and never carries one, and the demo's own completeness
 * rule stays the two parameters it tests. The sentence itself is the screen's words, not ours.
 */
const incomplete = computed(
  () =>
    clientId === null ||
    clientId === '' ||
    state === null ||
    codeChallenge === null ||
    codeChallenge === '',
)

const { loaded, account } = useSession()

/**
 * A page with no session is a page nobody can answer, and the sign-in form knows how to come back.
 *
 * `return_to` is the parameter that form already honours, and it checks the destination's origin
 * before it uses it (`LoginPage.returnTo`) — which is why this is the mechanism rather than a new
 * one. The address is the whole thing, query included: those parameters are the login, and losing
 * them across the sign-in would land the person on a screen that cannot work.
 *
 * `replace` rather than `assign`, because a failed attempt must not become a page the Back button
 * keeps returning to.
 */
watch(
  [loaded, account],
  () => {
    if (loaded.value && account.value === null) {
      window.location.replace(loginPath())
    }
  },
  { immediate: true },
)

/**
 * The name this machine will be called on 「登录设备」. Required, and the server checks that too.
 *
 * **It starts from the address, not from an empty box** (2026-10-16). The CLI is the one party that
 * knows which machine it is running on, so it reads that name off the system and puts it in the
 * address (`name=`); this screen has no way to know it — the browser does not hand out hostnames, and
 * the server only sees an IP and a User-Agent. Without the pre-fill every login cost a person a name
 * they had already given their computer.
 *
 * **Filled is not fixed.** It stays an input and it stays required: the suggestion is right most of
 * the time and useless exactly where naming matters most — a VPS called `localhost`, an EC2 box
 * called `ip-10-0-1-23…` — and an empty name is a row on 「登录设备」 with nothing to recognize it by.
 * No `name` on the address (an older CLI, a hand-written link) is simply an empty box, which is where
 * this screen started. `design/README.md` §怎么打开「授权请求」 carries the table of where the CLI
 * reads it on each platform.
 */
const name = ref(query.get('name') ?? '')

/**
 * The write that is out, if any. Both buttons wait on it: they are one round trip apart, and a second
 * press would be a second login.
 */
const busy = ref(false)

/**
 * What to say when the server did not take it.
 *
 * **A state the design does not draw**, and the reason it is the server's own sentence rather than one
 * written here: every sentence on this screen has a source, and this one's is the error envelope,
 * through the same `codeMessage` the rest of the app uses. The element and its styling are the
 * dialog's own (`.form-error`), which is where the fragment puts a refusal for this card. Reported in
 * the delivery notes rather than settled here.
 */
const failure = ref<string | null>(null)

/** Whether the button can be pressed: there is a name, and no request is already out. */
const canAgree = computed(() => name.value.trim() !== '' && !busy.value)

async function agree(): Promise<void> {
  if (!canAgree.value) {
    return
  }
  await answer(
    startCliSignIn({
      name: name.value.trim(),
      client_id: clientId as string,
      scope: scopeParameter.value,
      state: state as string,
      code_challenge: codeChallenge as string,
    }),
  )
}

async function refuse(): Promise<void> {
  if (busy.value) {
    return
  }
  await answer(refuseCliSignIn({ client_id: clientId as string, state: state as string }))
}

/**
 * Either answer, and both go the same way: the server says where the browser lands next, and the
 * navigation is the browser's rather than the client's.
 *
 * The refusal is the same shape on purpose — the page never has to know two formats, and it has never
 * known the CLI's loopback address, which is read from the registered client row on the server.
 */
async function answer(
  pending: ReturnType<typeof startCliSignIn>,
): Promise<void> {
  busy.value = true
  failure.value = null
  const result = await pending
  busy.value = false
  if (!result.ok) {
    if (result.code === 'unauthenticated') {
      // The session ended between loading this page and pressing the button. There is no anonymous
      // version of this screen, so the person is sent to sign in and brought back — the same answer
      // the device list gives its own expired session.
      window.location.replace(loginPath())
      return
    }
    failure.value = codeMessage(result.code, result.message)
    return
  }
  window.location.assign(result.data.redirect_to)
}
</script>

<template>
  <!-- The design's standalone ground: the screen is the card, and there is no site under it to put
       out of focus. -->
  <div class="overlay is-standalone">
    <div
      class="dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="dialogTitle"
      tabindex="-1"
    >
      <p class="dialog-brand">
        <svg class="mark" viewBox="0 0 24 24" aria-hidden="true">
          <rect x="3" y="4" width="18" height="4.5" rx="2.25" fill="currentColor" />
          <rect
            x="3"
            y="10.5"
            width="13"
            height="4.5"
            rx="2.25"
            fill="currentColor"
            opacity="0.6"
          />
          <rect x="3" y="17" width="8" height="4.5" rx="2.25" fill="currentColor" opacity="0.32" />
        </svg>SkillMaster
      </p>
      <!-- Same title in both states below, because it is the same request — one that cannot be
           finished is still this screen. -->
      <h2 class="dialog-title" id="dialogTitle">授权请求</h2>

      <!-- A person arrives here by editing the address or following something old, and saying so beats
           a form that silently does nothing. The design's own sentence, unchanged, on one line so
           that what is rendered is that string and not a version of it with the breaks folded in. -->
      <div v-if="incomplete" class="dialog-view">
        <p>这个链接不完整，缺少 <code>client_id</code>、<code>state</code> 或 <code>code_challenge</code>，无法完成授权。请回到终端重新运行 <code>skillmaster login</code>。</p>
      </div>

      <div v-else class="dialog-view">
        <p v-if="failure !== null" class="form-error" role="alert">{{ failure }}</p>

        <!-- The one input, first, the way both forms the design draws put theirs: it is what the
             person has to answer, and everything under it is the explanation of what answering
             costs. -->
        <div class="field">
          <label for="consentName">名称</label>
          <input
            id="consentName"
            v-model="name"
            name="name"
            type="text"
            maxlength="60"
            autocomplete="off"
            placeholder="我的 MacBook"
            aria-describedby="consentNameHint"
          />
          <p class="hint" id="consentNameHint">这台机器的名称。它只出现在你的「登录设备」里。</p>
        </div>

        <p class="consent-who">
          <strong>{{ clientName ?? clientId }}</strong>
          <!-- As a block rather than run on inside the sentence, which is the fragment's own rule:
               inline, the sentence's last line is left holding a lone character. -->
          <span v-if="clientName === null" class="hint">（这个客户端不在已知名单里，请确认它确实是你安装的）</span>
          请求以下权限：
        </p>

        <ul class="consent-scopes">
          <li v-for="scope in scopes" :key="scope">
            <template v-if="SCOPE_MEANINGS[scope] !== undefined">{{ SCOPE_MEANINGS[scope] }}</template>
            <!-- Raw, and said to be undescribed: a blank would read as harmless. -->
            <template v-else>
              <code>{{ scope }}</code>
              <span class="hint">（我们不知道这一项的含义，请谨慎同意）</span>
            </template>
          </li>
        </ul>

        <!-- Two sentences, and the first one used to be false in two ways. It said 「一份长期凭据」
             and 「要结束它，请重置密码」: the credential is not long-lived (7 days, and 24 hours of not
             being used), and since v4 it *is* revocable on its own — it is a 登录设备 like any other,
             ended with 退出. Both words come from `design/voice-spec.md` §三. They are two lines
             because they answer two different questions: how long this lasts, and how to end it
             early. -->
        <p class="hint">同意后不再询问。7 天或闲置 24 小时后失效。</p>
        <p class="hint">如需提前结束，请在控制台的「登录设备」中退出。</p>

        <button
          type="button"
          class="btn btn-block dialog-submit"
          :disabled="!canAgree"
          @click="agree()"
        >
          同意并继续
        </button>
        <p class="dialog-foot">
          <button type="button" class="link" :disabled="busy" @click="refuse()">拒绝</button>
        </p>
      </div>
    </div>
  </div>
</template>
