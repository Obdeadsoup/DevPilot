import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

import MainLayout from '@/layouts/MainLayout.vue'
import LoginView from '@/views/LoginView.vue'
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

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: LoginView,
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
        path: 'developer-console',
        name: 'DeveloperConsole',
        component: DeveloperConsoleView,
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, _from, next) => {
  const authStore = useAuthStore()

  if (!authStore.accessToken && sessionStorage.getItem('devpilot_access_token')) {
    authStore.restoreSession()
  }

  const isPublic = to.meta.public === true

  if (!isPublic && !authStore.isAuthenticated) {
    next({ path: '/login', query: { returnUrl: to.fullPath } })
  } else {
    next()
  }
})

export default router
