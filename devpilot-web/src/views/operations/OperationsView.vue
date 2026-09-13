<template>
  <div class="operations-view-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <h2>运行恢复</h2>
            <span class="sub-text">处理已停止自动重试的项目事件与仓库同步</span>
          </div>
        </div>
      </template>

      <el-tabs v-model="activeTab" type="border-card">
        <el-tab-pane label="失败事件" name="outbox">
          <el-alert
            title="项目事件恢复"
            type="info"
            description="这里展示处理失败且已停止自动重试的项目事件。恢复后，系统会创建一次新的处理记录。"
            show-icon
            :closable="false"
            style="margin-bottom: 16px;"
          />

          <div class="mb-3" style="text-align: right;">
            <el-button @click="fetchOutboxData">刷新失败事件</el-button>
          </div>

          <PageState :loading="outboxLoading" :error="outboxHasError" :error-msg="outboxErrorMsg" :empty="outboxItems.length === 0" @retry="fetchOutboxData">
            <el-table :data="outboxItems" stripe style="width: 100%;">
              <el-table-column prop="eventType" label="事件类型" min-width="180">
                <template #default="{ row }">
                  <code>{{ row.eventType }}</code>
                </template>
              </el-table-column>
              <el-table-column prop="aggregateType" label="关联内容" min-width="140" />
              <el-table-column prop="aggregateId" label="内容编号" width="100">
                <template #default="{ row }">
                  <code>{{ row.aggregateId }}</code>
                </template>
              </el-table-column>
              <el-table-column prop="retryCount" label="重试次数" width="90" />
              <el-table-column prop="updatedAt" label="更新时间" min-width="160" />

              <el-table-column label="操作" width="140" fixed="right">
                <template #default="{ row }">
                  <el-button type="danger" size="small" @click="openReplayOutbox(row)">
                    恢复处理
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

            <RawJsonPanel :data="outboxRawJson" title="技术详情" />
          </PageState>
        </el-tab-pane>

        <el-tab-pane label="仓库同步失败" name="sync">
          <el-alert
            title="仓库同步恢复"
            type="warning"
            description="输入仓库绑定编号可查看已停止自动重试的同步记录。恢复后，系统会创建一次新的仓库同步。"
            show-icon
            :closable="false"
            style="margin-bottom: 16px;"
          />

          <div class="filter-bar">
            <el-form :inline="true">
              <el-form-item label="仓库绑定编号" required>
                <el-input-number v-model="bindingIdInput" :min="1" placeholder="输入编号" style="width: 160px;" />
                <el-button type="primary" style="margin-left: 12px;" @click="fetchSyncData">
                  查询失败记录
                </el-button>
              </el-form-item>
            </el-form>
          </div>

          <PageState :loading="syncLoading" :error="syncHasError" :error-msg="syncErrorMsg" :empty="syncItems.length === 0" @retry="fetchSyncData">
            <el-table :data="syncItems" stripe style="width: 100%;">
              <el-table-column prop="resourceType" label="资源类型" width="130">
                <template #default="{ row }">
                  <el-tag size="small">{{ row.resourceType === 'COMMITS' ? '提交记录' : row.resourceType }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="triggerType" label="触发方式" width="110">
                <template #default="{ row }">{{ row.triggerType === 'MANUAL' ? '手动同步' : row.triggerType }}</template>
              </el-table-column>
              <el-table-column prop="attemptCount" label="尝试次数" width="90" />
              <el-table-column prop="completedAt" label="完成/失败时间" min-width="160" />

              <el-table-column label="操作" width="140" fixed="right">
                <template #default="{ row }">
                  <el-button type="danger" size="small" @click="openReplaySync(row)">
                    恢复同步
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

            <RawJsonPanel :data="syncRawJson" title="技术详情" />
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
import type { DeadOutboxEventResponse, DeadGitHubSyncRunResponse } from '@/types/operations'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'
import ConflictDialog from '@/components/ConflictDialog.vue'
import ReplayDialog from '@/components/operations/ReplayDialog.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

const route = useRoute()

const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)

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
      outboxErrorMsg.value = productErrorMessage(res, '暂时无法加载失败事件，请稍后重试。')
    }
  } catch (err: unknown) {
    outboxHasError.value = true
    outboxErrorMsg.value = unexpectedErrorMessage(err, '暂时无法加载失败事件，请稍后重试。')
  } finally {
    outboxLoading.value = false
  }
}

async function fetchSyncData() {
  if (!bindingIdInput.value) {
    ElMessage.warning('请输入仓库绑定编号')
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
      syncErrorMsg.value = productErrorMessage(res, '暂时无法加载同步失败记录，请稍后重试。')
    }
  } catch (err: unknown) {
    syncHasError.value = true
    syncErrorMsg.value = unexpectedErrorMessage(err, '暂时无法加载同步失败记录，请稍后重试。')
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
  replayDialogRef.value?.show('项目事件', event.id, event.version)
}

function openReplaySync(run: DeadGitHubSyncRunResponse) {
  currentReplayContext.value = {
    type: 'sync',
    id: run.id,
    version: run.version,
    bindingId: bindingIdInput.value,
  }
  replayDialogRef.value?.show('仓库同步', run.id, run.version)
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
      ElMessage.success('恢复请求已受理')
      replayDialogRef.value?.closeDialog()
      refreshActiveTab()
    } else if (res.httpStatus === 409) {
      replayDialogRef.value?.closeDialog()
      conflictDialogRef.value?.show(res.code, res.message)
    } else {
      ElMessage.error(productErrorMessage(res, '恢复请求未能提交，请稍后重试。'))
    }
  } catch (err: unknown) {
    ElMessage.error(unexpectedErrorMessage(err, '恢复请求未能提交，请稍后重试。'))
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
