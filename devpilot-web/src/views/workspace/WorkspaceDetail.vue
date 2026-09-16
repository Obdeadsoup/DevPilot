<template>
  <div class="workspace-detail-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span>{{ workspace?.name || '工作区设置' }}</span>
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
            <el-descriptions-item label="访问标识">
              <code>{{ workspace.slug }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="所有者">
              <code>{{ workspace.ownerUserId }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ workspace.createdAt }}
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">
              {{ workspace.updatedAt }}
            </el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">工作区资料</el-divider>

          <el-form :model="editForm" label-position="top" style="max-width: 600px;">
            <el-form-item label="名称" required>
              <el-input v-model="editForm.name" maxlength="100" show-word-limit />
            </el-form-item>

            <el-form-item label="描述">
              <el-input v-model="editForm.description" type="textarea" :rows="3" maxlength="500" show-word-limit />
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :loading="updating" @click="handleUpdate">
                保存资料更新
              </el-button>
            </el-form-item>
          </el-form>

          <el-divider content-position="left">工作区状态</el-divider>

          <div class="action-bar">
            <template v-if="workspace.status === 'ACTIVE'">
              <el-button type="danger" :loading="actionLoading" @click="confirmStateChange('disable')">
                禁用工作区
              </el-button>
            </template>
            <template v-else-if="workspace.status === 'DISABLED'">
              <el-button type="success" :loading="actionLoading" @click="confirmStateChange('reactivate')">
                重新启用工作区
              </el-button>
            </template>
          </div>

          <el-divider content-position="left">协作成员</el-divider>
          <el-form inline :model="inviteForm">
            <el-form-item label="已注册邮箱"><el-input v-model="inviteForm.email" placeholder="member@example.com" /></el-form-item>
            <el-form-item label="角色"><el-select v-model="inviteForm.role" style="width: 120px"><el-option label="成员" value="MEMBER" /><el-option label="管理员" value="ADMIN" /><el-option label="只读" value="VIEWER" /></el-select></el-form-item>
            <el-form-item><el-button type="primary" :loading="inviting" :disabled="!inviteForm.email" @click="inviteMember">发送邀请</el-button></el-form-item>
          </el-form>
          <el-table v-loading="membersLoading" :data="members" empty-text="暂无可显示的成员或您没有查看权限">
            <el-table-column prop="userId" label="用户 ID" width="120" />
            <el-table-column prop="role" label="角色" width="130" />
            <el-table-column prop="status" label="状态" width="130" />
            <el-table-column prop="joinedAt" label="加入时间" min-width="180"><template #default="scope">{{ scope.row.joinedAt || '等待接受' }}</template></el-table-column>
          </el-table>

          <RawJsonPanel :data="{ workspaceId: workspace.id, ownerUserId: workspace.ownerUserId, version: workspace.version, response: rawJson }" title="技术详情" />
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
  inviteWorkspaceMemberApi,
  listWorkspaceMembersApi,
} from '@/api/modules/workspace'
import { useScopeStore } from '@/stores/scope'
import type { Workspace, WorkspaceMember } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'
import ConflictDialog from '@/components/ConflictDialog.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

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
const members = ref<WorkspaceMember[]>([])
const membersLoading = ref(false)
const inviting = ref(false)
const inviteForm = reactive<{ email: string; role: WorkspaceMember['role'] }>({ email: '', role: 'MEMBER' })

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
      void loadMembers()
    } else {
      hasError.value = true
      errorMsg.value = productErrorMessage(res, '无法加载工作区，请稍后重试。')
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = unexpectedErrorMessage(err, '暂时无法加载工作区，请稍后重试。')
  } finally {
    loading.value = false
  }
}

async function loadMembers() {
  membersLoading.value = true
  try {
    const res = await listWorkspaceMembersApi(workspaceId)
    if (res.success && res.data) members.value = res.data
  } finally {
    membersLoading.value = false
  }
}

async function inviteMember() {
  inviting.value = true
  try {
    const res = await inviteWorkspaceMemberApi(workspaceId, inviteForm.email.trim(), inviteForm.role)
    if (res.success) {
      ElMessage.success('邀请已创建，等待对方接受')
      inviteForm.email = ''
      await loadMembers()
    } else ElMessage.error(productErrorMessage(res, '邀请发送失败，请检查成员邮箱后重试。'))
  } finally {
    inviting.value = false
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
      ElMessage.success('工作区资料已更新')
      workspace.value = res.data
      editForm.expectedVersion = res.data.version
      scopeStore.setWorkspace(res.data.id, res.data.name)
    } else if (res.httpStatus === 409) {
      conflictDialogRef.value?.show(res.code, res.message)
    } else {
      ElMessage.error(productErrorMessage(res, '工作区更新失败，请稍后重试。'))
    }
  } catch (err: any) {
    ElMessage.error(unexpectedErrorMessage(err, '工作区更新失败，请稍后重试。'))
  } finally {
    updating.value = false
  }
}

async function confirmStateChange(action: 'disable' | 'reactivate') {
  if (!workspace.value) return
  const isDisable = action === 'disable'
  const title = isDisable ? '禁用工作区' : '重新启用工作区'
  const content = isDisable
    ? '禁用后，该工作区下的项目将暂停访问。确定继续吗？'
    : '重新启用后，成员可以继续访问工作区。确定继续吗？'

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
      ElMessage.success(isDisable ? '工作区已禁用' : '工作区已重新启用')
      workspace.value = res.data
      editForm.expectedVersion = res.data.version
    } else if (res.httpStatus === 409) {
      conflictDialogRef.value?.show(res.code, res.message)
    } else {
      ElMessage.error(productErrorMessage(res, '工作区状态更新失败，请稍后重试。'))
    }
  } catch (err: any) {
    if (err !== 'cancel') {
      ElMessage.error(unexpectedErrorMessage(err, '工作区状态更新失败，请稍后重试。'))
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
