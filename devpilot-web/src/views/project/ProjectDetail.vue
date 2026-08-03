<template>
  <div class="project-detail-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span>项目概览与管理 (ID: {{ projectId }})</span>
            <StatusBadge v-if="project" :status="project.status" type="project" style="margin-left: 12px;" />
          </div>
          <div>
            <el-button type="success" size="small" @click="goToRepositories">查看关联 GitHub 仓库</el-button>
            <el-button size="small" @click="fetchDetail">刷新</el-button>
          </div>
        </div>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" @retry="fetchDetail">
        <template v-if="project">
          <el-descriptions :column="2" border class="mb-4">
            <el-descriptions-item label="ID">
              <code>{{ project.id }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="Project Key (不可修改)">
              <el-tag type="info"><code>{{ project.projectKey }}</code></el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="Workspace ID">
              <code>{{ project.workspaceId }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="当前 Version">
              <code>v{{ project.version }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ project.createdAt }}
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">
              {{ project.updatedAt }}
            </el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">编辑项目资料 (PUT /api/v1/.../projects/{id})</el-divider>

          <el-form :model="editForm" label-position="top" style="max-width: 600px;">
            <el-form-item label="项目名称 (name)" required>
              <el-input v-model="editForm.name" :disabled="project.status === 'ARCHIVED'" maxlength="100" show-word-limit />
            </el-form-item>

            <el-form-item label="可见性 (visibility)" required>
              <el-radio-group v-model="editForm.visibility" :disabled="project.status === 'ARCHIVED'">
                <el-radio value="PRIVATE">PRIVATE</el-radio>
                <el-radio value="INTERNAL">INTERNAL</el-radio>
              </el-radio-group>
            </el-form-item>

            <el-form-item label="描述 (description)">
              <el-input
                v-model="editForm.description"
                type="textarea"
                :rows="3"
                :disabled="project.status === 'ARCHIVED'"
                maxlength="500"
                show-word-limit
              />
            </el-form-item>

            <el-form-item label="提交版本号 (expectedVersion)">
              <el-input-number v-model="editForm.expectedVersion" :min="0" disabled />
            </el-form-item>

            <el-form-item v-if="project.status !== 'ARCHIVED'">
              <el-button type="primary" :loading="updating" @click="handleUpdate">
                保存资料更新
              </el-button>
            </el-form-item>
            <el-alert v-else type="warning" show-icon :closable="false" title="已归档项目 (ARCHIVED) 处于只读状态，无法更新资料" />
          </el-form>

          <el-divider content-position="left">项目生命周期状态转换</el-divider>

          <div class="action-bar">
            <!-- Action buttons based on project status -->
            <template v-if="project.status === 'PLANNING'">
              <el-button type="success" :loading="actionLoading" @click="handleStatusAction('activate')">
                激活项目 (PLANNING → ACTIVE)
              </el-button>
              <el-button type="warning" :loading="actionLoading" @click="handleStatusAction('archive')">
                归档项目 (PLANNING → ARCHIVED)
              </el-button>
            </template>

            <template v-else-if="project.status === 'ACTIVE'">
              <el-button type="warning" :loading="actionLoading" @click="handleStatusAction('archive')">
                归档项目 (ACTIVE → ARCHIVED)
              </el-button>
            </template>

            <template v-else-if="project.status === 'ARCHIVED'">
              <el-button type="primary" :loading="actionLoading" @click="handleStatusAction('restore')">
                恢复项目 (ARCHIVED → ACTIVE)
              </el-button>
            </template>
          </div>

          <RawJsonPanel :data="rawJson" title="GET /api/v1/.../projects/{id} 原始响应" />
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
  getProjectApi,
  updateProjectApi,
  activateProjectApi,
  archiveProjectApi,
  restoreProjectApi,
} from '@/api/modules/project'
import { useScopeStore } from '@/stores/scope'
import type { Project } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'
import ConflictDialog from '@/components/ConflictDialog.vue'

const route = useRoute()
const router = useRouter()
const scopeStore = useScopeStore()

const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)

const loading = ref(false)
const updating = ref(false)
const actionLoading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')
const project = ref<Project | null>(null)
const rawJson = ref<any>(null)

const conflictDialogRef = ref()

const editForm = reactive({
  name: '',
  description: '',
  visibility: 'PRIVATE' as 'PRIVATE' | 'INTERNAL',
  expectedVersion: 0,
})

async function fetchDetail() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await getProjectApi(workspaceId, projectId)
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      project.value = res.data
      editForm.name = res.data.name
      editForm.description = res.data.description || ''
      editForm.visibility = res.data.visibility
      editForm.expectedVersion = res.data.version

      scopeStore.setWorkspace(res.data.workspaceId, '')
      scopeStore.setProject(res.data.id, res.data.projectKey, res.data.name)
    } else {
      hasError.value = true
      errorMsg.value = res.message || '项目不存在或无权限访问'
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = err.message || '网络连接失败'
  } finally {
    loading.value = false
  }
}

async function handleUpdate() {
  if (!project.value) return
  updating.value = true

  try {
    const res = await updateProjectApi(workspaceId, projectId, {
      name: editForm.name.trim(),
      visibility: editForm.visibility,
      description: editForm.description ? editForm.description.trim() : undefined,
      expectedVersion: editForm.expectedVersion,
    })

    if (res.success && res.data) {
      ElMessage.success('项目资料更新成功')
      project.value = res.data
      editForm.expectedVersion = res.data.version
      scopeStore.setProject(res.data.id, res.data.projectKey, res.data.name)
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

async function handleStatusAction(action: 'activate' | 'archive' | 'restore') {
  if (!project.value) return
  let actionName = '激活'
  if (action === 'archive') actionName = '归档'
  if (action === 'restore') actionName = '恢复'

  const promptText = `确定要${actionName}该项目吗？(expectedVersion = ${project.value.version})`

  try {
    await ElMessageBox.confirm(promptText, `${actionName}确认`, {
      confirmButtonText: '确定执行',
      cancelButtonText: '取消',
      type: action === 'archive' ? 'warning' : 'info',
    })

    actionLoading.value = true
    let res
    if (action === 'activate') {
      res = await activateProjectApi(workspaceId, projectId, project.value.version)
    } else if (action === 'archive') {
      res = await archiveProjectApi(workspaceId, projectId, project.value.version)
    } else {
      res = await restoreProjectApi(workspaceId, projectId, project.value.version)
    }

    if (res.success && res.data) {
      ElMessage.success(`项目已${actionName}`)
      project.value = res.data
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

function goToRepositories() {
  router.push(`/workspaces/${workspaceId}/projects/${projectId}/repositories`)
}

onMounted(() => {
  fetchDetail()
})
</script>

<style scoped>
.project-detail-container {
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
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
