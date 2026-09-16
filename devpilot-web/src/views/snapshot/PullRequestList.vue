<template>
  <div class="pr-list-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <h2>GitHub Pull Requests</h2>
            <span class="sub-text">同步到当前项目的代码变更快照</span>
          </div>
          <el-button @click="fetchData">刷新列表</el-button>
        </div>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" :empty="items.length === 0" @retry="fetchData">
        <el-table :data="items" stripe style="width: 100%;">
          <el-table-column prop="number" label="PR #" width="90">
            <template #default="{ row }">
              <el-tag type="info">#{{ row.number }}</el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="title" label="标题" min-width="200">
            <template #default="{ row }">
              <div style="display: flex; align-items: center; gap: 8px;">
                <el-tag v-if="row.draft" size="small" type="info" effect="plain">草稿</el-tag>
                <router-link
                  :to="`/workspaces/${workspaceId}/projects/${projectId}/github/pull-requests/${row.id}`"
                  style="color: #409eff; font-weight: 500; text-decoration: none;"
                >
                  {{ row.title }}
                </router-link>
              </div>
            </template>
          </el-table-column>

          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <StatusBadge :status="row.status" type="pr" />
            </template>
          </el-table-column>

          <el-table-column prop="authorLogin" label="作者" width="130">
            <template #default="{ row }">
              <code>@{{ row.authorLogin }}</code>
            </template>
          </el-table-column>

          <el-table-column label="分支（来源 → 目标）" min-width="160">
            <template #default="{ row }">
              <code>{{ row.headRef }}</code> → <code>{{ row.baseRef }}</code>
            </template>
          </el-table-column>

          <el-table-column prop="githubUpdatedAt" label="GitHub 更新时间" min-width="160" />

          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button
                type="primary"
                link
                size="small"
                @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/github/pull-requests/${row.id}`)"
              >
                查看变更与评审
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-bar">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="size"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            :total="total"
            @size-change="fetchData"
            @current-change="fetchData"
          />
        </div>

        <RawJsonPanel :data="rawJson" title="技术详情" />
      </PageState>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { listPullRequestsApi } from '@/api/modules/snapshot'
import type { GitHubPullRequest } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

const route = useRoute()
const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')

const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<GitHubPullRequest[]>([])
const rawJson = ref<any>(null)

async function fetchData() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await listPullRequestsApi(workspaceId, projectId, page.value, size.value)
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      items.value = res.data.items || []
      total.value = res.data.total || 0
    } else {
      hasError.value = true
      errorMsg.value = productErrorMessage(res, '暂时无法加载 Pull Requests，请稍后重试。')
    }
  } catch (err: unknown) {
    hasError.value = true
    errorMsg.value = unexpectedErrorMessage(err, '暂时无法加载 Pull Requests，请稍后重试。')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.pr-list-container {
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
.pagination-bar {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
