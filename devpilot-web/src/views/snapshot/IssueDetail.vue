<template>
  <div class="issue-detail-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span>GitHub Issue</span>
            <StatusBadge v-if="issue" :status="issue.state" type="issue" style="margin-left: 12px;" />
          </div>
          <div>
            <el-button type="success" size="small" @click="createTaskDialogVisible = true">
              创建任务
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
            <el-descriptions-item label="发起人">
              <code>@{{ issue.authorLogin }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="负责人">
              <template v-if="assignees.length">
                <el-tag v-for="a in assignees" :key="a" size="small" style="margin-right: 4px;">@{{ a }}</el-tag>
              </template>
              <span v-else class="text-muted">无</span>
            </el-descriptions-item>
            <el-descriptions-item label="标签">
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

          <el-divider content-position="left">问题描述</el-divider>

          <ExternalContent :content="issue.body" :untrusted="issue.externalUntrustedContent" />

          <RawJsonPanel :data="rawJson" title="技术详情" />
        </template>
      </PageState>

      <!-- Create Task Dialog -->
      <el-dialog v-model="createTaskDialogVisible" title="从 GitHub Issue 创建任务" width="480px">
        <el-form label-position="top">
          <el-form-item label="优先级">
            <el-radio-group v-model="createTaskForm.priority">
              <el-radio value="LOW">低</el-radio>
              <el-radio value="MEDIUM">中（默认）</el-radio>
              <el-radio value="HIGH">高</el-radio>
              <el-radio value="URGENT">紧急</el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="负责人编号（可选）">
            <el-input-number v-model="createTaskForm.assigneeUserId" :min="1" style="width: 100%;" placeholder="请输入成员编号" />
          </el-form-item>

          <el-form-item label="截止时间（可选）">
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
            创建任务
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
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

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
      errorMsg.value = productErrorMessage(res, '没有找到这个 GitHub Issue，请返回列表重试。')
    }
  } catch (err: unknown) {
    hasError.value = true
    errorMsg.value = unexpectedErrorMessage(err, '暂时无法加载 GitHub Issue，请稍后重试。')
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
      ElMessage.success('任务已创建')
      createTaskDialogVisible.value = false
      router.push(`/workspaces/${workspaceId}/projects/${projectId}/tasks/${res.data.id}`)
    } else if (res.code === 'TASK_0504') {
      ElMessage.warning('这个 GitHub Issue 已有关联任务')
    } else {
      ElMessage.error(productErrorMessage(res, '暂时无法创建任务，请稍后重试。'))
    }
  } catch (err: unknown) {
    ElMessage.error(unexpectedErrorMessage(err, '暂时无法创建任务，请稍后重试。'))
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
