<script setup lang="ts">
import { reactive, watch } from "vue";
import type { KnowledgeBase } from "@/api/types";

const props = defineProps<{
  modelValue: boolean;
  knowledgeBases: KnowledgeBase[];
  selectedIds: number[];
  loading: boolean;
  saving: boolean;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  save: [knowledgeBaseIds: number[]];
}>();
const form = reactive({ knowledgeBaseIds: [] as number[] });

watch(() => props.modelValue, visible => {
  if (visible) form.knowledgeBaseIds = [...props.selectedIds];
});
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="调整会话知识库"
    width="520px"
    destroy-on-close
    :close-on-click-modal="!saving"
    :close-on-press-escape="!saving"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="binding-intro">
      <strong>为当前会话选择检索范围</strong>
      <p>可不选、选择一个或多个知识库。修改只影响之后发送的问题，不改变历史回答。</p>
    </div>
    <el-select
      v-model="form.knowledgeBaseIds"
      multiple
      filterable
      collapse-tags
      collapse-tags-tooltip
      :max-collapse-tags="3"
      :loading="loading"
      placeholder="不绑定，使用通用 AI 对话"
      style="width: 100%"
    >
      <el-option v-for="base in knowledgeBases" :key="base.id" :label="base.name" :value="base.id">
        <div class="binding-option"><span>{{ base.name }}</span><small>#{{ base.id }}</small></div>
      </el-option>
    </el-select>
    <div v-if="form.knowledgeBaseIds.length === 0" class="general-hint">当前将使用通用 AI 对话，不检索知识库。</div>
    <template #footer>
      <el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="emit('save', [...form.knowledgeBaseIds])">保存检索范围</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.binding-intro { margin: -2px 0 18px; }
.binding-intro strong { color: #173956; font-size: 15px; }
.binding-intro p { margin: 6px 0 0; color: #71869a; font-size: 13px; line-height: 1.7; }
.binding-option { display: flex; justify-content: space-between; gap: 16px; }
.binding-option small { color: #9aabba; }
.general-hint { margin-top: 12px; padding: 10px 12px; color: #547087; font-size: 12px; background: #f3f7fa; border-radius: 6px; }
</style>
