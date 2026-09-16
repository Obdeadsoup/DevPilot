<template>
  <div class="project-create-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>创建项目</span>
          <el-button link @click="$router.push(`/workspaces/${workspaceId}/projects`)">返回列表</el-button>
        </div>
      </template>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        style="max-width: 600px;"
      >
        <el-form-item label="项目标识（创建后不可更改）" prop="projectKey">
          <el-input
            v-model="form.projectKey"
            placeholder="例如：WEB"
            maxlength="12"
            @input="form.projectKey = form.projectKey.toUpperCase()"
          />
          <div class="field-hint">
            使用 2–12 位大写字母或数字，并以字母开头；同一工作区内不可重复。
          </div>
        </el-form-item>

        <el-form-item label="项目名称" prop="name">
          <el-input
            v-model="form.name"
            placeholder="例如：DevPilot Web Client"
            maxlength="100"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="可见范围" prop="visibility">
          <el-radio-group v-model="form.visibility">
            <el-radio value="PRIVATE">私有（仅项目成员访问）</el-radio>
            <el-radio value="INTERNAL">工作区内可见</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="项目描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="可选项目描述"
            maxlength="500"
            show-word-limit
          />
          <div class="field-hint">最多 500 个字符。</div>
        </el-form-item>

        <el-form-item style="margin-top: 24px;">
          <el-button type="primary" :loading="loading" @click="handleSubmit">
            创建项目
          </el-button>
          <el-button @click="$router.push(`/workspaces/${workspaceId}/projects`)">取消</el-button>
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
import { createProjectApi } from '@/api/modules/project'
import RawJsonPanel from '@/components/RawJsonPanel.vue'
import { productErrorMessage, unexpectedErrorMessage } from '@/utils/productError'

const route = useRoute()
const router = useRouter()
const workspaceId = Number(route.params.workspaceId)

const formRef = ref<FormInstance>()
const loading = ref(false)
const rawJson = ref<any>(null)

const form = reactive({
  name: '',
  projectKey: '',
  description: '',
  visibility: 'PRIVATE' as 'PRIVATE' | 'INTERNAL',
})

const validateKey = (_rule: any, value: string, callback: any) => {
  if (!value) {
    return callback(new Error('请输入 Project Key'))
  }
  const key = value.trim().toUpperCase()
  const regex = /^[A-Z][A-Z0-9]{1,11}$/
  if (!regex.test(key)) {
    return callback(new Error('Key 格式错误: 必须大写字母开头，大写字母或数字，2-12 位'))
  }
  callback()
}

const rules: FormRules = {
  projectKey: [{ validator: validateKey, trigger: 'blur' }],
  name: [
    { required: true, message: '请输入项目名称', trigger: 'blur' },
    { max: 100, message: '长度不能超过 100 字符', trigger: 'blur' },
  ],
  visibility: [{ required: true, message: '请选择可见性', trigger: 'change' }],
  description: [{ max: 500, message: '描述不能超过 500 字符', trigger: 'blur' }],
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true

    try {
      const res = await createProjectApi(workspaceId, {
        name: form.name.trim(),
        projectKey: form.projectKey.trim().toUpperCase(),
        visibility: form.visibility,
        description: form.description ? form.description.trim() : undefined,
      })
      rawJson.value = res.rawJson

      if (res.success && res.data) {
        ElMessage.success('项目创建成功')
        router.push(`/workspaces/${workspaceId}/projects/${res.data.id}/overview`)
      } else {
        ElMessage.error(productErrorMessage(res, '项目创建失败，请检查填写内容后重试。', '项目标识已被使用，请更换后重试。'))
      }
    } catch (err: any) {
      ElMessage.error(unexpectedErrorMessage(err, '暂时无法创建项目，请稍后重试。'))
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.project-create-container {
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
