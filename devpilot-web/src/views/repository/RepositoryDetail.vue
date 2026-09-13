<template>
  <div class="repository-detail-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span>{{ binding?.fullName || 'GitHub 仓库' }}</span>
            <StatusBadge v-if="binding" :status="binding.bindingStatus" type="binding" style="margin-left: 12px;" />
          </div>
          <div>
            <el-button
              v-if="binding && binding.bindingStatus === 'ACTIVE'"
              type="success"
              size="small"
              @click="triggerSync"
            >
              同步提交
            </el-button>
            <el-button size="small" @click="fetchDetail">刷新</el-button>
          </div>
        </div>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" @retry="fetchDetail">
        <template v-if="binding">
          <el-descriptions :column="2" border class="mb-4">
            <el-descriptions-item label="GitHub 仓库">
              <a :href="binding.htmlUrl" target="_blank" rel="noopener noreferrer" style="color: #409eff; text-decoration: none;">
                {{ binding.fullName }}
              </a>
            </el-descriptions-item>
            <el-descriptions-item label="默认分支">
              <code>{{ binding.defaultBranch }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="可见性">
              {{ binding.visibility }}
            </el-descriptions-item>
            <el-descriptions-item label="访问凭据">
              <el-tag :type="binding.hasApiCredential ? 'success' : 'danger'" size="small">
                {{ binding.hasApiCredential ? '已关联' : '未关联' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="Webhook 密钥">
              <el-tag :type="binding.hasWebhookSecret ? 'success' : 'info'" size="small">
                {{ binding.hasWebhookSecret ? '已关联' : '未关联' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="上次 GitHub 校验时间">
              {{ binding.lastVerifiedAt || '未校验' }}
            </el-descriptions-item>
            <el-descriptions-item label="上次提交同步">
              {{ binding.lastSyncedAt || '从未同步' }}
            </el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">分支</el-divider>
          <el-alert v-if="branchesError" type="error" show-icon :title="branchesError" style="margin-bottom: 12px;" />
          <el-table v-loading="branchesLoading" :data="branches" empty-text="当前没有可用分支">
            <el-table-column prop="name" label="分支" min-width="260">
              <template #default="scope">
                <code>{{ scope.row.name }}</code>
                <el-tag v-if="scope.row.name === binding.defaultBranch" size="small" type="success" style="margin-left: 8px;">默认</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="HEAD" min-width="220">
              <template #default="scope"><code :title="scope.row.commitSha">{{ shortSha(scope.row.commitSha) }}</code></template>
            </el-table-column>
          </el-table>

          <el-divider content-position="left">仓库设置</el-divider>

          <div class="action-bar">
            <!-- Refresh Metadata -->
            <el-button type="primary" :loading="actionLoading" @click="handleAction('refresh')">
              刷新仓库信息
            </el-button>

            <!-- Disable / Reactivate -->
            <template v-if="binding.bindingStatus === 'ACTIVE'">
              <el-button type="warning" :loading="actionLoading" @click="handleAction('disable')">
                禁用绑定
              </el-button>
            </template>
            <template v-else-if="binding.bindingStatus === 'DISABLED'">
              <el-button type="success" :loading="actionLoading" @click="handleAction('reactivate')">
                重新启用绑定
              </el-button>
            </template>

            <!-- Unbind -->
            <el-button type="danger" :loading="actionLoading" @click="handleAction('unbind')">
              解绑仓库
            </el-button>
          </div>

          <RawJsonPanel :data="{ bindingId: binding.id, githubRepositoryId: binding.githubRepositoryId, version: binding.version, response: rawJson }" title="技术详情" />
        </template>
      </PageState>

      <ConflictDialog ref="conflictDialogRef" @refresh="fetchDetail" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getRepositoryApi,
  disableRepositoryApi,
  reactivateRepositoryApi,
  refreshRepositoryApi,
  unbindRepositoryApi,
  listRepositoryBranchesApi,
} from '@/api/modules/repository'
import { triggerCommitSyncApi } from '@/api/modules/sync'
import type { GitHubBranch, GitHubRepositoryBinding } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'
import ConflictDialog from '@/components/ConflictDialog.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

const route = useRoute()
const router = useRouter()

const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)
const bindingId = Number(route.params.bindingId)

const loading = ref(false)
const actionLoading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')
const binding = ref<GitHubRepositoryBinding | null>(null)
const branches = ref<GitHubBranch[]>([])
const branchesLoading = ref(false)
const branchesError = ref('')
const rawJson = ref<any>(null)

const conflictDialogRef = ref()

async function fetchDetail() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await getRepositoryApi(workspaceId, projectId, bindingId)
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      binding.value = res.data
      void fetchBranches()
    } else {
      hasError.value = true
      errorMsg.value = productErrorMessage(res, '无法加载 GitHub 仓库，请稍后重试。')
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = unexpectedErrorMessage(err, '暂时无法加载 GitHub 仓库，请稍后重试。')
  } finally {
    loading.value = false
  }
}

async function fetchBranches() {
  if (!binding.value) return
  branchesLoading.value = true
  branchesError.value = ''
  try {
    const res = await listRepositoryBranchesApi(workspaceId, projectId, binding.value.id)
    if (res.success && res.data) branches.value = res.data
    else branchesError.value = productErrorMessage(res, '无法加载仓库分支，请稍后重试。')
  } catch (err: any) {
    branchesError.value = unexpectedErrorMessage(err, '暂时无法加载仓库分支，请稍后重试。')
  } finally {
    branchesLoading.value = false
  }
}

function shortSha(sha: string) {
  return sha.slice(0, 12)
}

async function handleAction(action: 'refresh' | 'disable' | 'reactivate' | 'unbind') {
  if (!binding.value) return
  const version = binding.value.version

  if (action === 'unbind') {
    try {
      await ElMessageBox.confirm(
        '解绑后，该仓库将移出当前项目；历史活动与快照仍会保留。确定继续吗？',
        '解绑确认',
        { confirmButtonText: '确定解绑', cancelButtonText: '取消', type: 'warning' }
      )
    } catch {
      return
    }
  }

  actionLoading.value = true
  try {
    let res
    if (action === 'refresh') {
      res = await refreshRepositoryApi(workspaceId, projectId, bindingId, version)
    } else if (action === 'disable') {
      res = await disableRepositoryApi(workspaceId, projectId, bindingId, version)
    } else if (action === 'reactivate') {
      res = await reactivateRepositoryApi(workspaceId, projectId, bindingId, version)
    } else {
      res = await unbindRepositoryApi(workspaceId, projectId, bindingId, version)
    }

    if (res.success) {
      if (action === 'unbind') {
        ElMessage.success('仓库解绑成功')
        router.push(`/workspaces/${workspaceId}/projects/${projectId}/repositories`)
      } else {
        const actionLabel = action === 'refresh' ? '仓库信息已刷新' : action === 'disable' ? '仓库绑定已禁用' : '仓库绑定已重新启用'
        ElMessage.success(actionLabel)
        if (res.data) binding.value = res.data
        else fetchDetail()
      }
    } else if (res.httpStatus === 409) {
      conflictDialogRef.value?.show(res.code, res.message)
    } else {
      ElMessage.error(productErrorMessage(res, '仓库设置更新失败，请稍后重试。'))
    }
  } catch (err: any) {
    ElMessage.error(unexpectedErrorMessage(err, '仓库设置更新失败，请稍后重试。'))
  } finally {
    actionLoading.value = false
  }
}

async function triggerSync() {
  if (!binding.value) return
  try {
    const res = await triggerCommitSyncApi(workspaceId, projectId, binding.value.id)
    if (res.success && res.data) {
      ElMessage.success('提交同步已开始')
      router.push(`/workspaces/${workspaceId}/projects/${projectId}/sync-runs/${binding.value.id}/${res.data.runId}`)
    } else {
      ElMessage.error(productErrorMessage(res, '无法开始提交同步，请稍后重试。'))
    }
  } catch (err: any) {
    ElMessage.error(unexpectedErrorMessage(err, '无法开始提交同步，请稍后重试。'))
  }
}

onMounted(() => {
  fetchDetail()
})
</script>

<style scoped>
.repository-detail-container {
  max-width: 900px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.action-bar {
  margin: 16px 0;
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.mb-4 {
  margin-bottom: 16px;
}
.field-hint {
  font-size: 12px;
  color: #909399;
}
</style>
