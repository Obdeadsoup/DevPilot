<template>
  <el-drawer
    v-model="notificationStore.drawerVisible"
    title="消息通知 (Notifications)"
    size="520px"
    direction="rtl"
  >
    <div class="drawer-header-meta">
      <div class="stream-status">
        <span>SSE 实时推送: </span>
        <el-tag :type="streamTagType" size="small">
          {{ notificationStore.streamState }}
        </el-tag>
      </div>
      <div>
        <el-button
          type="primary"
          link
          size="small"
          :disabled="notificationStore.unreadCount === 0"
          @click="handleMarkAllRead"
        >
          全部标为已读
        </el-button>
        <el-button link size="small" @click="$router.push('/notifications')">
          查看完整通知页
        </el-button>
      </div>
    </div>

    <el-divider style="margin: 12px 0;" />

    <PageState :loading="notificationStore.loading" :empty="notificationStore.items.length === 0">
      <template #empty-action>
        <span class="text-muted">暂无未读消息通知</span>
      </template>

      <div class="notification-list">
        <el-card
          v-for="item in notificationStore.items"
          :key="item.id"
          shadow="hover"
          class="notification-card"
          :class="{ 'is-read': item.status === 'READ' }"
        >
          <div class="notif-header">
            <el-tag size="small" :type="item.status === 'UNREAD' ? 'danger' : 'info'">
              {{ item.type }}
            </el-tag>
            <span class="notif-time">{{ item.occurredAt }}</span>
          </div>

          <div class="notif-title">{{ item.title }}</div>
          <div class="notif-content">{{ item.content }}</div>

          <div class="notif-footer">
            <el-button type="primary" link size="small" @click="navigateToTarget(item)">
              查看关联{{ item.targetType }}
            </el-button>
            <el-button
              v-if="item.status === 'UNREAD'"
              type="info"
              link
              size="small"
              @click="handleMarkRead(item)"
            >
              标为已读
            </el-button>
          </div>
        </el-card>
      </div>
    </PageState>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useNotificationStore } from '@/stores/notification'
import type { NotificationResponse } from '@/types/notification'
import PageState from '@/components/PageState.vue'

const router = useRouter()
const notificationStore = useNotificationStore()

const streamTagType = computed(() => {
  switch (notificationStore.streamState) {
    case 'open':
      return 'success'
    case 'connecting':
      return 'warning'
    case 'retrying':
      return 'danger'
    default:
      return 'info'
  }
})

async function handleMarkRead(item: NotificationResponse) {
  const res = await notificationStore.markRead(item.id, item.version)
  if (res.success) {
    ElMessage.success('已标记为已读')
  } else if (res.httpStatus === 409) {
    ElMessage.warning('版本已变化，已自动刷新')
    notificationStore.fetchNotifications('UNREAD', 1, 20)
  } else {
    ElMessage.error(res.message || '标记已读失败')
  }
}

async function handleMarkAllRead() {
  const res = await notificationStore.markAllRead()
  if (res.success) {
    ElMessage.success(`已将所有未读通知标为已读`)
  } else {
    ElMessage.error(res.message || '操作失败')
  }
}

function navigateToTarget(item: NotificationResponse) {
  notificationStore.drawerVisible = false
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
</script>

<style scoped>
.drawer-header-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
}
.stream-status {
  display: flex;
  align-items: center;
  gap: 6px;
}
.notification-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.notification-card {
  border-radius: 6px;
}
.notification-card.is-read {
  opacity: 0.7;
}
.notif-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.notif-time {
  font-size: 12px;
  color: #909399;
}
.notif-title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
  margin-bottom: 4px;
}
.notif-content {
  font-size: 13px;
  color: #606266;
  white-space: pre-wrap;
  margin-bottom: 8px;
}
.notif-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
  border-top: 1px dashed #ebeef5;
  padding-top: 6px;
}
.text-muted {
  color: #909399;
}
</style>
