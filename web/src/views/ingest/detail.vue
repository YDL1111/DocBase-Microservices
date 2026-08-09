<script setup lang="ts">
/**
 * 导入任务详情页。
 *
 * 功能：展示任务时间线、状态、错误信息，支持重试/取消。
 *
 * 安全约束：
 *  - lastError 按纯文本展示，不使用 v-html，防止 HTML 注入；
 *  - objectKey 默认脱敏（折叠展示）；
 *  - 不输出 Token、内部 API Key 或消息队列凭据。
 */
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessageBox } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import {
  getIngestTask,
  retryIngestTask,
  cancelIngestTask
} from "@/api/ingest";
import { message } from "@/utils/message";
import {
  ingestTaskStatusLabel,
  ingestTaskStatusTagType,
  ingestTaskTypeLabel,
  isRetryableStatus,
  isCancelableStatus,
  isActiveStatus,
  type IngestTask
} from "@/api/types";

const route = useRoute();
const router = useRouter();

/**
 * 验证并获取任务 ID。
 * 必须为正安全整数，否则返回 null（重定向 404）。
 */
const taskId = computed<number | null>(() => {
  const raw = route.params.taskId;
  const num = Number(raw);
  if (!Number.isInteger(num) || num <= 0 || !Number.isSafeInteger(num)) {
    return null;
  }
  return num;
});

const task = ref<IngestTask | null>(null);
const loading = ref(false);
const operating = ref(false);

/**
 * 请求序号。taskId 变化时递增，用于丢弃过期响应。
 * 与 targetId 共同保证：只有最新 taskId 的最新请求才能写入。
 */
let requestSeq = 0;

/**
 * 轮询定时器（串行 setTimeout，避免重叠）。
 * 重叠防护由串行 setTimeout 保证；状态防护由 requestSeq 保证。
 */
let pollTimer: ReturnType<typeof setTimeout> | null = null;
const POLL_INTERVAL = 4000;

/** objectKey 是否展开 */
const objectKeyExpanded = ref(false);

/**
 * 加载任务详情。
 *
 * 异步隔离策略：
 *  - 入口处递增 requestSeq，立即清空旧 task/objectKeyExpanded/旧轮询；
 *  - 捕获当前 targetId 与 seq；
 *  - 仅在 seq 与 targetId 均仍匹配时写入 task/loading；
 *  - 请求失败（含 403/404）必须清空 task，不能保留上一任务内容。
 */
/**
 * @param mode "switch" — taskId 变化时调用，清空旧内容；
 *             "poll"  — 轮询刷新，静默合并，不清空旧内容（避免闪烁）。
 */
async function fetchTask(mode: "switch" | "poll" = "switch") {
  const currentId = taskId.value;
  if (currentId === null) return;

  const seq = ++requestSeq;
  if (mode === "switch") {
    // 切换任务时立即清空旧内容与旧轮询，避免展示过期数据
    task.value = null;
    objectKeyExpanded.value = false;
    stopPolling();
    loading.value = true;
  }

  try {
    const result = await getIngestTask(currentId);
    // 仅当前 seq 且 taskId 未变化时写入
    if (seq !== requestSeq || currentId !== taskId.value) return;
    task.value = result;
  } catch {
    // 请求失败（含 403/404）必须清空，不能保留旧任务
    if (seq === requestSeq && currentId === taskId.value) {
      if (mode === "switch") {
        task.value = null;
        message.error("任务不存在或无权访问");
      }
      // poll 模式下保留旧内容，静默忽略
    }
  } finally {
    // 旧请求（seq 已变化）或 taskId 已变化：不得关闭新请求的 loading，不得调整新任务的轮询
    if (seq !== requestSeq || currentId !== taskId.value) return;
    if (mode === "switch") {
      loading.value = false;
    }
    // 轮询模式下根据最新状态决定是否继续
    updatePollingState();
  }
}

/**
 * 重试/取消操作前捕获 targetId，
 * 整个 confirm + API 请求过程使用该快照，禁止重新读取 taskId。
 */
async function handleRetry() {
  const targetId = taskId.value;
  if (targetId === null || operating.value) return;
  try {
    await ElMessageBox.confirm("确定重试该任务吗？", "重试确认", {
      type: "warning"
    });
  } catch {
    return; // 用户取消确认
  }
  operating.value = true;
  try {
    await retryIngestTask(targetId);
    message.success("重试已触发");
    // 刷新当前任务（若仍是同一任务）
    if (targetId === taskId.value) fetchTask();
  } catch {
    if (targetId === taskId.value) fetchTask();
  } finally {
    operating.value = false;
  }
}

async function handleCancel() {
  const targetId = taskId.value;
  if (targetId === null || operating.value) return;
  try {
    await ElMessageBox.confirm("确定取消该任务吗？", "取消确认", {
      type: "warning"
    });
  } catch {
    return; // 用户取消确认
  }
  operating.value = true;
  try {
    await cancelIngestTask(targetId);
    message.success("任务已取消");
    if (targetId === taskId.value) fetchTask();
  } catch {
    if (targetId === taskId.value) fetchTask();
  } finally {
    operating.value = false;
  }
}

// ========================= 轮询 =========================

/**
 * 启动轮询（串行 setTimeout，避免请求重叠）。
 * 仅当任务处于活跃状态时调度下一次；终态时清除定时器。
 */
function startPolling() {
  stopPolling();
  pollTimer = setTimeout(async () => {
    pollTimer = null;
    // 终态或空任务不发起轮询；fetchTask 内部 updatePollingState 会停止
    if (!task.value || !isActiveStatus(task.value.status)) {
      return;
    }
    // "poll" 模式：静默合并，不清空旧内容；requestSeq 保证过期响应不写状态
    await fetchTask("poll");
  }, POLL_INTERVAL);
}

function stopPolling() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

function handleVisibilityChange() {
  if (document.hidden) {
    stopPolling();
  } else if (task.value && isActiveStatus(task.value.status)) {
    startPolling();
  }
}

/** 任务加载后根据状态启动/停止轮询 */
function updatePollingState() {
  if (task.value && isActiveStatus(task.value.status)) {
    startPolling();
  } else {
    stopPolling();
  }
}

// ========================= objectKey 脱敏 =========================

const displayObjectKey = computed(() => {
  if (!task.value?.objectKey) return "—";
  if (objectKeyExpanded.value) return task.value.objectKey;
  // 脱敏：只显示前 8 个字符 + ... + 后 4 个字符
  const key = task.value.objectKey;
  if (key.length <= 16) return key;
  return `${key.slice(0, 8)}...${key.slice(-4)}`;
});

/** 截断并转义 lastError（纯文本展示） */
const displayLastError = computed(() => {
  if (!task.value?.lastError) return "—";
  // 截断到 500 字符，防止过长
  const err = task.value.lastError;
  if (err.length <= 500) return err;
  return err.slice(0, 500) + "...";
});

// ========================= 生命周期 =========================

onMounted(() => {
  if (taskId.value === null) {
    router.replace("/error/404");
    return;
  }
  fetchTask();
  document.addEventListener("visibilitychange", handleVisibilityChange);
});

onUnmounted(() => {
  stopPolling();
  document.removeEventListener("visibilitychange", handleVisibilityChange);
});

// 任务加载后根据状态启动/停止轮询
watch(
  () => task.value?.status,
  () => {
    updatePollingState();
  }
);

// 监听 taskId 变化（从其他任务切回）
watch(
  () => taskId.value,
  (newId) => {
    if (newId === null) {
      router.replace("/error/404");
      return;
    }
    fetchTask();
  }
);
</script>

<template>
  <div class="ingest-detail">
    <!-- 无效 ID -->
    <div v-if="taskId === null" class="invalid-id">
      <el-result icon="error" title="404" sub-title="无效的任务 ID" />
    </div>

    <div v-else v-loading="loading" class="detail-container">
      <div v-if="task" class="detail-header">
        <h2>任务 #{{ task.id }}</h2>
        <div class="header-actions">
          <el-button
            v-if="isRetryableStatus(task.status)"
            v-auth="'ingest:task:retry'"
            type="warning"
            :disabled="operating"
            @click="handleRetry"
          >
            重试
          </el-button>
          <el-button
            v-if="isCancelableStatus(task.status)"
            v-auth="'ingest:task:cancel'"
            type="danger"
            :disabled="operating"
            @click="handleCancel"
          >
            取消
          </el-button>
          <el-button :icon="Refresh" circle @click="fetchTask" />
        </div>
      </div>

      <div v-if="task" class="detail-content">
        <!-- 基本信息 -->
        <el-descriptions :column="2" border>
          <el-descriptions-item label="ID">{{ task.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="ingestTaskStatusTagType(task.status)">
              {{ ingestTaskStatusLabel(task.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="类型">
            {{ ingestTaskTypeLabel(task.taskType) }}
          </el-descriptions-item>
          <el-descriptions-item label="重试次数">
            {{ task.attemptCount }}
          </el-descriptions-item>
          <el-descriptions-item label="知识库 ID">
            {{ task.knowledgeBaseId }}
          </el-descriptions-item>
          <el-descriptions-item label="文档 ID">
            {{ task.documentId }}
          </el-descriptions-item>
          <el-descriptions-item label="文件名" :span="2">
            {{ task.fileName || "—" }}
          </el-descriptions-item>
          <el-descriptions-item label="Content Type">
            {{ task.contentType || "—" }}
          </el-descriptions-item>
          <el-descriptions-item label="Chunk 数量">
            {{ task.chunkCount ?? "—" }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- objectKey（脱敏展示） -->
        <div class="section">
          <div class="section-header">
            <span class="section-title">Object Key</span>
            <el-button link @click="objectKeyExpanded = !objectKeyExpanded">
              {{ objectKeyExpanded ? "折叠" : "展开" }}
            </el-button>
          </div>
          <pre class="code-block">{{ displayObjectKey }}</pre>
        </div>

        <!-- 时间线 -->
        <div class="section">
          <div class="section-title">时间线</div>
          <el-timeline>
            <el-timeline-item
              v-if="task.createdAt"
              :timestamp="task.createdAt"
              type="primary"
              placement="top"
            >
              创建
            </el-timeline-item>
            <el-timeline-item
              v-if="task.startedAt"
              :timestamp="task.startedAt"
              type="warning"
              placement="top"
            >
              开始处理
            </el-timeline-item>
            <el-timeline-item
              v-if="task.finishedAt"
              :timestamp="task.finishedAt"
              :type="task.status === 'SUCCEEDED' ? 'success' : 'danger'"
              placement="top"
            >
              {{ task.status === "SUCCEEDED" ? "完成" : "结束" }}
            </el-timeline-item>
          </el-timeline>
        </div>

        <!-- 错误信息（纯文本，截断） -->
        <div v-if="task.lastError" class="section">
          <div class="section-title">错误信息</div>
          <pre class="code-block error-block">{{ displayLastError }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.ingest-detail {
  background: #fff;
  padding: 16px;
  border-radius: 4px;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;

  h2 {
    margin: 0;
    font-size: 18px;
  }
}

.header-actions {
  display: flex;
  gap: 8px;
}

.section {
  margin-top: 24px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.section-title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
  margin-bottom: 8px;
}

.code-block {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 12px;
  font-family: monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}

.error-block {
  background: #fef0f0;
  border-color: #f56c6c;
  color: #f56c6c;
}
</style>
