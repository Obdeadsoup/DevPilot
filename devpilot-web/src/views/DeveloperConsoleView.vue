<template>
  <div class="dev-console-container">
    <el-card shadow="never">
      <template #header>
        <div class="console-header">
          <div>
            <h3>开发者联调审计日志 (Developer Inspector)</h3>
            <span class="sub-text">记录最近 {{ devConsoleStore.logs.length }} / 20 次前端 API 请求（仅存内存，敏感信息已脱敏）</span>
          </div>
          <div>
            <el-button type="danger" size="small" link @click="devConsoleStore.clearLogs">
              清空日志
            </el-button>
          </div>
        </div>
      </template>

      <!-- Static Notice for GitHub Webhook -->
      <el-alert
        title="GitHub Webhook 接收端说明 (External Callback API): POST /api/v1/github/webhooks"
        type="warning"
        description="本接口由 GitHub 服务器向 DevPilot 后端发起回调验证，依赖 X-Hub-Signature-256 HMAC 签名。前端只提供静态说明，严禁发起请求或伪造回调。"
        show-icon
        :closable="false"
        style="margin-bottom: 16px;"
      />

      <el-empty v-if="devConsoleStore.logs.length === 0" description="暂无请求记录" />

      <div v-else class="logs-list">
        <el-card
          v-for="log in devConsoleStore.logs"
          :key="log.id"
          class="log-item"
          shadow="hover"
        >
          <div class="log-top">
            <div class="log-title">
              <el-tag :type="log.method === 'GET' ? 'success' : 'warning'" size="small" effect="dark">
                {{ log.method }}
              </el-tag>
              <span class="url-text">{{ log.url }}</span>
            </div>
            <div class="log-meta">
              <StatusBadge :status="log.code || 'UNKNOWN'" />
              <el-tag :type="log.httpStatus === 200 || log.httpStatus === 201 ? 'success' : 'danger'" size="small">
                HTTP {{ log.httpStatus || 'N/A' }}
              </el-tag>
              <span class="time-text">{{ log.durationMs }} ms</span>
              <span class="time-text">{{ log.timestamp }}</span>
            </div>
          </div>

          <div class="log-details">
            <el-descriptions :column="2" size="small" border>
              <el-descriptions-item label="Business Code">
                <code>{{ log.code }}</code>
              </el-descriptions-item>
              <el-descriptions-item label="Message">
                {{ log.message }}
              </el-descriptions-item>
              <el-descriptions-item label="Headers (Redacted)">
                <pre class="inline-code">{{ JSON.stringify(log.headers, null, 2) }}</pre>
              </el-descriptions-item>
              <el-descriptions-item label="Request Body (Redacted)">
                <pre class="inline-code">{{ log.body ? JSON.stringify(log.body, null, 2) : 'null' }}</pre>
              </el-descriptions-item>
            </el-descriptions>

            <div class="log-actions">
              <el-button type="primary" size="small" link @click="copyCurl(log)">
                复制等价 curl (含占位符)
              </el-button>
              <el-button
                v-if="log.method === 'GET'"
                type="success"
                size="small"
                link
                @click="refetchGet(log)"
              >
                再次刷新 (仅限预定义 GET)
              </el-button>
            </div>

            <RawJsonPanel :data="log.rawResponse" title="原始响应 JSON (Raw Response)" />
          </div>
        </el-card>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { useDeveloperConsoleStore } from '@/stores/developerConsole'
import { generateCurlCommand } from '@/utils/curl'
import { request } from '@/api/client'
import type { RequestAuditLog } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

defineProps<{
  embedded?: boolean
}>()

const devConsoleStore = useDeveloperConsoleStore()

function copyCurl(log: RequestAuditLog) {
  const curlCmd = generateCurlCommand(log.method, log.url, log.headers, log.body)
  navigator.clipboard.writeText(curlCmd)
  ElMessage.success('已复制 curl 命令 (包含安全占位符)')
}

async function refetchGet(log: RequestAuditLog) {
  if (log.method !== 'GET') {
    ElMessage.warning('禁止对非 GET 写请求重放')
    return
  }
  ElMessage.info(`正在重新获取: ${log.url}`)
  await request({
    url: log.url,
    method: 'GET',
  })
}
</script>

<style scoped>
.dev-console-container {
  padding: 8px;
}
.console-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.console-header h3 {
  margin: 0;
  font-size: 16px;
  color: #303133;
}
.sub-text {
  font-size: 12px;
  color: #909399;
}
.logs-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.log-item {
  border-radius: 6px;
}
.log-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.log-title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.url-text {
  font-family: Consolas, monospace;
  font-weight: 600;
  font-size: 14px;
  color: #2c3e50;
}
.log-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.time-text {
  font-size: 12px;
  color: #909399;
}
.log-details {
  margin-top: 8px;
}
.inline-code {
  margin: 0;
  font-size: 12px;
  max-height: 80px;
  overflow: auto;
  white-space: pre-wrap;
}
.log-actions {
  display: flex;
  gap: 16px;
  margin-top: 12px;
}
</style>
