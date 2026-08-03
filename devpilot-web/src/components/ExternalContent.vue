<template>
  <div class="external-content-container">
    <div v-if="untrusted" class="security-warning">
      <el-tag type="danger" size="small" effect="dark">
        安全提示: 不可信外部内容
      </el-tag>
      <span class="warning-text">来自 GitHub 的原始文本，已禁用 HTML/Script 渲染以防止 XSS</span>
    </div>
    <div class="content-body">
      <pre v-if="content">{{ sanitizedText }}</pre>
      <el-empty v-else description="无正文内容 (Empty Body)" :image-size="60" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { sanitizeExternalText } from '@/utils/safeExternalContent'

const props = defineProps<{
  content?: string | null
  untrusted?: boolean
}>()

const sanitizedText = computed(() => sanitizeExternalText(props.content))
</script>

<style scoped>
.external-content-container {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  background-color: #fafafa;
  padding: 12px;
  margin-top: 8px;
}
.security-warning {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px dashed #dcdfe6;
}
.warning-text {
  font-size: 12px;
  color: #909399;
}
.content-body pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: inherit;
  font-size: 14px;
  color: #303133;
}
</style>
