<template>
  <div class="register-container">
    <el-card class="register-card">
      <template #header>
        <div class="card-header">
          <h2>注册 DevPilot 本地账号</h2>
          <span class="sub-title">POST /api/v1/auth/register</span>
        </div>
      </template>

      <el-alert
        v-if="errorMessage"
        type="error"
        show-icon
        :title="errorTitle"
        :description="errorMessage"
        style="margin-bottom: 20px;"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleRegister">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" maxlength="64" show-word-limit autocomplete="username" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" maxlength="254" autocomplete="email" />
        </el-form-item>
        <el-form-item label="邮箱验证码" prop="verificationCode">
          <el-input v-model="form.verificationCode" maxlength="6" inputmode="numeric" autocomplete="one-time-code">
            <template #append>
              <el-button :loading="sendingCode" :disabled="cooldownSeconds > 0" @click="sendCode">
                {{ cooldownSeconds > 0 ? `${cooldownSeconds} 秒后重发` : '发送验证码' }}
              </el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" maxlength="72" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" maxlength="72" show-password autocomplete="new-password" />
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          title="密码至少 12 位，且必须包含字母和数字。用户名、邮箱将规范化为小写。"
          style="margin-bottom: 20px;"
        />
        <el-button type="primary" :loading="loading" style="width: 100%;" size="large" @click="handleRegister">
          注册账号
        </el-button>
      </el-form>
      <div class="footer-note">已有账号？<router-link to="/login">返回登录</router-link></div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { registerApi, sendEmailVerificationCodeApi } from '@/api/modules/auth'

const router = useRouter()
const formRef = ref<FormInstance>()
const loading = ref(false)
const errorTitle = ref('')
const errorMessage = ref('')
const form = reactive({ username: '', email: '', verificationCode: '', password: '', confirmPassword: '' })
const sendingCode = ref(false)
const cooldownSeconds = ref(0)
let cooldownTimer: number | undefined

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 64, message: '用户名长度为 3 到 64 位', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9][A-Za-z0-9._-]*$/, message: '用户名只能使用字母、数字、点、下划线或连字符', trigger: 'blur' },
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
  const emailValid = await formRef.value?.validateField('email').then(() => true).catch(() => false)
  if (!emailValid || sendingCode.value || cooldownSeconds.value > 0) return
  sendingCode.value = true
  errorTitle.value = ''
  errorMessage.value = ''
  try {
    const result = await sendEmailVerificationCodeApi(form.email.trim())
    if (!result.success) {
      errorTitle.value = `验证码发送失败 [${result.code}]`
      errorMessage.value = result.message || '请稍后重试。'
      return
    }
    ElMessage.success('验证码已发送，请查收邮箱。')
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
      errorTitle.value = `注册失败 [${result.code}]`
      errorMessage.value = result.message || '请检查输入后重试。'
      return
    }
    ElMessage.success('注册成功，请登录。')
    await router.push('/login')
  } finally {
    loading.value = false
  }
}

onUnmounted(() => { if (cooldownTimer) window.clearInterval(cooldownTimer) })
</script>

<style scoped>
.register-container { display: flex; justify-content: center; align-items: center; min-height: 100vh; background: #f0f2f5; padding: 20px; }
.register-card { width: 440px; }
.card-header h2 { margin: 0 0 6px; font-size: 20px; color: #303133; }
.sub-title, .footer-note { font-size: 13px; color: #909399; }
.footer-note { margin-top: 20px; text-align: center; }
</style>
