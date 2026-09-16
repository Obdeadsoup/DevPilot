<template>
  <el-dialog
    v-model="visible"
    title="内容已更新"
    width="520px"
    :close-on-click-modal="false"
  >
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="其他协作者已更新这项内容"
    >
      <template #default>
        <div>为避免覆盖他人的修改，请先获取最新内容，再重新提交你的更改。</div>
      </template>
    </el-alert>

    <div style="margin-top: 16px;">
      <ul>
        <li>点击“获取最新内容”刷新当前页面。</li>
        <li>确认最新内容后，再次完成本次操作。</li>
      </ul>
    </div>

    <TechnicalDetails summary="错误标识与原始信息">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="错误标识"><code>{{ code || '—' }}</code></el-descriptions-item>
        <el-descriptions-item label="原始信息">{{ message || '无' }}</el-descriptions-item>
      </el-descriptions>
    </TechnicalDetails>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="handleRefresh">
        获取最新内容
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import TechnicalDetails from '@/components/TechnicalDetails.vue'

const emit = defineEmits(['refresh'])

const visible = ref(false)
const code = ref('')
const message = ref('')

function show(errorCode: string, errorMsg: string) {
  code.value = errorCode
  message.value = errorMsg
  visible.value = true
}

function handleRefresh() {
  visible.value = false
  emit('refresh')
}

defineExpose({
  show,
})
</script>
