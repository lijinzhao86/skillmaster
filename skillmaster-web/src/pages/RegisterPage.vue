<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { register, requestRegistrationCode } from '../api/account'
import { codeMessage, fieldErrors as mapFieldErrors, messageFor } from '../api/errors'
import type { Field } from '../api/errors'
import { useCodeRequest } from '../composables/useCodeRequest'
import { codeIssue, passwordIssue, usernameIssue } from '../validation'
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
} = useCodeRequest(requestRegistrationCode)

const username = ref('')
const password = ref('')
const submitting = ref(false)
const problems = ref<Partial<Record<Field, string>>>({})
const banner = ref<string | null>(null)

onMounted(() => {
  // An image on screen before anybody has typed anything, rather than an empty box.
  void refreshCaptcha()
})

async function submit(): Promise<void> {
  problems.value = {}
  banner.value = null
  const local: Partial<Record<Field, string>> = {}
  const usernameProblem = usernameIssue(username.value)
  if (usernameProblem !== null) {
    local.username = messageFor('username', usernameProblem, '')
  }
  const passwordProblem = passwordIssue(password.value, username.value, phone.value)
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
  const result = await register({
    phone: phone.value,
    code: code.value,
    password: password.value,
    username: username.value,
  })
  submitting.value = false

  if (result.ok) {
    // Registration signs the account in, so there is nothing to do but go and be signed in.
    window.location.assign('/')
    return
  }

  const serverMessage = codeMessage(result.code, result.message)
  const serverProblems = mapFieldErrors(result.details, serverMessage)
  if (Object.keys(serverProblems).length > 0) {
    problems.value = serverProblems
  } else if (result.code === 'verification_code_invalid') {
    // No details on this one, but it is plainly about the field it sits next to.
    problems.value = { code: serverMessage }
  } else {
    banner.value = serverMessage
  }
}
</script>

<template>
  <form class="card" @submit.prevent="submit">
    <h2>注册</h2>
    <FormBanner :message="banner" />
    <FormBanner :message="stepBanner" />

    <div class="field">
      <label for="phone">手机号</label>
      <input id="phone" v-model="phone" inputmode="numeric" autocomplete="tel" maxlength="11" />
      <!-- Both steps can refuse the phone number: the code request checks its shape, and the
           registration itself is where `already_registered` comes from. -->
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
      <label for="username">用户名</label>
      <input id="username" v-model="username" autocomplete="username" maxlength="30" />
      <FieldError :message="problems.username" />
      <!-- Said here rather than on a help page: it becomes the first segment of every address this
           account publishes, and addresses get pinned and copied, so it cannot be changed later. -->
      <p class="hint">
        用户名不可更改，它会成为你发布的每个 skill 地址的第一段（<code>用户名/名称</code>）。请不要使用真实姓名或手机号。
      </p>
    </div>

    <div class="field">
      <label for="password">密码</label>
      <input id="password" v-model="password" type="password" autocomplete="new-password" />
      <FieldError :message="problems.password" />
      <!-- The rule is visible because it is a restriction the user has to be told about: the
           form refuses characters they may well have typed on purpose, and it has to say so
           here rather than as a refusal afterwards. -->
      <p class="hint">至少 8 个字符，只能用英文字母、数字和符号（不能用中文或全角字符）。用一句长口令比堆特殊符号更安全。</p>
    </div>

    <button type="submit" :disabled="submitting || !sent">
      {{ submitting ? '注册中…' : '注册' }}
    </button>

    <p class="muted">已经有账号？<a href="/login">登录</a></p>
  </form>
</template>
