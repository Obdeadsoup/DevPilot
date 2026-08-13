<template>
  <div class="task-list-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <h2>Task 任务列表 (GET .../tasks)</h2>
            <span class="sub-text">Project ID: {{ projectId }}</span>
          </div>
          <div>
            <el-button type="primary" @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/tasks/new`)">
              创建新 Task
            </el-button>
            <el-button @click="fetchData">刷新</el-button>
          </div>
        </div>
      </template>

      <!-- Filter Bar -->
      <div class="filter-bar">
        <el-form :inline="true">
          <el-form-item label="状态 (status)">
            <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 140px;" @change="handleFilterChange">
              <el-option label="BACKLOG" value="BACKLOG" />
              <el-option label="TODO" value="TODO" />
              <el-option label="IN_PROGRESS" value="IN_PROGRESS" />
              <el-option label="IN_REVIEW" value="IN_REVIEW" />
              <el-option label="DONE" value="DONE" />
              <el-option label="CANCELED" value="CANCELED" />
            </el-select>
          </el-form-item>

          <el-form-item label="优先级 (priority)">
            <el-select v-model="filter.priority" placeholder="全部优先级" clearable style="width: 130px;" @change="handleFilterChange">
              <el-option label="LOW" value="LOW" />
              <el-option label="MEDIUM" value="MEDIUM" />
              <el-option label="HIGH" value="HIGH" />
              <el-option label="URGENT" value="URGENT" />
            </el-select>
          </el-form-item>

          <el-form-item label="负责人 User ID">
            <el-input-number v-model="filter.assigneeUserId" :min="1" placeholder="ID" style="width: 120px;" controls-position="right" @change="handleFilterChange" />
          </el-form-item>

          <el-form-item label="创建者 User ID">
            <el-input-number v-model="filter.reporterUserId" :min="1" placeholder="ID" style="width: 120px;" controls-position="right" @change="handleFilterChange" />
          </el-form-item>

          <el-form-item label="截止之前 (dueBefore)">
            <el-date-picker
              v-model="filter.dueBefore"
              type="datetime"
              placeholder="选择时间"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 180px;"
              @change="handleFilterChange"
            />
          </el-form-item>
        </el-form>
      </div>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" :empty="items.length === 0" @retry="fetchData">
        <template #empty-action>
          <el-button type="primary" @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/tasks/new`)">
            创建首个 Task
          </el-button>
        </template>

        <el-table :data="items" stripe style="width: 100%;">
          <el-table-column prop="displayKey" label="Key #" width="110">
            <template #default="{ row }">
              <el-tag type="info" effect="plain">
                <code>{{ row.displayKey }}</code>
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="title" label="任务标题 (Title)" min-width="200">
            <template #default="{ row }">
              <router-link
                :to="`/workspaces/${workspaceId}/projects/${projectId}/tasks/${row.id}`"
                style="color: #409eff; font-weight: 500; text-decoration: none;"
              >
                {{ row.title }}
              </router-link>
            </template>
          </el-table-column>

          <el-table-column prop="status" label="状态" width="120">
            <template #default="{ row }">
              <StatusBadge :status="row.status" type="task" />
            </template>
          </el-table-column>

          <el-table-column prop="priority" label="优先级" width="110">
            <template #default="{ row }">
              <StatusBadge :status="row.priority" type="priority" />
            </template>
          </el-table-column>

          <el-table-column prop="assigneeUserId" label="负责人" width="120">
            <template #default="{ row }">
              <span v-if="row.assigneeUserId">User #{{ row.assigneeUserId }}</span>
              <span v-else class="text-muted">未分配</span>
            </template>
          </el-table-column>

          <el-table-column prop="dueAt" label="截止日期" min-width="150">
            <template #default="{ row }">
              {{ row.dueAt || '无' }}
            </template>
          </el-table-column>

          <el-table-column prop="version" label="Version" width="90">
            <template #default="{ row }">
              <code>v{{ row.version }}</code>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button
                type="primary"
                link
                size="small"
                @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/tasks/${row.id}`)"
              >
                查看/办理
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

        <RawJsonPanel :data="rawJson" title="GET .../tasks 原始响应" />
      </PageState>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { listTasksApi } from '@/api/modules/task'
import type { TaskResponse, TaskStatus, TaskPriority } from '@/types/task'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const route = useRoute()
const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')

const filter = reactive({
  status: '' as TaskStatus | '',
  priority: '' as TaskPriority | '',
  assigneeUserId: undefined as number | undefined,
  reporterUserId: undefined as number | undefined,
  dueBefore: undefined as string | undefined,
})

const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<TaskResponse[]>([])
const rawJson = ref<any>(null)

async function fetchData() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await listTasksApi(workspaceId, projectId, {
      page: page.value,
      size: size.value,
      status: (filter.status || undefined) as TaskStatus,
      priority: (filter.priority || undefined) as TaskPriority,
      assigneeUserId: filter.assigneeUserId || undefined,
      reporterUserId: filter.reporterUserId || undefined,
      dueBefore: filter.dueBefore || undefined,
    })
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      items.value = res.data.items || []
      total.value = res.data.total || 0
    } else {
      hasError.value = true
      errorMsg.value = res.message || '获取 Task 列表失败'
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

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.task-list-container {
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
.text-muted {
  color: #909399;
}
.pagination-bar {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
