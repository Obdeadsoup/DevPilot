<template>
  <div class="notification-page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <h2>消息通知列表 (GET /api/v1/notifications)</h2>
            <span class="sub-text">实时 SSE 状态: {{ notificationStore.streamState }}</span>
          </div>
          <div>
            <el-button
              type="primary"
              :disabled="notificationStore.unreadCount === 0"
              @click="handleMarkAllRead"
            >
              一键全部标为已读 (POST .../read-all)
            </el-button>
            <el-button @click="fetchData">刷新</el-button>
          </div>
        </div>
      </template>

      <!-- Filter Controls -->
      <div class="filter-bar">
        <el-form :inline="true">
          <el-form-item label="状态 (status)">
            <el-select v-model="statusFilter" placeholder="全部" clearable style="width: 140px;" @change="handleFilterChange">
              <el-option label="UNREAD (未读)" value="UNREAD" />
              <el-option label="READ (已读)" value="READ" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" :empty="items.length === 0" @retry="fetchData">
        <el-table :data="items" stripe style="width: 100%;">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'UNREAD' ? 'danger' : 'info'" size="small">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="type" label="通知类型" min-width="180">
            <template #default="{ row }">
              <el-tag size="small" type="info">{{ row.type }}</el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="title" label="标题" min-width="180" />

          <el-table-column prop="content" label="正文内容" min-width="240">
            <template #default="{ row }">
              <span class="content-text">{{ row.content }}</span>
            </template>
          </el-table-column>

          <el-table-column prop="occurredAt" label="触发时间" min-width="160" />

          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="navigateToTarget(row)">
                查看 {{ row.targetType }}
              </el-button>
              <el-button
                v-if="row.status === 'UNREAD'"
                type="info"
                link
                size="small"
                @click="handleMarkRead(row)"
              >
                标为已读
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

        <RawJsonPanel :data="rawJson" title="GET /api/v1/notifications 原始响应" />
      </PageState>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listNotificationsApi } from '@/api/modules/notification'
import { useNotificationStore } from '@/stores/notification'
import type { NotificationResponse, NotificationStatus } from '@/types/notification'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const router = useRouter()
const notificationStore = useNotificationStore()

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')

const statusFilter = ref<NotificationStatus | ''>('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<NotificationResponse[]>([])
const rawJson = ref<any>(null)

async function fetchData() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await listNotificationsApi(
      (statusFilter.value || undefined) as NotificationStatus,
      page.value,
      size.value
    )
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      items.value = res.data.items || []
      total.value = res.data.total || 0
    } else {
      hasError.value = true
      errorMsg.value = res.message || '获取通知列表失败'
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

async function handleMarkRead(item: NotificationResponse) {
  const res = await notificationStore.markRead(item.id, item.version)
  if (res.success) {
    ElMessage.success('已标记为已读')
    item.status = 'READ'
  } else {
    ElMessage.error(res.message || '标记失败')
  }
}

async function handleMarkAllRead() {
  const res = await notificationStore.markAllRead()
  if (res.success) {
    ElMessage.success('所有未读通知已标为已读')
    fetchData()
  } else {
    ElMessage.error(res.message || '操作失败')
  }
}

function navigateToTarget(item: NotificationResponse) {
  const w = item.workspaceId
  const p = item.projectId

  if (item.targetType === 'TASK') {
    router.push(`/workspaces/${w}/projects/${p}/tasks/${item.targetId}`)
  } else if (item.targetType === 'PULL_REQUEST') {
    router.push(`/workspaces/${w}/projects/${p}/github/pull-requests/${item.targetId}`)
  } else if (item.targetType === 'PROJECT') {
    router.push(`/workspaces/${w}/projects/${p}/overview`)
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.notification-page-container {
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
.content-text {
  font-size: 13px;
  color: #606266;
}
.pagination-bar {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
