import type { TaskStatus, TaskAction, TaskPriority } from '@/types/task'

export const TASK_STATUS_TRANSITIONS: Record<TaskStatus, { action: TaskAction; endpoint: string; label: string; type: string }[]> = {
  BACKLOG: [
    { action: 'PLANNED', endpoint: 'plan', label: '加入待办', type: 'primary' },
    { action: 'CANCELED', endpoint: 'cancel', label: '取消任务', type: 'danger' },
  ],
  TODO: [
    { action: 'STARTED', endpoint: 'start', label: '开始处理', type: 'primary' },
    { action: 'RETURNED_TO_BACKLOG', endpoint: 'return-to-backlog', label: '返回待规划', type: 'info' },
    { action: 'CANCELED', endpoint: 'cancel', label: '取消任务', type: 'danger' },
  ],
  IN_PROGRESS: [
    { action: 'SUBMITTED_FOR_REVIEW', endpoint: 'submit-for-review', label: '提交审核', type: 'warning' },
    { action: 'CANCELED', endpoint: 'cancel', label: '取消任务', type: 'danger' },
  ],
  IN_REVIEW: [
    { action: 'COMPLETED', endpoint: 'complete', label: '确认完成', type: 'success' },
    { action: 'CHANGES_REQUESTED', endpoint: 'request-changes', label: '要求修改', type: 'warning' },
    { action: 'CANCELED', endpoint: 'cancel', label: '取消任务', type: 'danger' },
  ],
  DONE: [
    { action: 'REOPENED', endpoint: 'reopen', label: '重新打开', type: 'info' },
  ],
  CANCELED: [
    { action: 'REOPENED', endpoint: 'reopen', label: '重新打开', type: 'info' },
  ],
}

export const TASK_STATUS_LABELS: Record<TaskStatus, { label: string; tagType: string }> = {
  BACKLOG: { label: 'BACKLOG', tagType: 'info' },
  TODO: { label: 'TODO', tagType: 'primary' },
  IN_PROGRESS: { label: 'IN_PROGRESS', tagType: 'warning' },
  IN_REVIEW: { label: 'IN_REVIEW', tagType: 'warning' },
  DONE: { label: 'DONE', tagType: 'success' },
  CANCELED: { label: 'CANCELED', tagType: 'danger' },
}

export const TASK_PRIORITY_LABELS: Record<TaskPriority, { label: string; tagType: string }> = {
  LOW: { label: 'LOW', tagType: 'info' },
  MEDIUM: { label: 'MEDIUM', tagType: '' },
  HIGH: { label: 'HIGH', tagType: 'warning' },
  URGENT: { label: 'URGENT', tagType: 'danger' },
}
