<template>
  <div class="workspace-detail-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span>Workspace 详情 (ID: {{ workspaceId }})</span>
            <StatusBadge v-if="workspace" :status="workspace.status" type="workspace" style="margin-left: 12px;" />
          </div>
          <div>
            <el-button type="primary" size="small" @click="enterProjectList">进入项目列表</el-button>
            <el-button size="small" @click="fetchDetail">刷新</el-button>
          </div>
        </div>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" @retry="fetchDetail">
        <template v-if="workspace">
          <el-descriptions :column="2" border class="mb-4">
            <el-descriptions-item label="ID">
              <code>{{ workspace.id }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="Slug (不可修改)">
              <code>{{ workspace.slug }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="Owner User ID">
              <code>{{ workspace.ownerUserId }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="当前 Version (版本号)">
              <el-tag type="info" size="small">v{{ workspace.version }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ workspace.createdAt }}
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">
              {{ workspace.updatedAt }}
            </el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">编辑 Workspace 资料 (PUT /api/v1/workspaces/{id})</el-divider>

          <el-form :model="editForm" label-position="top" style="max-width: 600px;">
            <el-form-item label="名称 (name)" required>
              <el-input v-model="editForm.name" maxlength="100" show-word-limit />
            </el-form-item>

            <el-form-item label="描述 (description)">
              <el-input v-model="editForm.description" type="textarea" :rows="3" maxlength="500" show-word-limit />
            </el-form-item>

            <el-form-item label="提交版本号 (expectedVersion)">
              <el-input-number v-model="editForm.expectedVersion" :min="0" disabled />
              <span class="field-hint" style="margin-left: 12px;">自动使用当前最新 version = {{ workspace.version }}</span>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :loading="updating" @click="handleUpdate">
                保存资料更新
              </el-button>
            </el-form-item>
          </el-form>

          <el-divider content-position="left">状态转换与生命周期管理</el-divider>

          <div class="action-bar">
            <template v-if="workspace.status === 'ACTIVE'">
              <el-button type="danger" :loading="actionLoading" @click="confirmStateChange('disable')">
                禁用 Workspace (POST .../disable)
              </el-button>
            </template>
            <template v-else-if="workspace.status === 'DISABLED'">
              <el-button type="success" :loading="actionLoading" @click="confirmStateChange('reactivate')">
                重新启用 Workspace (POST .../reactivate)
              </el-button>
            </template>
          </div>

          <RawJsonPanel :data="rawJson" title="GET /api/v1/workspaces/{id} 原始响应" />
        </template>
      </PageState>

      <ConflictDialog ref="conflictDialogRef" @refresh="fetchDetail" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getWorkspaceApi,
  updateWorkspaceApi,
  disableWorkspaceApi,
  reactivateWorkspaceApi,
} from '@/api/modules/workspace'
import { useScopeStore } from '@/stores/scope'
import type { Workspace } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'
import ConflictDialog from '@/components/ConflictDialog.vue'

const route = useRoute()
const router = useRouter()
const scopeStore = useScopeStore()

const workspaceId = Number(route.params.workspaceId)
const loading = ref(false)
const updating = ref(false)
const actionLoading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')
const workspace = ref<Workspace | null>(null)
const rawJson = ref<any>(null)

const conflictDialogRef = ref()

const editForm = reactive({
  name: '',
  description: '',
  expectedVersion: 0,
})

async function fetchDetail() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await getWorkspaceApi(workspaceId)
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      workspace.value = res.data
      editForm.name = res.data.name
      editForm.description = res.data.description || ''
      editForm.expectedVersion = res.data.version

      scopeStore.setWorkspace(res.data.id, res.data.name)
    } else {
      hasError.value = true
      errorMsg.value = res.message || 'Workspace 不存在或无权限访问'
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = err.message || '网络连接失败'
  } finally {
    loading.value = false
  }
}

async function handleUpdate() {
  if (!workspace.value) return
  updating.value = true

  try {
    const res = await updateWorkspaceApi(workspaceId, {
      name: editForm.name.trim(),
      description: editForm.description ? editForm.description.trim() : undefined,
      expectedVersion: editForm.expectedVersion,
    })

    if (res.success && res.data) {
      ElMessage.success('Workspace 资料更新成功')
      workspace.value = res.data
      editForm.expectedVersion = res.data.version
      scopeStore.setWorkspace(res.data.id, res.data.name)
    } else if (res.httpStatus === 409) {
      conflictDialogRef.value?.show(res.code, res.message)
    } else {
      ElMessage.error(`更新失败 [${res.code}]: ${res.message}`)
    }
  } catch (err: any) {
    ElMessage.error(err.message || '请求错误')
  } finally {
    updating.value = false
  }
}

async function confirmStateChange(action: 'disable' | 'reactivate') {
  if (!workspace.value) return
  const isDisable = action === 'disable'
  const title = isDisable ? '禁用 Workspace 确认' : '重新启用 Workspace 确认'
  const content = isDisable
    ? `禁用后该 Workspace 下的项目将暂停访问。确定要禁用吗？(expectedVersion = ${workspace.value.version})`
    : `确定要重新启用该 Workspace 吗？(expectedVersion = ${workspace.value.version})`

  try {
    await ElMessageBox.confirm(content, title, {
      confirmButtonText: '确定执行',
      cancelButtonText: '取消',
      type: isDisable ? 'warning' : 'info',
    })

    actionLoading.value = true
    const res = isDisable
      ? await disableWorkspaceApi(workspaceId, workspace.value.version)
      : await reactivateWorkspaceApi(workspaceId, workspace.value.version)

    if (res.success && res.data) {
      ElMessage.success(isDisable ? 'Workspace 已禁用' : 'Workspace 已重新启用')
      workspace.value = res.data
      editForm.expectedVersion = res.data.version
    } else if (res.httpStatus === 409) {
      conflictDialogRef.value?.show(res.code, res.message)
    } else {
      ElMessage.error(`操作失败 [${res.code}]: ${res.message}`)
    }
  } catch (err: any) {
    if (err !== 'cancel') {
      ElMessage.error(err.message || '操作取消或异常')
    }
  } finally {
    actionLoading.value = false
  }
}

function enterProjectList() {
  if (workspace.value) {
    scopeStore.setWorkspace(workspace.value.id, workspace.value.name)
  }
  router.push(`/workspaces/${workspaceId}/projects`)
}

onMounted(() => {
  fetchDetail()
})
</script>

<style scoped>
.workspace-detail-container {
  max-width: 900px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.field-hint {
  font-size: 12px;
  color: #909399;
}
.action-bar {
  margin: 16px 0;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
