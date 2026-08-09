<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { ElMessageBox } from "element-plus";
import { Delete, Plus, Refresh } from "@element-plus/icons-vue";
import { listDocuments, deleteDocument } from "@/api/knowledge";
import { useKnowledgeStoreHook } from "@/store/modules/knowledge";
import { message } from "@/utils/message";
import { documentStatusLabel, ingestStatusLabel, type KnowledgeDocument } from "@/api/types";
import DocumentUploadDialog from "./document-upload-dialog.vue";

const props = defineProps<{ knowledgeBaseId: number | null }>();
const knowledgeStore = useKnowledgeStoreHook();
const loading = ref(false);
const uploadVisible = ref(false);
const pagination = reactive({ current: 1, size: 10 });

const POLL_INTERVAL = 4000;
let pollTimer: ReturnType<typeof setTimeout> | null = null;
let inFlight = false;
let needsRefresh = false;
let mounted = true;
let contextVersion = 0;
let requestSequence = 0;
const pendingPage = { current: 1, size: 10 };

const hasActiveDocuments = computed(() => knowledgeStore.documentList.some(isActiveDocument));
function isActiveDocument(document: KnowledgeDocument): boolean {
  return document.ingestStatus === 1 || document.ingestStatus === 2;
}
function isCurrentContext(baseId: number, version: number, storeSeq: number): boolean {
  return mounted && props.knowledgeBaseId === baseId && contextVersion === version && knowledgeStore.getRequestSeq() === storeSeq;
}

async function fetchDocuments(source: "user" | "poll" = "user"): Promise<void> {
  if (props.knowledgeBaseId === null) return;
  if (source === "poll" && inFlight) return;
  if (source === "user" && inFlight) {
    needsRefresh = true;
    pendingPage.current = pagination.current;
    pendingPage.size = pagination.size;
    return;
  }

  const baseId = props.knowledgeBaseId;
  const version = contextVersion;
  const storeSeq = knowledgeStore.getRequestSeq();
  const requestId = ++requestSequence;
  inFlight = true;
  loading.value = true;
  try {
    const result = await listDocuments(baseId, { current: pagination.current, size: pagination.size });
    if (!isCurrentContext(baseId, version, storeSeq) || requestId !== requestSequence) return;
    knowledgeStore.setDocumentList(result.records, result.total, storeSeq);
  } catch {
    // The request layer has already displayed a safe error message.
  } finally {
    if (requestId !== requestSequence) return;
    inFlight = false;
    if (isCurrentContext(baseId, version, storeSeq)) {
      loading.value = false;
      updatePollingState();
    }
    maybeRunPendingRefresh();
  }
}

function maybeRunPendingRefresh(): void {
  if (!mounted || !needsRefresh) return;
  needsRefresh = false;
  pagination.current = pendingPage.current;
  pagination.size = pendingPage.size;
  void fetchDocuments("user");
}

function startPolling(): void {
  stopPolling();
  if (document.hidden || !hasActiveDocuments.value || !mounted) return;
  pollTimer = setTimeout(async () => {
    pollTimer = null;
    if (!document.hidden && hasActiveDocuments.value) await fetchDocuments("poll");
  }, POLL_INTERVAL);
}
function stopPolling(): void {
  if (pollTimer !== null) clearTimeout(pollTimer);
  pollTimer = null;
}
function updatePollingState(): void {
  if (hasActiveDocuments.value) startPolling(); else stopPolling();
}
function handleVisibilityChange(): void {
  if (document.hidden) stopPolling(); else updatePollingState();
}

function refreshDocuments(): void { void fetchDocuments("user"); }
function handlePageChange(page: number): void { pagination.current = page; refreshDocuments(); }
function handleSizeChange(size: number): void { pagination.size = size; pagination.current = 1; refreshDocuments(); }
function handleUploaded(_documentId: number, sourceKnowledgeBaseId: number): void {
  if (sourceKnowledgeBaseId !== props.knowledgeBaseId) return;
  pagination.current = 1;
  refreshDocuments();
}
function handleUploadRefresh(sourceKnowledgeBaseId: number): void {
  if (sourceKnowledgeBaseId === props.knowledgeBaseId) refreshDocuments();
}

async function handleDelete(documentId: number, title: string): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除文档“${title}”吗？删除后不可恢复。`, "删除确认", { type: "warning" });
  } catch {
    return;
  }
  try {
    await deleteDocument(documentId);
    message.success("删除成功");
    if (knowledgeStore.documentList.length === 1 && pagination.current > 1) pagination.current -= 1;
    refreshDocuments();
  } catch {
    // The request layer has already displayed a safe error message.
  }
}

function formatFileSize(bytes: number): string {
  if (!bytes) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

watch(() => props.knowledgeBaseId, () => {
  contextVersion += 1;
  stopPolling();
  pagination.current = 1;
  needsRefresh = true;
  if (!inFlight) maybeRunPendingRefresh();
}, { immediate: true });
watch(() => knowledgeStore.currentBase, base => {
  if (base && base.id === props.knowledgeBaseId) refreshDocuments();
});

onMounted(() => document.addEventListener("visibilitychange", handleVisibilityChange));
onUnmounted(() => {
  mounted = false;
  needsRefresh = false;
  stopPolling();
  document.removeEventListener("visibilitychange", handleVisibilityChange);
});
</script>

<template>
  <div v-auth="'knowledge:document:list'" class="detail-documents">
    <div class="toolbar">
      <div class="toolbar-actions">
        <el-button v-auth="'knowledge:document:create'" type="primary" :icon="Plus" @click="uploadVisible = true">上传文档</el-button>
        <el-button :icon="Refresh" circle aria-label="刷新文档" @click="refreshDocuments" />
      </div>
      <span class="status-hint">上传进度是 HTTP 传输进度；入库状态由异步处理更新。</span>
    </div>

    <el-table v-loading="loading" :data="knowledgeStore.documentList" stripe>
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="originalFilename" label="文件名" min-width="180" show-overflow-tooltip />
      <el-table-column label="大小" width="100"><template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template></el-table-column>
      <el-table-column label="入库状态" width="110"><template #default="{ row }"><el-tag :type="row.ingestStatus === 3 ? 'success' : row.ingestStatus === 4 ? 'danger' : row.ingestStatus === 2 ? 'warning' : 'info'">{{ ingestStatusLabel(row.ingestStatus) }}</el-tag></template></el-table-column>
      <el-table-column label="文档状态" width="100"><template #default="{ row }">{{ documentStatusLabel(row.status) }}</template></el-table-column>
      <el-table-column label="版本" width="80"><template #default="{ row }">v{{ row.version }}</template></el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><el-button v-auth="'knowledge:document:delete'" link type="danger" :icon="Delete" @click="handleDelete(row.id, row.title)">删除</el-button></template></el-table-column>
    </el-table>

    <el-pagination v-if="knowledgeStore.documentTotal > 0" class="pagination" layout="total, sizes, prev, pager, next, jumper" :total="knowledgeStore.documentTotal" :current-page="pagination.current" :page-size="pagination.size" :page-sizes="[10, 20, 50]" @current-change="handlePageChange" @size-change="handleSizeChange" />

    <DocumentUploadDialog v-model="uploadVisible" :knowledge-base-id="knowledgeBaseId" :folder-tree="knowledgeStore.folderTree" :default-folder-id="knowledgeStore.currentFolderId" @uploaded="handleUploaded" @refresh="handleUploadRefresh" />
  </div>
</template>

<style lang="scss" scoped>
.detail-documents { padding: 16px 0; }
.toolbar { display: flex; gap: 8px; margin-bottom: 16px; align-items: center; justify-content: space-between; }
.toolbar-actions { display: flex; gap: 8px; }
.status-hint { color: var(--el-text-color-secondary); font-size: 12px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
</style>
