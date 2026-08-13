import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  listNotificationsApi,
  getUnreadNotificationCountApi,
  markNotificationReadApi,
  markAllNotificationsReadApi,
} from '@/api/modules/notification'
import type { NotificationResponse, NotificationStatus } from '@/types/notification'

export const useNotificationStore = defineStore('notification', () => {
  const unreadCount = ref<number>(0)
  const items = ref<NotificationResponse[]>([])
  const drawerVisible = ref<boolean>(false)
  const streamState = ref<'idle' | 'connecting' | 'open' | 'retrying' | 'closed'>('idle')
  const loading = ref<boolean>(false)

  function setUnreadCount(count: number) {
    unreadCount.value = Math.max(0, count)
  }

  function setStreamState(state: 'idle' | 'connecting' | 'open' | 'retrying' | 'closed') {
    streamState.value = state
  }

  function toggleDrawer() {
    drawerVisible.value = !drawerVisible.value
    if (drawerVisible.value) {
      fetchNotifications('UNREAD', 1, 20)
    }
  }

  async function fetchUnreadCount() {
    try {
      const res = await getUnreadNotificationCountApi()
      if (res.success && res.data) {
        setUnreadCount(res.data.count)
      }
    } catch {
      // Ignore error
    }
  }

  async function fetchNotifications(status?: NotificationStatus, page = 1, size = 20) {
    loading.value = true
    try {
      const res = await listNotificationsApi(status, page, size)
      if (res.success && res.data) {
        items.value = res.data.items || []
      }
    } finally {
      loading.value = false
    }
  }

  async function markRead(id: number, expectedVersion: number) {
    const res = await markNotificationReadApi(id, expectedVersion)
    if (res.success) {
      const target = items.value.find((item) => item.id === id)
      if (target) {
        target.status = 'READ'
      }
      fetchUnreadCount()
    }
    return res
  }

  async function markAllRead() {
    const res = await markAllNotificationsReadApi()
    if (res.success) {
      items.value.forEach((item) => {
        item.status = 'READ'
      })
      setUnreadCount(0)
    }
    return res
  }

  function onNotificationReceived(_data: any) {
    // If drawer or list is open, refresh first page
    if (drawerVisible.value) {
      fetchNotifications('UNREAD', 1, 20)
    }
  }

  function clearNotifications() {
    unreadCount.value = 0
    items.value = []
    streamState.value = 'closed'
  }

  return {
    unreadCount,
    items,
    drawerVisible,
    streamState,
    loading,
    setUnreadCount,
    setStreamState,
    toggleDrawer,
    fetchUnreadCount,
    fetchNotifications,
    markRead,
    markAllRead,
    onNotificationReceived,
    clearNotifications,
  }
})
