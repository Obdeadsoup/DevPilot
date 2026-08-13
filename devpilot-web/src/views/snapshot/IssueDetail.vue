<template>
  <div class="issue-detail-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span>GitHub Issue 快照详情 (ID: {{ issueId }})</span>
            <StatusBadge v-if="issue" :status="issue.state" type="issue" style="margin-left: 12px;" />
          </div>
          <div>
            <el-button type="success" size="small" @click="createTaskDialogVisible = true">
              创建为本地 Task (POST .../from-github-issue)
            </el-button>
            <el-button link @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/github/issues`)">
              返回列表
            </el-button>
            <el-button size="small" @click="fetchDetail">刷新</el-button>
          </div>
        </div>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" @retry="fetchDetail">
        <template v-if="issue">
          <div class="issue-title">
            <h2>#{{ issue.number }} {{ issue.title }}</h2>
          </div>

          <el-descriptions :column="2" border class="mb-4">
            <el-descriptions-item label="快照主键 ID">
              <code>{{ issue.id }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="GitHub 官方 Issue ID">
              <code>{{ issue.githubIssueId }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="提办人 (Author)">
              <code>@{{ issue.authorLogin }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="指派人 (Assignees)">
              <template v-if="assignees.length">
                <el-tag v-for="a in assignees" :key="a" size="small" style="margin-right: 4px;">@{{ a }}</el-tag>
              </template>
              <span v-else class="text-muted">无</span>
            </el-descriptions-item>
            <el-descriptions-item label="标签 (Labels)">
              <template v-if="labels.length">
                <el-tag v-for="l in labels" :key="l" size="small" type="info" style="margin-right: 4px;">{{ l }}</el-tag>
              </template>
              <span v-else class="text-muted">无</span>
            </el-descriptions-item>
            <el-descriptions-item label="关闭时间">
              {{ issue.closedAt || '未关闭' }}
            </el-descriptions-item>
            <el-descriptions-item label="GitHub 创建时间">
              {{ issue.githubCreatedAt }}
            </el-descriptions-item>
            <el-descriptions-item label="GitHub 更新时间">
              {{ issue.githubUpdatedAt }}
            </el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">Issue 正文 (Body)</el-divider>

          <ExternalContent :content="issue.body" :untrusted="issue.externalUntrustedContent" />

          <RawJsonPanel :data="rawJson" title="GET .../github/issues/{id} 原始响应" />
        </template>
      </PageState>

      <!-- Create Task Dialog -->
      <el-dialog v-model="createTaskDialogVisible" title="从当前 GitHub Issue 显式创建 Task" width="480px">
        <el-form label-position="top">
          <el-form-item label="优先级 (priority)">
            <el-radio-group v-model="createTaskForm.priority">
              <el-radio value="LOW">LOW</el-radio>
              <el-radio value="MEDIUM">MEDIUM (默认)</el-radio>
              <el-radio value="HIGH">HIGH</el-radio>
              <el-radio value="URGENT">URGENT</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="分配负责人 User ID (可选)">
            <el-input-number v-model="createTaskForm.assigneeUserId" :min="1" style="width: 100%;" placeholder="数字 User ID" />
          </el-form-item>

          <el-form-item label="截止时间 (dueAt, 可选)">
            <el-date-picker
              v-model="createTaskForm.dueAt"
              type="datetime"
              placeholder="选择时间"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 100%;"
            />
          </el-form-item>
        </el-form>

        <template #footer>
          <el-button @click="createTaskDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="creatingTask" @click="handleCreateTaskFromIssue">
            确认创建 Task
          </el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getIssueApi } from '@/api/modules/snapshot'
import { createTaskFromIssueApi } from '@/api/modules/task'
import { parseJsonArraySafe } from '@/utils/safeExternalContent'
import type { GitHubIssue } from '@/types/api'
import type { TaskPriority } from '@/types/task'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import ExternalContent from '@/components/ExternalContent.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const route = useRoute()
const router = useRouter()

const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)
const issueId = Number(route.params.issueId)

const loading = ref(false)
const creatingTask = ref(false)
const hasError = ref(false)
const errorMsg = ref('')
const issue = ref<GitHubIssue | null>(null)
const rawJson = ref<any>(null)

const createTaskDialogVisible = ref(false)
const createTaskForm = reactive({
  priority: 'MEDIUM' as TaskPriority,
  assigneeUserId: undefined as number | undefined,
  dueAt: undefined as string | undefined,
})

const assignees = computed(() => parseJsonArraySafe(issue.value?.assigneesJson))
const labels = computed(() => parseJsonArraySafe(issue.value?.labelsJson))

async function fetchDetail() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await getIssueApi(workspaceId, projectId, issueId)
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      issue.value = res.data
    } else {
      hasError.value = true
      errorMsg.value = res.message || 'Issue 快照不存在'
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = err.message || '网络连接失败'
  } finally {
    loading.value = false
  }
}

async function handleCreateTaskFromIssue() {
  creatingTask.value = true
  try {
    const res = await createTaskFromIssueApi(workspaceId, projectId, issueId, {
      priority: createTaskForm.priority,
      assigneeUserId: createTaskForm.assigneeUserId || undefined,
      dueAt: createTaskForm.dueAt || undefined,
    })

    if (res.success && res.data) {
      ElMessage.success('从 Issue 创建 Task 成功')
      createTaskDialogVisible.value = false
      router.push(`/workspaces/${workspaceId}/projects/${projectId}/tasks/${res.data.id}`)
    } else if (res.code === 'TASK_0504') {
      ElMessage.warning('该 Issue 快照已经关联了有效 Task')
    } else {
      ElMessage.error(`创建 Task 失败 [${res.code}]: ${res.message}`)
    }
  } catch (err: any) {
    ElMessage.error(err.message || '网络请求错误')
  } finally {
    creatingTask.value = false
  }
}

onMounted(() => {
  fetchDetail()
})
</script>

<style scoped>
.issue-detail-container {
  max-width: 900px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.issue-title h2 {
  margin: 0 0 16px 0;
  font-size: 20px;
  color: #303133;
}
.mb-4 {
  margin-bottom: 16px;
}
.text-muted {
  color: #909399;
}
</style>
