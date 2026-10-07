<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  checkUsername,
  register,
  registrationCodeCaptcha,
  requestRegistrationCode,
} from '../api/account'
import { codeMessage, fieldErrors as mapFieldErrors, PASSWORD_HINT } from '../api/errors'
import type { Field } from '../api/errors'
import type { Account } from '../api/types'
import { useCodeRequest } from '../composables/useCodeRequest'
import { useFieldChecks } from '../composables/useFieldChecks'
import { codeIssue, isBlank, passwordIssue, phoneIssue, usernameIssue } from '../validation'
import CaptchaField from '../components/CaptchaField.vue'
import CodeInput from '../components/CodeInput.vue'
import FieldFeedback from '../components/FieldFeedback.vue'
import FormBanner from '../components/FormBanner.vue'
import PasswordField from '../components/PasswordField.vue'

const {
  phone,
  code,
  sent,
  sending,
  remaining,
  canSend,
  problems: stepProblems,
  banner: stepBanner,
  captchaRequired,
  captchaAnswer,
  captchaImage,
  captchaError,
  refreshCaptcha,
  requireCaptcha,
  requestCode,
} = useCodeRequest(requestRegistrationCode)

/*
 * Two screens on one route, and the state above survives the switch because nothing is unmounted —
 * which is the whole reason not to give the second screen its own URL. It has nothing to be reached
 * with: the number the code went to lives in this component, and a visitor arriving at a second
 * address directly would have no code and no phone.
 */
const step = ref<1 | 2>(1)
const username = ref('')
const password = ref('')
const submitting = ref(false)
/** The account the server just created, which is what turns this page into the success screen. */
const registered = ref<Account | null>(null)
const problems = ref<Partial<Record<Field, string>>>({})
const banner = ref<string | null>(null)

/** The number a code was last sent to, so going back to fix a username does not send another. */
const codePhone = ref('')

const { blurred, refusedByRules, refusedByServer, verdicts } = useFieldChecks(problems, {
  username: {
    value: username,
    check: usernameIssue,
    verify: async (candidate) => {
      const result = await checkUsername(candidate)
      // `data == null` is reachable: the client answers a 200 whose body is JSON `null` with `ok:
      // true` and no data. Reading through it would throw inside a call nobody awaits, and the field
      // would sit at 「检查中…」 for the rest of the page's life.
      if (!result.ok || result.data == null) {
        return undefined
      }
      return result.data.issue
    },
  },
  phone: { value: phone, check: phoneIssue },
  // Against the other two fields, because that is what the server does: a password built out of the
  // handle or the number is refused whatever else is right about it. They are named as dependencies
  // for the same reason — a verdict about all three is not a verdict about the password alone.
  password: {
    value: password,
    check: (value) => passwordIssue(value, username.value, phone.value),
    dependsOn: [username, phone],
  },
  code: { value: code, check: codeIssue },
})

/**
 * The step's own messages are about the value that was submitted, so a change to that value takes
 * them away — the rule the field checks already apply to this page's map. Without this, the phone
 * keeps saying 「这个手机号已经注册过了」 while somebody types a different number into it.
 *
 * One watcher per box rather than one over both: the message each carries is about its own value, so
 * a change to one must not take away what the other is still saying.
 */
watch(phone, () => {
  delete stepProblems.value.phone
})
watch(captchaAnswer, () => {
  delete stepProblems.value.captcha
})

/**
 * What the first screen's button says, which has to be what it does.
 *
 * `alreadyPaidFor` is the one state where the two could part company: the button walks forward
 * without sending, so it must not be labelled as a send. That state is reached by a submit refusal
 * sending somebody back to fix a field — a person who came back to correct a username, not to ask
 * for another message — and it is also why the countdown does not hold the button dark there: the
 * walk forward costs nothing, so a cooldown that has not elapsed is not a reason to block it.
 */
const sendLabel = computed(() => {
  if (sending.value) {
    return '发送中…'
  }
  if (alreadyPaidFor.value) {
    return '下一步'
  }
  if (remaining.value > 0) {
    // A wait either way, but not the same wait: a first attempt refused for being too soon has sent
    // nothing, and calling that a resend promises a message that never went out.
    return sent.value ? `${remaining.value} 秒后可重发` : `${remaining.value} 秒后可重试`
  }
  return sent.value ? '重新发送验证码' : '下一步'
})

/**
 * A send waits out the cooldown; walking forward does not.
 *
 * Only the send spends a text message, which is what the countdown exists to space out — so gating
 * the walk forward on it would make somebody who came back to fix a username wait a minute for
 * nothing.
 */
const firstStepBlocked = computed(
  () =>
    !ready.value ||
    // A refusal the send itself came back with, which no verdict of the field checks knows about: the
    // phone is well-formed, and it is the server that says it already has an account. Lit, the button
    // would invite the same refused request again.
    stepProblems.value.phone !== undefined ||
    (!alreadyPaidFor.value && !canSend.value),
)

/**
 * Whether the first screen is finished, which is what lights the button.
 *
 * Ticked rather than merely non-empty, and that is the reason the checks run while somebody types:
 * the last field they are in is the one that never gets left, so a button that waited for a blur
 * would stay dark for a form that is complete.
 *
 * The captcha is asked for by whether it is filled rather than by a tick, because nothing local can
 * know a correct answer — the server holds the answer, and a challenge is spent by being used. So
 * this is the one box the form cannot promise anything about, and the submit is where it is judged.
 */
const ready = computed(
  () =>
    verdicts.value.username === 'ok' &&
    verdicts.value.phone === 'ok' &&
    verdicts.value.password === 'ok' &&
    (!captchaRequired.value || !isBlank(captchaAnswer.value)),
)

/**
 * Whether the person asked for another code from the second screen.
 *
 * 「a code has already gone to this number」 and 「somebody wants another one」 are different questions,
 * and this is the second. Walking back to fix a username must not spend a second message — but
 * 「重新获取验证码」 is somebody saying the code did not arrive, and with the two questions treated as
 * one that button could only ever walk forward again: there was no way at all to get a second code
 * for a number, whether the first one timed out or simply never turned up.
 */
const wantsAnotherCode = ref(false)

/** The code was sent to the number on screen, so the second step is already paid for. */
const alreadyPaidFor = computed(
  () => !wantsAnotherCode.value && sent.value && phone.value === codePhone.value,
)

onMounted(async () => {
  // Asked as the form opens: an address that has already had its one free send is one whose captcha
  // belongs on screen from the start, and finding that out by pressing the button and being refused
  // makes the first thing a returning visitor sees a refusal they did nothing to earn.
  //
  // A failed answer draws nothing and leaves the refusal path to handle it, so the cost of not being
  // able to ask is the click this is here to save — never a form that cannot be finished.
  const answer = await registrationCodeCaptcha()
  // `data == null` is reachable — a 200 whose body is JSON `null`, or a 204 — and reading through it
  // would throw inside a callback nobody awaits. Saying nothing is the documented fallback either
  // way, so the field is simply left off.
  if (answer.ok && answer.data?.required === true) {
    await requireCaptcha()
  }
})

/**
 * The way back to the screen whose button sends, which is also where the captcha belongs.
 *
 * The captcha is revealed here rather than when the send succeeded, and that is not tidiness: on the
 * way out, this screen is still mounted for the tick between the flag changing and the step changing,
 * so a field revealed then is drawn and taken away again — the flicker that is worse than never
 * showing it. Coming back is a deliberate move to the screen it belongs on, so it appears where the
 * person is looking, which is the one moment it can appear without being a surprise.
 */
function backToDetails(): void {
  // A free send is spent by going out, so the next one needs a captcha — and the person is about to
  // ask for exactly that. Skipped when one is already up, whose image the send that succeeded has
  // already replaced: drawing another would be a request for a picture that is on screen.
  if (sent.value && !captchaRequired.value) {
    void requireCaptcha()
  }
  // Said here rather than inferred later: this screen was reached by asking for another code, so the
  // button on it must really ask for one.
  wantsAnotherCode.value = true
  step.value = 1
}

/**
 * The first step: everything that can be got wrong before a text message is spent, then the message.
 *
 * The code is not re-sent when the number has not changed — an address's free send is spent by the
 * first one, so walking back to fix a username and forward again would cost a second message and a
 * captcha, for a code the person already has.
 */
async function next(): Promise<void> {
  banner.value = null
  // Issue codes, not sentences: they are handed to the checks, which is where the wording for an
  // issue lives and where a rule's verdict belongs — so the next judgement re-states them, and a
  // password refused for matching the phone stops being refused the moment the phone is corrected.
  // `required` included: what a box being empty means is a rule here like any other, and the checks'
  // own silence about it is about a box nobody has been asked about yet, not about a submit that has.
  const local: Partial<Record<Field, string>> = {}
  const usernameProblem = usernameIssue(username.value)
  if (usernameProblem !== null) {
    local.username = usernameProblem
  }
  const phoneProblem = phoneIssue(phone.value)
  if (phoneProblem !== null) {
    local.phone = phoneProblem
  }
  const passwordProblem = passwordIssue(password.value, username.value, phone.value)
  if (passwordProblem !== null) {
    local.password = passwordProblem
  }
  if (Object.keys(local).length > 0) {
    refusedByRules(local)
    return
  }

  if (alreadyPaidFor.value) {
    step.value = 2
    return
  }

  // The step opens only when the send says so. `sent` cannot be asked: it stays true from the first
  // send onward, so a refused resend would leave it true and the page would advance claiming a code
  // had gone out — with the refusal sitting in a field that is not on the next screen.
  //
  // The number is captured before the request rather than read after it, because the request can take
  // seconds and the box stays editable throughout: somebody who corrects a digit while the button
  // says 「发送中…」 would otherwise leave the page claiming the message went to the number they have
  // now, and — since that is what `alreadyPaidFor` compares — walk forward without ever sending one
  // there. The message went where the request said, which is what was in the box when it was made.
  const sentTo = phone.value
  const opensTheCodeStep = await requestCode()
  if (opensTheCodeStep) {
    codePhone.value = sentTo
    wantsAnotherCode.value = false
    step.value = 2
  }
}

async function submit(): Promise<void> {
  banner.value = null
  const codeProblem = codeIssue(code.value)
  if (codeProblem !== null) {
    refusedByRules({ code: codeProblem })
    return
  }

  // One object, used both as the body and as the record of what was sent, so the two cannot drift:
  // the boxes stay editable while the request is in flight, and a refusal is an answer about these
  // values rather than about whatever is in them by the time it lands.
  const sent = {
    phone: phone.value,
    code: code.value,
    password: password.value,
    username: username.value,
  }
  submitting.value = true
  const result = await register(sent)
  submitting.value = false

  if (result.ok) {
    // In place, rather than straight to `/`, because this is the one moment one fact is news: the
    // username cannot be changed and becomes the first segment of every address this account will
    // publish. Everything the screen shows is in the response that just arrived, so it costs no
    // second request — and leaving is a deliberate click, not something done to the person.
    if (result.data == null) {
      // The account exists and the cookie is already the new session, but there is no username or
      // namespace to show — and falling through would draw the code step again, which reads as "not
      // registered" over an account that was just created. So the honest end is the site root.
      goHome()
      return
    }
    registered.value = result.data
    return
  }

  const serverMessage = codeMessage(result.code, result.message)
  if (result.code === 'verification_code_invalid') {
    // No details on this one, but it is plainly about the field it sits next to.
    refusedByServer({ code: serverMessage }, sent)
    return
  }
  const serverProblems = mapFieldErrors(result.details, serverMessage)
  if (Object.keys(serverProblems).length > 0) {
    // Nothing to show means every refusal named a field this form does not have, and a refusal nobody
    // can see is worse than one in the wrong place.
    if (!refusedByServer(serverProblems, sent)) {
      banner.value = serverMessage
      return
    }
    // A refusal about a field on the first screen sends the person back to it. Left here it would be
    // a sentence about a box nobody can see — and this is the narrow race where a username the check
    // called free was taken between then and now.
    if (wantsFirstStep(serverProblems)) {
      step.value = 1
    }
    return
  }
  banner.value = serverMessage
}

function wantsFirstStep(serverProblems: Partial<Record<Field, string>>): boolean {
  return (
    serverProblems.username !== undefined ||
    serverProblems.phone !== undefined ||
    serverProblems.password !== undefined
  )
}

/** A full load, because the session cookie registration just replaced is what `/` reads. */
function goHome(): void {
  window.location.assign('/')
}
</script>

<template>
  <!-- The screen that exists for one moment, and the only place that says what the account is now
       worth knowing: the namespace, and the shape of the addresses it will appear in. -->
  <div v-if="registered" class="card">
    <h2>注册成功</h2>
    <p>欢迎，{{ registered.username }}。账号已经建好，也已经登录。</p>

    <dl class="facts">
      <dt>用户名</dt>
      <dd>{{ registered.username }}</dd>
      <dt>命名空间</dt>
      <dd>{{ registered.namespace }}</dd>
    </dl>

    <p class="hint">
      用户名不可更改，它会成为你发布的每个 skill 地址的第一段，例如
      <code>{{ registered.namespace }}/我的技能</code>。
    </p>
    <!-- The honest "what happens next": there is no next step to offer yet, and inventing one would
         be worse than saying so — the landing page says the same thing for the same reason. -->
    <p class="hint">
      浏览与发布 skill 的界面还没做，所以现在还点不了下一步。等命令行工具能装、能发布了，这里会换成去发布第一个 skill。
    </p>

    <button type="button" @click="goHome()">开始使用</button>
  </div>

  <form v-else-if="step === 1" class="card" @submit.prevent="next">
    <h2>注册</h2>
    <FormBanner :message="banner" />
    <FormBanner :message="stepBanner" />

    <div class="field">
      <label for="username">用户名</label>
      <input
        id="username"
        v-model="username"
        autocomplete="username"
        maxlength="30"
        placeholder="必填"
        aria-required="true"
        @blur="blurred('username')"
      />
      <FieldFeedback :message="problems.username" :verdict="verdicts.username" />
      <!-- Said here rather than on a help page: it becomes the first segment of every address this
           account publishes, and addresses get pinned and copied, so it cannot be changed later. -->
      <p class="hint">
        用户名不可更改，它会成为你发布的每个 skill 地址的第一段（<code>用户名/名称</code>）。请不要使用真实姓名或手机号。
      </p>
    </div>

    <div class="field">
      <label for="phone">手机号</label>
      <input
        id="phone"
        v-model="phone"
        inputmode="numeric"
        autocomplete="tel"
        maxlength="11"
        placeholder="必填"
        aria-required="true"
        @blur="blurred('phone')"
      />
      <!-- Both steps can refuse the phone number: this one checks its shape, and the send is where
           `already_registered` comes from. -->
      <FieldFeedback :message="problems.phone ?? stepProblems.phone" :verdict="verdicts.phone" />
    </div>

    <PasswordField
      id="password"
      label="密码"
      v-model="password"
      autocomplete="new-password"
      :error="problems.password"
      :verdict="verdicts.password"
      :hint="PASSWORD_HINT"
      @blur="blurred('password')"
    />

    <!-- Last, and only when the server asks for it: registration's first send from an address needs
         no captcha, and no client can know in advance which send that is. -->
    <CaptchaField
      v-if="captchaRequired"
      v-model="captchaAnswer"
      :image="captchaImage"
      :error="captchaError ?? stepProblems.captcha"
      @refresh="refreshCaptcha()"
    />

    <button type="submit" :disabled="firstStepBlocked">{{ sendLabel }}</button>

    <p class="muted">已经有账号？<a href="/login">登录</a></p>
  </form>

  <form v-else class="card" @submit.prevent="submit">
    <h2>验证手机号</h2>
    <FormBanner :message="banner" />
    <FormBanner :message="stepBanner" />

    <div class="field">
      <label for="sms-code">短信验证码</label>
      <CodeInput id="sms-code" v-model="code" @blur="blurred('code')" />
      <FieldFeedback :message="problems.code" :verdict="verdicts.code" />
      <!-- The number the message actually went to, not whatever is in the box now: the phone box is
           editable while the request is in flight, so after a correction mid-send the two differ —
           and naming the new one would send somebody to wait for a message that went elsewhere. -->
      <p class="hint">验证码已发送至 {{ codePhone }}。{{ remaining > 0 ? `${remaining} 秒后可以重新发送。` : '没收到可以重新获取。' }}</p>
    </div>

    <!-- The same rule as the first screen's button: ticked before it can be pressed. The code is a
         local rule, so its tick arrives as the sixth digit does. -->
    <button type="submit" :disabled="submitting || verdicts.code !== 'ok'">
      {{ submitting ? '注册中…' : '注册' }}
    </button>

    <p class="muted">
      <!-- Back rather than here: the send is what the captcha stands in front of, and that is the
           first screen's button. -->
      <button type="button" class="link" @click="backToDetails()">重新获取验证码</button>
      　·　<a href="/login">去登录</a>
    </p>
  </form>
</template>
