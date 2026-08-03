<template>
  <div class="sync-run-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span>Commit Sync Run 运行状态 (ID: {{ runId }})</span>
            <StatusBadge v-if="syncRun" :status="syncRun.status" type="syncRun" style="margin-left: 12px;" />
          </div>
          <div>
            <el-tag v-if="polling" type="warning" size="small" effect="dark">
              短轮询进行中 (3s)...
            </el-tag>
            <el-button size="small" style="margin-left: 8px;" @click="fetchDetail">手动刷新</el-button>
          </div>
        </div>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" @retry="fetchDetail">
        <template v-if="syncRun">
          <el-alert
            v-if="syncRun.status === 'RUNNING' || syncRun.status === 'PENDING'"
            type="info"
            title="后台同步任务处理中..."
            show-icon
            :closable="false"
            style="margin-bottom: 16px;"
          />
          <el-alert
            v-else-if="syncRun.status === 'RETRY_WAIT'"
            type="warning"
            :title="`触发 GitHub 限流或临时网络失败，等待重试 (下次重试: ${syncRun.nextRetryAt || '等待中'})`"
            show-icon
            :closable="false"
            style="margin-bottom: 16px;"
          />
          <el-alert
            v-else-if="syncRun.status === 'SUCCEEDED'"
            type="success"
            title="同步任务已成功完成！"
            show-icon
            :closable="false"
            style="margin-bottom: 16px;"
          />
          <el-alert
            v-else-if="syncRun.status === 'DEAD'"
            type="error"
            :title="`同步任务已终态失败 [错误码: ${syncRun.lastErrorCode || 'DEAD'}]`"
            show-icon
            :closable="false"
            style="margin-bottom: 16px;"
          />

          <el-descriptions :column="2" border class="mb-4">
            <el-descriptions-item label="Run ID">
              <code>{{ syncRun.id }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="Repository Binding ID">
              <code>{{ syncRun.repositoryBindingId }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="资源类型 (ResourceType)">
              <el-tag size="small">{{ syncRun.resourceType }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="触发方式 (TriggerType)">
              <el-tag size="small" type="info">{{ syncRun.triggerType }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="尝试次数 (AttemptCount)">
              {{ syncRun.attemptCount }}
            </el-descriptions-item>
            <el-descriptions-item label="最后错误稳定码">
              <code>{{ syncRun.lastErrorCode || '无' }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="开始时间">
              {{ syncRun.startedAt || '未开始' }}
            </el-descriptions-item>
            <el-descriptions-item label="完成时间">
              {{ syncRun.completedAt || '未完成' }}
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ syncRun.createdAt }}
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">
              {{ syncRun.updatedAt }}
            </el-descriptions-item>
          </el-descriptions>

          <RawJsonPanel :data="rawJson" title="GET .../sync-runs/{runId} 原始响应" />
        </template>
      </PageState>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { getSyncRunApi } from '@/api/modules/sync'
import type { GitHubSyncRun } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const route = useRoute()
const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)
const bindingId = Number(route.params.bindingId)
const runId = Number(route.params.runId)

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')
const polling = ref(false)
const syncRun = ref<GitHubSyncRun | null>(null)
const rawJson = ref<any>(null)

let timer: number | null = null

async function fetchDetail() {
  if (!syncRun.value) loading.value = true
  hasError.value = false

  try {
    const res = await getSyncRunApi(workspaceId, projectId, bindingId, runId)
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      syncRun.value = res.data

      const st = res.data.status
      if (['PENDING', 'RUNNING', 'RETRY_WAIT'].includes(st)) {
        startPolling()
      } else {
        stopPolling()
      }
    } else {
      hasError.value = true
      errorMsg.value = res.message || 'Sync Run 不存在'
      stopPolling()
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = err.message || '网络连接失败'
    stopPolling()
  } finally {
    loading.value = false
  }
}

function startPolling() {
  if (timer) return
  polling.value = true
  timer = window.setInterval(() => {
    if (document.visibilityState === 'visible') {
      fetchDetail()
    }
  }, 3000)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  polling.value = false
}

function handleVisibilityChange() {
  if (document.visibilityState === 'hidden') {
    stopPolling()
  } else if (syncRun.value && ['PENDING', 'RUNNING', 'RETRY_WAIT'].includes(syncRun.value.status)) {
    fetchDetail()
  }
}

onMounted(() => {
  fetchDetail()
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onUnmounted(() => {
  stopPolling()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})
</script>

<style scoped>
.sync-run-container {
  max-width: 900px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
