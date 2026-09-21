<template>
  <div class="knowledge-page">
    <el-card>
      <template #header>
        <PageHeader title="项目知识库" description="上传项目文档并通过 Hybrid Retrieval 验证可检索内容。">
          <template #actions>
            <el-button :loading="loading" @click="loadDocuments">刷新</el-button>
          </template>
        </PageHeader>
      </template>

      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="知识检索会先执行当前用户的 Project RBAC，再按项目范围过滤候选内容。"
      />

      <section class="knowledge-section" aria-labelledby="upload-title">
        <div class="section-heading">
          <div>
            <h2 id="upload-title">上传文档</h2>
            <p>支持 Markdown、TXT、PDF，单文件最大 20 MB。</p>
          </div>
        </div>
        <el-upload
          drag
          action="#"
          accept=".md,.txt,.pdf"
          :auto-upload="false"
          :limit="1"
          :file-list="fileList"
          :on-change="handleFileChange"
          :on-remove="handleFileRemove"
        >
          <el-icon class="upload-icon"><UploadFilled /></el-icon>
          <div class="el-upload__text">拖放文件到这里，或<em>点击选择</em></div>
          <template #tip><div class="el-upload__tip">原始文件进入对象存储，解析与索引在后台异步执行。</div></template>
        </el-upload>
        <div class="upload-actions">
          <el-button type="primary" :loading="uploading" :disabled="!selectedFile" @click="uploadDocument">
            上传并开始入库
          </el-button>
        </div>
      </section>

      <el-divider />

      <section class="knowledge-section" aria-labelledby="documents-title">
        <div class="section-heading">
          <div>
            <h2 id="documents-title">知识文档</h2>
            <p>状态从 UPLOADED 进入 INGESTING，完成后可用于检索。</p>
          </div>
          <el-tag effect="plain">{{ documents.length }} 个文档</el-tag>
        </div>
        <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" :empty="documents.length === 0" @retry="loadDocuments">
          <el-table :data="documents" stripe style="width: 100%">
            <el-table-column prop="filename" label="文件" min-width="220" />
            <el-table-column label="大小" width="110"><template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template></el-table-column>
            <el-table-column label="状态" min-width="210">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag>
                <template v-if="row.status === 'FAILED'">
                  <div class="failure-reason">{{ failureReason(row.failureCode) }}</div>
                  <TechnicalDetails summary="失败标识">
                    <code>{{ row.failureCode || 'INGESTION_ERROR' }}</code>
                  </TechnicalDetails>
                </template>
              </template>
            </el-table-column>
            <el-table-column prop="chunkCount" label="Chunks" width="100" />
            <el-table-column label="更新时间" min-width="180"><template #default="{ row }">{{ row.updatedAt }}</template></el-table-column>
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'FAILED'" link type="primary" :loading="retryingId === row.documentId" @click="retryDocument(row)">重试</el-button>
              </template>
            </el-table-column>
          </el-table>
        </PageState>
      </section>
    </el-card>

    <el-card class="search-card">
      <template #header>
        <div class="section-heading">
          <div>
            <h2>Retrieval Playground</h2>
            <p>查看 Query Rewrite、Dense、BM25、RRF 与 Rerank 的最终结果。</p>
          </div>
        </div>
      </template>
      <el-form label-position="top" @submit.prevent="search">
        <el-form-item label="问题">
          <el-input v-model="query" type="textarea" :rows="3" maxlength="2000" show-word-limit placeholder="例如：为什么这个项目使用 Outbox？" />
        </el-form-item>
        <el-button type="primary" :loading="searching" :disabled="!query.trim()" @click="search">检索项目知识</el-button>
      </el-form>

      <template v-if="searchResult">
        <el-descriptions class="search-summary" :column="1" border>
          <el-descriptions-item label="Standalone Query">{{ searchResult.rewrittenQuery }}</el-descriptions-item>
          <el-descriptions-item label="Knowledge Version"><code>{{ searchResult.knowledgeVersion }}</code></el-descriptions-item>
        </el-descriptions>
        <el-empty v-if="searchResult.hits.length === 0" description="没有召回相关内容" />
        <div v-else class="search-results">
          <article v-for="hit in searchResult.hits" :key="hit.chunkId" class="result-item">
            <div class="result-header">
              <strong>{{ hit.sourceFile }}</strong>
              <el-tag size="small" effect="plain">#{{ hit.chunkIndex }}</el-tag>
              <span>Rerank {{ hit.rerankScore.toFixed(3) }}</span>
            </div>
            <p>{{ hit.content }}</p>
            <div class="score-row">
              <span>Dense {{ hit.denseScore.toFixed(3) }}</span>
              <span>BM25 {{ hit.sparseScore.toFixed(3) }}</span>
              <span>RRF {{ hit.fusionScore.toFixed(4) }}</span>
            </div>
          </article>
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, type UploadFile, type UploadFiles, type UploadUserFile } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import {
  listKnowledgeDocumentsApi,
  retryKnowledgeDocumentApi,
  searchProjectKnowledgeApi,
  uploadKnowledgeDocumentApi,
} from '@/api/modules/knowledge'
import PageHeader from '@/components/PageHeader.vue'
import PageState from '@/components/PageState.vue'
import TechnicalDetails from '@/components/TechnicalDetails.vue'
import type { KnowledgeDocument, KnowledgeDocumentStatus, KnowledgeSearchResult } from '@/types/api'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

const route = useRoute()
const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)
const documents = ref<KnowledgeDocument[]>([])
const loading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')
const uploading = ref(false)
const retryingId = ref('')
const fileList = ref<UploadUserFile[]>([])
const selectedFile = ref<File | null>(null)
const query = ref('')
const searching = ref(false)
const searchResult = ref<KnowledgeSearchResult | null>(null)
const queryHistory = ref<string[]>([])
let pollTimer: number | undefined

async function loadDocuments() {
  loading.value = true
  hasError.value = false
  try {
    const res = await listKnowledgeDocumentsApi(workspaceId, projectId)
    if (res.success && res.data) documents.value = res.data
    else {
      hasError.value = true
      errorMsg.value = productErrorMessage(res, '暂时无法加载知识文档。')
    }
  } catch (error: unknown) {
    hasError.value = true
    errorMsg.value = unexpectedErrorMessage(error, '暂时无法加载知识文档。')
  } finally {
    loading.value = false
    schedulePoll()
  }
}

function handleFileChange(file: UploadFile, files: UploadFiles) {
  const raw = file.raw
  const extension = raw?.name.toLowerCase().match(/\.[^.]+$/)?.[0]
  if (!raw || !extension || !['.md', '.txt', '.pdf'].includes(extension)) {
    ElMessage.error('仅支持 Markdown、TXT 或 PDF 文件。')
    selectedFile.value = null
    fileList.value = []
    return
  }
  if (raw.size > 20 * 1024 * 1024) {
    ElMessage.error('单文件不能超过 20 MB。')
    selectedFile.value = null
    fileList.value = []
    return
  }
  selectedFile.value = raw
  fileList.value = files
}

function handleFileRemove() {
  selectedFile.value = null
  fileList.value = []
}

async function uploadDocument() {
  if (!selectedFile.value) return
  uploading.value = true
  try {
    const res = await uploadKnowledgeDocumentApi(workspaceId, projectId, selectedFile.value)
    if (!res.success) return ElMessage.error(productErrorMessage(res, '文档上传失败。'))
    ElMessage.success('文档已上传，正在异步入库')
    selectedFile.value = null
    fileList.value = []
    await loadDocuments()
  } catch (error: unknown) {
    ElMessage.error(unexpectedErrorMessage(error, '文档上传失败。'))
  } finally {
    uploading.value = false
  }
}

async function retryDocument(document: KnowledgeDocument) {
  retryingId.value = document.documentId
  try {
    const res = await retryKnowledgeDocumentApi(workspaceId, projectId, document.documentId, document.version)
    if (!res.success) return ElMessage.error(productErrorMessage(res, '重新入库失败。'))
    ElMessage.success('已重新提交入库任务')
    await loadDocuments()
  } catch (error: unknown) {
    ElMessage.error(unexpectedErrorMessage(error, '重新入库失败。'))
  } finally {
    retryingId.value = ''
  }
}

async function search() {
  const currentQuery = query.value.trim()
  if (!currentQuery) return
  searching.value = true
  try {
    const res = await searchProjectKnowledgeApi(workspaceId, projectId, currentQuery, queryHistory.value.slice(-6))
    if (!res.success || !res.data) return ElMessage.error(productErrorMessage(res, '知识检索失败。'))
    searchResult.value = res.data
    queryHistory.value.push(currentQuery)
  } catch (error: unknown) {
    ElMessage.error(unexpectedErrorMessage(error, '知识检索失败。'))
  } finally {
    searching.value = false
  }
}

function schedulePoll() {
  if (pollTimer) window.clearTimeout(pollTimer)
  if (documents.value.some(document => ['UPLOADED', 'INGESTING'].includes(document.status))) {
    pollTimer = window.setTimeout(loadDocuments, 2000)
  }
}

function statusType(status: KnowledgeDocumentStatus) {
  return ({ READY: 'success', FAILED: 'danger', INGESTING: 'warning', UPLOADED: 'info', DELETED: 'info' } as const)[status]
}

function failureReason(code: string | null) {
  if (code === 'RESOURCEACCESSEXCEPTION') return '模型服务暂时不可用，请稍后重试。'
  if (code === 'ILLEGALARGUMENTEXCEPTION') return '文档没有可入库的内容，请检查文件。'
  return '文档入库失败，请查看详情后重试。'
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

onMounted(loadDocuments)
onUnmounted(() => { if (pollTimer) window.clearTimeout(pollTimer) })
</script>

<style scoped>
.knowledge-page { max-width: var(--content-max); margin: 0 auto; }
.search-card { margin-top: var(--space-5); }
.knowledge-section { margin-top: var(--space-6); }
.section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-4); margin-bottom: var(--space-4); }
.section-heading h2 { margin: 0; font-size: var(--font-size-lg); }
.section-heading p { margin: var(--space-1) 0 0; color: var(--color-text-muted); font-size: var(--font-size-sm); }
.upload-icon { font-size: 48px; color: var(--color-text-subtle); }
.upload-actions { margin-top: var(--space-4); }
.search-summary { margin-top: var(--space-6); }
.search-results { display: grid; gap: var(--space-3); margin-top: var(--space-4); }
.result-item { padding: var(--space-4); border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface-subtle); }
.result-item p { white-space: pre-wrap; line-height: 1.65; }
.result-header, .score-row { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-2); }
.result-header span, .score-row { color: var(--color-text-muted); font-size: var(--font-size-xs); }
.failure-reason { margin-top: var(--space-1); color: var(--color-text-muted); font-size: var(--font-size-xs); }
.knowledge-page :deep(.technical-details) { margin-top: var(--space-1); }
</style>
