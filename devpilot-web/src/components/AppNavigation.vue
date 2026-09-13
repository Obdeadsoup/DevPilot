<template>
  <el-menu :default-active="route.path" router class="app-navigation" @select="$emit('navigate')">
    <el-menu-item index="/workspaces">
      <el-icon><Grid /></el-icon>
      <span>工作区</span>
    </el-menu-item>

    <template v-if="scopeStore.currentWorkspaceId">
      <el-menu-item-group title="WORKSPACE">
        <el-menu-item :index="workspacePath('/projects')">
          <el-icon><Folder /></el-icon>
          <span>项目</span>
        </el-menu-item>
        <el-menu-item :index="workspacePath('/audit-logs')">
          <el-icon><Postcard /></el-icon>
          <span>审计记录</span>
        </el-menu-item>
      </el-menu-item-group>
    </template>

    <template v-if="scopeStore.currentWorkspaceId && scopeStore.currentProjectId">
      <el-menu-item-group :title="scopeStore.currentProjectKey || 'PROJECT'">
        <el-menu-item :index="projectPath('/overview')">
          <el-icon><DataLine /></el-icon>
          <span>项目概览</span>
        </el-menu-item>
        <el-menu-item :index="projectPath('/tasks')">
          <el-icon><Checked /></el-icon>
          <span>任务</span>
        </el-menu-item>
        <el-menu-item :index="projectPath('/repositories')">
          <el-icon><Connection /></el-icon>
          <span>Repository</span>
        </el-menu-item>
        <el-menu-item :index="projectPath('/activities')">
          <el-icon><List /></el-icon>
          <span>活动</span>
        </el-menu-item>
        <el-sub-menu index="github-snapshots">
          <template #title>
            <el-icon><Document /></el-icon>
            <span>GitHub 快照</span>
          </template>
          <el-menu-item :index="projectPath('/github/issues')">Issues</el-menu-item>
          <el-menu-item :index="projectPath('/github/pull-requests')">Pull requests</el-menu-item>
        </el-sub-menu>
        <el-menu-item :index="projectPath('/agent')">
          <el-icon><MagicStick /></el-icon>
          <span>Agent</span>
        </el-menu-item>
        <el-menu-item :index="projectPath('/operations')">
          <el-icon><Tools /></el-icon>
          <span>运行恢复</span>
        </el-menu-item>
      </el-menu-item-group>
    </template>

    <el-menu-item index="/notifications">
      <el-icon><Bell /></el-icon>
      <span>通知</span>
    </el-menu-item>
  </el-menu>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useScopeStore } from '@/stores/scope'
import { Bell, Checked, Connection, DataLine, Document, Folder, Grid, List, MagicStick, Postcard, Tools } from '@element-plus/icons-vue'

defineEmits<{ navigate: [] }>()

const route = useRoute()
const scopeStore = useScopeStore()

function workspacePath(suffix: string) {
  return `/workspaces/${scopeStore.currentWorkspaceId}${suffix}`
}

function projectPath(suffix: string) {
  return `${workspacePath(`/projects/${scopeStore.currentProjectId}`)}${suffix}`
}
</script>

<style scoped>
.app-navigation {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--color-sidebar-muted);
  --el-menu-hover-bg-color: rgba(255, 255, 255, 0.06);
  --el-menu-active-color: #ffffff;
  border: 0;
  padding: var(--space-2);
}

.app-navigation :deep(.el-menu-item),
.app-navigation :deep(.el-sub-menu__title) {
  height: 42px;
  margin-bottom: 2px;
  border-radius: var(--radius-sm);
}

.app-navigation :deep(.el-menu-item.is-active) { background: rgba(86, 112, 255, 0.26); }
.app-navigation :deep(.el-menu-item-group__title) { color: #647086; font-size: 10px; letter-spacing: 0.14em; padding-top: var(--space-5); }
.app-navigation :deep(.el-sub-menu .el-menu-item) { min-width: 0; padding-left: 48px !important; }
</style>
