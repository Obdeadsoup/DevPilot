<template>
  <div class="audit-log-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <h2>审计日志查询 (GET .../audit-logs)</h2>
            <span class="sub-text">Workspace ID: {{ workspaceId }}</span>
          </div>
          <el-button @click="fetchData">刷新</el-button>
        </div>
      </template>

      <!-- Filter Controls -->
      <div class="filter-bar">
        <el-form :inline="true">
          <el-form-item label="项目 (projectId)">
            <el-input-number v-model="filter.projectId" :min="1" placeholder="Project ID" style="width: 130px;" controls-position="right" @change="handleFilterChange" />
          </el-form-item>

          <el-form-item label="操作人 User ID">
            <el-input-number v-model="filter.actorUserId" :min="1" placeholder="User ID" style="width: 120px;" controls-position="right" @change="handleFilterChange" />
          </el-form-item>

          <el-form-item label="操作类型 (actionType)">
            <el-select v-model="filter.actionType" placeholder="全部类型" clearable style="width: 180px;" @change="handleFilterChange">
              <el-option label="OUTBOX_REPLAY_REQUESTED" value="OUTBOX_REPLAY_REQUESTED" />
              <el-option label="OUTBOX_REPLAY_CREATED" value="OUTBOX_REPLAY_CREATED" />
              <el-option label="OUTBOX_REPLAY_REJECTED" value="OUTBOX_REPLAY_REJECTED" />
              <el-option label="GITHUB_SYNC_REPLAY_REQUESTED" value="GITHUB_SYNC_REPLAY_REQUESTED" />
              <el-option label="GITHUB_SYNC_REPLAY_CREATED" value="GITHUB_SYNC_REPLAY_CREATED" />
              <el-option label="GITHUB_SYNC_REPLAY_REJECTED" value="GITHUB_SYNC_REPLAY_REJECTED" />
              <el-option label="OUTBOX_DEAD_VIEWED" value="OUTBOX_DEAD_VIEWED" />
              <el-option label="GITHUB_SYNC_DEAD_VIEWED" value="GITHUB_SYNC_DEAD_VIEWED" />
            </el-select>
          </el-form-item>

          <el-form-item label="结果 (result)">
            <el-select v-model="filter.result" placeholder="全部结果" clearable style="width: 130px;" @change="handleFilterChange">
              <el-option label="SUCCESS" value="SUCCESS" />
              <el-option label="FAILURE" value="FAILURE" />
              <el-option label="DENIED" value="DENIED" />
            </el-select>
          </el-form-item>

          <el-form-item label="起始时间">
            <el-date-picker
              v-model="filter.occurredFrom"
              type="datetime"
              placeholder="开始时间"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 175px;"
              @change="handleFilterChange"
            />
          </el-form-item>

          <el-form-item label="截至时间">
            <el-date-picker
              v-model="filter.occurredTo"
              type="datetime"
              placeholder="结束时间"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 175px;"
              @change="handleFilterChange"
            />
          </el-form-item>
        </el-form>
      </div>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" :empty="items.length === 0" @retry="fetchData">
        <el-table :data="items" stripe style="width: 100%;">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="actionType" label="动作类型 (Action)" min-width="210">
            <template #default="{ row }">
              <code>{{ row.actionType }}</code>
            </template>
          </el-table-column>

          <el-table-column prop="result" label="结果" width="100">
            <template #default="{ row }">
              <StatusBadge :status="row.result" type="audit" />
            </template>
          </el-table-column>

          <el-table-column prop="actorType" label="主体 (Actor)" width="120">
            <template #default="{ row }">
              <span>{{ row.actorType }}</span>
              <span v-if="row.actorUserId"> (#{{ row.actorUserId }})</span>
            </template>
          </el-table-column>

          <el-table-column prop="resourceType" label="资源类型" width="160">
            <template #default="{ row }">
              <code>{{ row.resourceType }}</code>
              <span v-if="row.resourceId"> (#{{ row.resourceId }})</span>
            </template>
          </el-table-column>

          <el-table-column prop="reason" label="重放/操作原因" min-width="180">
            <template #default="{ row }">
              <span>{{ row.reason || '-' }}</span>
            </template>
          </el-table-column>

          <el-table-column prop="occurredAt" label="发生时间" min-width="160" />

          <el-table-column label="详情" width="90" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="showDetail(row)">
                查看
              </el-button>
            </template>
          </el-table-column>
        </el-table>

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

        <RawJsonPanel :data="rawJson" title="GET .../audit-logs 原始响应" />
      </PageState>

      <!-- Audit Detail Drawer -->
      <el-drawer v-model="drawerVisible" title="审计日志元数据详情" size="550px">
        <template v-if="selectedRecord">
          <el-descriptions :column="1" border class="mb-3">
            <el-descriptions-item label="Audit Record ID">
              <code>{{ selectedRecord.id }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="Request ID">
              <code>{{ selectedRecord.requestId || 'N/A' }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="Correlation ID">
              <code>{{ selectedRecord.correlationId || 'N/A' }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="错误码 (errorCode)">
              <code>{{ selectedRecord.errorCode || '无' }}</code>
            </el-descriptions-item>
          </el-descriptions>

          <RawJsonPanel :data="selectedRecord.metadataJson" title="Audit Metadata JSON" />
        </template>
      </el-drawer>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { listAuditLogsApi } from '@/api/modules/audit'
import { useScopeStore } from '@/stores/scope'
import type { AuditRecordResponse, AuditActionType, AuditResourceType, AuditResult } from '@/types/audit'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const route = useRoute()
const scopeStore = useScopeStore()

const workspaceId = Number(route.params.workspaceId || scopeStore.currentWorkspaceId || 1)

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')

const filter = reactive({
  projectId: scopeStore.currentProjectId || undefined as number | undefined,
  actorUserId: undefined as number | undefined,
  actionType: '' as AuditActionType | '',
  resourceType: '' as AuditResourceType | '',
  result: '' as AuditResult | '',
  occurredFrom: undefined as string | undefined,
  occurredTo: undefined as string | undefined,
})

const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<AuditRecordResponse[]>([])
const rawJson = ref<any>(null)

const drawerVisible = ref(false)
const selectedRecord = ref<AuditRecordResponse | null>(null)

async function fetchData() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await listAuditLogsApi(workspaceId, {
      projectId: filter.projectId || undefined,
      actorUserId: filter.actorUserId || undefined,
      actionType: (filter.actionType || undefined) as AuditActionType,
      resourceType: (filter.resourceType || undefined) as AuditResourceType,
      result: (filter.result || undefined) as AuditResult,
      occurredFrom: filter.occurredFrom || undefined,
      occurredTo: filter.occurredTo || undefined,
      page: page.value,
      size: size.value,
    })
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      items.value = res.data.items || []
      total.value = res.data.total || 0
    } else {
      hasError.value = true
      errorMsg.value = res.message || '获取审计日志失败'
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = err.message || '网络连接失败'
  } finally {
    loading.value = false
  }
}

function handleFilterChange() {
  page.value = 1
  fetchData()
}

function showDetail(record: AuditRecordResponse) {
  selectedRecord.value = record
  drawerVisible.value = true
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.audit-log-container {
  max-width: 1100px;
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
.filter-bar {
  margin-bottom: 16px;
  padding: 12px;
  background-color: #fafafa;
  border-radius: 6px;
}
.pagination-bar {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.mb-3 {
  margin-bottom: 12px;
}
</style>
