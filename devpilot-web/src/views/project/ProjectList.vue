<template>
  <div class="project-list-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <h2>项目列表 (GET /api/v1/workspaces/{workspaceId}/projects)</h2>
            <span class="sub-text">Workspace ID: {{ workspaceId }}</span>
          </div>
          <div>
            <el-button type="primary" @click="$router.push(`/workspaces/${workspaceId}/projects/new`)">
              创建项目
            </el-button>
            <el-button @click="fetchData">刷新</el-button>
          </div>
        </div>
      </template>

      <!-- Filter Controls -->
      <div class="filter-bar">
        <el-form :inline="true">
          <el-form-item label="状态 (status)">
            <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px;" @change="handleFilterChange">
              <el-option label="PLANNING" value="PLANNING" />
              <el-option label="ACTIVE" value="ACTIVE" />
              <el-option label="ARCHIVED" value="ARCHIVED" />
            </el-select>
          </el-form-item>

          <el-form-item label="可见性 (visibility)">
            <el-select v-model="visibilityFilter" placeholder="全部" clearable style="width: 140px;" @change="handleFilterChange">
              <el-option label="PRIVATE" value="PRIVATE" />
              <el-option label="INTERNAL" value="INTERNAL" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" :empty="items.length === 0" @retry="fetchData">
        <template #empty-action>
          <el-button type="primary" @click="$router.push(`/workspaces/${workspaceId}/projects/new`)">创建首个项目</el-button>
        </template>

        <el-table :data="items" stripe style="width: 100%;">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="projectKey" label="Project Key" width="130">
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
                {{ row.visibility }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="120">
            <template #default="{ row }">
              <StatusBadge :status="row.status" type="project" />
            </template>
          </el-table-column>
          <el-table-column prop="version" label="Version" width="100">
            <template #default="{ row }">
              <code>v{{ row.version }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" min-width="160" />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="enterProject(row)">
                概览与管理
              </el-button>
              <el-button type="success" link size="small" @click="goToRepositories(row)">
                仓库绑定
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

        <RawJsonPanel :data="rawJson" title="GET /api/v1/workspaces/{id}/projects 原始响应" />
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
import RawJsonPanel from '@/components/RawJsonPanel.vue'

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
const rawJson = ref<any>(null)

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
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      items.value = res.data.items || []
      total.value = res.data.total || 0
    } else {
      hasError.value = true
      errorMsg.value = res.message || '获取项目列表失败'
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
