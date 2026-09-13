<template>
  <div class="page-header">
    <div class="page-header__copy">
      <h1>{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header__actions"><slot name="actions" /></div>
  </div>
</template>

<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>

<style scoped>
.page-header { display: flex; align-items: center; justify-content: space-between; gap: var(--space-5); }
.page-header__copy { min-width: 0; }
.page-header h1 { margin: 0; color: var(--color-text); font-size: var(--font-size-lg); letter-spacing: -.01em; }
.page-header p { margin: var(--space-1) 0 0; color: var(--color-text-muted); font-size: var(--font-size-sm); line-height: 1.5; }
.page-header__actions { display: flex; flex: 0 0 auto; gap: var(--space-2); }
@media (max-width: 640px) {
  .page-header { align-items: flex-start; flex-direction: column; }
  .page-header__actions { width: 100%; }
  .page-header__actions :deep(.el-button) { flex: 1; margin-left: 0; }
}
</style>
