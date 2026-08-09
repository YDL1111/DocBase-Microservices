<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import type { FormInstance, FormRules } from "element-plus";
import type { CreateChatSessionRequest, KnowledgeBase } from "@/api/types";

const props = defineProps<{ modelValue: boolean; knowledgeBases: KnowledgeBase[]; loadingKnowledgeBases: boolean; creating: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; create: [request: CreateChatSessionRequest]; opened: [] }>();
const formRef = ref<FormInstance>();
const form = reactive<CreateChatSessionRequest>({ title: "", knowledgeBaseId: null });
const rules: FormRules = { title: [{ max: 255, message: "标题不能超过 255 个字符", trigger: "blur" }] };
watch(() => props.modelValue, visible => { if (visible) { form.title = ""; form.knowledgeBaseId = null; emit("opened"); } });
async function submit(): Promise<void> { const valid = await formRef.value?.validate().catch(() => false); if (valid) emit("create", { title: form.title.trim(), knowledgeBaseId: form.knowledgeBaseId }); }
</script>

<template>
  <el-dialog :model-value="modelValue" title="新建会话" width="460px" :close-on-click-modal="!creating" @update:model-value="emit('update:modelValue', $event)">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="92px"><el-form-item label="会话标题" prop="title"><el-input v-model="form.title" maxlength="255" show-word-limit placeholder="可留空" /></el-form-item><el-form-item label="知识库"><el-select v-model="form.knowledgeBaseId" clearable placeholder="未绑定知识库" :loading="loadingKnowledgeBases" style="width: 100%"><el-option v-for="base in knowledgeBases" :key="base.id" :label="base.name" :value="base.id" /></el-select></el-form-item></el-form>
    <template #footer><el-button :disabled="creating" @click="emit('update:modelValue', false)">取消</el-button><el-button type="primary" :loading="creating" @click="submit">创建</el-button></template>
  </el-dialog>
</template>
