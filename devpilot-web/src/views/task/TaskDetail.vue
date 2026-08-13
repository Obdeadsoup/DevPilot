<template>
  <div class="task-detail-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span>Task 详情 (Key: {{ task?.displayKey || `#${taskId}` }})</span>
            <StatusBadge v-if="task" :status="task.status" type="task" style="margin-left: 10px;" />
            <StatusBadge v-if="task" :status="task.priority" type="priority" style="margin-left: 6px;" />
          </div>
          <div>
            <el-button link @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/tasks`)">
              返回 Task 列表
            </el-button>
            <el-button size="small" @click="fetchDetail">刷新</el-button>
          </div>
        </div>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" @retry="fetchDetail">
        <template v-if="task">
          <!-- Title & Workflow Action Bar Header -->
          <div class="task-header-box mb-4">
            <h2 class="task-title">{{ task.title }}</h2>

            <div class="action-bar-wrapper">
              <TaskActionBar
                :status="task.status"
                :version="task.version"
                :loading-action="executingEndpoint"
                @execute-action="handleExecuteWorkflowAction"
              />
            </div>
          </div>

          <el-descriptions :column="2" border class="mb-4">
            <el-descriptions-item label="Task ID">
              <code>{{ task.id }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="Display Key">
              <code>{{ task.displayKey }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="创建者 (Reporter)">
              <code>User #{{ task.reporterUserId }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="当前负责人 (Assignee)">
              <span v-if="task.assigneeUserId">
                <code>User #{{ task.assigneeUserId }}</code>
                <el-button type="danger" link size="small" style="margin-left: 8px;" @click="handleUnassign">取消分配</el-button>
              </span>
              <span v-else class="text-muted">
                未分配
                <el-button type="primary" link size="small" style="margin-left: 8px;" @click="assignDialogVisible = true">分配负责人</el-button>
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="截止时间 (dueAt)">
              {{ task.dueAt || '无' }}
            </el-descriptions-item>
            <el-descriptions-item label="完成 / 取消时间">
              <span v-if="task.completedAt">完成于 {{ task.completedAt }}</span>
              <span v-else-if="task.canceledAt">取消于 {{ task.canceledAt }}</span>
              <span v-else class="text-muted">未完成</span>
            </el-descriptions-item>
            <el-descriptions-item label="当前 Version">
              <code>v{{ task.version }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">
              {{ task.updatedAt }}
            </el-descriptions-item>
          </el-descriptions>

          <!-- Description Section -->
          <el-divider content-position="left">任务描述 (Description)</el-divider>

          <div class="description-body mb-4">
            <pre v-if="task.description">{{ task.description }}</pre>
            <el-empty v-else description="无详细描述" :image-size="50" />
          </div>

          <!-- Edit Profile Dialog Trigger & Drawer -->
          <div class="mb-4" style="text-align: right;">
            <el-button type="primary" plain size="small" @click="openEditDialog">
              编辑 Task 资料 (PUT .../tasks/{id})
            </el-button>
          </div>

          <!-- GitHub Links Section -->
          <el-divider content-position="left">关联 GitHub 快照 (GitHub Links)</el-divider>
          <TaskGitHubLinks
            :workspace-id="workspaceId"
            :project-id="projectId"
            :task-id="taskId"
            :task-version="task.version"
            :links="links"
            :loading="linksLoading"
            @refresh="fetchLinks"
          />

          <!-- Status History Timeline -->
          <el-divider content-position="left">状态变更历史记录 (Status History)</el-divider>
          <TaskHistoryTimeline :history="history" />

          <RawJsonPanel :data="rawJson" title="GET .../tasks/{id} 原始响应" />
        </template>
      </PageState>

      <!-- Edit Task Profile Dialog -->
      <el-dialog v-model="editDialogVisible" title="编辑 Task 资料" width="600px" :close-on-click-modal="false">
        <el-form label-position="top">
          <el-form-item label="标题 (title)" required>
            <el-input v-model="editForm.title" maxlength="255" show-word-limit />
          </el-form-item>

          <el-form-item label="优先级 (priority)" required>
            <el-radio-group v-model="editForm.priority">
              <el-radio value="LOW">LOW</el-radio>
              <el-radio value="MEDIUM">MEDIUM</el-radio>
              <el-radio value="HIGH">HIGH</el-radio>
              <el-radio value="URGENT">URGENT</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="截止时间 (dueAt)">
            <el-date-picker
              v-model="editForm.dueAt"
              type="datetime"
              placeholder="选择截止时间"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 240px;"
            />
          </el-form-item>

          <el-form-item label="详细描述 (description)">
            <el-input v-model="editForm.description" type="textarea" :rows="4" maxlength="10000" show-word-limit />
          </el-form-item>
        </el-form>

        <template #footer>
          <el-button @click="editDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="updating" @click="handleUpdateProfile">
            保存更新
          </el-button>
        </template>
      </el-dialog>

      <!-- Assign User Dialog -->
      <el-dialog v-model="assignDialogVisible" title="分配任务负责人" width="400px">
        <el-form label-position="top">
          <el-form-item label="负责人 User ID" required>
            <el-input-number v-model="assigneeUserIdInput" :min="1" style="width: 100%;" placeholder="数字 User ID" />
            <div class="field-hint">提示：后端未开放成员列表 API，请直接输入有效的数字 User ID。</div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="assignDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="assigning" @click="handleAssign">确认分配</el-button>
        </template>
      </el-dialog>

      <ConflictDialog ref="conflictDialogRef" @refresh="fetchDetail" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getTaskApi,
  updateTaskApi,
  assignTaskApi,
  unassignTaskApi,
  listTaskGitHubLinksApi,
  planTaskApi,
  returnTaskToBacklogApi,
  startTaskApi,
  submitTaskForReviewApi,
  requestTaskChangesApi,
  completeTaskApi,
  cancelTaskApi,
  reopenTaskApi,
} from '@/api/modules/task'
import type { TaskResponse, TaskStatusHistoryResponse, TaskGitHubLinkResponse, TaskPriority } from '@/types/task'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'
import ConflictDialog from '@/components/ConflictDialog.vue'
import TaskActionBar from '@/components/task/TaskActionBar.vue'
import TaskHistoryTimeline from '@/components/task/TaskHistoryTimeline.vue'
import TaskGitHubLinks from '@/components/task/TaskGitHubLinks.vue'

const route = useRoute()
const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)
const taskId = Number(route.params.taskId)

const loading = ref(false)
const linksLoading = ref(false)
const updating = ref(false)
const assigning = ref(false)
const executingEndpoint = ref<string | null>(null)
const hasError = ref(false)
const errorMsg = ref('')

const task = ref<TaskResponse | null>(null)
const history = ref<TaskStatusHistoryResponse[]>([])
const links = ref<TaskGitHubLinkResponse[]>([])
const rawJson = ref<any>(null)

const conflictDialogRef = ref()

const editDialogVisible = ref(false)
const assignDialogVisible = ref(false)
const assigneeUserIdInput = ref<number | undefined>(undefined)

const editForm = reactive({
  title: '',
  description: '',
  priority: 'MEDIUM' as TaskPriority,
  dueAt: undefined as string | undefined,
})

async function fetchDetail() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const [res, linksRes] = await Promise.all([
      getTaskApi(workspaceId, projectId, taskId),
      listTaskGitHubLinksApi(workspaceId, projectId, taskId),
    ])

    rawJson.value = res.rawJson
    if (res.success && res.data) {
      task.value = res.data.task
      history.value = res.data.history || []
    } else {
      hasError.value = true
      errorMsg.value = res.message || 'Task 不存在或无访问权限'
    }

    if (linksRes.success && linksRes.data) {
      links.value = linksRes.data
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = err.message || '网络连接失败'
  } finally {
    loading.value = false
  }
}

async function fetchLinks() {
  linksLoading.value = true
  try {
    const res = await listTaskGitHubLinksApi(workspaceId, projectId, taskId)
    if (res.success && res.data) {
      links.value = res.data
    }
  } catch {
    // Ignore error
  } finally {
    linksLoading.value = false
  }
}

function openEditDialog() {
  if (!task.value) return
  editForm.title = task.value.title
  editForm.description = task.value.description || ''
  editForm.priority = task.value.priority
  editForm.dueAt = task.value.dueAt || undefined
  editDialogVisible.value = true
}

async function handleUpdateProfile() {
  if (!task.value) return
  updating.value = true

  try {
    const res = await updateTaskApi(workspaceId, projectId, taskId, {
      title: editForm.title.trim(),
      description: editForm.description ? editForm.description.trim() : undefined,
      priority: editForm.priority,
      dueAt: editForm.dueAt || undefined,
      expectedVersion: task.value.version,
    })

    if (res.success && res.data) {
      ElMessage.success('Task 资料更新成功')
      editDialogVisible.value = false
      fetchDetail()
    } else if (res.httpStatus === 409) {
      conflictDialogRef.value?.show(res.code, res.message)
    } else {
      ElMessage.error(`更新失败 [${res.code}]: ${res.message}`)
    }
  } catch (err: any) {
    ElMessage.error(err.message || '请求失败')
  } finally {
    updating.value = false
  }
}

async function handleAssign() {
  if (!task.value || !assigneeUserIdInput.value) return
  assigning.value = true

  try {
    const res = await assignTaskApi(workspaceId, projectId, taskId, {
      assigneeUserId: assigneeUserIdInput.value,
      expectedVersion: task.value.version,
    })

    if (res.success && res.data) {
      ElMessage.success('任务分配成功')
      assignDialogVisible.value = false
      fetchDetail()
    } else if (res.httpStatus === 409) {
      conflictDialogRef.value?.show(res.code, res.message)
    } else {
      ElMessage.error(`分配失败 [${res.code}]: ${res.message}`)
    }
  } catch (err: any) {
    ElMessage.error(err.message || '网络请求错误')
  } finally {
    assigning.value = false
  }
}

async function handleUnassign() {
  if (!task.value) return
  try {
    await ElMessageBox.confirm('确定要取消该 Task 的负责人分配吗？', '取消分配确认', {
      type: 'warning',
      confirmButtonText: '确定取消',
    })

    const res = await unassignTaskApi(workspaceId, projectId, taskId, {
      expectedVersion: task.value.version,
    })

    if (res.success && res.data) {
      ElMessage.success('已取消负责人分配')
      fetchDetail()
    } else if (res.httpStatus === 409) {
      conflictDialogRef.value?.show(res.code, res.message)
    } else {
      ElMessage.error(`操作失败 [${res.code}]: ${res.message}`)
    }
  } catch (err: any) {
    if (err !== 'cancel') ElMessage.error(err.message || '操作异常')
  }
}

async function handleExecuteWorkflowAction(endpoint: string, reason?: string) {
  if (!task.value) return
  executingEndpoint.value = endpoint

  const payload = {
    reason,
    expectedVersion: task.value.version,
  }

  try {
    let res
    switch (endpoint) {
      case 'plan':
        res = await planTaskApi(workspaceId, projectId, taskId, payload)
        break
      case 'return-to-backlog':
        res = await returnTaskToBacklogApi(workspaceId, projectId, taskId, payload)
        break
      case 'start':
        res = await startTaskApi(workspaceId, projectId, taskId, payload)
        break
      case 'submit-for-review':
        res = await submitTaskForReviewApi(workspaceId, projectId, taskId, payload)
        break
      case 'request-changes':
        res = await requestTaskChangesApi(workspaceId, projectId, taskId, payload)
        break
      case 'complete':
        res = await completeTaskApi(workspaceId, projectId, taskId, payload)
        break
      case 'cancel':
        res = await cancelTaskApi(workspaceId, projectId, taskId, payload)
        break
      case 'reopen':
        res = await reopenTaskApi(workspaceId, projectId, taskId, payload)
        break
    }

    if (res && res.success) {
      ElMessage.success('状态流转动作执行成功')
      fetchDetail()
    } else if (res && res.httpStatus === 409) {
      conflictDialogRef.value?.show(res.code, res.message)
    } else if (res && res.httpStatus === 403) {
      ElMessage.error(`权限不足 [${res.code}]: ${res.message}`)
    } else if (res) {
      ElMessage.error(`动作失败 [${res.code}]: ${res.message}`)
    }
  } catch (err: any) {
    ElMessage.error(err.message || '请求失败')
  } finally {
    executingEndpoint.value = null
  }
}

onMounted(() => {
  fetchDetail()
})
</script>

<style scoped>
.task-detail-container {
  max-width: 950px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.task-header-box {
  background-color: #fafafa;
  padding: 16px;
  border-radius: 6px;
  border: 1px solid #ebeef5;
}
.task-title {
  margin: 0 0 12px 0;
  font-size: 20px;
  color: #303133;
}
.action-bar-wrapper {
  margin-top: 8px;
}
.description-body {
  background-color: #fdfdfd;
  padding: 12px;
  border-radius: 6px;
  border: 1px solid #e4e7ed;
}
.description-body pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: inherit;
  font-size: 14px;
}
.mb-4 {
  margin-bottom: 16px;
}
.text-muted {
  color: #909399;
}
.field-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
</style>
