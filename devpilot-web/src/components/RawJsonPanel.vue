<template>
  <div class="raw-json-panel">
    <el-collapse v-model="activeNames">
      <el-collapse-item name="json">
        <template #title>
          <div class="panel-header">
            <span>{{ title || '原始 JSON / 响应 Payload' }}</span>
            <el-button type="primary" link size="small" @click.stop="copyJson">
              复制 JSON
            </el-button>
          </div>
        </template>
        <pre class="json-code"><code>{{ formattedJson }}</code></pre>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps<{
  data: any
  title?: string
}>()

const activeNames = ref<string[]>([])

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
  margin-top: 16px;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  padding-right: 12px;
  font-weight: 500;
}
.json-code {
  background-color: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  font-family: 'Fira Code', Consolas, Monaco, monospace;
  font-size: 13px;
  max-height: 400px;
}
</style>
