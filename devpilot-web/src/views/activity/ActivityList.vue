<template>
  <div class="activity-list-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <h2>Project Activity 时间线 (GET .../activities)</h2>
            <span class="sub-text">Project ID: {{ projectId }}</span>
          </div>
          <el-button @click="fetchData">刷新</el-button>
        </div>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" :empty="items.length === 0" @retry="fetchData">
        <div class="timeline-wrapper">
          <el-timeline>
            <el-timeline-item
              v-for="item in items"
              :key="item.id"
              :timestamp="item.occurredAt"
              placement="top"
              :type="item.sourceType === 'GITHUB' ? 'primary' : 'info'"
            >
              <el-card shadow="never" class="activity-card">
                <div class="activity-header">
                  <span class="activity-title">{{ item.title }}</span>
                  <div class="tags">
                    <el-tag size="small" type="info">{{ item.sourceType }}</el-tag>
                    <el-tag size="small" type="success">{{ item.activityType }}</el-tag>
                  </div>
                </div>

                <div v-if="item.summary" class="activity-summary">
                  <ExternalContent :content="item.summary" :untrusted="item.sourceType === 'GITHUB'" />
                </div>

                <RawJsonPanel v-if="item.metadataJson" :data="item.metadataJson" title="Activity 元数据 JSON" />
              </el-card>
            </el-timeline-item>
          </el-timeline>
        </div>

        <div class="pagination-bar">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="size"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            :total="total"
            @size-change="fetchData"
            @current-change="fetchData"
          />
        </div>

        <RawJsonPanel :data="rawJson" title="GET .../activities 原始响应" />
      </PageState>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { listActivitiesApi } from '@/api/modules/activity'
import type { ActivityResponse } from '@/types/api'
import PageState from '@/components/PageState.vue'
import ExternalContent from '@/components/ExternalContent.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const route = useRoute()
const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')

const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<ActivityResponse[]>([])
const rawJson = ref<any>(null)

async function fetchData() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await listActivitiesApi(workspaceId, projectId, page.value, size.value)
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      items.value = res.data.items || []
      total.value = res.data.total || 0
    } else {
      hasError.value = true
      errorMsg.value = res.message || '获取 Activity 时间线失败'
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = err.message || '网络连接失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.activity-list-container {
  max-width: 1000px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-header h2 {
  margin: 0;
  font-size: 18px;
  color: #303133;
}
.sub-text {
  font-size: 12px;
  color: #909399;
}
.timeline-wrapper {
  padding: 16px 8px;
}
.activity-card {
  border-radius: 6px;
  background-color: #fdfdfd;
}
.activity-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.activity-title {
  font-weight: 600;
  font-size: 15px;
  color: #303133;
}
.tags {
  display: flex;
  gap: 6px;
}
.activity-summary {
  margin-top: 8px;
}
.pagination-bar {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
