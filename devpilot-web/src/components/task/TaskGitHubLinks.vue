<template>
  <div class="task-github-links-container">
    <div class="links-header mb-3">
      <span class="title">关联 GitHub 快照 (GitHub Snapshot Links)</span>
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
      title="关联现有 GitHub Issue / PR 快照"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item label="快照资源类型 (resourceType)" required>
          <el-radio-group v-model="form.resourceType" @change="handleTypeChange">
            <el-radio value="ISSUE">GitHub Issue 快照</el-radio>
            <el-radio value="PULL_REQUEST">GitHub Pull Request 快照</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="快照主键 ID (snapshotId)" required>
          <el-input-number v-model="form.snapshotId" :min="1" style="width: 100%;" placeholder="例如 Issue/PR 在系统中的快照 ID" />
          <div class="field-hint">注意：填入的是本地 Snapshot ID，不是 GitHub Number。可以前往 GitHub 快照列表查看。</div>
        </el-form-item>

        <el-form-item label="关联类型 (relationType)" required>
          <el-select v-model="form.relationType" style="width: 100%;">
            <el-option label="TRACKS (追踪 Issue)" value="TRACKS" />
            <el-option label="IMPLEMENTED_BY (由 PR 实现)" value="IMPLEMENTED_BY" />
            <el-option label="RELATED_TO (相关)" value="RELATED_TO" />
          </el-select>
        </el-form-item>

        <el-form-item label="提交 Task 版本号 (expectedTaskVersion)">
          <el-input-number :model-value="taskVersion" disabled style="width: 100%;" />
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
      ElMessage.error(`关联失败 [${res.code}]: ${res.message}`)
    }
  } catch (err: any) {
    ElMessage.error(err.message || '网络请求失败')
  } finally {
    submitting.value = false
  }
}

async function handleRemoveLink(link: TaskGitHubLinkResponse) {
  try {
    await ElMessageBox.confirm(
      `确定要移除该 GitHub 关联吗？(Task version = ${props.taskVersion}, Link version = ${link.version})`,
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
      ElMessage.error(`移除失败 [${res.code}]: ${res.message}`)
    }
  } catch (err: any) {
    if (err !== 'cancel') {
      ElMessage.error(err.message || '操作取消或异常')
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
