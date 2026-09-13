<template>
  <div class="agent-run-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span>项目 Agent</span>
            <el-tag v-if="run" :type="statusTagType(run.status)" effect="dark" style="margin-left: 10px;">
              {{ runStatusLabel(run.status) }}
            </el-tag>
          </div>
          <el-button v-if="run" :loading="refreshing" @click="refreshRun">刷新状态</el-button>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        title="选择仓库和分支后，本次运行会固定当前 Commit。只读操作可直接执行，写入操作需要你的确认。"
        style="margin-bottom: 20px;"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="GitHub 仓库">
          <el-select
            v-model="selectedRepositoryId"
            placeholder="选择仓库"
            :loading="repositoryLoading"
            :disabled="repositoryLoading || isRunActive"
            style="width: 100%;"
            @change="selectRepository"
          >
            <el-option
              v-for="repository in repositories"
              :key="repository.id"
              :label="repository.fullName"
              :value="repository.id"
            >
              <span>{{ repository.fullName }}</span>
              <span class="branch-option-sha">{{ repository.defaultBranch }}</span>
            </el-option>
          </el-select>
          <div v-if="!repositoryLoading && repositories.length === 0" class="field-hint">
            当前项目没有可用的 GitHub 仓库，本次运行将不附带代码上下文。
          </div>
        </el-form-item>
        <el-form-item label="分支">
          <el-select v-model="selectedBranch" placeholder="选择分支" :loading="branchesLoading"
            :disabled="!repositoryBinding || branchesLoading || Boolean(branchesError) || isRunActive" style="width: 100%;">
            <el-option v-for="branch in branches" :key="branch.name" :label="branch.name" :value="branch.name">
              <span>{{ branch.name }}</span>
              <span class="branch-option-sha">{{ shortSha(branch.commitSha) }}</span>
            </el-option>
          </el-select>
          <div v-if="repositoryBinding && !branchesLoading && !branchesError && branches.length === 0" class="field-hint">这个仓库没有可选择的分支。</div>
          <div v-if="repositories.length > 0 && !repositoryBinding && !repositoryLoading" class="field-hint">选择仓库后即可加载分支。</div>
        </el-form-item>
        <el-alert v-if="branchesError" type="error" show-icon title="无法加载仓库分支" :description="branchesError" style="margin-bottom: 16px;" />
        <el-form-item label="请求内容" prop="input">
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
          <el-button type="primary" :loading="starting" :disabled="startDisabled" @click="startRun">启动运行</el-button>
          <el-button v-if="isRunActive" type="danger" :loading="cancelling" @click="cancelRun">取消运行</el-button>
        </el-space>
      </el-form>

      <el-alert v-if="errorMessage" type="error" show-icon :title="errorMessage" style="margin-top: 20px;" />

      <template v-if="run">
        <el-divider content-position="left">运行状态</el-divider>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="当前状态">{{ runStatusLabel(run.status) }}</el-descriptions-item>
          <el-descriptions-item label="Repository">{{ run.repositoryFullName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Branch"><code>{{ run.branchName || '-' }}</code></el-descriptions-item>
          <el-descriptions-item label="Commit"><code :title="run.commitSha || ''">{{ run.commitSha ? shortSha(run.commitSha) : '-' }}</code></el-descriptions-item>
          <el-descriptions-item label="启动时间">{{ run.startedAt || '等待运行' }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ run.finishedAt || '尚未结束' }}</el-descriptions-item>
        </el-descriptions>
        <TechnicalDetails summary="运行标识、连接状态与失败诊断">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="Run ID"><code>{{ run.runId }}</code></el-descriptions-item>
            <el-descriptions-item label="Request ID"><code>{{ run.requestId }}</code></el-descriptions-item>
            <el-descriptions-item label="Event stream">{{ streamState }}</el-descriptions-item>
            <el-descriptions-item label="Failure kind">{{ run.failureKind || '-' }}</el-descriptions-item>
          </el-descriptions>
        </TechnicalDetails>

        <el-card v-if="proposal" shadow="never" class="proposal-card">
          <template #header><strong>待确认操作：{{ proposal.toolName }}</strong></template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="作用域">当前工作区 / 当前项目</el-descriptions-item>
            <el-descriptions-item label="状态">{{ proposalStatusLabel(proposal.status) }}</el-descriptions-item>
            <el-descriptions-item label="过期时间">{{ proposal.expiresAt }}</el-descriptions-item>
            <el-descriptions-item label="固定参数"><pre>{{ JSON.stringify(proposal.arguments, null, 2) }}</pre></el-descriptions-item>
          </el-descriptions>
          <el-space v-if="proposal.status === 'PENDING_APPROVAL'" style="margin-top: 12px">
            <el-button type="success" :loading="deciding" @click="decideProposal('APPROVE')">批准并执行</el-button>
            <el-button type="danger" :loading="deciding" @click="decideProposal('REJECT')">拒绝该操作</el-button>
          </el-space>
        </el-card>

        <el-divider content-position="left">执行进度</el-divider>
        <el-empty v-if="events.length === 0" description="等待运行事件…" />
        <el-timeline v-else>
          <el-timeline-item v-for="item in events" :key="item.key" :timestamp="item.timestamp" :type="item.type">
            <strong>{{ item.title }}</strong>
            <span v-if="item.detail" class="event-detail">{{ item.detail }}</span>
          </el-timeline-item>
        </el-timeline>

        <el-divider v-if="run.finalOutput" content-position="left">最终回答</el-divider>
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
        <el-table-column label="Branch" min-width="130"><template #default="scope"><code>{{ scope.row.branchName || '-' }}</code></template></el-table-column>
        <el-table-column label="Commit" min-width="120"><template #default="scope"><code :title="scope.row.commitSha || ''">{{ scope.row.commitSha ? shortSha(scope.row.commitSha) : '-' }}</code></template></el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="scope"><el-tag :type="statusTagType(scope.row.status)">{{ runStatusLabel(scope.row.status) }}</el-tag></template>
        </el-table-column>
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
import { cancelAgentRunApi, decideAgentProposalApi, getAgentProposalApi, getAgentRunApi, getPendingAgentProposalApi, listAgentRunsApi, startAgentRunApi } from '@/api/modules/agent'
import { listRepositoriesApi, listRepositoryBranchesApi } from '@/api/modules/repository'
import { connectAgentRunStream, type AgentRunStreamMessage } from '@/services/agentRunStream'
import type { AgentRun, AgentRunHistoryItem, AgentRunStatus, AgentToolProposal, GitHubBranch, GitHubRepositoryBinding, PageResponse } from '@/types/api'
import TechnicalDetails from '@/components/TechnicalDetails.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

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
const deciding = ref(false)
const proposal = ref<AgentToolProposal | null>(null)
const refreshing = ref(false)
const historyLoading = ref(false)
const historyError = ref('')
const historyStatus = ref('')
const history = ref<PageResponse<AgentRunHistoryItem>>({ page: 0, size: 20, total: 0, items: [] })
const repositories = ref<GitHubRepositoryBinding[]>([])
const selectedRepositoryId = ref<number | undefined>()
const repositoryBinding = computed(() =>
  repositories.value.find(repository => repository.id === selectedRepositoryId.value) || null)
const repositoryLoading = ref(false)
const branches = ref<GitHubBranch[]>([])
const branchesLoading = ref(false)
const branchesError = ref('')
const selectedBranch = ref<string | undefined>()
const lastEventId = ref<string | null>(null)
let disconnectStream: (() => void) | null = null
let reconnectAttempts = 0

const isRunActive = computed(() => ['PENDING', 'RUNNING', 'WAITING_APPROVAL'].includes(run.value?.status || ''))
const startDisabled = computed(() => isRunActive.value || repositoryLoading.value || branchesLoading.value
  || Boolean(branchesError.value) || Boolean(repositoryBinding.value && (!selectedBranch.value || branches.value.length === 0)))

async function startRun() {
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return
  starting.value = true
  errorMessage.value = ''
  events.value = []
  proposal.value = null
  lastEventId.value = null
  try {
    const result = await startAgentRunApi(workspaceId, projectId, {
      input: form.input.trim(),
      ...(repositoryBinding.value ? {
        repositoryBindingId: repositoryBinding.value.id,
        branchName: selectedBranch.value,
      } : {}),
    })
    if (!result.success || !result.data) {
      errorMessage.value = productErrorMessage(result, '暂时无法启动 Agent，请稍后重试。')
      return
    }
    run.value = result.data
    addEvent('run-accepted', '运行已创建', '正在准备项目上下文。', 'primary')
    openStream()
    void loadHistory(0)
  } finally {
    starting.value = false
  }
}

async function loadRepositoryContext() {
  repositoryLoading.value = true
  branchesError.value = ''
  try {
    const result = await listRepositoriesApi(workspaceId, projectId, { page: 1, size: 20, status: 'ACTIVE' })
    if (!result.success || !result.data) {
      branchesError.value = productErrorMessage(result, '暂时无法加载项目仓库。')
      return
    }
    repositories.value = result.data.items
    selectedRepositoryId.value = repositories.value[0]?.id
    if (!repositoryBinding.value) return
    await loadBranches()
  } catch (err: unknown) {
    branchesError.value = unexpectedErrorMessage(err, '暂时无法加载项目仓库。')
  } finally {
    repositoryLoading.value = false
  }
}

async function loadBranches() {
  if (!repositoryBinding.value) return
  branchesLoading.value = true
  branchesError.value = ''
  try {
    const result = await listRepositoryBranchesApi(workspaceId, projectId, repositoryBinding.value.id)
    if (!result.success || !result.data) {
      branchesError.value = productErrorMessage(result, '暂时无法加载仓库分支。')
      return
    }
    branches.value = result.data
    selectedBranch.value = branches.value.some(branch => branch.name === repositoryBinding.value?.defaultBranch)
      ? repositoryBinding.value.defaultBranch
      : undefined
    if (!selectedBranch.value) branchesError.value = '默认分支当前不可用，请选择其他仓库。'
  } catch (err: unknown) {
    branchesError.value = unexpectedErrorMessage(err, '暂时无法加载仓库分支。')
  } finally {
    branchesLoading.value = false
  }
}

async function selectRepository() {
  branches.value = []
  selectedBranch.value = undefined
  branchesError.value = ''
  if (repositoryBinding.value) await loadBranches()
}

function shortSha(sha: string) {
  return sha.slice(0, 12)
}

async function loadHistory(page = history.value.page) {
  historyLoading.value = true
  historyError.value = ''
  try {
    const result = await listAgentRunsApi(workspaceId, projectId, page, history.value.size, historyStatus.value || undefined)
    if (result.success && result.data) history.value = result.data
    else historyError.value = productErrorMessage(result, '暂时无法加载 Agent 运行历史。')
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
    errorMessage.value = productErrorMessage(result, '暂时无法加载这条运行记录。')
    return
  }
  disconnectStream?.()
  run.value = result.data
  events.value = []
  proposal.value = null
  if (run.value.status === 'WAITING_APPROVAL') void loadPendingProposal()
  if (isRunActive.value) openStream()
}

async function cancelRun() {
  if (!run.value) return
  cancelling.value = true
  try {
    const result = await cancelAgentRunApi(workspaceId, projectId, run.value.runId)
    if (result.success && result.data) {
      run.value = result.data
      addEvent('cancel-requested', '已请求取消', '正在等待运行结束。', 'warning')
    } else {
      errorMessage.value = productErrorMessage(result, '暂时无法取消运行，请重试。')
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
    if (result.success && result.data) {
      run.value = result.data
      if (run.value.status === 'WAITING_APPROVAL') void loadPendingProposal()
    }
    else errorMessage.value = productErrorMessage(result, '暂时无法刷新 Agent 状态。')
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
    addEvent('replay-gap', '进度已重新同步', '已刷新最新运行状态。', 'warning')
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
  if (message.event === 'run-waiting-approval' && data?.proposalId) {
    void loadProposal(data.proposalId)
    void refreshRun()
    streamState.value = '等待审批'
  }
  if (message.event === 'run-resumed') {
    if (proposal.value) void loadProposal(proposal.value.proposalId)
    void refreshRun()
    streamState.value = '已恢复'
  }
  if (message.event === 'run-succeeded' || message.event === 'run-failed' || message.event === 'run-cancelled') {
    void refreshRun()
    streamState.value = '已完成'
  }
}

async function loadProposal(proposalId: string) {
  if (!run.value) return
  const result = await getAgentProposalApi(workspaceId, projectId, run.value.runId, proposalId)
  if (result.success && result.data) proposal.value = result.data
  else errorMessage.value = productErrorMessage(result, '暂时无法加载待确认操作。')
}

async function loadPendingProposal() {
  if (!run.value) return
  const result = await getPendingAgentProposalApi(workspaceId, projectId, run.value.runId)
  if (result.success && result.data) proposal.value = result.data
}

async function decideProposal(decision: 'APPROVE' | 'REJECT') {
  if (!run.value || !proposal.value) return
  deciding.value = true
  try {
    const result = await decideAgentProposalApi(
      workspaceId, projectId, run.value.runId, proposal.value.proposalId, decision,
    )
    if (!result.success || !result.data) {
      errorMessage.value = productErrorMessage(result, '暂时无法提交你的决定，请重试。')
      return
    }
    proposal.value = result.data
    addEvent(`proposal-${Date.now()}`, decision === 'APPROVE' ? '操作已批准' : '操作已拒绝',
      `${result.data.toolName} · ${result.data.status}`, decision === 'APPROVE' ? 'success' : 'warning')
    openStream()
    void refreshRun()
  } finally {
    deciding.value = false
  }
}

function handleStreamError(_message: string) {
  streamState.value = '连接失败'
  if (!isRunActive.value || reconnectAttempts >= 2) {
    errorMessage.value = '实时进度暂时中断，可点击“刷新状态”查看最新结果。'
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
    'run-waiting-approval': 'Agent 等待人工审批',
    'run-resumed': 'Agent 已从审批点恢复',
  } as Record<string, string>)[event] || event
}

function eventType(event: string): TimelineItem['type'] {
  if (event === 'run-succeeded') return 'success'
  if (event === 'run-failed') return 'danger'
  if (event === 'run-cancelled') return 'warning'
  if (event === 'run-waiting-approval') return 'warning'
  return 'primary'
}

function statusTagType(status: AgentRunStatus) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'CANCELLED') return 'warning'
  if (status === 'WAITING_APPROVAL') return 'warning'
  return 'info'
}

function runStatusLabel(status: AgentRunStatus) {
  return ({
    PENDING: '准备中', RUNNING: '运行中', WAITING_APPROVAL: '等待确认',
    SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
  } as Record<AgentRunStatus, string>)[status]
}

function proposalStatusLabel(status: AgentToolProposal['status']) {
  return ({
    PENDING_APPROVAL: '等待确认', EXECUTING: '执行中', EXECUTED: '已执行',
    REJECTED: '已拒绝', EXPIRED: '已过期', FAILED: '失败',
  } as Record<AgentToolProposal['status'], string>)[status]
}

onMounted(() => {
  void loadHistory(0)
  void loadRepositoryContext()
})
onUnmounted(() => disconnectStream?.())
</script>

<style scoped>
.agent-run-view { max-width: 920px; margin: 0 auto; }
.card-header { display: flex; align-items: center; justify-content: space-between; font-weight: 600; }
.event-detail { display: block; margin-top: 4px; color: #606266; white-space: pre-wrap; word-break: break-word; }
.final-output { white-space: pre-wrap; word-break: break-word; background: #fafafa; }
.history-pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.field-hint { margin-top: 6px; color: #909399; font-size: 12px; }
.branch-option-sha { float: right; margin-left: 24px; color: #909399; font-family: monospace; }
.proposal-card { margin-top: 16px; border-color: #e6a23c; }
.proposal-card pre { margin: 0; white-space: pre-wrap; word-break: break-word; }
</style>
