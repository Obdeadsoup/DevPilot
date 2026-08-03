<template>
  <div class="repository-create-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>绑定 GitHub 仓库 (POST .../github-repositories)</span>
          <el-button link @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/repositories`)">
            返回列表
          </el-button>
        </div>
      </template>

      <el-alert
        title="凭据引用名称说明 (Security Warning)"
        type="warning"
        show-icon
        :closable="false"
        style="margin-bottom: 20px;"
      >
        <template #default>
          <div>
            <code>apiCredentialRef</code> 与 <code>webhookSecretRef</code> 填写的是服务器宿主<b>环境变量名</b>（例如 <code>DEVPILOT_GITHUB_API_TOKEN_LOCAL</code>），<b>绝不能输入真实的 GitHub PAT 或 Webhook 密钥明文！</b>
          </div>
        </template>
      </el-alert>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        style="max-width: 600px;"
      >
        <el-form-item label="GitHub Owner / Organization (owner)" prop="owner">
          <el-input
            v-model="form.owner"
            placeholder="例如: example-org"
            maxlength="39"
          />
        </el-form-item>

        <el-form-item label="GitHub 仓库名 (repositoryName)" prop="repositoryName">
          <el-input
            v-model="form.repositoryName"
            placeholder="例如: example-repo"
            maxlength="100"
          />
        </el-form-item>

        <el-form-item label="API Token 环境变量引用名 (apiCredentialRef)" prop="apiCredentialRef">
          <el-input
            v-model="form.apiCredentialRef"
            placeholder="例如: DEVPILOT_GITHUB_API_TOKEN_LOCAL"
            maxlength="200"
          />
          <div class="field-hint">建议格式: <code>^DEVPILOT_GITHUB_API_TOKEN_[A-Z0-9_]+$</code></div>
        </el-form-item>

        <el-form-item label="Webhook Secret 环境变量引用名 (webhookSecretRef)" prop="webhookSecretRef">
          <el-input
            v-model="form.webhookSecretRef"
            placeholder="例如: DEVPILOT_GITHUB_WEBHOOK_SECRET_LOCAL"
            maxlength="200"
          />
          <div class="field-hint">建议格式: <code>^DEVPILOT_GITHUB_WEBHOOK_SECRET_[A-Z0-9_]+$</code></div>
        </el-form-item>

        <el-form-item style="margin-top: 24px;">
          <el-button type="primary" :loading="loading" @click="handleSubmit">
            提交绑定 (将验证 GitHub REST API)
          </el-button>
          <el-button @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/repositories`)">
            取消
          </el-button>
        </el-form-item>
      </el-form>

      <RawJsonPanel v-if="rawJson" :data="rawJson" title="绑定仓库响应 JSON" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createRepositoryApi } from '@/api/modules/repository'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const route = useRoute()
const router = useRouter()

const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)

const formRef = ref<FormInstance>()
const loading = ref(false)
const rawJson = ref<any>(null)

const form = reactive({
  owner: '',
  repositoryName: '',
  apiCredentialRef: 'DEVPILOT_GITHUB_API_TOKEN_LOCAL',
  webhookSecretRef: 'DEVPILOT_GITHUB_WEBHOOK_SECRET_LOCAL',
})

const validateOwner = (_rule: any, value: string, callback: any) => {
  if (!value) return callback(new Error('请输入 Owner'))
  if (value.includes('--')) {
    return callback(new Error('GitHub Owner 不能包含连续的连字符 "--"'))
  }
  callback()
}

const validateRepo = (_rule: any, value: string, callback: any) => {
  if (!value) return callback(new Error('请输入仓库名'))
  if (value === '.' || value === '..') {
    return callback(new Error('仓库名不能为 "." 或 ".."'))
  }
  callback()
}

const rules: FormRules = {
  owner: [{ validator: validateOwner, trigger: 'blur' }],
  repositoryName: [{ validator: validateRepo, trigger: 'blur' }],
  apiCredentialRef: [{ required: true, message: '请输入 API 凭据引用名称', trigger: 'blur' }],
  webhookSecretRef: [{ required: true, message: '请输入 Webhook 密钥引用名称', trigger: 'blur' }],
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true

    try {
      const res = await createRepositoryApi(workspaceId, projectId, {
        owner: form.owner.trim(),
        repositoryName: form.repositoryName.trim(),
        apiCredentialRef: form.apiCredentialRef.trim(),
        webhookSecretRef: form.webhookSecretRef.trim(),
      })
      rawJson.value = res.rawJson

      if (res.success && res.data) {
        ElMessage.success('GitHub 仓库绑定成功')
        router.push(`/workspaces/${workspaceId}/projects/${projectId}/repositories/${res.data.id}`)
      } else {
        ElMessage.error(`绑定失败 [${res.code}]: ${res.message}`)
      }
    } catch (err: any) {
      ElMessage.error(err.message || '网络无法连接')
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.repository-create-container {
  max-width: 800px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.field-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
</style>
