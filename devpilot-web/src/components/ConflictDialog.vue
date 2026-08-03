<template>
  <el-dialog
    v-model="visible"
    title="并发更新冲突 (409 Conflict)"
    width="520px"
    :close-on-click-modal="false"
  >
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="资源版本号 (version) 已失效"
    >
      <template #default>
        <div>后端业务错误码: <code>{{ code }}</code></div>
        <div style="margin-top: 4px;">{{ message || '资源已被其他请求并发修改，无法直接写入。' }}</div>
      </template>
    </el-alert>

    <div style="margin-top: 16px;">
      <p>按照 DevPilot 接口规范：</p>
      <ul>
        <li>禁止自动静默重试更新请求。</li>
        <li>请先点击“重新获取最新数据”拉取最新版本 (expectedVersion)。</li>
        <li>确认无误后再提交修改。</li>
      </ul>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="handleRefresh">
        重新获取最新数据
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'

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
