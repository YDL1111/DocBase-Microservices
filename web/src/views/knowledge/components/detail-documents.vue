<script setup lang="ts">
/**
 * 知识库文档管理标签页。
 * 功能：文档列表、分页、删除、版本信息展示、入库状态展示。
 *
 * 注意：本阶段不实现完整上传进度轮询，入库状态仅作展示。
 * knowledgeBaseId 来自 props，folderId 来自当前选择的目录。
 */
import { onMounted, reactive, ref, watch } from "vue";
import { ElMessageBox } from "element-plus";
import { Delete, Refresh } from "@element-plus/icons-vue";
import { listDocuments, deleteDocument } from "@/api/knowledge";
import { useKnowledgeStoreHook } from "@/store/modules/knowledge";
import { message } from "@/utils/message";
import {
  ingestStatusLabel,
  documentStatusLabel
} from "@/api/types";

const props = defineProps<{ knowledgeBaseId: number | null }>();

const knowledgeStore = useKnowledgeStoreHook();

const loading = ref(false);

const pagination = reactive({
  current: 1,
  size: 10
});

async function fetchDocuments() {
  if (props.knowledgeBaseId === null) return;
  loading.value = true;
  // 捕获请求序号，写入时校验，防止异步乱序响应串库
  const seq = knowledgeStore.getRequestSeq();
  try {
    const res = await listDocuments(props.knowledgeBaseId, {
      current: pagination.current,
      size: pagination.size
    });
    knowledgeStore.setDocumentList(res.records, res.total, seq);
  } catch {
    // 错误提示已由请求层处理
  } finally {
    loading.value = false;
  }
}

function handlePageChange(page: number) {
  pagination.current = page;
  fetchDocuments();
}

function handleSizeChange(size: number) {
  pagination.size = size;
  pagination.current = 1;
  fetchDocuments();
}

async function handleDelete(documentId: number, title: string) {
  await ElMessageBox.confirm(
    `确定删除文档"${title}"吗？删除后不可恢复。`,
    "删除确认",
    { type: "warning" }
  );
  try {
    await deleteDocument(documentId);
    message.success("删除成功");
    if (knowledgeStore.documentList.length === 1 && pagination.current > 1) {
      pagination.current -= 1;
    }
    fetchDocuments();
  } catch {
    // 错误提示已由请求层处理
  }
}

function formatFileSize(bytes: number): string {
  if (!bytes) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * 文档列表在知识库详情加载完成后才请求。
 *
 * 注意：后端 listDocuments 不支持 folderId 筛选，
 * 因此目录点击不会过滤文档（P0-3 已移除该误导功能）。
 */
watch(
  () => knowledgeStore.currentBase,
  (base) => {
    if (base && base.id === props.knowledgeBaseId) {
      pagination.current = 1;
      fetchDocuments();
    }
  },
  { immediate: true }
);
</script>

<template>
  <div class="detail-documents">
    <!-- 刷新 -->
    <div class="toolbar">
      <el-button :icon="Refresh" circle @click="fetchDocuments" />
    </div>

    <!-- 文档列表 -->
    <el-table v-loading="loading" :data="knowledgeStore.documentList" stripe>
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="originalFilename" label="文件名" min-width="180" show-overflow-tooltip />
      <el-table-column label="大小" width="100">
        <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="入库状态" width="100">
        <template #default="{ row }">
          <el-tag
            :type="
              row.ingestStatus === 3
                ? 'success'
                : row.ingestStatus === 4
                  ? 'danger'
                  : row.ingestStatus === 2
                    ? 'warning'
                    : 'info'
            "
          >
            {{ ingestStatusLabel(row.ingestStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="文档状态" width="100">
        <template #default="{ row }">
          {{ documentStatusLabel(row.status) }}
        </template>
      </el-table-column>
      <el-table-column label="版本" width="80">
        <template #default="{ row }">v{{ row.version }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            v-auth="'knowledge:document:delete'"
            link
            type="danger"
            :icon="Delete"
            @click="handleDelete(row.id, row.title)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      v-if="knowledgeStore.documentTotal > 0"
      class="pagination"
      layout="total, sizes, prev, pager, next, jumper"
      :total="knowledgeStore.documentTotal"
      :current-page="pagination.current"
      :page-size="pagination.size"
      :page-sizes="[10, 20, 50]"
      @current-change="handlePageChange"
      @size-change="handleSizeChange"
    />
  </div>
</template>

<style lang="scss" scoped>
.detail-documents {
  padding: 16px 0;
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  align-items: center;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
