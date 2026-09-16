<template>
  <div class="project-list-container">
    <el-card>
      <template #header>
        <PageHeader title="项目" description="聚合任务、仓库活动与 Agent 运行上下文。">
          <template #actions>
            <el-button type="primary" @click="$router.push(`/workspaces/${workspaceId}/projects/new`)">
              创建项目
            </el-button>
            <el-button @click="fetchData">刷新</el-button>
          </template>
        </PageHeader>
      </template>

      <!-- Filter Controls -->
      <div class="filter-bar">
        <el-form class="filter-form" label-position="top">
          <el-form-item label="状态">
            <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px;" @change="handleFilterChange">
              <el-option label="规划中" value="PLANNING" />
              <el-option label="进行中" value="ACTIVE" />
              <el-option label="已归档" value="ARCHIVED" />
            </el-select>
          </el-form-item>

          <el-form-item label="可见性">
            <el-select v-model="visibilityFilter" placeholder="全部" clearable style="width: 140px;" @change="handleFilterChange">
              <el-option label="仅项目成员" value="PRIVATE" />
              <el-option label="工作区成员" value="INTERNAL" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" :empty="items.length === 0" @retry="fetchData">
        <template #empty-action>
          <el-button type="primary" @click="$router.push(`/workspaces/${workspaceId}/projects/new`)">创建首个项目</el-button>
        </template>

        <el-table :data="items" stripe style="width: 100%;">
          <el-table-column prop="projectKey" label="项目标识" width="130">
            <template #default="{ row }">
              <el-tag type="info" effect="plain">
                <code>{{ row.projectKey }}</code>
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="项目名称" min-width="150" />
          <el-table-column prop="visibility" label="可见性" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.visibility === 'PRIVATE' ? 'danger' : 'info'">
                {{ row.visibility === 'PRIVATE' ? '仅项目成员' : '工作区成员' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="120">
            <template #default="{ row }">
              <StatusBadge :status="row.status" type="project" />
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" min-width="160" />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="enterProject(row)">
                概览与管理
              </el-button>
              <el-button type="success" link size="small" @click="goToRepositories(row)">
                GitHub 仓库
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
import { listProjectsApi } from '@/api/modules/project'
import { useScopeStore } from '@/stores/scope'
import type { Project } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import PageHeader from '@/components/PageHeader.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

const route = useRoute()
const router = useRouter()
const scopeStore = useScopeStore()

const workspaceId = Number(route.params.workspaceId)

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')

const statusFilter = ref<string>('')
const visibilityFilter = ref<string>('')

const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<Project[]>([])

async function fetchData() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await listProjectsApi(workspaceId, {
      page: page.value,
      size: size.value,
      status: statusFilter.value || undefined,
      visibility: visibilityFilter.value || undefined,
    })
    if (res.success && res.data) {
      items.value = res.data.items || []
      total.value = res.data.total || 0
    } else {
      hasError.value = true
      errorMsg.value = productErrorMessage(res, '暂时无法加载项目，请稍后重试。')
    }
  } catch (err: unknown) {
    hasError.value = true
    errorMsg.value = unexpectedErrorMessage(err, '暂时无法加载项目，请稍后重试。')
  } finally {
    loading.value = false
  }
}

function handleFilterChange() {
  page.value = 1
  fetchData()
}

function enterProject(project: Project) {
  scopeStore.setProject(project.id, project.projectKey, project.name)
  router.push(`/workspaces/${workspaceId}/projects/${project.id}/overview`)
}

function goToRepositories(project: Project) {
  scopeStore.setProject(project.id, project.projectKey, project.name)
  router.push(`/workspaces/${workspaceId}/projects/${project.id}/repositories`)
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.project-list-container {
  max-width: 1100px;
  margin: 0 auto;
}
.filter-bar {
  margin-bottom: 16px;
  padding: 12px;
  background-color: #fafafa;
  border-radius: 6px;
}
.filter-form { display: grid; grid-template-columns: repeat(2, minmax(140px, 220px)); gap: 0 var(--space-4); }
.filter-form :deep(.el-form-item) { margin-bottom: 0; }
.pagination-bar {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
