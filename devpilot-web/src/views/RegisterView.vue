<template>
  <AuthShell>
    <template #eyebrow>CREATE ACCOUNT</template>
    <template #title>创建 DevPilot 账号</template>
    <template #description>通过邮箱验证创建账号，开始团队协作。</template>

    <el-alert v-if="errorMessage" type="error" show-icon :title="errorTitle" :description="errorMessage" class="form-alert" />
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleRegister">
      <div class="form-grid">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" maxlength="64" autocomplete="username" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" maxlength="254" autocomplete="email" />
        </el-form-item>
      </div>
      <el-form-item label="邮箱验证码" prop="verificationCode">
        <el-input v-model="form.verificationCode" maxlength="6" inputmode="numeric" autocomplete="one-time-code">
          <template #append>
            <el-button :loading="sendingCode" :disabled="cooldownSeconds > 0" @click="sendCode">
              {{ cooldownSeconds > 0 ? `${cooldownSeconds}s` : '发送验证码' }}
            </el-button>
          </template>
        </el-input>
      </el-form-item>
      <div class="form-grid">
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" maxlength="72" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" maxlength="72" show-password autocomplete="new-password" />
        </el-form-item>
      </div>
      <p class="password-hint">至少 12 位，并同时包含字母与数字。</p>
      <el-button type="primary" :loading="loading" class="primary-action" size="large" @click="handleRegister">创建账号</el-button>
    </el-form>

    <template #footer>已有账号？ <router-link :to="loginLocation">返回登录</router-link></template>
  </AuthShell>
</template>

<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import { registerApi, sendEmailVerificationCodeApi } from '@/api/modules/auth'
import AuthShell from '@/components/AuthShell.vue'
import { normalizeReturnUrl } from '@/router/returnUrl'
import { productErrorMessage } from '@/utils/productError'

const router = useRouter()
const route = useRoute()
const formRef = ref<FormInstance>()
const loading = ref(false)
const errorTitle = ref('')
const errorMessage = ref('')
const form = reactive({ username: '', email: '', verificationCode: '', password: '', confirmPassword: '' })
const sendingCode = ref(false)
const cooldownSeconds = ref(0)
let cooldownTimer: number | undefined

const returnUrl = computed(() => normalizeReturnUrl(route.query.returnUrl))
const loginLocation = computed(() => ({
  path: '/login',
  query: returnUrl.value === '/workspaces' ? {} : { returnUrl: returnUrl.value },
}))
const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 64, message: '用户名长度为 3 到 64 位', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9][A-Za-z0-9._-]*$/, message: '仅支持字母、数字、点、下划线或连字符', trigger: 'blur' },
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入有效邮箱地址', trigger: 'blur' },
  ],
  verificationCode: [
    { required: true, message: '请输入邮箱验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码必须为 6 位数字', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 12, max: 72, message: '密码长度为 12 到 72 位', trigger: 'blur' },
    { pattern: /[A-Za-z]/, message: '密码必须包含字母', trigger: 'blur' },
    { pattern: /\d/, message: '密码必须包含数字', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    { validator: (_rule, value, callback) => callback(value === form.password ? undefined : new Error('两次输入的密码不一致')), trigger: 'blur' },
  ],
}

async function sendCode() {
  const valid = await formRef.value?.validateField('email').then(() => true).catch(() => false)
  if (!valid || sendingCode.value || cooldownSeconds.value > 0) return
  sendingCode.value = true
  errorTitle.value = ''
  errorMessage.value = ''
  try {
    const result = await sendEmailVerificationCodeApi(form.email.trim())
    if (!result.success) {
      errorTitle.value = '验证码发送失败'
      errorMessage.value = productErrorMessage(result, '验证码发送失败，请稍后重试。')
      return
    }
    ElMessage.success('验证码已发送，请检查邮箱。')
    cooldownSeconds.value = 60
    cooldownTimer = window.setInterval(() => {
      cooldownSeconds.value -= 1
      if (cooldownSeconds.value <= 0 && cooldownTimer) {
        window.clearInterval(cooldownTimer)
        cooldownTimer = undefined
      }
    }, 1000)
  } finally {
    sendingCode.value = false
  }
}

async function handleRegister() {
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return
  loading.value = true
  errorTitle.value = ''
  errorMessage.value = ''
  try {
    const result = await registerApi({
      username: form.username.trim(),
      email: form.email.trim(),
      password: form.password,
      verificationCode: form.verificationCode,
    })
    if (!result.success) {
      errorTitle.value = '注册失败'
      errorMessage.value = productErrorMessage(result, '请检查输入后重试。')
      return
    }
    ElMessage.success('注册成功，请登录。')
    await router.push(loginLocation.value)
  } finally {
    loading.value = false
  }
}

onUnmounted(() => { if (cooldownTimer) window.clearInterval(cooldownTimer) })
</script>

<style scoped>
.form-alert { margin-bottom: var(--space-5); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-4); }
.password-hint { margin: calc(var(--space-2) * -1) 0 var(--space-5); color: var(--color-text-muted); font-size: var(--font-size-xs); }
.primary-action { width: 100%; }
@media (max-width: 540px) { .form-grid { grid-template-columns: 1fr; gap: 0; } }
</style>
