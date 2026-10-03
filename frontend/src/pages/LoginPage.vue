<template>
  <main class="auth-page">
    <section class="login-card">
      <div class="login-brand">
        <div class="brand-mark"><Sparkles :size="17" /></div>
        <span>HexWorkspace</span>
      </div>
      <div class="login-heading">
        <span class="eyebrow">WELCOME BACK</span>
        <h1>登录你的 Workspace</h1>
        <p>使用账户继续访问 Hexgent 和个人资料。</p>
      </div>
      <form class="login-form" @submit.prevent="submit">
        <label>用户名<el-input v-model="username" autocomplete="username" placeholder="输入用户名" /></label>
        <label>密码<el-input v-model="password" type="password" show-password autocomplete="current-password" placeholder="输入密码" /></label>
        <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" />
        <el-button class="login-button" type="primary" native-type="submit" :loading="loading">登录</el-button>
      </form>
    </section>
  </main>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Sparkles } from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'
import { userFacingError } from '../api/errors'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMessage = ref('')

async function submit(): Promise<void> {
  errorMessage.value = ''
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/hexgent'
    await router.replace(redirect)
  } catch (error) {
    errorMessage.value = userFacingError(error)
  } finally {
    loading.value = false
  }
}
</script>
