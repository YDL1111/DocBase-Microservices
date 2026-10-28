<script setup lang="ts">
import { computed } from "vue";
import { Position, VideoPause } from "@element-plus/icons-vue";

const props = defineProps<{ modelValue: string; streaming: boolean; canSend: boolean; settling?: boolean; maxLength?: number }>();
const emit = defineEmits<{ "update:modelValue": [value: string]; send: []; stop: [] }>();
const limit = computed(() => props.maxLength ?? 4000);
/** The input stays editable while draining/cancelling so edits are not lost, but sending is disabled. */
const inputDisabled = computed(() => props.streaming);
/** 接近上限时才显示字数，避免常态视觉噪音 */
const showCount = computed(() => props.modelValue.length > limit.value * 0.8);

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
    <div class="chat-composer__box">
      <el-input
        :model-value="modelValue"
        type="textarea"
        :rows="2"
        resize="none"
        :maxlength="limit"
        placeholder="向当前知识库提问…"
        :disabled="inputDisabled"
        @update:model-value="emit('update:modelValue', $event)"
        @keydown="onKeydown"
      />
      <div class="chat-composer__actions">
        <span v-if="streaming" class="chat-composer__status" role="status" aria-live="polite">正在生成回答</span>
        <span v-else-if="showCount" class="chat-composer__count" :class="{ 'is-limit': modelValue.length >= limit }">{{ modelValue.length }} / {{ limit }}</span>
        <span v-else class="chat-composer__hint">Enter 发送 · Shift + Enter 换行</span>
        <el-button
          type="primary"
          circle
          :icon="streaming ? VideoPause : Position"
          :title="streaming ? '停止生成' : '发送消息'"
          :aria-label="streaming ? '停止生成' : '发送消息'"
          :disabled="!streaming && (!canSend || !modelValue.trim())"
          @click="submit"
        />
      </div>
    </div>
  </footer>
</template>

<style scoped lang="scss">
.chat-composer {
  padding: 12px 22px 16px;
  background: var(--docbase-bg);
}

.chat-composer__box {
  max-width: 920px;
  margin: 0 auto;
  padding: 9px 10px 9px 15px;
  background: var(--docbase-surface);
  border: 1px solid var(--docbase-line);
  border-radius: 10px;
  box-shadow: 0 8px 22px rgba(29, 42, 37, 0.07);
}

.chat-composer__box:focus-within {
  border-color: var(--docbase-accent);
  box-shadow: 0 8px 24px rgba(22, 92, 64, 0.12);
}

.chat-composer :deep(.el-textarea__inner) {
  font-size: 15px;
  color: var(--docbase-text);
  min-height: 50px !important;
  padding: 4px 0;
  box-shadow: none;
}

.chat-composer__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 5px;
}

.chat-composer__status,
.chat-composer__hint,
.chat-composer__count {
  color: var(--docbase-subtle);
  font-size: 13px;
}

.chat-composer__status {
  color: var(--docbase-accent);
}

.chat-composer__count.is-limit {
  color: var(--docbase-danger);
}

.chat-composer__actions :deep(.el-button) {
  width: 36px;
  height: 36px;
}
</style>
