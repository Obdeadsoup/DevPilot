<template>
  <div class="agent-run-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span>Agent Run</span>
            <el-tag v-if="run" :type="statusTagType(run.status)" effect="dark" style="margin-left: 10px;">
              {{ run.status }}
            </el-tag>
          </div>
          <el-button v-if="run" :loading="refreshing" @click="refreshRun">刷新权威状态</el-button>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        title="请求会发送至 Java Core；Java 再通过既有 gRPC 调用 Python Agent。浏览器不会直接访问 Python 服务。"
        style="margin-bottom: 20px;"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="Agent 输入" prop="input">
          <el-input
            v-model="form.input"
            type="textarea"
            :rows="5"
            maxlength="10000"
            show-word-limit
            :disabled="starting || isRunActive"
            placeholder="例如：总结当前项目的未完成任务，并指出风险。"
          />
        </el-form-item>
        <el-space>
          <el-button type="primary" :loading="starting" :disabled="isRunActive" @click="startRun">启动 Agent</el-button>
          <el-button v-if="isRunActive" type="danger" :loading="cancelling" @click="cancelRun">取消当前 Run</el-button>
        </el-space>
      </el-form>

      <el-alert v-if="errorMessage" type="error" show-icon :title="errorMessage" style="margin-top: 20px;" />

      <template v-if="run">
        <el-divider content-position="left">Run 状态</el-divider>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="Run ID"><code>{{ run.runId }}</code></el-descriptions-item>
          <el-descriptions-item label="当前状态">{{ run.status }}</el-descriptions-item>
          <el-descriptions-item label="启动时间">{{ run.startedAt || '等待运行' }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ run.finishedAt || '尚未结束' }}</el-descriptions-item>
          <el-descriptions-item v-if="run.failureKind" label="失败类型">{{ run.failureKind }}</el-descriptions-item>
          <el-descriptions-item label="流连接">{{ streamState }}</el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">流式事件</el-divider>
        <el-empty v-if="events.length === 0" description="等待 Java Core 的 Agent SSE 事件…" />
        <el-timeline v-else>
          <el-timeline-item v-for="item in events" :key="item.key" :timestamp="item.timestamp" :type="item.type">
            <strong>{{ item.title }}</strong>
            <span v-if="item.detail" class="event-detail">{{ item.detail }}</span>
          </el-timeline-item>
        </el-timeline>

        <el-divider v-if="run.finalOutput" content-position="left">最终输出</el-divider>
        <el-card v-if="run.finalOutput" shadow="never" class="final-output">{{ run.finalOutput }}</el-card>
      </template>

      <el-divider content-position="left">运行历史</el-divider>
      <el-tabs v-model="historyStatus" @tab-change="reloadHistory">
        <el-tab-pane label="全部" name="" />
        <el-tab-pane label="成功" name="SUCCEEDED" />
        <el-tab-pane label="失败" name="FAILED" />
      </el-tabs>
      <el-alert v-if="historyError" type="error" show-icon :title="historyError" style="margin-bottom: 12px;" />
      <el-table v-loading="historyLoading" :data="history.items" empty-text="当前筛选条件下还没有运行记录">
        <el-table-column prop="runId" label="Run ID" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="120">
          <template #default="scope"><el-tag :type="statusTagType(scope.row.status)">{{ scope.row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="failureKind" label="失败类型" min-width="130"><template #default="scope">{{ scope.row.failureKind || '-' }}</template></el-table-column>
        <el-table-column prop="startedAt" label="启动时间" min-width="170" />
        <el-table-column label="操作" width="90"><template #default="scope"><el-button link type="primary" @click="openHistoryRun(scope.row.runId)">详情</el-button></template></el-table-column>
      </el-table>
      <div class="history-pager">
        <el-pagination layout="prev, pager, next" :current-page="history.page + 1" :page-size="history.size"
          :total="history.total" @current-change="changeHistoryPage" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { type FormInstance, type FormRules } from 'element-plus'
import { cancelAgentRunApi, getAgentRunApi, listAgentRunsApi, startAgentRunApi } from '@/api/modules/agent'
import { connectAgentRunStream, type AgentRunStreamMessage } from '@/services/agentRunStream'
import type { AgentRun, AgentRunHistoryItem, AgentRunStatus, PageResponse } from '@/types/api'

type TimelineItem = { key: string; timestamp: string; title: string; detail: string; type: 'primary' | 'success' | 'warning' | 'danger' | 'info' }

const route = useRoute()
const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)
const formRef = ref<FormInstance>()
const form = reactive({ input: '' })
const rules: FormRules = { input: [{ required: true, message: '请输入 Agent 请求内容', trigger: 'blur' }] }
const run = ref<AgentRun | null>(null)
const events = ref<TimelineItem[]>([])
const errorMessage = ref('')
const streamState = ref('未连接')
const starting = ref(false)
const cancelling = ref(false)
const refreshing = ref(false)
const historyLoading = ref(false)
const historyError = ref('')
const historyStatus = ref('')
const history = ref<PageResponse<AgentRunHistoryItem>>({ page: 0, size: 20, total: 0, items: [] })
const lastEventId = ref<string | null>(null)
let disconnectStream: (() => void) | null = null
let reconnectAttempts = 0

const isRunActive = computed(() => run.value?.status === 'PENDING' || run.value?.status === 'RUNNING')

async function startRun() {
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return
  starting.value = true
  errorMessage.value = ''
  events.value = []
  lastEventId.value = null
  try {
    const result = await startAgentRunApi(workspaceId, projectId, form.input.trim())
    if (!result.success || !result.data) {
      errorMessage.value = result.message || 'Agent Run 创建失败。'
      return
    }
    run.value = result.data
    addEvent('run-accepted', 'Run 已创建', `runId: ${result.data.runId}`, 'primary')
    openStream()
    void loadHistory(0)
  } finally {
    starting.value = false
  }
}

async function loadHistory(page = history.value.page) {
  historyLoading.value = true
  historyError.value = ''
  try {
    const result = await listAgentRunsApi(workspaceId, projectId, page, history.value.size, historyStatus.value || undefined)
    if (result.success && result.data) history.value = result.data
    else historyError.value = result.message || '无法读取 Agent 运行历史。'
  } finally {
    historyLoading.value = false
  }
}

function reloadHistory() {
  void loadHistory(0)
}

function changeHistoryPage(page: number) {
  void loadHistory(page - 1)
}

async function openHistoryRun(runId: string) {
  const result = await getAgentRunApi(workspaceId, projectId, runId)
  if (!result.success || !result.data) {
    errorMessage.value = result.message || '无法读取该 Run 详情。'
    return
  }
  disconnectStream?.()
  run.value = result.data
  events.value = []
  if (isRunActive.value) openStream()
}

async function cancelRun() {
  if (!run.value) return
  cancelling.value = true
  try {
    const result = await cancelAgentRunApi(workspaceId, projectId, run.value.runId)
    if (result.success && result.data) {
      run.value = result.data
      addEvent('cancel-requested', '已请求取消', '等待 Java Core 写入终态。', 'warning')
    } else {
      errorMessage.value = result.message || '取消请求失败。'
    }
  } finally {
    cancelling.value = false
  }
}

async function refreshRun() {
  if (!run.value) return
  refreshing.value = true
  try {
    const result = await getAgentRunApi(workspaceId, projectId, run.value.runId)
    if (result.success && result.data) run.value = result.data
    else errorMessage.value = result.message || '无法读取 Agent Run 状态。'
  } finally {
    refreshing.value = false
  }
}

function openStream() {
  if (!run.value) return
  disconnectStream?.()
  streamState.value = '连接中'
  disconnectStream = connectAgentRunStream({
    workspaceId,
    projectId,
    runId: run.value.runId,
    lastEventId: lastEventId.value,
    onEvent: handleStreamEvent,
    onError: handleStreamError,
  })
}

function handleStreamEvent(message: AgentRunStreamMessage) {
  if (message.id) lastEventId.value = message.id
  if (message.event === 'heartbeat') return
  if (message.event === 'replay-gap') {
    addEvent('replay-gap', '事件重放缺口', '已改用 GET 获取权威状态。', 'warning')
    void refreshRun()
    return
  }
  streamState.value = '已连接'
  const data = message.data
  addEvent(
    message.id || `${message.event}-${Date.now()}`,
    eventTitle(message.event),
    data?.toolName || data?.finalOutput || data?.failureKind || (data ? `步骤 ${data.step}` : ''),
    eventType(message.event),
  )
  if (message.event === 'run-succeeded' || message.event === 'run-failed' || message.event === 'run-cancelled') {
    void refreshRun()
    streamState.value = '已完成'
  }
}

function handleStreamError(message: string) {
  streamState.value = '连接失败'
  if (!isRunActive.value || reconnectAttempts >= 2) {
    errorMessage.value = `${message} 可使用“刷新权威状态”确认 Run 结果。`
    return
  }
  reconnectAttempts += 1
  window.setTimeout(openStream, reconnectAttempts * 1000)
}

function addEvent(key: string, title: string, detail: string, type: TimelineItem['type']) {
  events.value.push({ key, title, detail, type, timestamp: new Date().toLocaleTimeString() })
}

function eventTitle(event: string) {
  return ({
    'run-started': 'Agent 已启动',
    'model-step-started': '模型执行步骤',
    'tool-started': '工具调用开始',
    'tool-completed': '工具调用完成',
    'run-succeeded': 'Agent 执行成功',
    'run-failed': 'Agent 执行失败',
    'run-cancelled': 'Agent 已取消',
  } as Record<string, string>)[event] || event
}

function eventType(event: string): TimelineItem['type'] {
  if (event === 'run-succeeded') return 'success'
  if (event === 'run-failed') return 'danger'
  if (event === 'run-cancelled') return 'warning'
  return 'primary'
}

function statusTagType(status: AgentRunStatus) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'CANCELLED') return 'warning'
  return 'info'
}

onMounted(() => void loadHistory(0))
onUnmounted(() => disconnectStream?.())
</script>

<style scoped>
.agent-run-view { max-width: 920px; margin: 0 auto; }
.card-header { display: flex; align-items: center; justify-content: space-between; font-weight: 600; }
.event-detail { display: block; margin-top: 4px; color: #606266; white-space: pre-wrap; word-break: break-word; }
.final-output { white-space: pre-wrap; word-break: break-word; background: #fafafa; }
.history-pager { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
