<template>
  <el-container class="app-shell">
    <el-aside v-if="!isCompact" width="248px" class="app-sidebar">
      <button class="brand" type="button" @click="router.push('/workspaces')">
        <span class="brand__mark">DP</span>
        <span>
          <strong>DevPilot</strong>
          <small>项目协作平台</small>
        </span>
      </button>
      <AppNavigation />
      <div v-if="scopeStore.currentProjectId" class="scope-summary">
        <span>当前项目</span>
        <strong>{{ scopeStore.currentProjectKey }}</strong>
        <small>{{ scopeStore.currentProjectName }}</small>
      </div>
    </el-aside>

    <el-drawer v-model="mobileNavigationOpen" direction="ltr" size="min(84vw, 304px)" :with-header="false" class="mobile-navigation">
      <button class="brand" type="button" @click="navigateFromDrawer('/workspaces')">
        <span class="brand__mark">DP</span>
        <span><strong>DevPilot</strong><small>项目协作平台</small></span>
      </button>
      <AppNavigation @navigate="mobileNavigationOpen = false" />
    </el-drawer>

    <el-container class="app-stage">
      <el-header class="topbar">
        <div class="topbar__context">
          <el-button v-if="isCompact" text circle aria-label="打开导航" @click="mobileNavigationOpen = true">
            <el-icon :size="20"><Menu /></el-icon>
          </el-button>
          <el-breadcrumb separator="/" class="breadcrumbs">
            <el-breadcrumb-item :to="{ path: '/workspaces' }">工作区</el-breadcrumb-item>
            <el-breadcrumb-item v-if="scopeStore.currentWorkspaceId" :to="{ path: `/workspaces/${scopeStore.currentWorkspaceId}` }">
              {{ scopeStore.currentWorkspaceName }}
            </el-breadcrumb-item>
            <el-breadcrumb-item v-if="scopeStore.currentProjectId" :to="{ path: projectOverviewPath }">
              {{ scopeStore.currentProjectKey }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="topbar__actions">
          <el-tooltip content="通知" placement="bottom">
            <el-badge :value="notificationStore.unreadCount" :hidden="notificationStore.unreadCount === 0" :max="99">
              <el-button text circle aria-label="打开通知" @click="notificationStore.toggleDrawer">
                <el-icon :size="18"><Bell /></el-icon>
              </el-button>
            </el-badge>
          </el-tooltip>

          <el-dropdown v-if="authStore.user" @command="handleUserCommand">
            <button class="user-menu" type="button" aria-label="打开账号菜单">
              <el-avatar :size="30" class="user-menu__avatar">{{ userInitial }}</el-avatar>
              <span v-if="!isNarrow" class="user-menu__label">{{ authStore.user.displayName }}</span>
              <el-icon><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人资料</el-dropdown-item>
                <el-dropdown-item command="notifications">通知中心</el-dropdown-item>
                <el-dropdown-item divided command="developer">开发者工具</el-dropdown-item>
                <el-dropdown-item command="health">系统诊断 · {{ healthStatus }}</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button v-else type="primary" @click="router.push('/login')">登录</el-button>
        </div>
      </el-header>

      <el-main class="main-content">
        <router-view :key="route.fullPath" />
      </el-main>
    </el-container>

    <el-drawer v-model="devConsoleStore.drawerVisible" title="Developer console" size="min(92vw, 680px)" direction="rtl">
      <DeveloperConsoleView embedded />
    </el-drawer>
    <NotificationDrawer />
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Bell, Menu } from '@element-plus/icons-vue'

import { getHealthApi } from '@/api/modules/health'
import { logoutApi } from '@/api/modules/auth'
import AppNavigation from '@/components/AppNavigation.vue'
import NotificationDrawer from '@/components/notification/NotificationDrawer.vue'
import { notificationStreamService } from '@/services/notificationStream'
import { useAuthStore } from '@/stores/auth'
import { useDeveloperConsoleStore } from '@/stores/developerConsole'
import { useNotificationStore } from '@/stores/notification'
import { useScopeStore } from '@/stores/scope'
import DeveloperConsoleView from '@/views/DeveloperConsoleView.vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const scopeStore = useScopeStore()
const devConsoleStore = useDeveloperConsoleStore()
const notificationStore = useNotificationStore()
const mobileNavigationOpen = ref(false)
const viewportWidth = ref(window.innerWidth)
const healthStatus = ref('检查中')

const isCompact = computed(() => viewportWidth.value < 920)
const isNarrow = computed(() => viewportWidth.value < 560)
const projectOverviewPath = computed(() =>
  `/workspaces/${scopeStore.currentWorkspaceId}/projects/${scopeStore.currentProjectId}/overview`)
const userInitial = computed(() => authStore.user?.displayName?.trim().charAt(0).toUpperCase() || 'U')

function updateViewport() {
  viewportWidth.value = window.innerWidth
  if (!isCompact.value) mobileNavigationOpen.value = false
}

onMounted(async () => {
  window.addEventListener('resize', updateViewport)
  const health = await getHealthApi()
  healthStatus.value = health.success ? '正常' : '异常'
  if (authStore.isAuthenticated) {
    void notificationStore.fetchUnreadCount()
    notificationStreamService.connect()
  }
})

onUnmounted(() => {
  window.removeEventListener('resize', updateViewport)
  notificationStreamService.disconnect()
})

function navigateFromDrawer(path: string) {
  mobileNavigationOpen.value = false
  void router.push(path)
}

async function handleUserCommand(command: string) {
  if (command === 'profile') return router.push('/me')
  if (command === 'notifications') return router.push('/notifications')
  if (command === 'developer') return devConsoleStore.toggleDrawer()
  if (command === 'health') return router.push('/health')
  if (command !== 'logout') return
  try {
    await logoutApi()
  } finally {
    notificationStreamService.disconnect()
    notificationStore.clearNotifications()
    authStore.clearAuth()
    scopeStore.clearAll()
    await router.push('/login')
  }
}
</script>

<style scoped>
.app-shell { min-height: 100vh; background: var(--color-canvas); }
.app-stage { min-width: 0; min-height: 100vh; }

.app-sidebar {
  position: relative;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  background: var(--color-sidebar);
  color: #fff;
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  min-height: 70px;
  padding: var(--space-4) var(--space-5);
  color: inherit;
  background: transparent;
  border: 0;
  text-align: left;
  cursor: pointer;
}

.brand__mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  border: 1px solid rgba(255, 255, 255, 0.28);
  border-radius: var(--radius-sm);
  color: #fff;
  background: var(--color-accent);
  font: 700 12px/1 var(--font-mono);
  letter-spacing: 0.04em;
}

.brand strong, .brand small { display: block; }
.brand strong { font-size: 15px; letter-spacing: 0.01em; }
.brand small { margin-top: 2px; color: var(--color-sidebar-muted); font-size: 11px; }

.scope-summary {
  margin: auto var(--space-4) var(--space-4);
  padding: var(--space-4);
  border-top: 1px solid rgba(255, 255, 255, 0.11);
}

.scope-summary span, .scope-summary strong, .scope-summary small { display: block; }
.scope-summary span { color: #647086; font: 700 10px/1 var(--font-mono); letter-spacing: 0.13em; }
.scope-summary strong { margin-top: var(--space-3); font-family: var(--font-mono); }
.scope-summary small { margin-top: var(--space-1); color: var(--color-sidebar-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.topbar {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: 0 var(--space-6);
  background: color-mix(in srgb, var(--color-surface) 96%, transparent);
  border-bottom: 1px solid var(--color-border);
}

.topbar__context, .topbar__actions, .user-menu { display: flex; align-items: center; }
.topbar__context { min-width: 0; gap: var(--space-2); }
.topbar__actions { gap: var(--space-3); }
.breadcrumbs { min-width: 0; }

.user-menu {
  gap: var(--space-2);
  min-height: 40px;
  padding: var(--space-1) var(--space-2);
  color: var(--color-text);
  background: transparent;
  border: 0;
  border-radius: var(--radius-sm);
  cursor: pointer;
}
.user-menu:hover { background: var(--color-surface-subtle); }
.user-menu__avatar { color: #fff; background: #334155; font-weight: 700; }
.user-menu__label { max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--font-size-sm); }

.main-content { min-width: 0; padding: var(--space-6); overflow-x: hidden; }

.mobile-navigation :deep(.el-drawer__body) { padding: 0; background: var(--color-sidebar); color: #fff; }

@media (max-width: 720px) {
  .topbar { height: 58px; padding-inline: var(--space-3); }
  .breadcrumbs :deep(.el-breadcrumb__item:not(:last-child)) { display: none; }
  .main-content { padding: var(--space-3); }
}
</style>
