<template>
  <div class="task-list-container">
    <el-card>
      <template #header>
        <PageHeader title="任务" description="聚焦当前项目的优先级、负责人和交付状态。">
          <template #actions>
            <el-button type="primary" @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/tasks/new`)">
              创建任务
            </el-button>
            <el-button @click="fetchData">刷新</el-button>
          </template>
        </PageHeader>
      </template>

      <!-- Filter Bar -->
      <div class="filter-bar">
        <el-form class="filter-form" label-position="top">
          <el-form-item label="状态">
            <el-select v-model="filter.status" placeholder="全部状态" clearable style="width: 140px;" @change="handleFilterChange">
              <el-option label="待规划" value="BACKLOG" />
              <el-option label="待处理" value="TODO" />
              <el-option label="进行中" value="IN_PROGRESS" />
              <el-option label="审核中" value="IN_REVIEW" />
              <el-option label="已完成" value="DONE" />
              <el-option label="已取消" value="CANCELED" />
            </el-select>
          </el-form-item>

          <el-form-item label="优先级">
            <el-select v-model="filter.priority" placeholder="全部优先级" clearable style="width: 130px;" @change="handleFilterChange">
              <el-option label="低" value="LOW" />
              <el-option label="中" value="MEDIUM" />
              <el-option label="高" value="HIGH" />
              <el-option label="紧急" value="URGENT" />
            </el-select>
          </el-form-item>

          <el-form-item label="负责人编号">
            <el-input-number v-model="filter.assigneeUserId" :min="1" placeholder="成员编号" style="width: 120px;" controls-position="right" @change="handleFilterChange" />
          </el-form-item>

          <el-form-item label="创建者编号">
            <el-input-number v-model="filter.reporterUserId" :min="1" placeholder="成员编号" style="width: 120px;" controls-position="right" @change="handleFilterChange" />
          </el-form-item>

          <el-form-item label="截止时间">
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
            创建首个任务
          </el-button>
        </template>

        <el-table :data="items" stripe style="width: 100%;">
          <el-table-column prop="displayKey" label="任务编号" width="120">
            <template #default="{ row }">
              <el-tag type="info" effect="plain">
                <code>{{ row.displayKey }}</code>
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="title" label="任务标题" min-width="200">
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
              <span v-if="row.assigneeUserId">成员 #{{ row.assigneeUserId }}</span>
              <span v-else class="text-muted">未分配</span>
            </template>
          </el-table-column>

          <el-table-column prop="dueAt" label="截止日期" min-width="150">
            <template #default="{ row }">
              {{ row.dueAt || '无' }}
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
import PageHeader from '@/components/PageHeader.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

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
    if (res.success && res.data) {
      items.value = res.data.items || []
      total.value = res.data.total || 0
    } else {
      hasError.value = true
      errorMsg.value = productErrorMessage(res, '暂时无法加载任务，请稍后重试。')
    }
  } catch (err: unknown) {
    hasError.value = true
    errorMsg.value = unexpectedErrorMessage(err, '暂时无法加载任务，请稍后重试。')
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
.filter-bar {
  margin-bottom: 16px;
  padding: 12px;
  background-color: #fafafa;
  border-radius: 6px;
}
.filter-form { display: grid; grid-template-columns: repeat(5, minmax(120px, 1fr)); gap: 0 var(--space-4); }
.filter-form :deep(.el-form-item) { margin-bottom: 0; }
.filter-form :deep(.el-select), .filter-form :deep(.el-input-number), .filter-form :deep(.el-date-editor) { width: 100% !important; }
.text-muted {
  color: #909399;
}
.pagination-bar {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
@media (max-width: 1000px) { .filter-form { grid-template-columns: repeat(2, minmax(140px, 1fr)); row-gap: var(--space-3); } }
@media (max-width: 560px) { .filter-form { grid-template-columns: 1fr; } }
</style>
