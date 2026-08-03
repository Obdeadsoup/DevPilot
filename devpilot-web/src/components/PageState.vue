<template>
  <div>
    <!-- Loading Skeleton -->
    <div v-if="loading" class="state-loading">
      <el-skeleton :rows="skeletonRows" animated />
    </div>

    <!-- Error State -->
    <div v-else-if="error" class="state-error">
      <el-result
        icon="error"
        :title="errorTitle || '数据加载失败'"
        :sub-title="errorMsg"
      >
        <template #extra>
          <el-button type="primary" @click="$emit('retry')">重试 / 刷新</el-button>
        </template>
      </el-result>
    </div>

    <!-- Empty State -->
    <div v-else-if="empty" class="state-empty">
      <el-empty :description="emptyText || '暂无数据'">
        <template #extra>
          <slot name="empty-action" />
        </template>
      </el-empty>
    </div>

    <!-- Content Slot -->
    <slot v-else />
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    loading?: boolean
    error?: boolean
    empty?: boolean
    errorTitle?: string
    errorMsg?: string
    emptyText?: string
    skeletonRows?: number
  }>(),
  {
    loading: false,
    error: false,
    empty: false,
    errorTitle: '',
    errorMsg: '',
    emptyText: '',
    skeletonRows: 5,
  }
)

defineEmits(['retry'])
</script>

<style scoped>
.state-loading, .state-error, .state-empty {
  padding: 24px;
}
</style>
