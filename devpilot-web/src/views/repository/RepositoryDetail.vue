<template>
  <div class="repository-detail-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span>GitHub 仓库绑定详情 (ID: {{ bindingId }})</span>
            <StatusBadge v-if="binding" :status="binding.bindingStatus" type="binding" style="margin-left: 12px;" />
          </div>
          <div>
            <el-button
              v-if="binding && binding.bindingStatus === 'ACTIVE'"
              type="success"
              size="small"
              @click="triggerSync"
            >
              手工触发 Commit 同步
            </el-button>
            <el-button size="small" @click="fetchDetail">刷新</el-button>
          </div>
        </div>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" @retry="fetchDetail">
        <template v-if="binding">
          <el-descriptions :column="2" border class="mb-4">
            <el-descriptions-item label="Binding ID">
              <code>{{ binding.id }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="GitHub 官方 ID">
              <code>{{ binding.githubRepositoryId }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="仓库全名 (FullName)">
              <a :href="binding.htmlUrl" target="_blank" rel="noopener noreferrer" style="color: #409eff; text-decoration: none;">
                {{ binding.fullName }}
              </a>
            </el-descriptions-item>
            <el-descriptions-item label="默认分支">
              <code>{{ binding.defaultBranch }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="可见性 (Visibility)">
              {{ binding.visibility }}
            </el-descriptions-item>
            <el-descriptions-item label="当前 Version">
              <code>v{{ binding.version }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="API凭据关联">
              <el-tag :type="binding.hasApiCredential ? 'success' : 'danger'" size="small">
                {{ binding.hasApiCredential ? '已关联' : '未关联' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="Webhook Secret 关联">
              <el-tag :type="binding.hasWebhookSecret ? 'success' : 'info'" size="small">
                {{ binding.hasWebhookSecret ? '已关联' : '未关联' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="上次 GitHub 校验时间">
              {{ binding.lastVerifiedAt || '未校验' }}
            </el-descriptions-item>
            <el-descriptions-item label="上次 Commit 同步时间">
              {{ binding.lastSyncedAt || '从未同步' }}
              <span class="field-hint" style="display: block;">(后端缺口: 当前成功写路径可能尚未推进该字段)</span>
            </el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">Branches</el-divider>
          <el-alert v-if="branchesError" type="error" show-icon :title="branchesError" style="margin-bottom: 12px;" />
          <el-table v-loading="branchesLoading" :data="branches" empty-text="GitHub 当前没有返回可用 Branch">
            <el-table-column prop="name" label="Branch" min-width="260">
              <template #default="scope">
                <code>{{ scope.row.name }}</code>
                <el-tag v-if="scope.row.name === binding.defaultBranch" size="small" type="success" style="margin-left: 8px;">Default</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="HEAD" min-width="220">
              <template #default="scope"><code :title="scope.row.commitSha">{{ shortSha(scope.row.commitSha) }}</code></template>
            </el-table-column>
          </el-table>

          <el-divider content-position="left">绑定动作与状态流转</el-divider>

          <div class="action-bar">
            <!-- Refresh Metadata -->
            <el-button type="primary" :loading="actionLoading" @click="handleAction('refresh')">
              刷新 GitHub 元数据 (POST .../refresh)
            </el-button>

            <!-- Disable / Reactivate -->
            <template v-if="binding.bindingStatus === 'ACTIVE'">
              <el-button type="warning" :loading="actionLoading" @click="handleAction('disable')">
                禁用绑定 (POST .../disable)
              </el-button>
            </template>
            <template v-else-if="binding.bindingStatus === 'DISABLED'">
              <el-button type="success" :loading="actionLoading" @click="handleAction('reactivate')">
                重新启用绑定 (POST .../reactivate)
              </el-button>
            </template>

            <!-- Unbind -->
            <el-button type="danger" :loading="actionLoading" @click="handleAction('unbind')">
              解绑仓库 (POST .../unbind)
            </el-button>
          </div>

          <RawJsonPanel :data="rawJson" title="GET .../github-repositories/{id} 原始响应" />
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
      errorMsg.value = res.message || '仓库绑定不存在或已移除'
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = err.message || '网络连接失败'
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
    else branchesError.value = res.message || '无法加载 Repository Branches'
  } catch (err: any) {
    branchesError.value = err.message || '无法加载 Repository Branches'
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
        `解绑后该仓库将移出当前项目。同步的历史 Activity/Snapshot 将会保留。确定解绑吗？(expectedVersion = ${version})`,
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
        ElMessage.success(`操作成功 [${action}]`)
        if (res.data) binding.value = res.data
        else fetchDetail()
      }
    } else if (res.httpStatus === 409) {
      conflictDialogRef.value?.show(res.code, res.message)
    } else {
      ElMessage.error(`操作失败 [${res.code}]: ${res.message}`)
    }
  } catch (err: any) {
    ElMessage.error(err.message || '网络请求异常')
  } finally {
    actionLoading.value = false
  }
}

async function triggerSync() {
  if (!binding.value) return
  try {
    const res = await triggerCommitSyncApi(workspaceId, projectId, binding.value.id)
    if (res.success && res.data) {
      ElMessage.success(`Commit 同步已触发 (Run ID: ${res.data.runId})`)
      router.push(`/workspaces/${workspaceId}/projects/${projectId}/sync-runs/${binding.value.id}/${res.data.runId}`)
    } else {
      ElMessage.error(`触发失败 [${res.code}]: ${res.message}`)
    }
  } catch (err: any) {
    ElMessage.error(err.message || '网络请求错误')
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
