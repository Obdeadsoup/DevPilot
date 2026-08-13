<template>
  <div class="task-action-bar">
    <span class="action-label">状态流转动作: </span>
    <template v-if="availableActions.length">
      <el-button
        v-for="act in availableActions"
        :key="act.endpoint"
        :type="act.type as any"
        size="small"
        :loading="loadingAction === act.endpoint"
        @click="openActionDialog(act)"
      >
        {{ act.label }}
      </el-button>
    </template>
    <span v-else class="text-muted">当前状态无可用状态流转</span>

    <!-- Action Confirmation Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="`确认执行: ${selectedAction?.label}`"
      width="480px"
      :close-on-click-modal="false"
    >
      <div style="margin-bottom: 12px;">
        确定要执行此操作吗？当前版本号 <code>expectedVersion = {{ version }}</code>。
      </div>

      <el-form label-position="top">
        <el-form-item label="操作原因说明 (reason, 可选)">
          <el-input
            v-model="reasonInput"
            type="textarea"
            :rows="2"
            placeholder="可选填写本次状态变更原因说明 (最多 1000 字符)"
            maxlength="1000"
            show-word-limit
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="confirmAction">
          确认提交
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { TASK_STATUS_TRANSITIONS } from '@/utils/taskTransitions'
import type { TaskStatus } from '@/types/task'

const props = defineProps<{
  status: TaskStatus
  version: number
  loadingAction?: string | null
}>()

const emit = defineEmits<{
  (e: 'execute-action', endpoint: string, reason?: string): void
}>()

const availableActions = computed(() => {
  return TASK_STATUS_TRANSITIONS[props.status] || []
})

const dialogVisible = ref(false)
const selectedAction = ref<any>(null)
const reasonInput = ref('')
const submitting = ref(false)

function openActionDialog(act: any) {
  selectedAction.value = act
  reasonInput.value = ''
  dialogVisible.value = true
}

function confirmAction() {
  if (!selectedAction.value) return
  submitting.value = true
  emit('execute-action', selectedAction.value.endpoint, reasonInput.value.trim() || undefined)
  dialogVisible.value = false
  submitting.value = false
}
</script>

<style scoped>
.task-action-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.action-label {
  font-weight: 600;
  font-size: 13px;
  color: #606266;
}
.text-muted {
  font-size: 13px;
  color: #909399;
}
</style>
