<template>
  <el-tag :type="tagType" :effect="effect" size="small">
    {{ status }}
  </el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  status: string
  type?: 'workspace' | 'project' | 'binding' | 'syncRun' | 'issue' | 'pr' | 'review' | 'task' | 'priority' | 'audit' | 'replay'
  draft?: boolean
}>()

const effect = 'light'

const tagType = computed(() => {
  const s = props.status.toUpperCase()

  switch (props.type) {
    case 'workspace':
      return s === 'ACTIVE' ? 'success' : 'danger'

    case 'project':
      if (s === 'PLANNING') return 'info'
      if (s === 'ACTIVE') return 'success'
      if (s === 'ARCHIVED') return 'warning'
      return 'info'

    case 'binding':
      return s === 'ACTIVE' ? 'success' : 'warning'

    case 'syncRun':
      if (s === 'PENDING') return 'info'
      if (s === 'RUNNING') return 'primary'
      if (s === 'RETRY_WAIT') return 'warning'
      if (s === 'SUCCEEDED') return 'success'
      if (s === 'DEAD') return 'danger'
      return 'info'

    case 'issue':
      return s === 'OPEN' ? 'success' : 'info'

    case 'pr':
      if (s === 'OPEN') return 'success'
      if (s === 'CLOSED') return 'info'
      if (s === 'MERGED') return 'primary'
      return 'info'

    case 'review':
      if (s === 'APPROVED') return 'success'
      if (s === 'COMMENTED') return 'info'
      if (s === 'CHANGES_REQUESTED') return 'danger'
      if (s === 'DISMISSED') return 'warning'
      return 'info'

    case 'task':
      if (s === 'BACKLOG') return 'info'
      if (s === 'TODO') return 'primary'
      if (s === 'IN_PROGRESS') return 'warning'
      if (s === 'IN_REVIEW') return 'warning'
      if (s === 'DONE') return 'success'
      if (s === 'CANCELED') return 'danger'
      return 'info'

    case 'priority':
      if (s === 'LOW') return 'info'
      if (s === 'MEDIUM') return 'primary'
      if (s === 'HIGH') return 'warning'
      if (s === 'URGENT') return 'danger'
      return 'info'

    case 'audit':
      if (s === 'SUCCESS') return 'success'
      if (s === 'FAILURE') return 'warning'
      if (s === 'DENIED') return 'danger'
      return 'info'

    case 'replay':
      if (s === 'ACCEPTED' || s === 'SUCCEEDED') return 'success'
      if (s === 'PENDING' || s === 'REPLAYING') return 'warning'
      return 'info'

    default:
      if (['ACTIVE', 'OPEN', 'APPROVED', 'SUCCEEDED', 'UP', 'SUCCESS', 'DONE'].includes(s)) return 'success'
      if (['DISABLED', 'CLOSED', 'ARCHIVED', 'DEAD', 'DOWN', 'FAILURE', 'DENIED', 'CANCELED'].includes(s)) return 'danger'
      return 'info'
  }
})
</script>
