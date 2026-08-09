<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{ modelValue: string; streaming: boolean; canSend: boolean; maxLength?: number }>();
const emit = defineEmits<{ "update:modelValue": [value: string]; send: []; stop: [] }>();
const limit = computed(() => props.maxLength ?? 4000);

function submit(): void {
  if (props.streaming) emit("stop");
  else if (props.canSend && props.modelValue.trim()) emit("send");
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== "Enter" || event.shiftKey || event.isComposing) return;
  event.preventDefault();
  submit();
}
</script>

<template>
  <footer class="chat-composer">
    <el-input
      :model-value="modelValue"
      type="textarea"
      :rows="3"
      resize="none"
      :maxlength="limit"
      show-word-limit
      placeholder="输入问题，Enter 发送，Shift + Enter 换行"
      :disabled="streaming"
      @update:model-value="emit('update:modelValue', $event)"
      @keydown="onKeydown"
    />
    <div class="chat-composer__actions">
      <span v-if="streaming" class="chat-composer__status">正在生成回答…</span>
      <span v-else class="chat-composer__hint">仅能向当前会话关联的知识库提问</span>
      <el-button type="primary" :disabled="!streaming && (!canSend || !modelValue.trim())" @click="submit">
        {{ streaming ? "停止生成" : "发送" }}
      </el-button>
    </div>
  </footer>
</template>

<style scoped lang="scss">
.chat-composer { padding: 16px 24px; border-top: 1px solid var(--el-border-color-light); background: var(--el-bg-color); }
.chat-composer__actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 10px; }
.chat-composer__status { color: var(--el-color-primary); font-size: 13px; }.chat-composer__hint { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
