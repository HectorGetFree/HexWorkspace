import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '../api/auth'
import { bindAuthClearHandler } from '../api/client'
import { setAccessToken } from '../api/session'
import type { User } from '../types/auth'

export const useAuthStore = defineStore('auth', () => {
  const currentUser = ref<User | null>(null)
  const initializing = ref(true)
  const authenticated = computed(() => currentUser.value !== null)
  let initializationPromise: Promise<void> | null = null

  function clear(): void {
    setAccessToken(null)
    currentUser.value = null
  }

  bindAuthClearHandler(clear)

  async function login(username: string, password: string): Promise<void> {
    currentUser.value = await authApi.login(username, password)
  }

  async function initializeAuth(): Promise<void> {
    if (initializationPromise) {
      return initializationPromise
    }

    initializationPromise = (async () => {
      initializing.value = true
      try {
        currentUser.value = await authApi.restoreSession()
      } catch {
        clear()
      } finally {
        initializing.value = false
        initializationPromise = null
      }
    })()

    return initializationPromise
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      clear()
    }
  }

  return { currentUser, initializing, authenticated, login, initializeAuth, logout, clear }
})
