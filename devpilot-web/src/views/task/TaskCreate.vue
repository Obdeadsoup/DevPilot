<template>
  <div class="task-create-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>创建 Task (POST .../projects/{projectId}/tasks)</span>
          <el-button link @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/tasks`)">
            返回列表
          </el-button>
        </div>
      </template>

      <el-alert
        title="初始状态与负责人说明"
        type="info"
        description="新创建的 Task 默认处于 BACKLOG 状态。后端当前未提供成员查询 API，负责人请输入该项目内有效 ACTIVE 用户的数字 ID。"
        show-icon
        :closable="false"
        style="margin-bottom: 20px;"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        style="max-width: 650px;"
      >
        <el-form-item label="任务标题 (title)" prop="title">
          <el-input
            v-model="form.title"
            placeholder="例如: 实现接口参数自动校验与错误处理"
            maxlength="255"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="优先级 (priority)" prop="priority">
          <el-radio-group v-model="form.priority">
            <el-radio value="LOW">LOW</el-radio>
            <el-radio value="MEDIUM">MEDIUM (默认)</el-radio>
            <el-radio value="HIGH">HIGH</el-radio>
            <el-radio value="URGENT">URGENT</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="分配负责人 User ID (assigneeUserId, 可选)" prop="assigneeUserId">
          <el-input-number
            v-model="form.assigneeUserId"
            :min="1"
            placeholder="数字 User ID"
            style="width: 200px;"
            controls-position="right"
          />
        </el-form-item>

        <el-form-item label="截止时间 (dueAt, 可选)" prop="dueAt">
          <el-date-picker
            v-model="form.dueAt"
            type="datetime"
            placeholder="选择截止日期时间"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 260px;"
          />
        </el-form-item>

        <el-form-item label="详细描述 (description, 可选)" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="5"
            placeholder="任务详细要求、前置条件与验收标准"
            maxlength="10000"
            show-word-limit
          />
        </el-form-item>

        <el-form-item style="margin-top: 24px;">
          <el-button type="primary" :loading="loading" @click="handleSubmit">
            提交创建 (进入 BACKLOG)
          </el-button>
          <el-button @click="$router.push(`/workspaces/${workspaceId}/projects/${projectId}/tasks`)">
            取消
          </el-button>
        </el-form-item>
      </el-form>

      <RawJsonPanel v-if="rawJson" :data="rawJson" title="创建 Task 响应 JSON" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createTaskApi } from '@/api/modules/task'
import type { TaskPriority } from '@/types/task'
import RawJsonPanel from '@/components/RawJsonPanel.vue'

const route = useRoute()
const router = useRouter()

const workspaceId = Number(route.params.workspaceId)
const projectId = Number(route.params.projectId)

const formRef = ref<FormInstance>()
const loading = ref(false)
const rawJson = ref<any>(null)

const form = reactive({
  title: '',
  description: '',
  priority: 'MEDIUM' as TaskPriority,
  assigneeUserId: undefined as number | undefined,
  dueAt: undefined as string | undefined,
})

const rules: FormRules = {
  title: [
    { required: true, message: '请输入任务标题', trigger: 'blur' },
    { max: 255, message: '长度不能超过 255 字符', trigger: 'blur' },
  ],
  description: [{ max: 10000, message: '描述不能超过 10000 字符', trigger: 'blur' }],
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true

    try {
      const res = await createTaskApi(workspaceId, projectId, {
        title: form.title.trim(),
        description: form.description ? form.description.trim() : undefined,
        priority: form.priority,
        assigneeUserId: form.assigneeUserId || undefined,
        dueAt: form.dueAt || undefined,
      })
      rawJson.value = res.rawJson

      if (res.success && res.data) {
        ElMessage.success('Task 创建成功')
        router.push(`/workspaces/${workspaceId}/projects/${projectId}/tasks/${res.data.id}`)
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
.task-create-container {
  max-width: 850px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
</style>
