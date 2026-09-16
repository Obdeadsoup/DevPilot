<template>
  <div class="repository-list-container">
    <el-card>
      <template #header>
        <PageHeader title="GitHub 仓库" description="管理项目关联的仓库、凭据与同步状态。">
          <template #actions>
            <el-button type="primary" @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/repositories/new`)">
              绑定仓库
            </el-button>
            <el-button @click="fetchData">刷新</el-button>
          </template>
        </PageHeader>
      </template>

      <!-- Filter Controls -->
      <div class="filter-bar">
        <el-form :inline="true">
          <el-form-item label="绑定状态">
            <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px;" @change="handleFilterChange">
              <el-option label="启用" value="ACTIVE" />
              <el-option label="已禁用" value="DISABLED" />
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
          <el-table-column prop="hasApiCredential" label="访问凭据" width="110">
            <template #default="{ row }">
              <el-tag :type="row.hasApiCredential ? 'success' : 'danger'" size="small">
                {{ row.hasApiCredential ? '已配置' : '缺失' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="hasWebhookSecret" label="Webhook 密钥" width="120">
            <template #default="{ row }">
              <el-tag :type="row.hasWebhookSecret ? 'success' : 'info'" size="small">
                {{ row.hasWebhookSecret ? '已配置' : '未配置' }}
              </el-tag>
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
                同步提交
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
import PageHeader from '@/components/PageHeader.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

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
    if (res.success && res.data) {
      items.value = res.data.items || []
      total.value = res.data.total || 0
    } else {
      hasError.value = true
      errorMsg.value = productErrorMessage(res, '无法加载 GitHub 仓库，请稍后重试。')
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = unexpectedErrorMessage(err, '暂时无法加载 GitHub 仓库，请稍后重试。')
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
      ElMessage.success('提交同步已开始')
      router.push(`/workspaces/${workspaceId}/projects/${projectId}/sync-runs/${binding.id}/${res.data.runId}`)
    } else {
      ElMessage.error(productErrorMessage(res, '无法开始提交同步，请稍后重试。'))
    }
  } catch (err: any) {
    ElMessage.error(unexpectedErrorMessage(err, '无法开始提交同步，请稍后重试。'))
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
