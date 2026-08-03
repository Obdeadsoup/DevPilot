<template>
  <div class="repository-list-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <h2>GitHub 仓库绑定 (GET .../github-repositories)</h2>
            <span class="sub-text">Project ID: {{ projectId }}</span>
          </div>
          <div>
            <el-button type="primary" @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/repositories/new`)">
              绑定新 GitHub 仓库
            </el-button>
            <el-button @click="fetchData">刷新</el-button>
          </div>
        </div>
      </template>

      <!-- Filter Controls -->
      <div class="filter-bar">
        <el-form :inline="true">
          <el-form-item label="绑定状态">
            <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px;" @change="handleFilterChange">
              <el-option label="ACTIVE" value="ACTIVE" />
              <el-option label="DISABLED" value="DISABLED" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" :empty="items.length === 0" @retry="fetchData">
        <template #empty-action>
          <el-button type="primary" @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/repositories/new`)">
            绑定首个 GitHub 仓库
          </el-button>
        </template>

        <el-table :data="items" stripe style="width: 100%;">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="fullName" label="仓库全名" min-width="160">
            <template #default="{ row }">
              <a :href="row.htmlUrl" target="_blank" rel="noopener noreferrer" style="color: #409eff; text-decoration: none;">
                {{ row.fullName }}
              </a>
            </template>
          </el-table-column>
          <el-table-column prop="defaultBranch" label="默认分支" width="110">
            <template #default="{ row }">
              <code>{{ row.defaultBranch }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="bindingStatus" label="状态" width="110">
            <template #default="{ row }">
              <StatusBadge :status="row.bindingStatus" type="binding" />
            </template>
          </el-table-column>
          <el-table-column prop="hasApiCredential" label="API凭据" width="90">
            <template #default="{ row }">
              <el-tag :type="row.hasApiCredential ? 'success' : 'danger'" size="small">
                {{ row.hasApiCredential ? '已配置' : '缺失' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="hasWebhookSecret" label="Webhook Secret" width="120">
            <template #default="{ row }">
              <el-tag :type="row.hasWebhookSecret ? 'success' : 'info'" size="small">
                {{ row.hasWebhookSecret ? '已配置' : '未配置' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="version" label="Version" width="90">
            <template #default="{ row }">
              <code>v{{ row.version }}</code>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="goToDetail(row)">
                详情/管理
              </el-button>
              <el-button
                v-if="row.bindingStatus === 'ACTIVE'"
                type="success"
                link
                size="small"
                @click="triggerSync(row)"
              >
                触发 Commit 同步
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

        <RawJsonPanel :data="rawJson" title="GET .../github-repositories 原始响应" />
      </PageState>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listRepositoriesApi } from '@/api/modules/repository'
import { triggerCommitSyncApi } from '@/api/modules/sync'
import type { GitHubRepositoryBinding } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const route = useRoute()
const router = useRouter()

const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')

const statusFilter = ref<string>('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<GitHubRepositoryBinding[]>([])
const rawJson = ref<any>(null)

async function fetchData() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await listRepositoriesApi(workspaceId, projectId, {
      page: page.value,
      size: size.value,
      status: statusFilter.value || undefined,
    })
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      items.value = res.data.items || []
      total.value = res.data.total || 0
    } else {
      hasError.value = true
      errorMsg.value = res.message || '获取仓库绑定列表失败'
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = err.message || '网络连接失败'
  } finally {
    loading.value = false
  }
}

function handleFilterChange() {
  page.value = 1
  fetchData()
}

function goToDetail(binding: GitHubRepositoryBinding) {
  router.push(`/workspaces/${workspaceId}/projects/${projectId}/repositories/${binding.id}`)
}

async function triggerSync(binding: GitHubRepositoryBinding) {
  try {
    const res = await triggerCommitSyncApi(workspaceId, projectId, binding.id)
    if (res.success && res.data) {
      ElMessage.success(`Commit 同步已触发 (Run ID: ${res.data.runId})`)
      router.push(`/workspaces/${workspaceId}/projects/${projectId}/sync-runs/${binding.id}/${res.data.runId}`)
    } else {
      ElMessage.error(`触发失败 [${res.code}]: ${res.message}`)
    }
  } catch (err: any) {
    ElMessage.error(err.message || '网络请求错误')
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.repository-list-container {
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
</style>
