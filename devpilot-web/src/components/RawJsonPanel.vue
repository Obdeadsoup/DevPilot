<template>
  <div class="raw-json-panel">
    <TechnicalDetails :title="title || '技术详情'" summary="原始数据与诊断信息">
      <div class="panel-header">
        <span>原始数据</span>
        <el-button type="primary" link size="small" @click="copyJson">
          复制 JSON
        </el-button>
      </div>
      <pre class="json-code"><code>{{ formattedJson }}</code></pre>
    </TechnicalDetails>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import TechnicalDetails from '@/components/TechnicalDetails.vue'

const props = defineProps<{
  data: any
  title?: string
}>()

const formattedJson = computed(() => {
  if (props.data === undefined || props.data === null) {
    return 'null'
  }
  if (typeof props.data === 'string') {
    try {
      return JSON.stringify(JSON.parse(props.data), null, 2)
    } catch {
      return props.data
    }
  }
  try {
    return JSON.stringify(props.data, null, 2)
  } catch {
    return String(props.data)
  }
})

function copyJson() {
  navigator.clipboard.writeText(formattedJson.value)
  ElMessage.success('已复制到剪贴板')
}
</script>

<style scoped>
.raw-json-panel {
  margin-top: var(--space-5);
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  margin-bottom: var(--space-2);
  font-weight: 500;
}
.json-code {
  margin: 0;
  padding: var(--space-3);
  color: #d4d4d4;
  background-color: #1e1e1e;
  border-radius: var(--radius-sm);
  overflow-x: auto;
  font-family: 'Fira Code', Consolas, Monaco, monospace;
  font-size: 13px;
  max-height: 400px;
}
</style>
