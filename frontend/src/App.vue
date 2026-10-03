<template>
  <div v-if="auth.initializing" class="app-loading">正在恢复登录状态…</div>
  <RouterView v-else />
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()
onMounted(() => {
  if (auth.initializing) void auth.initializeAuth()
})
</script>
