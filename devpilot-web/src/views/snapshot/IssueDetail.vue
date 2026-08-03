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
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getIssueApi } from '@/api/modules/snapshot'
import { parseJsonArraySafe } from '@/utils/safeExternalContent'
import type { GitHubIssue } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import ExternalContent from '@/components/ExternalContent.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const route = useRoute()
const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)
const issueId = Number(route.params.issueId)

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')
const issue = ref<GitHubIssue | null>(null)
const rawJson = ref<any>(null)

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
