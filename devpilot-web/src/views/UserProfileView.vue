<template>
  <div class="profile-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>个人资料</span>
          <el-button type="primary" size="small" :loading="loading" @click="fetchProfile">
            刷新个人资料
          </el-button>
        </div>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" @retry="fetchProfile">
        <el-descriptions :column="1" border v-if="user">
          <el-descriptions-item label="用户编号">
            <code>{{ user.id }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="用户名">
            {{ user.username }}
          </el-descriptions-item>
          <el-descriptions-item label="显示名称">
            {{ user.displayName }}
          </el-descriptions-item>
          <el-descriptions-item label="邮箱">
            <span v-if="user.email">{{ user.email }}</span>
            <span v-else class="text-muted">暂未提供</span>
          </el-descriptions-item>
        </el-descriptions>

        <div style="margin-top: 20px; text-align: right;">
          <el-button type="danger" @click="handleLogout">退出登录</el-button>
        </div>

        <RawJsonPanel :data="rawJson" title="技术详情" />
      </PageState>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getMeApi, logoutApi } from '@/api/modules/auth'
import { useAuthStore } from '@/stores/auth'
import type { User } from '@/types/api'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')
const user = ref<User | null>(null)
const rawJson = ref<any>(null)

async function fetchProfile() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await getMeApi()
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      user.value = res.data
      authStore.setUser(res.data)
    } else {
      hasError.value = true
      errorMsg.value = productErrorMessage(res, '暂时无法加载个人资料，请稍后重试。')
    }
  } catch (err: unknown) {
    hasError.value = true
    errorMsg.value = unexpectedErrorMessage(err, '暂时无法加载个人资料，请稍后重试。')
  } finally {
    loading.value = false
  }
}

async function handleLogout() {
  try {
    await logoutApi()
  } finally {
    authStore.clearAuth()
    router.push('/login')
  }
}

onMounted(() => {
  fetchProfile()
})
</script>

<style scoped>
.profile-container {
  max-width: 800px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.text-muted {
  color: var(--color-text-subtle);
}
</style>
