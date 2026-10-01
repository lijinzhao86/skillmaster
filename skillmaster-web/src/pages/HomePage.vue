<script setup lang="ts">
import { ref } from 'vue'
import { logout } from '../api/account'
import { codeMessage } from '../api/errors'
import { useSession } from '../composables/useSession'
import FormBanner from '../components/FormBanner.vue'

const { account, problem: sessionProblem } = useSession()
const banner = ref<string | null>(null)
const signingOut = ref(false)

async function signOut(): Promise<void> {
  signingOut.value = true
  const result = await logout()
  signingOut.value = false
  if (result.ok) {
    window.location.assign('/login')
    return
  }
  banner.value = codeMessage(result.code, result.message)
}
</script>

<template>
  <div class="card">
    <FormBanner :message="banner" />
    <template v-if="account">
      <h2>你已登录</h2>
      <dl class="facts">
        <dt>用户名</dt>
        <dd>{{ account.username }}</dd>
        <dt>命名空间</dt>
        <dd>{{ account.namespace }}</dd>
      </dl>
      <p class="hint">
        这个页面暂时只用来确认登录成功。浏览与发布 skill 的界面还没做。
      </p>
      <button type="button" :disabled="signingOut" @click="signOut">
        {{ signingOut ? '退出中…' : '退出登录' }}
      </button>
    </template>
    <template v-else-if="sessionProblem">
      <!-- The session read failed for a reason that is not "nobody is signed in". Saying "还没有登录"
           here would be a claim this page cannot make. -->
      <h2>无法确认登录状态</h2>
      <p class="muted">{{ sessionProblem }}</p>
      <p><a href="/login">去登录</a>　·　<a href="/register">注册</a></p>
    </template>
    <template v-else>
      <!-- Reached when somebody opens `/` without a session. Not an error, so not a banner. -->
      <h2>还没有登录</h2>
      <p><a href="/login">去登录</a>　·　<a href="/register">注册</a></p>
    </template>
  </div>
</template>
