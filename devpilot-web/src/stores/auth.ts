import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User, LoginResponse } from '@/types/api'

const TOKEN_KEY = 'devpilot_access_token'
const EXPIRES_KEY = 'devpilot_token_expires_at'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  const expiresAt = ref<number | null>(null)
  const user = ref<User | null>(null)
  const restoring = ref<boolean>(false)

  const isAuthenticated = computed(() => {
    if (!accessToken.value) return false
    if (expiresAt.value && Date.now() >= expiresAt.value) return false
    return true
  })

  function setAuth(data: LoginResponse) {
    accessToken.value = data.accessToken
    const exp = Date.now() + data.expiresInSeconds * 1000
    expiresAt.value = exp
    user.value = data.user

    sessionStorage.setItem(TOKEN_KEY, data.accessToken)
    sessionStorage.setItem(EXPIRES_KEY, String(exp))
  }

  function setUser(u: User) {
    user.value = u
  }

  function clearAuth() {
    accessToken.value = null
    expiresAt.value = null
    user.value = null
    sessionStorage.removeItem(TOKEN_KEY)
    sessionStorage.removeItem(EXPIRES_KEY)
  }

  function restoreSession(): boolean {
    restoring.value = true
    try {
      const savedToken = sessionStorage.getItem(TOKEN_KEY)
      const savedExpires = sessionStorage.getItem(EXPIRES_KEY)

      if (savedToken && savedExpires) {
        const expNum = parseInt(savedExpires, 10)
        if (!isNaN(expNum) && Date.now() < expNum) {
          accessToken.value = savedToken
          expiresAt.value = expNum
          return true
        }
      }
      clearAuth()
      return false
    } finally {
      restoring.value = false
    }
  }

  return {
    accessToken,
    expiresAt,
    user,
    restoring,
    isAuthenticated,
    setAuth,
    setUser,
    clearAuth,
    restoreSession,
  }
})
