<script setup lang="ts">
/**
 * 导入任务列表页。
 *
 * 功能：分页、状态筛选、任务展示、重试/取消操作。
 *
 * 注意：
 *  - IngestTaskController 是全局管理接口，不按用户/知识库过滤；
 *  - 该页面定位为管理页面，不宣称普通用户只能看到自己的任务；
 *  - 后端没有 SSE/WebSocket，使用有界轮询刷新活跃任务。
 */
import { onMounted, onUnmounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessageBox } from "element-plus";
import { Refresh } from "@element-plus/icons-vue";
import {
  listIngestTasks,
  retryIngestTask,
  cancelIngestTask
} from "@/api/ingest";
import { message } from "@/utils/message";
import { formatBackendDateTime } from "@/utils/date-time";
import {
  ingestTaskStatusLabel,
  ingestTaskStatusTagType,
  ingestTaskTypeLabel,
  isRetryableStatus,
  isCancelableStatus,
  isActiveStatus,
  type IngestTask
} from "@/api/types";

const router = useRouter();

// ========================= 状态 =========================

const loading = ref(false);
const tasks = ref<IngestTask[]>([]);
const total = ref(0);

/**
 * 请求序号。每次分页/筛选变化时递增，
 * 防止旧响应覆盖新响应。
 */
let requestSeq = 0;

const pagination = reactive({
  current: 1,
  size: 10
});

const filterStatus = ref<string | undefined>(undefined);

/** 操作中的任务 ID 集合（防重复点击） */
const operatingIds = ref<Set<number>>(new Set());

/**
 * 轮询定时器（串行 setTimeout，避免请求重叠）。
 * 全部终态后清除定时器，不再调度下一次。
 */
let pollTimer: ReturnType<typeof setTimeout> | null = null;
const POLL_INTERVAL = 4000; // 4 秒

/**
 * 组件是否已挂载。卸载后禁止补发请求或重新注册轮询。
 */
const mounted = ref(true);

/**
 * 待执行的刷新（pending-refresh）机制。
 *
 * 问题：简单的 `if (inFlight) return` 会把用户触发的查询/重置/分页/手动刷新
 * 以及 retry/cancel 后的刷新一并丢弃，导致用户操作"丢失"。
 *
 * 方案：
 *  - 仅自动轮询可在请求飞行中跳过（不记录 pending，因为轮询会再次触发）；
 *  - 用户触发的刷新绝不丢弃：置位 needsRefresh，并保存此刻最新的分页/筛选参数；
 *  - 当前请求结束后，若 needsRefresh 且组件仍挂载，立即补发一次"最新参数"请求；
 *  - 多个 pending 自然合并为一次补发（needsRefresh 是布尔标记）；
 *  - 全程只有一个网络请求在飞（pending 不产生并发）。
 */
let needsRefresh = false;
const pendingParams = { current: 1, size: 10, status: undefined as string | undefined };

/** 触发一次列表请求。`source` 区分用户操作与自动轮询。 */
async function fetchTasks(source: "user" | "poll") {
  // 自动轮询：请求飞行中则直接跳过（轮询会再次触发，无需记录 pending）
  if (source === "poll" && inFlight) return;

  // 用户触发：请求飞行中则记录 pending 与最新参数，等当前请求结束后补发
  if (source === "user" && inFlight) {
    needsRefresh = true;
    pendingParams.current = pagination.current;
    pendingParams.size = pagination.size;
    pendingParams.status = filterStatus.value;
    return;
  }

  const seq = ++requestSeq;
  inFlight = true;
  loading.value = true;
  try {
    const res = await listIngestTasks({
      current: pagination.current,
      size: pagination.size,
      status: filterStatus.value
    });
    // 序号已变化说明已被新的请求覆盖，丢弃旧响应
    if (seq !== requestSeq) return;
    tasks.value = res.records;
    total.value = res.total;
  } catch {
    // 错误提示已由请求层处理
  } finally {
    // 只有最终有效请求（当前 seq）可以更新 loading 与轮询状态
    if (seq === requestSeq) {
      inFlight = false;
      loading.value = false;
      // 组件卸载后不得重新注册轮询或补发请求
      if (mounted.value) {
        // 列表加载完成后，根据是否有活跃任务控制轮询
        updatePollingState();
        // 补发被合并的用户请求（多个 pending 合并为一次）
        maybeFirePendingRefresh();
      }
    }
    // 过期请求（seq 已变化）不得修改任何状态
  }
}

/**
 * 补发一次 pending 的用户请求（使用最新保存的参数）。
 * 多个 pending 合并为一次；不产生网络并发。
 */
function maybeFirePendingRefresh() {
  if (!mounted.value || !needsRefresh) return;
  needsRefresh = false;
  // 应用最新保存的参数后，以用户身份触发一次请求
  pagination.current = pendingParams.current;
  pagination.size = pendingParams.size;
  filterStatus.value = pendingParams.status;
  fetchTasks("user");
}

/**
 * 请求互斥锁。确保手动刷新、筛选、分页与定时轮询不会并发。
 * 发请求前获取；仅当前 seq 的请求在 finally 中释放并调度轮询。
 */
let inFlight = false;

// ========================= 轮询 =========================

/**
 * 调度下一次轮询。
 * 仅当存在活跃任务时才设置定时器；全部终态时清除，不再调度。
 */
function startPolling() {
  stopPolling();
  if (!tasks.value.some(t => isActiveStatus(t.status))) {
    return; // 全部终态：清除定时器，不调度
  }
  pollTimer = setTimeout(async () => {
    pollTimer = null;
    if (!tasks.value.some(t => isActiveStatus(t.status))) {
      return; // 再次检查，避免竞态
    }
    // 自动轮询：请求飞行中则跳过；用户刷新 pending 会由当前请求结束后补发
    await fetchTasks("poll");
  }, POLL_INTERVAL);
}

function stopPolling() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

/** 根据当前任务状态决定启动或停止轮询 */
function updatePollingState() {
  if (tasks.value.some(t => isActiveStatus(t.status))) {
    startPolling();
  } else {
    stopPolling();
  }
}

/** 页面可见性变化时控制轮询 */
function handleVisibilityChange() {
  if (document.hidden) {
    stopPolling();
  } else {
    startPolling();
  }
}

// ========================= 操作 =========================

/**
 * 重试/取消前捕获 targetId 快照，
 * 整个 confirm + API 请求过程使用该快照，禁止重新读取可能变化的参数。
 */
async function handleRetry(taskId: number) {
  if (operatingIds.value.has(taskId)) return;
  try {
    await ElMessageBox.confirm("确定重试该任务吗？", "重试确认", {
      type: "warning"
    });
  } catch {
    return; // 用户取消确认
  }
  operatingIds.value.add(taskId);
  try {
    await retryIngestTask(taskId);
    message.success("重试已触发");
    // 重新获取服务端状态（用户触发，绝不丢失）
    fetchTasks("user");
  } catch {
    // 错误提示已由请求层处理；INVALID_STATUS 时后端返回错误，刷新列表
    fetchTasks("user");
  } finally {
    operatingIds.value.delete(taskId);
  }
}

async function handleCancel(taskId: number) {
  if (operatingIds.value.has(taskId)) return;
  try {
    await ElMessageBox.confirm("确定取消该任务吗？", "取消确认", {
      type: "warning"
    });
  } catch {
    return; // 用户取消确认
  }
  operatingIds.value.add(taskId);
  try {
    await cancelIngestTask(taskId);
    message.success("任务已取消");
    fetchTasks("user");
  } catch {
    fetchTasks("user");
  } finally {
    operatingIds.value.delete(taskId);
  }
}

function goToDetail(taskId: number) {
  router.push(`/ingest/tasks/${taskId}`);
}

// ========================= 事件 =========================

function handleSearch() {
  pagination.current = 1;
  fetchTasks("user");
}

function handleReset() {
  filterStatus.value = undefined;
  pagination.current = 1;
  fetchTasks("user");
}

function handlePageChange(page: number) {
  pagination.current = page;
  fetchTasks("user");
}

function handleSizeChange(size: number) {
  pagination.size = size;
  pagination.current = 1;
  fetchTasks("user");
}

// ========================= 生命周期 =========================

onMounted(() => {
  fetchTasks("user");
  startPolling();
  document.addEventListener("visibilitychange", handleVisibilityChange);
});

onUnmounted(() => {
  // 标记卸载，阻止 pending 请求补发与轮询重注册
  mounted.value = false;
  stopPolling();
  document.removeEventListener("visibilitychange", handleVisibilityChange);
});
</script>

<template>
  <div class="ingest-list">
    <div class="toolbar">
      <h2>导入任务</h2>
      <div class="toolbar-actions">
        <el-select
          v-model="filterStatus"
          placeholder="任务状态"
          clearable
          style="width: 140px"
        >
          <el-option value="PENDING" label="待处理" />
          <el-option value="PROCESSING" label="处理中" />
          <el-option value="DISPATCHED" label="已分发" />
          <el-option value="SUCCEEDED" label="已完成" />
          <el-option value="FAILED" label="失败" />
          <el-option value="RETRY_WAIT" label="等待重试" />
          <el-option value="DEAD" label="永久失败" />
          <el-option value="CANCELLED" label="已取消" />
        </el-select>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
        <el-button :icon="Refresh" circle @click="fetchTasks('user')" />
      </div>
    </div>

    <!-- 空状态 -->
    <el-empty
      v-if="!loading && tasks.length === 0"
      description="暂无导入任务"
    />

    <!-- 任务列表 -->
    <el-table v-loading="loading" :data="tasks" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="fileName" label="文件名" min-width="180" show-overflow-tooltip />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          {{ ingestTaskTypeLabel(row.taskType) }}
        </template>
      </el-table-column>
      <el-table-column prop="knowledgeBaseId" label="知识库 ID" width="120" />
      <el-table-column prop="documentId" label="文档 ID" width="120" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="ingestTaskStatusTagType(row.status)">
            {{ ingestTaskStatusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="attemptCount" label="重试次数" width="100" />
      <el-table-column label="创建时间" width="190">
        <template #default="{ row }">{{ formatBackendDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button
            v-auth="'ingest:task:view'"
            link
            type="primary"
            @click="goToDetail(row.id)"
          >
            详情
          </el-button>
          <el-button
            v-if="isRetryableStatus(row.status)"
            v-auth="'ingest:task:retry'"
            link
            type="warning"
            :disabled="operatingIds.has(row.id)"
            @click="handleRetry(row.id)"
          >
            重试
          </el-button>
          <el-button
            v-if="isCancelableStatus(row.status)"
            v-auth="'ingest:task:cancel'"
            link
            type="danger"
            :disabled="operatingIds.has(row.id)"
            @click="handleCancel(row.id)"
          >
            取消
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      v-if="total > 0"
      class="pagination"
      layout="total, sizes, prev, pager, next, jumper"
      :total="total"
      :current-page="pagination.current"
      :page-size="pagination.size"
      :page-sizes="[10, 20, 50]"
      @current-change="handlePageChange"
      @size-change="handleSizeChange"
    />
  </div>
</template>

<style lang="scss" scoped>
.ingest-list {
  background: #fff;
  padding: 16px;
  border-radius: 4px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;

  h2 {
    margin: 0;
    font-size: 18px;
  }
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
