<template>
  <div class="workspace-list-container">
    <el-card>
      <template #header>
        <PageHeader title="工作区" description="选择团队空间，继续进入项目与研发工作流。">
          <template #actions>
            <el-button type="primary" @click="$router.push('/workspaces/new')">
              创建工作区
            </el-button>
            <el-button @click="fetchData">刷新</el-button>
          </template>
        </PageHeader>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" :empty="items.length === 0" @retry="fetchData">
        <template #empty-action>
          <el-button type="primary" @click="$router.push('/workspaces/new')">创建首个工作区</el-button>
        </template>

        <el-table :data="items" stripe style="width: 100%;">
          <el-table-column prop="name" label="名称" min-width="180" />
          <el-table-column prop="slug" label="访问标识" min-width="120">
            <template #default="{ row }">
              <code>{{ row.slug }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="120">
            <template #default="{ row }">
              <StatusBadge :status="row.status" type="workspace" />
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" min-width="160" />
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="enterWorkspace(row)">
                进入项目列表
              </el-button>
              <el-button type="info" link size="small" @click="$router.push(`/workspaces/${row.id}`)">
                详情/编辑
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

      </PageState>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listWorkspacesApi } from '@/api/modules/workspace'
import { useScopeStore } from '@/stores/scope'
import type { Workspace } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import PageHeader from '@/components/PageHeader.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

const router = useRouter()
const scopeStore = useScopeStore()

const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')

const page = ref(1)
const size = ref(20)
const total = ref(0)
const items = ref<Workspace[]>([])

async function fetchData() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await listWorkspacesApi(page.value, size.value)
    if (res.success && res.data) {
      items.value = res.data.items || []
      total.value = res.data.total || 0
    } else {
      hasError.value = true
      errorMsg.value = productErrorMessage(res, '暂时无法加载工作区，请稍后重试。')
    }
  } catch (err: unknown) {
    hasError.value = true
    errorMsg.value = unexpectedErrorMessage(err, '暂时无法加载工作区，请稍后重试。')
  } finally {
    loading.value = false
  }
}

function enterWorkspace(ws: Workspace) {
  scopeStore.setWorkspace(ws.id, ws.name)
  scopeStore.clearProject()
  router.push(`/workspaces/${ws.id}/projects`)
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.workspace-list-container {
  max-width: 1100px;
  margin: 0 auto;
}
.pagination-bar {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
