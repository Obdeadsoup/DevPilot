<template>
  <el-dialog
    v-model="visible"
    :title="`高风险人工 Replay 重放确认 (${targetType})`"
    width="520px"
    :close-on-click-modal="false"
  >
    <el-alert
      type="warning"
      show-icon
      :closable="false"
      title="高风险运维动作说明"
      style="margin-bottom: 16px;"
    >
      <template #default>
        <div>此操作将重新触发终态失败 (DEAD) 资源的底层处理。请必须提供详细合规的重放原因。</div>
        <div>目标 ID: <code>#{{ targetId }}</code> | 当前版本: <code>expectedVersion = {{ expectedVersion }}</code></div>
      </template>
    </el-alert>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="重放原因说明 (reason, 必填 10-500 字符)" prop="reason">
        <el-input
          v-model="form.reason"
          type="textarea"
          :rows="3"
          placeholder="请输入本次 Replay 的真实运维原因（不少于 10 个字符，不能仅填写 retry 或纯标点）"
          maxlength="500"
          show-word-limit
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="danger" :loading="submitting" @click="handleConfirm">
        确认提交 Replay (HTTP 202)
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { type FormInstance, type FormRules } from 'element-plus'

const emit = defineEmits<{
  (e: 'submit-replay', reason: string, expectedVersion: number): void
}>()

const visible = ref(false)
const submitting = ref(false)
const targetType = ref('')
const targetId = ref(0)
const expectedVersion = ref(0)

const formRef = ref<FormInstance>()
const form = reactive({
  reason: '',
})

const validateReason = (_rule: any, value: string, callback: any) => {
  if (!value) {
    return callback(new Error('请输入重放原因说明'))
  }
  const trimmed = value.trim()
  if (trimmed.length < 10) {
    return callback(new Error('重放原因说明不能少于 10 个字符'))
  }
  const lower = trimmed.toLowerCase()
  if (['retry', 'test', 'replay', '123456'].includes(lower) || /^[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]+$/.test(trimmed)) {
    return callback(new Error('请输入有实际意义的运维重放原因，不能仅填简单单词或纯标点'))
  }
  callback()
}

const rules: FormRules = {
  reason: [{ validator: validateReason, trigger: 'blur' }],
}

function show(typeStr: string, id: number, versionNum: number) {
  targetType.value = typeStr
  targetId.value = id
  expectedVersion.value = versionNum
  form.reason = ''
  visible.value = true
}

function handleConfirm() {
  if (!formRef.value) return
  formRef.value.validate((valid) => {
    if (!valid) return
    emit('submit-replay', form.reason.trim(), expectedVersion.value)
  })
}

function closeDialog() {
  visible.value = false
  submitting.value = false
}

function setSubmitting(val: boolean) {
  submitting.value = val
}

defineExpose({
  show,
  closeDialog,
  setSubmitting,
})
</script>
