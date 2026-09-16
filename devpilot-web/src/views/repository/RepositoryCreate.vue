<template>
  <div class="repository-create-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>绑定 GitHub 仓库</span>
          <el-button link @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/repositories`)">
            返回列表
          </el-button>
        </div>
      </template>

      <el-alert
        title="凭据安全"
        type="warning"
        show-icon
        :closable="false"
        style="margin-bottom: 20px;"
      >
        <template #default>
          <div>
            此处只填写服务端已经配置的凭据引用名称，<b>不要输入真实的访问令牌或 Webhook 密钥。</b>
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
        <el-form-item label="GitHub 所有者或组织" prop="owner">
          <el-input
            v-model="form.owner"
            placeholder="例如：example-org"
            maxlength="39"
          />
        </el-form-item>

        <el-form-item label="GitHub 仓库名" prop="repositoryName">
          <el-input
            v-model="form.repositoryName"
            placeholder="例如：example-repo"
            maxlength="100"
          />
        </el-form-item>

        <el-form-item label="访问凭据引用名称" prop="apiCredentialRef">
          <el-input
            v-model="form.apiCredentialRef"
            placeholder="输入服务端已配置的引用名称"
            maxlength="200"
          />
          <div class="field-hint">该名称用于定位服务端凭据，不会保存令牌明文。</div>
        </el-form-item>

        <el-form-item label="Webhook 密钥引用名称" prop="webhookSecretRef">
          <el-input
            v-model="form.webhookSecretRef"
            placeholder="输入服务端已配置的引用名称"
            maxlength="200"
          />
          <div class="field-hint">用于验证 GitHub 事件来源，不会保存密钥明文。</div>
        </el-form-item>

        <el-form-item style="margin-top: 24px;">
          <el-button type="primary" :loading="loading" @click="handleSubmit">
            验证并绑定
          </el-button>
          <el-button @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/repositories`)">
            取消
          </el-button>
        </el-form-item>
      </el-form>

      <RawJsonPanel v-if="rawJson" :data="rawJson" title="技术详情" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createRepositoryApi } from '@/api/modules/repository'
import RawJsonPanel from '@/components/RawJsonPanel.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

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
  apiCredentialRef: '',
  webhookSecretRef: '',
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
  apiCredentialRef: [{ required: true, message: '请输入访问凭据引用名称', trigger: 'blur' }],
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
        ElMessage.error(productErrorMessage(res, '仓库绑定失败，请检查仓库与凭据设置后重试。', '该 GitHub 仓库已被绑定，请刷新后重试。'))
      }
    } catch (err: any) {
      ElMessage.error(unexpectedErrorMessage(err, '暂时无法绑定仓库，请稍后重试。'))
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
