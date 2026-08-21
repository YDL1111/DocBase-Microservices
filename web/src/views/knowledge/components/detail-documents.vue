<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { ElMessageBox } from "element-plus";
import { Delete, Edit, Plus, Refresh, RefreshRight, View } from "@element-plus/icons-vue";
import { listDocuments, deleteDocument, getDocumentContent, reingestDocument, updateDocument } from "@/api/knowledge";
import { useKnowledgeStoreHook } from "@/store/modules/knowledge";
import { message } from "@/utils/message";
import { DocumentStatus, IngestStatus, documentStatusLabel, ingestStatusLabel, type KnowledgeDocument, type UpdateDocumentRequest } from "@/api/types";
import DocumentUploadDialog from "./document-upload-dialog.vue";

const props = defineProps<{ knowledgeBaseId: number | null }>();
const knowledgeStore = useKnowledgeStoreHook();
const loading = ref(false);
const uploadVisible = ref(false);
const editVisible = ref(false);
const editSubmitting = ref(false);
const editingDocumentId = ref<number | null>(null);
const operatingIds = ref(new Set<number>());
const editForm = reactive<UpdateDocumentRequest>({ title: "", folderId: 0, visibility: 1, status: 1 });
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
function qaAvailability(document: KnowledgeDocument): { label: string; type: "success" | "warning" | "danger" | "info" } {
  if (document.status !== DocumentStatus.PUBLISHED) {
    return { label: document.status === DocumentStatus.DRAFT ? "草稿，未发布" : "已归档", type: "info" };
  }
  if (document.ingestStatus === IngestStatus.SUCCESS) return { label: "可用于问答", type: "success" };
  if (document.ingestStatus === IngestStatus.FAILED) return { label: "入库失败", type: "danger" };
  return { label: "等待入库", type: "warning" };
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

function setOperating(documentId: number, operating: boolean): void {
  const next = new Set(operatingIds.value);
  if (operating) next.add(documentId); else next.delete(documentId);
  operatingIds.value = next;
}

async function handlePreview(row: KnowledgeDocument): Promise<void> {
  if (operatingIds.value.has(row.id)) return;
  const previewWindow = window.open("about:blank", "_blank");
  if (!previewWindow) {
    message.warning("浏览器阻止了预览窗口，请允许本站打开新窗口后重试。");
    return;
  }
  // Detach the new tab before navigating it to the authorized Blob URL.
  previewWindow.opener = null;
  setOperating(row.id, true);
  try {
    const blob = await getDocumentContent(row.id);
    const url = URL.createObjectURL(blob);
    previewWindow.location.href = url;
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch {
    previewWindow.close();
  } finally {
    setOperating(row.id, false);
  }
}

function openEdit(row: KnowledgeDocument): void {
  if (operatingIds.value.has(row.id)) return;
  editingDocumentId.value = row.id;
  editForm.title = row.title;
  editForm.folderId = row.folderId ?? 0;
  editForm.visibility = row.visibility;
  editForm.status = row.status;
  editVisible.value = true;
}

async function submitEdit(): Promise<void> {
  const targetId = editingDocumentId.value;
  if (targetId === null || editSubmitting.value || !editForm.title.trim()) return;
  editSubmitting.value = true;
  try {
    await updateDocument(targetId, {
      title: editForm.title.trim(),
      folderId: editForm.folderId,
      visibility: editForm.visibility,
      status: editForm.status
    });
    editVisible.value = false;
    message.success("文档信息已更新");
    refreshDocuments();
  } catch {
    // Keep the dialog and input for correction.
  } finally {
    editSubmitting.value = false;
  }
}

async function handleReingest(row: KnowledgeDocument): Promise<void> {
  if (operatingIds.value.has(row.id) || isActiveDocument(row)) return;
  setOperating(row.id, true);
  try {
    await ElMessageBox.confirm(`重新入库将重新解析“${row.title}”并生成新版本，是否继续？`, "重新入库", { type: "warning" });
    await reingestDocument(row.id);
    if (mounted) {
      message.success("重新入库任务已创建");
      refreshDocuments();
    }
  } catch {
    // Confirmation cancellation is silent; request failures are handled centrally.
  } finally {
    setOperating(row.id, false);
  }
}

async function handlePublishToggle(row: KnowledgeDocument): Promise<void> {
  if (operatingIds.value.has(row.id)) return;
  const publish = row.status !== DocumentStatus.PUBLISHED;
  setOperating(row.id, true);
  try {
    await ElMessageBox.confirm(
      publish
        ? `发布“${row.title}”后，入库成功时将参与 AI 问答，是否继续？`
        : `撤回“${row.title}”后将立即停止参与 AI 问答，是否继续？`,
      publish ? "发布文档" : "撤回发布",
      { type: publish ? "info" : "warning" }
    );
    await updateDocument(row.id, {
      title: row.title,
      folderId: row.folderId ?? 0,
      visibility: row.visibility,
      status: publish ? DocumentStatus.PUBLISHED : DocumentStatus.DRAFT
    });
    if (mounted) {
      message.success(publish
        ? (row.ingestStatus === IngestStatus.SUCCESS ? "文档已发布，可用于 AI 问答" : "文档已发布，入库完成后可用于 AI 问答")
        : "文档已撤回，不再参与 AI 问答");
      refreshDocuments();
    }
  } catch {
    // Confirmation cancellation is silent; request failures are handled centrally.
  } finally {
    setOperating(row.id, false);
  }
}

async function handleDelete(documentId: number, title: string): Promise<void> {
  if (operatingIds.value.has(documentId)) return;
  setOperating(documentId, true);
  try {
    await ElMessageBox.confirm(`确定删除文档“${title}”吗？删除后不可恢复。`, "删除确认", { type: "warning" });
    await deleteDocument(documentId);
    if (mounted) {
      message.success("删除成功");
      if (knowledgeStore.documentList.length === 1 && pagination.current > 1) pagination.current -= 1;
      refreshDocuments();
    }
  } catch {
    // Confirmation cancellation is silent; request failures are handled centrally.
  } finally {
    setOperating(documentId, false);
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
      <el-table-column label="问答可用性" width="130"><template #default="{ row }"><el-tag :type="qaAvailability(row).type">{{ qaAvailability(row).label }}</el-tag></template></el-table-column>
      <el-table-column label="版本" width="80"><template #default="{ row }">v{{ row.version }}</template></el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="390" fixed="right">
        <template #default="{ row }">
          <el-button v-auth="'knowledge:document:list'" link type="primary" :icon="View" :loading="operatingIds.has(row.id)" @click="handlePreview(row)">预览</el-button>
          <el-button v-auth="'knowledge:document:update'" link type="primary" :icon="Edit" :disabled="operatingIds.has(row.id)" @click="openEdit(row)">编辑</el-button>
          <el-button v-auth="'knowledge:document:update'" link :type="row.status === DocumentStatus.PUBLISHED ? 'info' : 'success'" :disabled="operatingIds.has(row.id)" @click="handlePublishToggle(row)">{{ row.status === DocumentStatus.PUBLISHED ? '撤回' : '发布' }}</el-button>
          <el-button v-auth="'knowledge:document:update'" link type="warning" :icon="RefreshRight" :disabled="operatingIds.has(row.id) || isActiveDocument(row)" @click="handleReingest(row)">重新入库</el-button>
          <el-button v-auth="'knowledge:document:delete'" link type="danger" :icon="Delete" :disabled="operatingIds.has(row.id)" @click="handleDelete(row.id, row.title)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="knowledgeStore.documentTotal > 0" class="pagination" layout="total, sizes, prev, pager, next, jumper" :total="knowledgeStore.documentTotal" :current-page="pagination.current" :page-size="pagination.size" :page-sizes="[10, 20, 50]" @current-change="handlePageChange" @size-change="handleSizeChange" />

    <DocumentUploadDialog v-model="uploadVisible" :knowledge-base-id="knowledgeBaseId" :folder-tree="knowledgeStore.folderTree" :default-folder-id="knowledgeStore.currentFolderId" @uploaded="handleUploaded" @refresh="handleUploadRefresh" />

    <el-dialog v-model="editVisible" title="编辑文档信息" width="520px" destroy-on-close :close-on-click-modal="!editSubmitting">
      <el-form :model="editForm" label-width="86px">
        <el-form-item label="标题" required>
          <el-input v-model="editForm.title" maxlength="256" show-word-limit />
        </el-form-item>
        <el-form-item label="所属分类">
          <el-tree-select v-model="editForm.folderId" :data="knowledgeStore.folderTree" node-key="id" :props="{ label: 'name', children: 'children' }" check-strictly clearable placeholder="根目录" style="width: 100%" />
        </el-form-item>
        <el-form-item label="可见性">
          <el-select v-model="editForm.visibility" style="width: 100%"><el-option :value="1" label="私有" /><el-option :value="2" label="部门" /><el-option :value="3" label="公开" /></el-select>
        </el-form-item>
        <el-form-item label="文档状态">
          <el-select v-model="editForm.status" style="width: 100%"><el-option :value="1" label="草稿" /><el-option :value="2" label="已发布" /><el-option :value="3" label="已归档" /></el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button :disabled="editSubmitting" @click="editVisible = false">取消</el-button><el-button type="primary" :loading="editSubmitting" :disabled="!editForm.title.trim()" @click="submitEdit">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.detail-documents { padding: 16px 0; }
.toolbar { display: flex; gap: 8px; margin-bottom: 16px; align-items: center; justify-content: space-between; }
.toolbar-actions { display: flex; gap: 8px; }
.status-hint { color: var(--el-text-color-secondary); font-size: 12px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
</style>
