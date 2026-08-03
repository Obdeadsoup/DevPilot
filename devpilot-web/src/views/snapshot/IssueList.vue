<template>
  <div class="issue-list-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <h2>GitHub Issue 快照 (GET .../github/issues)</h2>
            <span class="sub-text">Project ID: {{ projectId }}</span>
          </div>
          <el-button @click="fetchData">刷新列表</el-button>
        </div>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" :empty="items.length === 0" @retry="fetchData">
        <el-table :data="items" stripe style="width: 100%;">
          <el-table-column prop="number" label="Issue #" width="100">
            <template #default="{ row }">
              <el-tag type="info">#{{ row.number }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题 (Title)" min-width="200">
            <template #default="{ row }">
              <router-link
                :to="`/workspaces/${workspaceId}/projects/${projectId}/github/issues/${row.id}`"
                style="color: #409eff; font-weight: 500; text-decoration: none;"
              >
                {{ row.title }}
              </router-link>
            </template>
          </el-table-column>
          <el-table-column prop="state" label="状态" width="100">
            <template #default="{ row }">
              <StatusBadge :status="row.state" type="issue" />
            </template>
          </el-table-column>
          <el-table-column prop="authorLogin" label="提办人 (Author)" width="140">
            <template #default="{ row }">
              <code>@{{ row.authorLogin }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="githubUpdatedAt" label="GitHub 更新时间" min-width="160" />
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button
                type="primary"
                link
                size="small"
                @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/github/issues/${row.id}`)"
              >
                查看快照详情
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

        <RawJsonPanel :data="rawJson" title="GET .../github/issues 原始响应" />
      </PageState>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { listIssuesApi } from '@/api/modules/snapshot'
import type { GitHubIssue } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const route = useRoute()
const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')

const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<GitHubIssue[]>([])
const rawJson = ref<any>(null)

async function fetchData() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await listIssuesApi(workspaceId, projectId, page.value, size.value)
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      items.value = res.data.items || []
      total.value = res.data.total || 0
    } else {
      hasError.value = true
      errorMsg.value = res.message || '获取 Issue 列表失败'
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = err.message || '网络连接失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.issue-list-container {
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
