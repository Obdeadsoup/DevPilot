import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useScopeStore = defineStore('scope', () => {
  const currentWorkspaceId = ref<number | null>(null)
  const currentWorkspaceName = ref<string>('')
  const currentProjectId = ref<number | null>(null)
  const currentProjectKey = ref<string>('')
  const currentProjectName = ref<string>('')

  function setWorkspace(id: number, name: string) {
    currentWorkspaceId.value = id
    currentWorkspaceName.value = name
  }

  function setProject(id: number, key: string, name: string) {
    currentProjectId.value = id
    currentProjectKey.value = key
    currentProjectName.value = name
  }

  function clearProject() {
    currentProjectId.value = null
    currentProjectKey.value = ''
    currentProjectName.value = ''
  }

  function clearAll() {
    currentWorkspaceId.value = null
    currentWorkspaceName.value = ''
    clearProject()
  }

  return {
    currentWorkspaceId,
    currentWorkspaceName,
    currentProjectId,
    currentProjectKey,
    currentProjectName,
    setWorkspace,
    setProject,
    clearProject,
    clearAll,
  }
})
