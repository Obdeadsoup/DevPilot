<template>
  <div class="task-github-links-container">
    <div class="links-header mb-3">
      <span class="title">关联的 GitHub 工作项</span>
      <el-button type="primary" size="small" @click="dialogVisible = true">
        添加关联快照
      </el-button>
    </div>

    <PageState :loading="loading" :empty="links.length === 0">
      <template #empty-action>
        <span class="text-muted">暂无关联的 GitHub Issue 或 Pull Request 快照</span>
      </template>

      <el-table :data="links" stripe size="small" style="width: 100%;">
        <el-table-column prop="resourceType" label="类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.resourceType === 'ISSUE' ? 'warning' : 'primary'">
              {{ row.resourceType }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="externalNumber" label="GitHub #" width="90">
          <template #default="{ row }">
            <code>#{{ row.externalNumber }}</code>
          </template>
        </el-table-column>

        <el-table-column prop="externalTitle" label="快照标题" min-width="180">
          <template #default="{ row }">
            <router-link
              v-if="row.resourceType === 'ISSUE'"
              :to="`/workspaces/${workspaceId}/projects/${projectId}/github/issues/${row.snapshotId}`"
              style="color: #409eff; text-decoration: none;"
            >
              {{ row.externalTitle }}
            </router-link>
            <router-link
              v-else
              :to="`/workspaces/${workspaceId}/projects/${projectId}/github/pull-requests/${row.snapshotId}`"
              style="color: #409eff; text-decoration: none;"
            >
              {{ row.externalTitle }}
            </router-link>
          </template>
        </el-table-column>

        <el-table-column prop="relationType" label="关联关系" width="150">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ row.relationType }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'ACTIVE'"
              type="danger"
              link
              size="small"
              @click="handleRemoveLink(row)"
            >
              移除关联
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageState>

    <!-- Create Link Dialog -->
    <el-dialog
      v-model="dialogVisible"
      title="关联 GitHub 工作项"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item label="工作项类型" required>
          <el-radio-group v-model="form.resourceType" @change="handleTypeChange">
            <el-radio value="ISSUE">GitHub Issue 快照</el-radio>
            <el-radio value="PULL_REQUEST">GitHub Pull Request 快照</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="工作项编号" required>
          <el-input-number v-model="form.snapshotId" :min="1" style="width: 100%;" placeholder="在 GitHub 快照列表中查看编号" />
          <div class="field-hint">请输入 DevPilot 中已同步工作项的编号。</div>
        </el-form-item>

        <el-form-item label="关联关系" required>
          <el-select v-model="form.relationType" style="width: 100%;">
            <el-option label="追踪此问题" value="TRACKS" />
            <el-option label="由此变更实现" value="IMPLEMENTED_BY" />
            <el-option label="相关工作项" value="RELATED_TO" />
          </el-select>
        </el-form-item>

      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreateLink">
          确认关联
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createTaskGitHubLinkApi,
  removeTaskGitHubLinkApi,
} from '@/api/modules/task'
import type { TaskGitHubLinkResponse, TaskGitHubResourceType, TaskGitHubRelationType } from '@/types/task'
import PageState from '@/components/PageState.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

const props = defineProps<{
  workspaceId: number
  projectId: number
  taskId: number
  taskVersion: number
  links: TaskGitHubLinkResponse[]
  loading?: boolean
}>()

const emit = defineEmits(['refresh'])

const dialogVisible = ref(false)
const submitting = ref(false)

const form = reactive({
  resourceType: 'ISSUE' as TaskGitHubResourceType,
  snapshotId: 1,
  relationType: 'TRACKS' as TaskGitHubRelationType,
})

function handleTypeChange(val: TaskGitHubResourceType) {
  if (val === 'ISSUE') {
    form.relationType = 'TRACKS'
  } else {
    form.relationType = 'IMPLEMENTED_BY'
  }
}

async function handleCreateLink() {
  if (!form.snapshotId) return
  submitting.value = true

  try {
    const res = await createTaskGitHubLinkApi(
      props.workspaceId,
      props.projectId,
      props.taskId,
      {
        resourceType: form.resourceType,
        snapshotId: form.snapshotId,
        relationType: form.relationType,
        expectedTaskVersion: props.taskVersion,
      }
    )

    if (res.success) {
      ElMessage.success('关联快照成功')
      dialogVisible.value = false
      emit('refresh')
    } else {
      ElMessage.error(productErrorMessage(res, '关联失败，请确认工作项后重试。'))
    }
  } catch (err: any) {
    ElMessage.error(unexpectedErrorMessage(err, '关联失败，请稍后重试。'))
  } finally {
    submitting.value = false
  }
}

async function handleRemoveLink(link: TaskGitHubLinkResponse) {
  try {
    await ElMessageBox.confirm(
      '确定要移除该 GitHub 工作项关联吗？',
      '移除关联确认',
      { confirmButtonText: '确定移除', cancelButtonText: '取消', type: 'warning' }
    )

    const res = await removeTaskGitHubLinkApi(
      props.workspaceId,
      props.projectId,
      props.taskId,
      link.id,
      {
        expectedTaskVersion: props.taskVersion,
        expectedLinkVersion: link.version,
      }
    )

    if (res.success) {
      ElMessage.success('关联已移除')
      emit('refresh')
    } else {
      ElMessage.error(productErrorMessage(res, '移除关联失败，请稍后重试。'))
    }
  } catch (err: any) {
    if (err !== 'cancel') {
      ElMessage.error(unexpectedErrorMessage(err, '移除关联失败，请稍后重试。'))
    }
  }
}
</script>

<style scoped>
.task-github-links-container {
  margin-top: 12px;
}
.links-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}
.field-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
.mb-3 {
  margin-bottom: 12px;
}
.text-muted {
  color: #909399;
  font-size: 13px;
}
</style>
