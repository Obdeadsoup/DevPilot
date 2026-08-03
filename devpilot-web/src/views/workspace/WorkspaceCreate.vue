<template>
  <div class="workspace-create-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>创建 Workspace (POST /api/v1/workspaces)</span>
          <el-button link @click="$router.push('/workspaces')">返回列表</el-button>
        </div>
      </template>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        style="max-width: 600px;"
      >
        <el-form-item label="名称 (name)" prop="name">
          <el-input
            v-model="form.name"
            placeholder="例如: Student Team Workspace"
            maxlength="100"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="Slug (全局唯一标识)" prop="slug">
          <el-input
            v-model="form.slug"
            placeholder="例如: student-team (长度 1 或 3-64 字符)"
            maxlength="64"
          />
          <div class="field-hint">
            正则: <code>^[a-z0-9](?:[a-z0-9-]{1,62}[a-z0-9])?$</code>。后端当前规则不接受长度为 2 的 slug (如 "ab")。
          </div>
        </el-form-item>

        <el-form-item label="描述 (description)" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="可选 Workspace 描述"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>

        <el-form-item style="margin-top: 24px;">
          <el-button type="primary" :loading="loading" @click="handleSubmit">
            提交创建
          </el-button>
          <el-button @click="$router.push('/workspaces')">取消</el-button>
        </el-form-item>
      </el-form>

      <RawJsonPanel v-if="rawJson" :data="rawJson" title="创建响应 JSON" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createWorkspaceApi } from '@/api/modules/workspace'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const router = useRouter()

const formRef = ref<FormInstance>()
const loading = ref(false)
const rawJson = ref<any>(null)

const form = reactive({
  name: '',
  slug: '',
  description: '',
})

const validateSlug = (_rule: any, value: string, callback: any) => {
  if (!value) {
    return callback(new Error('请输入 slug'))
  }
  const trimmed = value.trim().toLowerCase()
  if (trimmed.length === 2) {
    return callback(new Error('后端规则提示：当前暂不接受长度为 2 的 slug (如 "ab")'))
  }
  const regex = /^[a-z0-9](?:[a-z0-9-]{1,62}[a-z0-9])?$/
  if (!regex.test(trimmed)) {
    return callback(new Error('格式错误: 小写字母数字与连字符，且首尾必须为字母数字'))
  }
  callback()
}

const rules: FormRules = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: 100, message: '长度不能超过 100 字符', trigger: 'blur' },
  ],
  slug: [{ validator: validateSlug, trigger: 'blur' }],
  description: [{ max: 500, message: '描述不能超过 500 字符', trigger: 'blur' }],
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true

    try {
      const res = await createWorkspaceApi({
        name: form.name.trim(),
        slug: form.slug.trim().toLowerCase(),
        description: form.description ? form.description.trim() : undefined,
      })
      rawJson.value = res.rawJson

      if (res.success && res.data) {
        ElMessage.success('Workspace 创建成功')
        router.push(`/workspaces/${res.data.id}`)
      } else {
        ElMessage.error(`创建失败 [${res.code}]: ${res.message}`)
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
.workspace-create-container {
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
