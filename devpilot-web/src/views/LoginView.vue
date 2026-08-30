<template>
  <div class="login-container">
    <el-card class="login-card">
      <template #header>
        <div class="card-header">
          <h2>DevPilot 本地联调测试控制台</h2>
          <span class="sub-title">系统登录 (POST /api/v1/auth/login)</span>
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

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @keyup.enter="handleLogin"
      >
        <el-form-item label="用户名 / 邮箱 (login)" prop="login">
          <el-input
            v-model="form.login"
            placeholder="例如: developer 或 developer@example.test"
            maxlength="254"
            clearable
          />
        </el-form-item>

        <el-form-item label="密码 (password)" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            maxlength="72"
            show-password
          />
        </el-form-item>

        <el-form-item style="margin-top: 24px;">
          <el-button
            type="primary"
            :loading="loading"
            style="width: 100%;"
            size="large"
            @click="handleLogin"
          >
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="footer-note">
        <span>还没有账号？</span>
        <router-link to="/register">注册本地账号</router-link>
        <span> | </span>
        <router-link to="/health">查看后端 Health 状态</router-link>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { loginApi } from '@/api/modules/auth'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const errorTitle = ref('')
const errorMessage = ref('')

const form = reactive({
  login: '',
  password: '',
})

const rules: FormRules = {
  login: [{ required: true, message: '请输入用户名或邮箱', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleLogin() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    errorTitle.value = ''
    errorMessage.value = ''

    try {
      const res = await loginApi({
        login: form.login.trim(),
        password: form.password,
      })

      if (res.success && res.data) {
        authStore.setAuth(res.data)
        const returnUrl = (route.query.returnUrl as string) || '/workspaces'
        router.push(returnUrl)
      } else {
        errorTitle.value = `登录失败 [${res.code || 'HTTP ' + res.httpStatus}]`
        errorMessage.value = res.message || '用户名或密码错误'
      }
    } catch (err: any) {
      errorTitle.value = '请求失败'
      errorMessage.value = err.message || '网络无法连接后端'
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background-color: #f0f2f5;
  padding: 20px;
}
.login-card {
  width: 440px;
}
.card-header h2 {
  margin: 0 0 6px 0;
  font-size: 20px;
  color: #303133;
}
.sub-title {
  font-size: 13px;
  color: #909399;
}
.footer-note {
  margin-top: 20px;
  font-size: 12px;
  color: #909399;
  text-align: center;
}
</style>
