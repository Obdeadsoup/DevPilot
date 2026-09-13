import type { NavigationGuardReturn, RouteLocationNormalized } from 'vue-router'

import { getProjectApi } from '@/api/modules/project'
import { getWorkspaceApi } from '@/api/modules/workspace'
import { useScopeStore } from '@/stores/scope'
import type { ApiResult } from '@/types/api'

function positiveRouteId(value: unknown): number | null {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : null
}

function scopeFailure(result: ApiResult<unknown>, from: string): NavigationGuardReturn {
  if (result.httpStatus === 403) {
    return { name: 'Forbidden', query: { from }, replace: true }
  }
  if (result.httpStatus === 404) {
    return { name: 'ResourceNotFound', query: { from }, replace: true }
  }
  return { name: 'ScopeUnavailable', query: { from }, replace: true }
}

/**
 * Route params own scope identity. Pinia only caches display metadata for navigation chrome.
 * A route never renders with guessed IDs or a scope inherited from a previous URL.
 */
export async function resolveRouteScope(to: RouteLocationNormalized): Promise<NavigationGuardReturn> {
  const scopeStore = useScopeStore()
  const rawWorkspaceId = to.params.workspaceId
  const rawProjectId = to.params.projectId

  if (rawWorkspaceId === undefined) {
    scopeStore.clearAll()
    return true
  }

  const workspaceId = positiveRouteId(rawWorkspaceId)
  if (workspaceId === null) {
    scopeStore.clearAll()
    return { name: 'NotFound', params: { pathMatch: to.path.slice(1).split('/') }, replace: true }
  }

  if (scopeStore.currentWorkspaceId !== workspaceId || !scopeStore.currentWorkspaceName) {
    const workspace = await getWorkspaceApi(workspaceId)
    if (!workspace.success || !workspace.data) {
      scopeStore.clearAll()
      return scopeFailure(workspace, to.fullPath)
    }
    scopeStore.setWorkspace(workspace.data.id, workspace.data.name)
  }

  if (rawProjectId === undefined) {
    scopeStore.clearProject()
    return true
  }

  const projectId = positiveRouteId(rawProjectId)
  if (projectId === null) {
    scopeStore.clearProject()
    return { name: 'NotFound', params: { pathMatch: to.path.slice(1).split('/') }, replace: true }
  }

  if (scopeStore.currentProjectId !== projectId || !scopeStore.currentProjectName) {
    const project = await getProjectApi(workspaceId, projectId)
    if (!project.success || !project.data) {
      scopeStore.clearProject()
      return scopeFailure(project, to.fullPath)
    }
    scopeStore.setProject(project.data.id, project.data.projectKey, project.data.name)
  }

  return true
}
