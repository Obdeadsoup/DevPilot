<template>
  <el-container class="layout-container">
    <!-- Top Header -->
    <el-header class="top-header">
      <div class="header-left">
        <span class="app-title">DevPilot Console</span>
        <el-divider direction="vertical" />
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/workspaces' }">Workspaces</el-breadcrumb-item>
          <el-breadcrumb-item v-if="scopeStore.currentWorkspaceId">
            <router-link :to="`/workspaces/${scopeStore.currentWorkspaceId}`">
              {{ scopeStore.currentWorkspaceName || `Workspace #${scopeStore.currentWorkspaceId}` }}
            </router-link>
          </el-breadcrumb-item>
          <el-breadcrumb-item v-if="scopeStore.currentProjectId">
            <router-link :to="`/workspaces/${scopeStore.currentWorkspaceId}/projects/${scopeStore.currentProjectId}/overview`">
              {{ scopeStore.currentProjectKey || `Project #${scopeStore.currentProjectId}` }}
            </router-link>
          </el-breadcrumb-item>
        </el-breadcrumb>
      </div>

      <div class="header-right">
        <!-- Health Indicator -->
        <el-tag
          :type="healthStatus === 'UP' ? 'success' : 'danger'"
          size="small"
          effect="dark"
          style="cursor: pointer;"
          @click="$router.push('/health')"
        >
          Backend: {{ healthStatus }}
        </el-tag>

        <!-- Notification Bell -->
        <el-badge
          :value="notificationStore.unreadCount"
          :hidden="notificationStore.unreadCount === 0"
          :max="99"
          class="bell-badge"
        >
          <el-button type="info" size="small" circle @click="notificationStore.toggleDrawer">
            <el-icon><Bell /></el-icon>
          </el-button>
        </el-badge>

        <!-- Developer Console Toggle -->
        <el-button type="info" size="small" link @click="devConsoleStore.toggleDrawer">
          开发者控制台 ({{ devConsoleStore.logs.length }})
        </el-button>

        <el-divider direction="vertical" />

        <!-- User Dropdown / Me -->
        <template v-if="authStore.user">
          <el-dropdown @command="handleUserCommand">
            <span class="user-info">
              <el-avatar :size="24" class="avatar">{{ authStore.user.displayName[0] || 'U' }}</el-avatar>
              <span>{{ authStore.user.displayName }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">当前用户详情</el-dropdown-item>
                <el-dropdown-item command="notifications">消息通知中心</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <template v-else>
          <el-button type="primary" size="small" @click="$router.push('/login')">登录</el-button>
        </template>
      </div>
    </el-header>

    <el-container class="body-container">
      <!-- Left Sidebar Navigation -->
      <el-aside width="220px" class="sidebar">
        <el-menu
          :default-active="$route.path"
          router
          class="sidebar-menu"
        >
          <el-menu-item index="/workspaces">
            <el-icon><MenuIcon /></el-icon>
            <span>Workspace 列表</span>
          </el-menu-item>

          <template v-if="scopeStore.currentWorkspaceId">
            <el-menu-item :index="`/workspaces/${scopeStore.currentWorkspaceId}/projects`">
              <el-icon><Folder /></el-icon>
              <span>项目列表</span>
            </el-menu-item>
            <el-menu-item :index="`/workspaces/${scopeStore.currentWorkspaceId}/audit-logs`">
              <el-icon><Postcard /></el-icon>
              <span>审计日志 (Audit)</span>
            </el-menu-item>
          </template>

          <template v-if="scopeStore.currentWorkspaceId && scopeStore.currentProjectId">
            <el-menu-item-group title="当前项目 (Project)">
              <el-menu-item :index="`/workspaces/${scopeStore.currentWorkspaceId}/projects/${scopeStore.currentProjectId}/overview`">
                <el-icon><InfoFilled /></el-icon>
                <span>项目概览</span>
              </el-menu-item>
              <el-menu-item :index="`/workspaces/${scopeStore.currentWorkspaceId}/projects/${scopeStore.currentProjectId}/tasks`">
                <el-icon><Checked /></el-icon>
                <span>Task 任务管理</span>
              </el-menu-item>
              <el-menu-item :index="`/workspaces/${scopeStore.currentWorkspaceId}/projects/${scopeStore.currentProjectId}/repositories`">
                <el-icon><Connection /></el-icon>
                <span>GitHub 仓库绑定</span>
              </el-menu-item>
              <el-menu-item :index="`/workspaces/${scopeStore.currentWorkspaceId}/projects/${scopeStore.currentProjectId}/activities`">
                <el-icon><List /></el-icon>
                <span>Activity 时间线</span>
              </el-menu-item>

              <el-sub-menu index="github-snapshots">
                <template #title>
                  <el-icon><Document /></el-icon>
                  <span>GitHub 快照</span>
                </template>
                <el-menu-item :index="`/workspaces/${scopeStore.currentWorkspaceId}/projects/${scopeStore.currentProjectId}/github/issues`">
                  <span>Issue 列表</span>
                </el-menu-item>
                <el-menu-item :index="`/workspaces/${scopeStore.currentWorkspaceId}/projects/${scopeStore.currentProjectId}/github/pull-requests`">
                  <span>Pull Request 列表</span>
                </el-menu-item>
              </el-sub-menu>

              <el-menu-item :index="`/workspaces/${scopeStore.currentWorkspaceId}/projects/${scopeStore.currentProjectId}/operations`">
                <el-icon><Tools /></el-icon>
                <span>DEAD 运维与 Replay</span>
              </el-menu-item>
            </el-menu-item-group>
          </template>

          <el-divider style="margin: 12px 0;" />

          <el-menu-item index="/notifications">
            <el-icon><Bell /></el-icon>
            <span>消息通知中心</span>
          </el-menu-item>
          <el-menu-item index="/developer-console">
            <el-icon><Monitor /></el-icon>
            <span>开发者控制台</span>
          </el-menu-item>
          <el-menu-item index="/health">
            <el-icon><Cpu /></el-icon>
            <span>后端 Health 检查</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <!-- Main Content Area -->
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>

    <!-- Developer Console Drawer -->
    <el-drawer
      v-model="devConsoleStore.drawerVisible"
      title="开发者联调控制台 (Developer Inspector)"
      size="650px"
      direction="rtl"
    >
      <DeveloperConsoleView embedded />
    </el-drawer>

    <!-- Notification Drawer -->
    <NotificationDrawer />
  </el-container>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useScopeStore } from '@/stores/scope'
import { useDeveloperConsoleStore } from '@/stores/developerConsole'
import { useNotificationStore } from '@/stores/notification'
import { notificationStreamService } from '@/services/notificationStream'
import { getHealthApi } from '@/api/modules/health'
import { logoutApi } from '@/api/modules/auth'
import DeveloperConsoleView from '@/views/DeveloperConsoleView.vue'
import NotificationDrawer from '@/components/notification/NotificationDrawer.vue'

import {
  Menu as MenuIcon,
  Folder,
  InfoFilled,
  Checked,
  Connection,
  List,
  Document,
  Tools,
  Postcard,
  Bell,
  Monitor,
  Cpu,
} from '@element-plus/icons-vue'

const router = useRouter()
const authStore = useAuthStore()
const scopeStore = useScopeStore()
const devConsoleStore = useDeveloperConsoleStore()
const notificationStore = useNotificationStore()

const healthStatus = ref<string>('UNKNOWN')

onMounted(async () => {
  try {
    const res = await getHealthApi()
    healthStatus.value = res.code || (res.success ? 'UP' : 'DOWN')
  } catch {
    healthStatus.value = 'DOWN'
  }

  if (authStore.isAuthenticated) {
    notificationStore.fetchUnreadCount()
    notificationStreamService.connect()
  }
})

onUnmounted(() => {
  notificationStreamService.disconnect()
})

async function handleUserCommand(command: string) {
  if (command === 'profile') {
    router.push('/me')
  } else if (command === 'notifications') {
    router.push('/notifications')
  } else if (command === 'logout') {
    try {
      await logoutApi()
    } finally {
      notificationStreamService.disconnect()
      notificationStore.clearNotifications()
      authStore.clearAuth()
      scopeStore.clearAll()
      router.push('/login')
    }
  }
}
</script>

<style scoped>
.layout-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
}
.top-header {
  height: 56px;
  background-color: #ffffff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.app-title {
  font-weight: 700;
  font-size: 16px;
  color: #303133;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.bell-badge {
  display: flex;
  align-items: center;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: 14px;
}
.avatar {
  background-color: #409eff;
  color: #fff;
  font-weight: 600;
}
.body-container {
  flex: 1;
  overflow: hidden;
}
.sidebar {
  background-color: #f8f9fa;
  border-right: 1px solid #e4e7ed;
}
.sidebar-menu {
  border-right: none;
  background-color: transparent;
}
.main-content {
  background-color: #f5f7fa;
  padding: 20px;
  overflow-y: auto;
}
</style>
