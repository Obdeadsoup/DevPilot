import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { resolveRouteScope } from '@/router/scopeResolver'

import MainLayout from '@/layouts/MainLayout.vue'
import LoginView from '@/views/LoginView.vue'
import RegisterView from '@/views/RegisterView.vue'
import HealthView from '@/views/HealthView.vue'

import WorkspaceList from '@/views/workspace/WorkspaceList.vue'
import WorkspaceCreate from '@/views/workspace/WorkspaceCreate.vue'
import WorkspaceDetail from '@/views/workspace/WorkspaceDetail.vue'

import ProjectList from '@/views/project/ProjectList.vue'
import ProjectCreate from '@/views/project/ProjectCreate.vue'
import ProjectDetail from '@/views/project/ProjectDetail.vue'

import RepositoryList from '@/views/repository/RepositoryList.vue'
import RepositoryCreate from '@/views/repository/RepositoryCreate.vue'
import RepositoryDetail from '@/views/repository/RepositoryDetail.vue'

import ActivityList from '@/views/activity/ActivityList.vue'
import IssueList from '@/views/snapshot/IssueList.vue'
import IssueDetail from '@/views/snapshot/IssueDetail.vue'

import PullRequestList from '@/views/snapshot/PullRequestList.vue'
import PullRequestDetail from '@/views/snapshot/PullRequestDetail.vue'

import SyncRunDetail from '@/views/sync/SyncRunDetail.vue'
import DeveloperConsoleView from '@/views/DeveloperConsoleView.vue'
import UserProfileView from '@/views/UserProfileView.vue'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: LoginView,
    meta: { public: true },
  },
  {
    path: '/register',
    name: 'Register',
    component: RegisterView,
    meta: { public: true },
  },
  {
    path: '/',
    component: MainLayout,
    children: [
      {
        path: '',
        redirect: '/workspaces',
      },
      {
        path: 'me',
        name: 'UserProfile',
        component: UserProfileView,
      },
      {
        path: 'health',
        name: 'Health',
        component: HealthView,
        meta: { public: true },
      },
      {
        path: 'notifications',
        name: 'NotificationList',
        component: () => import('@/views/notification/NotificationList.vue'),
      },
      {
        path: 'workspaces',
        name: 'WorkspaceList',
        component: WorkspaceList,
      },
      {
        path: 'workspaces/new',
        name: 'WorkspaceCreate',
        component: WorkspaceCreate,
      },
      {
        path: 'workspaces/:workspaceId',
        name: 'WorkspaceDetail',
        component: WorkspaceDetail,
      },
      {
        path: 'workspaces/:workspaceId/projects',
        name: 'ProjectList',
        component: ProjectList,
      },
      {
        path: 'workspaces/:workspaceId/projects/new',
        name: 'ProjectCreate',
        component: ProjectCreate,
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/overview',
        name: 'ProjectDetail',
        component: ProjectDetail,
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId',
        redirect: to => ({
          name: 'ProjectDetail',
          params: { workspaceId: to.params.workspaceId, projectId: to.params.projectId },
        }),
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/tasks',
        name: 'TaskList',
        component: () => import('@/views/task/TaskList.vue'),
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/tasks/new',
        name: 'TaskCreate',
        component: () => import('@/views/task/TaskCreate.vue'),
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/tasks/:taskId',
        name: 'TaskDetail',
        component: () => import('@/views/task/TaskDetail.vue'),
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/repositories',
        name: 'RepositoryList',
        component: RepositoryList,
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/repositories/new',
        name: 'RepositoryCreate',
        component: RepositoryCreate,
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/repositories/:bindingId',
        name: 'RepositoryDetail',
        component: RepositoryDetail,
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/activities',
        name: 'ActivityList',
        component: ActivityList,
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/github/issues',
        name: 'IssueList',
        component: IssueList,
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/github/issues/:issueId',
        name: 'IssueDetail',
        component: IssueDetail,
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/github/pull-requests',
        name: 'PullRequestList',
        component: PullRequestList,
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/github/pull-requests/:pullRequestId',
        name: 'PullRequestDetail',
        component: PullRequestDetail,
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/sync-runs/:bindingId/:runId',
        name: 'SyncRunDetail',
        component: SyncRunDetail,
      },
      {
        path: 'workspaces/:workspaceId/audit-logs',
        name: 'AuditLogList',
        component: () => import('@/views/audit/AuditLogList.vue'),
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/operations',
        name: 'OperationsView',
        component: () => import('@/views/operations/OperationsView.vue'),
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/agent',
        name: 'AgentRun',
        component: () => import('@/views/agent/AgentRunView.vue'),
      },
      {
        path: 'workspaces/:workspaceId/projects/:projectId/knowledge',
        name: 'ProjectKnowledge',
        component: () => import('@/views/knowledge/ProjectKnowledgeView.vue'),
      },
      {
        path: 'developer-console',
        name: 'DeveloperConsole',
        component: DeveloperConsoleView,
      },
      {
        path: 'forbidden',
        name: 'Forbidden',
        component: () => import('@/views/RouteProblemView.vue'),
        props: {
          statusCode: '403',
          title: '没有访问权限',
          description: '当前账号无权访问这个工作区或项目。请返回可用的工作区继续。',
        },
      },
      {
        path: 'resource-not-found',
        name: 'ResourceNotFound',
        component: () => import('@/views/RouteProblemView.vue'),
        props: {
          statusCode: '404',
          title: '资源不存在',
          description: '目标资源可能已被移除，或链接中的工作区、项目标识不正确。',
        },
      },
      {
        path: 'scope-unavailable',
        name: 'ScopeUnavailable',
        component: () => import('@/views/RouteProblemView.vue'),
        props: {
          statusCode: '暂时不可用',
          title: '无法加载项目上下文',
          description: '服务暂时没有返回工作区或项目信息。请稍后重试。',
        },
      },
      {
        path: ':pathMatch(.*)*',
        name: 'NotFound',
        component: () => import('@/views/RouteProblemView.vue'),
        props: {
          statusCode: '404',
          title: '页面不存在',
          description: '这个地址没有对应的 DevPilot 页面。',
        },
        meta: { public: true },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const authStore = useAuthStore()

  if (!authStore.restorationAttempted) {
    await authStore.restoreSession()
  }

  const isPublic = to.meta.public === true

  if (!isPublic && !authStore.isAuthenticated) {
    return { path: '/login', query: { returnUrl: to.fullPath } }
  }
  if (isPublic && authStore.isAuthenticated && (to.path === '/login' || to.path === '/register')) {
    return { path: '/workspaces' }
  }
  return resolveRouteScope(to)
})

export default router
