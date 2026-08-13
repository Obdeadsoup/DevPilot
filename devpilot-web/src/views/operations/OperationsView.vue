<template>
  <div class="operations-view-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <h2>DEAD 运维与人工重放 (Operations & Replay)</h2>
            <span class="sub-text">Workspace ID: {{ workspaceId }} | Project ID: {{ projectId }}</span>
          </div>
        </div>
      </template>

      <el-tabs v-model="activeTab" type="border-card">
        <!-- Tab 1: Outbox DEAD Events -->
        <el-tab-pane label="Outbox DEAD 事件运维" name="outbox">
          <el-alert
            title="Outbox DEAD 事件说明"
            type="info"
            description="展示处理失败且达到重试上限进入 DEAD 状态的领域 Outbox 事件。后端刻意不暴露 payload，仅显示事件元数据与状态。"
            show-icon
            :closable="false"
            style="margin-bottom: 16px;"
          />

          <div class="mb-3" style="text-align: right;">
            <el-button @click="fetchOutboxData">刷新 Outbox DEAD 列表</el-button>
          </div>

          <PageState :loading="outboxLoading" :error="outboxHasError" :error-msg="outboxErrorMsg" :empty="outboxItems.length === 0" @retry="fetchOutboxData">
            <el-table :data="outboxItems" stripe style="width: 100%;">
              <el-table-column prop="id" label="Event ID" width="90" />
              <el-table-column prop="eventType" label="事件类型 (EventType)" min-width="180">
                <template #default="{ row }">
                  <code>{{ row.eventType }}</code>
                </template>
              </el-table-column>
              <el-table-column prop="aggregateType" label="聚合根类型" min-width="140" />
              <el-table-column prop="aggregateId" label="聚合根 ID" width="100">
                <template #default="{ row }">
                  <code>{{ row.aggregateId }}</code>
                </template>
              </el-table-column>
              <el-table-column prop="retryCount" label="重试次数" width="90" />
              <el-table-column prop="lastErrorCode" label="最后错误码" width="130">
                <template #default="{ row }">
                  <code>{{ row.lastErrorCode || 'DEAD' }}</code>
                </template>
              </el-table-column>
              <el-table-column prop="version" label="Version" width="90">
                <template #default="{ row }">
                  <code>v{{ row.version }}</code>
                </template>
              </el-table-column>
              <el-table-column prop="updatedAt" label="更新时间" min-width="160" />

              <el-table-column label="操作" width="140" fixed="right">
                <template #default="{ row }">
                  <el-button type="danger" size="small" @click="openReplayOutbox(row)">
                    人工 Replay (202)
                  </el-button>
                </template>
              </el-table-column>
            </el-table>

            <div class="pagination-bar">
              <el-pagination
                v-model:current-page="outboxPage"
                v-model:page-size="outboxSize"
                :page-sizes="[10, 20, 50]"
                layout="total, sizes, prev, pager, next, jumper"
                :total="outboxTotal"
                @size-change="fetchOutboxData"
                @current-change="fetchOutboxData"
              />
            </div>

            <RawJsonPanel :data="outboxRawJson" title="GET .../operations/outbox/dead 原始响应" />
          </PageState>
        </el-tab-pane>

        <!-- Tab 2: GitHub Sync DEAD Runs -->
        <el-tab-pane label="GitHub Sync DEAD 运行运维" name="sync">
          <el-alert
            title="GitHub Sync DEAD 说明"
            type="warning"
            description="此处的列表必须选择特定的 Repository Binding，且固化参数 status=DEAD。重放将提交 Replay 理由，并由后端创建新 Run 异发执行。"
            show-icon
            :closable="false"
            style="margin-bottom: 16px;"
          />

          <div class="filter-bar">
            <el-form :inline="true">
              <el-form-item label="选择 Repository Binding ID" required>
                <el-input-number v-model="bindingIdInput" :min="1" placeholder="Binding ID" style="width: 160px;" />
                <el-button type="primary" style="margin-left: 12px;" @click="fetchSyncData">
                  查询 DEAD Runs
                </el-button>
              </el-form-item>
            </el-form>
          </div>

          <PageState :loading="syncLoading" :error="syncHasError" :error-msg="syncErrorMsg" :empty="syncItems.length === 0" @retry="fetchSyncData">
            <el-table :data="syncItems" stripe style="width: 100%;">
              <el-table-column prop="id" label="Run ID" width="90" />
              <el-table-column prop="resourceType" label="资源类型" width="130">
                <template #default="{ row }">
                  <el-tag size="small">{{ row.resourceType }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="triggerType" label="触发方式" width="110" />
              <el-table-column prop="attemptCount" label="尝试次数" width="90" />
              <el-table-column prop="lastErrorCode" label="最后错误码" width="130">
                <template #default="{ row }">
                  <code>{{ row.lastErrorCode || 'DEAD' }}</code>
                </template>
              </el-table-column>
              <el-table-column prop="version" label="Version" width="90">
                <template #default="{ row }">
                  <code>v{{ row.version }}</code>
                </template>
              </el-table-column>
              <el-table-column prop="completedAt" label="完成/失败时间" min-width="160" />

              <el-table-column label="操作" width="140" fixed="right">
                <template #default="{ row }">
                  <el-button type="danger" size="small" @click="openReplaySync(row)">
                    人工 Replay (202)
                  </el-button>
                </template>
              </el-table-column>
            </el-table>

            <div class="pagination-bar">
              <el-pagination
                v-model:current-page="syncPage"
                v-model:page-size="syncSize"
                :page-sizes="[10, 20, 50]"
                layout="total, sizes, prev, pager, next, jumper"
                :total="syncTotal"
                @size-change="fetchSyncData"
                @current-change="fetchSyncData"
              />
            </div>

            <RawJsonPanel :data="syncRawJson" title="GET .../sync-runs?status=DEAD 原始响应" />
          </PageState>
        </el-tab-pane>
      </el-tabs>

      <!-- Replay Modal Dialog -->
      <ReplayDialog ref="replayDialogRef" @submit-replay="handleExecuteReplay" />
      <ConflictDialog ref="conflictDialogRef" @refresh="refreshActiveTab" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  listDeadOutboxEventsApi,
  replayOutboxEventApi,
  listDeadGitHubSyncRunsApi,
  replayGitHubSyncRunApi,
} from '@/api/modules/operations'
import { useScopeStore } from '@/stores/scope'
import type { DeadOutboxEventResponse, DeadGitHubSyncRunResponse } from '@/types/operations'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'
import ConflictDialog from '@/components/ConflictDialog.vue'
import ReplayDialog from '@/components/operations/ReplayDialog.vue'

const route = useRoute()
const scopeStore = useScopeStore()

const workspaceId = Number(route.params.workspaceId || scopeStore.currentWorkspaceId || 1)
const projectId = Number(route.params.projectId || scopeStore.currentProjectId || 1)

const activeTab = ref<'outbox' | 'sync'>('outbox')

// Outbox DEAD State
const outboxLoading = ref(false)
const outboxHasError = ref(false)
const outboxErrorMsg = ref('')
const outboxPage = ref(1)
const outboxSize = ref(20)
const outboxTotal = ref(0)
const outboxItems = ref<DeadOutboxEventResponse[]>([])
const outboxRawJson = ref<any>(null)

// GitHub Sync DEAD State
const bindingIdInput = ref<number>(1)
const syncLoading = ref(false)
const syncHasError = ref(false)
const syncErrorMsg = ref('')
const syncPage = ref(1)
const syncSize = ref(20)
const syncTotal = ref(0)
const syncItems = ref<DeadGitHubSyncRunResponse[]>([])
const syncRawJson = ref<any>(null)

const replayDialogRef = ref()
const conflictDialogRef = ref()

const currentReplayContext = ref<{
  type: 'outbox' | 'sync'
  id: number
  version: number
  bindingId?: number
} | null>(null)

async function fetchOutboxData() {
  outboxLoading.value = true
  outboxHasError.value = false
  outboxErrorMsg.value = ''

  try {
    const res = await listDeadOutboxEventsApi(workspaceId, projectId, outboxPage.value, outboxSize.value)
    outboxRawJson.value = res.rawJson
    if (res.success && res.data) {
      outboxItems.value = res.data.items || []
      outboxTotal.value = res.data.total || 0
    } else {
      outboxHasError.value = true
      outboxErrorMsg.value = res.message || '获取 Outbox DEAD 列表失败'
    }
  } catch (err: any) {
    outboxHasError.value = true
    outboxErrorMsg.value = err.message || '网络连接失败'
  } finally {
    outboxLoading.value = false
  }
}

async function fetchSyncData() {
  if (!bindingIdInput.value) {
    ElMessage.warning('请选择要查询的 Repository Binding ID')
    return
  }
  syncLoading.value = true
  syncHasError.value = false
  syncErrorMsg.value = ''

  try {
    const res = await listDeadGitHubSyncRunsApi(
      workspaceId,
      projectId,
      bindingIdInput.value,
      syncPage.value,
      syncSize.value
    )
    syncRawJson.value = res.rawJson
    if (res.success && res.data) {
      syncItems.value = res.data.items || []
      syncTotal.value = res.data.total || 0
    } else {
      syncHasError.value = true
      syncErrorMsg.value = res.message || '获取 Sync DEAD 列表失败'
    }
  } catch (err: any) {
    syncHasError.value = true
    syncErrorMsg.value = err.message || '网络连接失败'
  } finally {
    syncLoading.value = false
  }
}

function openReplayOutbox(event: DeadOutboxEventResponse) {
  currentReplayContext.value = {
    type: 'outbox',
    id: event.id,
    version: event.version,
  }
  replayDialogRef.value?.show('Outbox Event', event.id, event.version)
}

function openReplaySync(run: DeadGitHubSyncRunResponse) {
  currentReplayContext.value = {
    type: 'sync',
    id: run.id,
    version: run.version,
    bindingId: bindingIdInput.value,
  }
  replayDialogRef.value?.show('GitHub Sync Run', run.id, run.version)
}

async function handleExecuteReplay(reason: string, expectedVersion: number) {
  if (!currentReplayContext.value) return
  const ctx = currentReplayContext.value
  replayDialogRef.value?.setSubmitting(true)

  try {
    let res
    if (ctx.type === 'outbox') {
      res = await replayOutboxEventApi(workspaceId, projectId, ctx.id, {
        reason,
        expectedVersion,
      })
    } else {
      res = await replayGitHubSyncRunApi(workspaceId, projectId, ctx.bindingId!, ctx.id, {
        reason,
        expectedVersion,
      })
    }

    if (res.httpStatus === 202 || (res.success && res.data)) {
      ElMessage.success(`Replay 请求已接受 (Receipt ID: ${res.data?.replayId})`)
      replayDialogRef.value?.closeDialog()
      refreshActiveTab()
    } else if (res.httpStatus === 409) {
      replayDialogRef.value?.closeDialog()
      conflictDialogRef.value?.show(res.code, res.message)
    } else {
      ElMessage.error(`Replay 拒绝 [${res.code}]: ${res.message}`)
    }
  } catch (err: any) {
    ElMessage.error(err.message || '请求失败')
  } finally {
    replayDialogRef.value?.setSubmitting(false)
  }
}

function refreshActiveTab() {
  if (activeTab.value === 'outbox') {
    fetchOutboxData()
  } else {
    fetchSyncData()
  }
}

onMounted(() => {
  fetchOutboxData()
})
</script>

<style scoped>
.operations-view-container {
  max-width: 1100px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-header h2 {
  margin: 0;
  font-size: 18px;
  color: #303133;
}
.sub-text {
  font-size: 12px;
  color: #909399;
}
.filter-bar {
  margin-bottom: 16px;
  padding: 12px;
  background-color: #fafafa;
  border-radius: 6px;
}
.pagination-bar {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.mb-3 {
  margin-bottom: 12px;
}
</style>
