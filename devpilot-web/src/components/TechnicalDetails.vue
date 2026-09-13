<template>
  <el-collapse v-model="activeSections" class="technical-details">
    <el-collapse-item name="details">
      <template #title>
        <div class="technical-details__title">
          <span>{{ title }}</span>
          <small v-if="summary">{{ summary }}</small>
        </div>
      </template>
      <div class="technical-details__body">
        <slot />
      </div>
    </el-collapse-item>
  </el-collapse>
</template>

<script setup lang="ts">
import { ref } from 'vue'

withDefaults(defineProps<{
  title?: string
  summary?: string
}>(), {
  title: '技术详情',
  summary: '运行标识与诊断信息',
})

const activeSections = ref<string[]>([])
</script>

<style scoped>
.technical-details {
  margin-top: var(--space-5);
  border-top: 1px solid var(--color-border);
  border-bottom: 0;
}

.technical-details__title {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  font-weight: 600;
}

.technical-details__title small {
  color: var(--color-text-subtle);
  font-size: var(--font-size-xs);
  font-weight: 400;
}

.technical-details__body {
  padding-bottom: var(--space-2);
}

@media (max-width: 560px) {
  .technical-details__title small { display: none; }
}
</style>
