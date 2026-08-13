<template>
  <div class="task-history-container">
    <el-empty v-if="!history || history.length === 0" description="暂无状态变更历史" :image-size="60" />

    <el-timeline v-else>
      <el-timeline-item
        v-for="item in history"
        :key="item.id"
        :timestamp="item.occurredAt"
        placement="top"
        type="primary"
      >
        <div class="history-card">
          <div class="history-header">
            <span class="action-tag">
              <el-tag size="small" type="success">{{ item.action }}</el-tag>
            </span>
            <span class="actor">操作人: <code>User #{{ item.actorUserId }}</code></span>
            <span class="version">Target Version: <code>v{{ item.taskVersion }}</code></span>
          </div>

          <div class="status-change">
            <StatusBadge v-if="item.fromStatus" :status="item.fromStatus" type="task" />
            <span v-else class="text-muted">CREATED</span>
            <span class="arrow">→</span>
            <StatusBadge :status="item.toStatus" type="task" />
          </div>

          <div v-if="item.reason" class="reason-box">
            <span class="reason-label">说明: </span>{{ item.reason }}
          </div>
        </div>
      </el-timeline-item>
    </el-timeline>
  </div>
</template>

<script setup lang="ts">
import type { TaskStatusHistoryResponse } from '@/types/task'
import StatusBadge from '@/components/StatusBadge.vue'

defineProps<{
  history: TaskStatusHistoryResponse[]
}>()
</script>

<style scoped>
.task-history-container {
  padding: 8px 0;
}
.history-card {
  background-color: #fafafa;
  border-radius: 6px;
  padding: 10px 14px;
  border: 1px solid #ebeef5;
}
.history-header {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}
.status-change {
  display: flex;
  align-items: center;
  gap: 8px;
}
.arrow {
  color: #909399;
  font-weight: bold;
}
.reason-box {
  margin-top: 6px;
  font-size: 13px;
  color: #606266;
  background-color: #ffffff;
  padding: 6px 10px;
  border-radius: 4px;
  border: 1px dashed #dcdfe6;
}
.reason-label {
  font-weight: 600;
  color: #303133;
}
.text-muted {
  color: #909399;
  font-size: 12px;
}
</style>
