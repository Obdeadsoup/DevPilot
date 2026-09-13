<template>
  <el-dialog
    v-model="visible"
    :title="`确认恢复处理（${targetType}）`"
    width="520px"
    :close-on-click-modal="false"
  >
    <el-alert
      type="warning"
      show-icon
      :closable="false"
      title="请确认恢复原因"
      style="margin-bottom: 16px;"
    >
      <template #default>
        <div>系统会为这条失败记录创建一次新的处理。请填写清晰、可追溯的恢复原因。</div>
      </template>
    </el-alert>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="恢复原因（必填，10–500 字）" prop="reason">
        <el-input
          v-model="form.reason"
          type="textarea"
          :rows="3"
          placeholder="请说明本次恢复处理的原因"
          maxlength="500"
          show-word-limit
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="danger" :loading="submitting" @click="handleConfirm">
        确认恢复
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
