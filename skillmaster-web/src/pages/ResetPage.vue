<script setup lang="ts">
import { ref, watch } from 'vue'
import { requestResetCode, resetPassword } from '../api/account'
import { codeMessage, fieldErrors as mapFieldErrors, PASSWORD_HINT } from '../api/errors'
import type { Field } from '../api/errors'
import { useCodeRequest } from '../composables/useCodeRequest'
import { useFieldChecks } from '../composables/useFieldChecks'
import { codeIssue, passwordIssue, phoneIssue } from '../validation'
import CaptchaField from '../components/CaptchaField.vue'
import CodeField from '../components/CodeField.vue'
import FieldFeedback from '../components/FieldFeedback.vue'
import FormBanner from '../components/FormBanner.vue'
import PasswordField from '../components/PasswordField.vue'

// Recovery's sends always need a captcha, so this flow shows one from the start rather than waiting
// to be told — registration's first send is the one that goes without, and it is not this one.
const {
  phone,
  code,
  sent,
  sending,
  remaining,
  canSend,
  problems: stepProblems,
  banner: stepBanner,
  captchaAnswer,
  captchaImage,
  captchaError,
  refreshCaptcha,
  requestCode,
} = useCodeRequest(requestResetCode, { captchaFromTheStart: true })

const password = ref('')
const submitting = ref(false)
const done = ref(false)
const problems = ref<Partial<Record<Field, string>>>({})
const banner = ref<string | null>(null)

const { blurred, refusedByRules, refusedByServer, verdicts } = useFieldChecks(problems, {
  phone: { value: phone, check: phoneIssue },
  // No username here — a reset cannot change the handle, so there is none to be too close to. The
  // number still counts, so the password's verdict has to follow it.
  password: {
    value: password,
    check: (value) => passwordIssue(value, '', phone.value),
    dependsOn: [phone],
  },
  code: { value: code, check: codeIssue },
})

/**
 * The step's own messages belong to the value that was submitted, so a change to that value takes
 * them away — the rule the field checks apply to this page's map. See the register page.
 *
 * One watcher per box: a change to one must not take away what the other is still saying.
 */
watch(phone, () => {
  delete stepProblems.value.phone
})
watch(captchaAnswer, () => {
  delete stepProblems.value.captcha
})

async function submit(): Promise<void> {
  banner.value = null
  const local: Partial<Record<Field, string>> = {}
  const passwordProblem = passwordIssue(password.value, '', phone.value)
  if (passwordProblem !== null) {
    local.password = passwordProblem
  }
  const codeProblem = codeIssue(code.value)
  if (codeProblem !== null) {
    local.code = codeProblem
  }
  // Issue codes, handed to the checks — see the register page. Nothing needs clearing first: the
  // checks own what each field shows, and an attempt that stops here can only speak about the fields
  // it actually looked at, so a sentence the server sent about some other field stays standing.
  if (Object.keys(local).length > 0) {
    refusedByRules(local)
    return
  }

  // One object, used both as the body and as the record of what was sent — see the register page.
  const sent = { phone: phone.value, code: code.value, password: password.value }
  submitting.value = true
  const result = await resetPassword(sent)
  submitting.value = false

  if (result.ok) {
    // Deliberately not signed in: every session this account had has just been revoked, including
    // the one that asked for this. Signing in with the new password is also what proves to the
    // person that the password they chose is the one that works.
    done.value = true
    return
  }

  const serverMessage = codeMessage(result.code, result.message)
  const serverProblems = mapFieldErrors(result.details, serverMessage)
  if (Object.keys(serverProblems).length > 0) {
    if (!refusedByServer(serverProblems, sent)) {
      banner.value = serverMessage
    }
  } else if (result.code === 'verification_code_invalid') {
    refusedByServer({ code: serverMessage }, sent)
  } else {
    banner.value = serverMessage
  }
}
</script>

<template>
  <div class="card">
    <template v-if="done">
      <h2>密码已重置</h2>
      <p>请用新密码重新登录。出于安全考虑，这个账号在所有设备上的登录都已经失效。</p>
      <p><a href="/login">去登录</a></p>
    </template>

    <form v-else @submit.prevent="submit">
      <h2>找回密码</h2>
      <FormBanner :message="banner" />
      <FormBanner :message="stepBanner" />

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
        <!-- Both steps can refuse the phone number: the code request checks its shape, and
             `no_account` comes from the reset itself. -->
        <FieldFeedback :message="problems.phone ?? stepProblems.phone" :verdict="verdicts.phone" />
      </div>

      <CaptchaField
        v-model="captchaAnswer"
        :image="captchaImage"
        :error="captchaError ?? stepProblems.captcha"
        @refresh="refreshCaptcha()"
      />

      <CodeField
        v-model="code"
        :error="problems.code"
        :verdict="verdicts.code"
        :remaining="remaining"
        :can-send="canSend"
        :sending="sending"
        @send="requestCode()"
        @blur="blurred('code')"
      />

      <PasswordField
        id="password"
        label="新密码"
        v-model="password"
        autocomplete="new-password"
        :error="problems.password"
        :verdict="verdicts.password"
        :hint="PASSWORD_HINT"
        @blur="blurred('password')"
      />

      <button type="submit" :disabled="submitting || !sent">
        {{ submitting ? '提交中…' : '重置密码' }}
      </button>

      <p class="muted">想起来了？<a href="/login">去登录</a></p>
    </form>
  </div>
</template>
