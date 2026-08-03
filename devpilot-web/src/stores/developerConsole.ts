import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { RequestAuditLog } from '@/types/api'

export const useDeveloperConsoleStore = defineStore('developerConsole', () => {
  const logs = ref<RequestAuditLog[]>([])
  const drawerVisible = ref<boolean>(false)

  function addLog(log: RequestAuditLog) {
    logs.value.unshift(log)
    if (logs.value.length > 20) {
      logs.value.pop()
    }
  }

  function clearLogs() {
    logs.value = []
  }

  function toggleDrawer() {
    drawerVisible.value = !drawerVisible.value
  }

  return {
    logs,
    drawerVisible,
    addLog,
    clearLogs,
    toggleDrawer,
  }
})
