<script setup lang="ts">
import { computed } from "vue";
import { Position, VideoPause } from "@element-plus/icons-vue";

const props = defineProps<{ modelValue: string; streaming: boolean; canSend: boolean; settling?: boolean; maxLength?: number }>();
const emit = defineEmits<{ "update:modelValue": [value: string]; send: []; stop: [] }>();
const limit = computed(() => props.maxLength ?? 4000);
/** The input stays editable while draining/cancelling so edits are not lost, but sending is disabled. */
const inputDisabled = computed(() => props.streaming);

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
        <span v-if="streaming" class="chat-composer__status">正在生成回答</span>
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
  background: #f7fafe;
}

.chat-composer__box {
  max-width: 920px;
  margin: 0 auto;
  padding: 9px 10px 9px 15px;
  background: #fff;
  border: 1px solid #cadbe8;
  border-radius: 8px;
  box-shadow: 0 8px 22px rgba(41, 79, 111, 0.08);
}

.chat-composer__box:focus-within {
  border-color: #6ca3ca;
  box-shadow: 0 8px 24px rgba(37, 111, 168, 0.12);
}

.chat-composer :deep(.el-textarea__inner) {
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
.chat-composer__hint {
  color: #7b8f9f;
  font-size: 11px;
}

.chat-composer__status {
  color: #256fa8;
}

.chat-composer__actions :deep(.el-button) {
  width: 36px;
  height: 36px;
}
</style>
