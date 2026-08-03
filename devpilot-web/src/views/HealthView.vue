<template>
  <div class="health-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>系统健康状态 (Actuator Health)</span>
          <el-button type="primary" size="small" :loading="loading" @click="checkHealth">
            刷新检查
          </el-button>
        </div>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" @retry="checkHealth">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="HTTP Status">
            <code>{{ httpStatus !== null ? httpStatus : 'N/A' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="Health Status">
            <StatusBadge :status="status" />
          </el-descriptions-item>
          <el-descriptions-item label="Endpoint URL">
            <code>/actuator/health</code>
          </el-descriptions-item>
          <el-descriptions-item label="响应耗时">
            {{ duration }} ms
          </el-descriptions-item>
        </el-descriptions>

        <RawJsonPanel :data="rawJson" title="Actuator Health 原始响应 JSON" />
      </PageState>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getHealthApi } from '@/api/modules/health'
import PageState from '@/components/PageState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')
const status = ref('UNKNOWN')
const httpStatus = ref<number | null>(null)
const duration = ref(0)
const rawJson = ref<any>(null)

async function checkHealth() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await getHealthApi()
    httpStatus.value = res.httpStatus
    duration.value = res.durationMs
    rawJson.value = res.rawJson

    if (res.data && res.data.status) {
      status.value = res.data.status
    } else {
      status.value = res.success ? 'UP' : 'DOWN'
    }

    if (!res.success) {
      hasError.value = true
      errorMsg.value = res.message || 'Health 探测失败'
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = err.message || '网络无法连接后端'
    status.value = 'DOWN'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  checkHealth()
})
</script>

<style scoped>
.health-container {
  max-width: 800px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
</style>
