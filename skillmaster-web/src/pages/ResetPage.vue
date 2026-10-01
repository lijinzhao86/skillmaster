<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { requestResetCode, resetPassword } from '../api/account'
import { codeMessage, fieldErrors as mapFieldErrors, messageFor } from '../api/errors'
import type { Field } from '../api/errors'
import { useCodeRequest } from '../composables/useCodeRequest'
import { codeIssue, passwordIssue } from '../validation'
import CaptchaField from '../components/CaptchaField.vue'
import CodeField from '../components/CodeField.vue'
import FieldError from '../components/FieldError.vue'
import FormBanner from '../components/FormBanner.vue'

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
} = useCodeRequest(requestResetCode)

const password = ref('')
const submitting = ref(false)
const done = ref(false)
const problems = ref<Partial<Record<Field, string>>>({})
const banner = ref<string | null>(null)

onMounted(() => {
  void refreshCaptcha()
})

async function submit(): Promise<void> {
  problems.value = {}
  banner.value = null
  const local: Partial<Record<Field, string>> = {}
  const passwordProblem = passwordIssue(password.value, '', phone.value)
  if (passwordProblem !== null) {
    local.password = messageFor('password', passwordProblem, '')
  }
  const codeProblem = codeIssue(code.value)
  if (codeProblem !== null) {
    local.code = messageFor('code', codeProblem, '')
  }
  if (Object.keys(local).length > 0) {
    problems.value = local
    return
  }

  submitting.value = true
  const result = await resetPassword({
    phone: phone.value,
    code: code.value,
    password: password.value,
  })
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
    problems.value = serverProblems
  } else if (result.code === 'verification_code_invalid') {
    problems.value = { code: serverMessage }
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
        <input id="phone" v-model="phone" inputmode="numeric" autocomplete="tel" maxlength="11" />
        <!-- Both steps can refuse the phone number: the code request checks its shape, and
             `no_account` comes from the reset itself. -->
        <FieldError :message="problems.phone ?? stepProblems.phone" />
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
        :remaining="remaining"
        :can-send="canSend"
        :sending="sending"
        @send="requestCode()"
      />

      <div class="field">
        <label for="password">新密码</label>
        <input id="password" v-model="password" type="password" autocomplete="new-password" />
        <FieldError :message="problems.password" />
        <!-- The rule is visible because it is a restriction the user has to be told about: the
           form refuses characters they may well have typed on purpose, and it has to say so
           here rather than as a refusal afterwards. -->
      <p class="hint">至少 8 个字符，只能用英文字母、数字和符号（不能用中文或全角字符）。用一句长口令比堆特殊符号更安全。</p>
      </div>

      <button type="submit" :disabled="submitting || !sent">
        {{ submitting ? '提交中…' : '重置密码' }}
      </button>

      <p class="muted">想起来了？<a href="/login">去登录</a></p>
    </form>
  </div>
</template>
