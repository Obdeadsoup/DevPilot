<template>
  <div class="pr-detail-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span>GitHub Pull Request 详情 (ID: {{ pullRequestId }})</span>
            <StatusBadge v-if="pr" :status="pr.status" type="pr" style="margin-left: 12px;" />
            <el-tag v-if="pr && pr.draft" type="info" size="small" style="margin-left: 8px;">Draft</el-tag>
          </div>
          <div>
            <el-button link @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/github/pull-requests`)">
              返回列表
            </el-button>
            <el-button size="small" @click="fetchData">刷新</el-button>
          </div>
        </div>
      </template>

      <PageState :loading="loading" :error="hasError" :error-msg="errorMsg" @retry="fetchData">
        <template v-if="pr">
          <div class="pr-title">
            <h2>#{{ pr.number }} {{ pr.title }}</h2>
          </div>

          <el-descriptions :column="2" border class="mb-4">
            <el-descriptions-item label="PR 快照主键 ID">
              <code>{{ pr.id }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="GitHub 官方 PR ID">
              <code>{{ pr.githubPullRequestId }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="作者 (Author)">
              <code>@{{ pr.authorLogin }}</code>
            </el-descriptions-item>
            <el-descriptions-item label="Head Ref / SHA">
              <code>{{ pr.headRef }}</code> (<code>{{ pr.headSha.substring(0, 7) }}</code>)
            </el-descriptions-item>
            <el-descriptions-item label="Base Ref / SHA">
              <code>{{ pr.baseRef }}</code> (<code>{{ pr.baseSha.substring(0, 7) }}</code>)
            </el-descriptions-item>
            <el-descriptions-item label="合并/关闭时间">
              <span v-if="pr.mergedAt">合并于 {{ pr.mergedAt }}</span>
              <span v-else-if="pr.closedAt">关闭于 {{ pr.closedAt }}</span>
              <span v-else class="text-muted">OPEN 未合并</span>
            </el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">PR 正文 (Body)</el-divider>
          <ExternalContent :content="pr.body" :untrusted="pr.externalUntrustedContent" />

          <!-- Reviews Section -->
          <el-divider content-position="left">
            Reviews 评审列表 (GET .../pull-requests/{{ pullRequestId }}/reviews)
          </el-divider>

          <div v-if="reviewsLoading" class="p-3">
            <el-skeleton :rows="3" animated />
          </div>

          <div v-else-if="reviews.length === 0" class="empty-reviews">
            <el-empty description="暂无 Review 记录" :image-size="60" />
          </div>

          <div v-else class="reviews-list">
            <el-card v-for="rev in reviews" :key="rev.id" class="review-item" shadow="never">
              <div class="review-header">
                <div>
                  <span class="reviewer">@{{ rev.reviewerLogin }}</span>
                  <StatusBadge :status="rev.state" type="review" style="margin-left: 8px;" />
                </div>
                <span class="review-time">{{ rev.submittedAt }}</span>
              </div>
              <div v-if="rev.body" class="review-body">
                <ExternalContent :content="rev.body" :untrusted="rev.externalUntrustedContent" />
              </div>
            </el-card>
          </div>

          <RawJsonPanel :data="rawJson" title="GET .../pull-requests/{id} 原始响应" />
        </template>
      </PageState>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getPullRequestApi, listReviewsApi } from '@/api/modules/snapshot'
import type { GitHubPullRequest, GitHubReview } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import PageState from '@/components/PageState.vue'
import ExternalContent from '@/components/ExternalContent.vue'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const route = useRoute()
const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)
const pullRequestId = Number(route.params.pullRequestId)

const loading = ref(false)
const reviewsLoading = ref(false)
const hasError = ref(false)
const errorMsg = ref('')

const pr = ref<GitHubPullRequest | null>(null)
const reviews = ref<GitHubReview[]>([])
const rawJson = ref<any>(null)

async function fetchData() {
  loading.value = true
  hasError.value = false
  errorMsg.value = ''

  try {
    const res = await getPullRequestApi(workspaceId, projectId, pullRequestId)
    rawJson.value = res.rawJson
    if (res.success && res.data) {
      pr.value = res.data
      fetchReviews()
    } else {
      hasError.value = true
      errorMsg.value = res.message || 'Pull Request 快照不存在'
    }
  } catch (err: any) {
    hasError.value = true
    errorMsg.value = err.message || '网络连接失败'
  } finally {
    loading.value = false
  }
}

async function fetchReviews() {
  reviewsLoading.value = true
  try {
    const res = await listReviewsApi(workspaceId, projectId, pullRequestId)
    if (res.success && res.data) {
      reviews.value = res.data
    }
  } catch {
    // Reviews error handled gracefully
  } finally {
    reviewsLoading.value = false
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.pr-detail-container {
  max-width: 900px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.pr-title h2 {
  margin: 0 0 16px 0;
  font-size: 20px;
  color: #303133;
}
.mb-4 {
  margin-bottom: 16px;
}
.text-muted {
  color: #909399;
}
.reviews-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.review-item {
  border-radius: 6px;
  background-color: #fcfcfc;
}
.review-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.reviewer {
  font-weight: 600;
  color: #303133;
}
.review-time {
  font-size: 12px;
  color: #909399;
}
.review-body {
  margin-top: 8px;
}
.empty-reviews {
  padding: 12px;
}
</style>
