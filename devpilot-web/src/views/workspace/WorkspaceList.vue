<template>
  <div class="workspace-list-container">
    <el-card class="invitation-card">
      <template #header>
        <div class="section-heading">
          <div>
            <h2>待处理邀请</h2>
            <p>接受后即可进入对应工作区；拒绝不会加入工作区。</p>
          </div>
          <el-tag v-if="invitations.length" type="warning" effect="plain">
            {{ invitations.length }} 个待处理
          </el-tag>
        </div>
      </template>

      <div v-loading="invitationLoading" class="invitation-content">
        <el-alert
          v-if="invitationError"
          :title="invitationError"
          type="error"
          show-icon
          :closable="false"
        >
          <template #default>
            <el-button link type="primary" @click="fetchInvitations">重试</el-button>
          </template>
        </el-alert>

        <el-empty
          v-else-if="!invitationLoading && invitations.length === 0"
          description="暂无待处理邀请"
          :image-size="72"
        />

        <el-table v-else :data="invitations" stripe style="width: 100%">
          <el-table-column label="工作区" min-width="220">
            <template #default="{ row }">
              <div class="workspace-identity">
                <span>{{ row.workspaceName }}</span>
                <code>{{ row.workspaceSlug }}</code>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="角色" width="120">
            <template #default="{ row }">
              <el-tag effect="plain">{{ roleLabel(row.role) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button
                type="primary"
                size="small"
                :loading="processingKey === `accept-${row.workspaceId}`"
                :disabled="Boolean(processingKey)"
                @click="acceptInvitation(row)"
              >
                接受
              </el-button>
              <el-button
                type="danger"
                plain
                size="small"
                :loading="processingKey === `reject-${row.workspaceId}`"
                :disabled="Boolean(processingKey)"
                @click="rejectInvitation(row)"
              >
                拒绝
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <el-card class="workspace-card">
      <template #header>
        <PageHeader title="工作区" description="选择团队空间，继续进入项目与研发工作流。">
          <template #actions>
            <el-button type="primary" @click="$router.push('/workspaces/new')">
              创建工作区
            </el-button>
            <el-button @click="refreshAll">刷新</el-button>
          </template>
        </PageHeader>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" :empty="items.length === 0" @retry="fetchWorkspaces">
        <template #empty-action>
          <el-button type="primary" @click="$router.push('/workspaces/new')">创建首个工作区</el-button>
        </template>

        <el-table :data="items" stripe style="width: 100%;">
          <el-table-column prop="name" label="名称" min-width="180" />
          <el-table-column prop="slug" label="访问标识" min-width="120">
            <template #default="{ row }">
              <code>{{ row.slug }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="120">
            <template #default="{ row }">
              <StatusBadge :status="row.status" type="workspace" />
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" min-width="160" />
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="enterWorkspace(row)">
                进入项目列表
              </el-button>
              <el-button type="info" link size="small" @click="$router.push(`/workspaces/${row.id}`)">
                详情/编辑
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-bar">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="size"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            :total="total"
            @size-change="fetchWorkspaces"
            @current-change="fetchWorkspaces"
          />
        </div>

      </PageState>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  acceptWorkspaceInvitationApi,
  listMyWorkspaceInvitationsApi,
  listWorkspacesApi,
  rejectWorkspaceInvitationApi,
} from '@/api/modules/workspace'
import { useScopeStore } from '@/stores/scope'
import type { Workspace, WorkspaceInvitation } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import PageHeader from '@/components/PageHeader.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

const router = useRouter()
const scopeStore = useScopeStore()

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')
const invitationLoading = ref(false)
const invitationError = ref('')
const invitations = ref<WorkspaceInvitation[]>([])
const processingKey = ref('')

const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<Workspace[]>([])

async function fetchWorkspaces() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await listWorkspacesApi(page.value, size.value)
    if (res.success && res.data) {
      items.value = res.data.items || []
      total.value = res.data.total || 0
    } else {
      hasError.value = true
      errorMsg.value = productErrorMessage(res, '暂时无法加载工作区，请稍后重试。')
    }
  } catch (err: unknown) {
    hasError.value = true
    errorMsg.value = unexpectedErrorMessage(err, '暂时无法加载工作区，请稍后重试。')
  } finally {
    loading.value = false
  }
}

async function fetchInvitations() {
  invitationLoading.value = true
  invitationError.value = ''
  try {
    const res = await listMyWorkspaceInvitationsApi()
    if (res.success && res.data) {
      invitations.value = res.data
    } else {
      invitationError.value = productErrorMessage(res, '暂时无法加载邀请，请稍后重试。')
    }
  } catch (err: unknown) {
    invitationError.value = unexpectedErrorMessage(err, '暂时无法加载邀请，请稍后重试。')
  } finally {
    invitationLoading.value = false
  }
}

async function refreshAll() {
  await Promise.all([fetchWorkspaces(), fetchInvitations()])
}

async function acceptInvitation(invitation: WorkspaceInvitation) {
  processingKey.value = `accept-${invitation.workspaceId}`
  try {
    const res = await acceptWorkspaceInvitationApi(invitation.workspaceId, invitation.version)
    if (!res.success) {
      ElMessage.error(productErrorMessage(res, '接受邀请失败，请刷新后重试。'))
      return
    }
    invitations.value = invitations.value.filter(item => item.workspaceId !== invitation.workspaceId)
    page.value = 1
    await fetchWorkspaces()
    ElMessage.success(`已加入工作区「${invitation.workspaceName}」`)
  } catch (err: unknown) {
    ElMessage.error(unexpectedErrorMessage(err, '接受邀请失败，请刷新后重试。'))
  } finally {
    processingKey.value = ''
  }
}

async function rejectInvitation(invitation: WorkspaceInvitation) {
  try {
    await ElMessageBox.confirm(
      `拒绝后将不会加入工作区「${invitation.workspaceName}」。`,
      '拒绝工作区邀请',
      { confirmButtonText: '确认拒绝', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }

  processingKey.value = `reject-${invitation.workspaceId}`
  try {
    const res = await rejectWorkspaceInvitationApi(invitation.workspaceId, invitation.version)
    if (!res.success) {
      ElMessage.error(productErrorMessage(res, '拒绝邀请失败，请刷新后重试。'))
      return
    }
    invitations.value = invitations.value.filter(item => item.workspaceId !== invitation.workspaceId)
    ElMessage.success('已拒绝工作区邀请')
  } catch (err: unknown) {
    ElMessage.error(unexpectedErrorMessage(err, '拒绝邀请失败，请刷新后重试。'))
  } finally {
    processingKey.value = ''
  }
}

function roleLabel(role: WorkspaceInvitation['role']) {
  return ({ ADMIN: '管理员', MEMBER: '成员', VIEWER: '只读成员' } as const)[role]
}

function enterWorkspace(ws: Workspace) {
  scopeStore.setWorkspace(ws.id, ws.name)
  scopeStore.clearProject()
  router.push(`/workspaces/${ws.id}/projects`)
}

onMounted(() => {
  refreshAll()
})
</script>

<style scoped>
.workspace-list-container {
  max-width: 1100px;
  margin: 0 auto;
}
.invitation-card {
  margin-bottom: var(--space-5);
}
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
}
.section-heading h2 {
  margin: 0;
  font-size: var(--font-size-lg);
}
.section-heading p {
  margin: var(--space-1) 0 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
}
.invitation-content {
  min-height: 96px;
}
.workspace-identity {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.workspace-identity code {
  color: var(--color-text-muted);
}
.pagination-bar {
  margin-top: var(--space-4);
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 720px) {
  .section-heading {
    align-items: flex-start;
  }
}
</style>
