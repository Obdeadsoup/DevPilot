<template>
  <AuthShell>
    <template #eyebrow>WELCOME BACK</template>
    <template #title>登录 DevPilot</template>
    <template #description>继续处理团队项目、研发活动与 Agent Run。</template>

    <el-alert v-if="errorMessage" type="error" show-icon :title="errorTitle" :description="errorMessage" class="form-alert" />
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleLogin">
      <el-form-item label="用户名或邮箱" prop="login">
        <el-input v-model="form.login" placeholder="name@example.com" maxlength="254" clearable autocomplete="username" />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input v-model="form.password" type="password" placeholder="输入密码" maxlength="72" show-password autocomplete="current-password" />
      </el-form-item>
      <el-button type="primary" :loading="loading" class="primary-action" size="large" @click="handleLogin">登录</el-button>
    </el-form>

    <template #footer>
      还没有账号？
      <router-link :to="registerLocation">创建账号</router-link>
    </template>
  </AuthShell>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'

import { loginApi } from '@/api/modules/auth'
import AuthShell from '@/components/AuthShell.vue'
import { normalizeReturnUrl } from '@/router/returnUrl'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const formRef = ref<FormInstance>()
const loading = ref(false)
const errorTitle = ref('')
const errorMessage = ref('')
const form = reactive({ login: '', password: '' })
const rules: FormRules = {
  login: [{ required: true, message: '请输入用户名或邮箱', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}
const registerLocation = computed(() => {
  const returnUrl = normalizeReturnUrl(route.query.returnUrl)
  return { path: '/register', query: returnUrl === '/workspaces' ? {} : { returnUrl } }
})

async function handleLogin() {
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return
  loading.value = true
  errorTitle.value = ''
  errorMessage.value = ''
  try {
    const result = await loginApi({ login: form.login.trim(), password: form.password })
    if (!result.success || !result.data) {
      errorTitle.value = '登录失败'
      errorMessage.value = result.message || '用户名或密码不正确。'
      return
    }
    authStore.setAuth(result.data)
    await router.push(normalizeReturnUrl(route.query.returnUrl))
  } catch (error: any) {
    errorTitle.value = '暂时无法登录'
    errorMessage.value = error.message || '请检查网络连接后重试。'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.form-alert { margin-bottom: var(--space-5); }
.primary-action { width: 100%; margin-top: var(--space-2); }
</style>
