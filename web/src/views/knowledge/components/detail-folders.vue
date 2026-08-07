<script setup lang="ts">
/**
 * 知识库目录树标签页。
 * 功能：查询目录树、新建目录、编辑目录、删除目录。
 *
 * 关键设计：
 *  - 所有请求携带 knowledgeBaseId（来自 props）；
 *  - 目录树使用 el-tree 展示，支持递归渲染；
 *  - 删除前确认，删除后刷新树；
 *  - 知识库切换时自动重新加载。
 */
import { onMounted, reactive, ref, watch } from "vue";
import type { FormInstance, FormRules } from "element-plus";
import { ElMessageBox } from "element-plus";
import { Plus, FolderOpened, Edit, Delete } from "@element-plus/icons-vue";
import {
  getFolderTree,
  createFolder,
  updateFolder,
  deleteFolder
} from "@/api/knowledge";
import { useKnowledgeStoreHook } from "@/store/modules/knowledge";
import { message } from "@/utils/message";
import type { CreateFolderRequest, UpdateFolderRequest, FolderNode } from "@/api/types";

const props = defineProps<{ knowledgeBaseId: number | null }>();

const knowledgeStore = useKnowledgeStoreHook();

const loading = ref(false);
const dialogVisible = ref(false);
const editingNode = ref<FolderNode | null>(null);
const formRef = ref<FormInstance>();
const treeRef = ref();

const form = reactive<CreateFolderRequest & { id?: number }>({
  parentId: 0,
  name: "",
  sortNum: 0
});

const rules: FormRules = {
  name: [
    { required: true, message: "请输入目录名称", trigger: "blur" },
    { max: 128, message: "名称不超过 128 个字符", trigger: "blur" }
  ]
};

async function fetchTree() {
  if (props.knowledgeBaseId === null) return;
  loading.value = true;
  // 捕获请求序号，写入时校验，防止异步乱序响应串库
  const seq = knowledgeStore.getRequestSeq();
  try {
    const tree = await getFolderTree(props.knowledgeBaseId);
    knowledgeStore.setFolderTree(tree, seq);
  } catch {
    // 错误提示已由请求层处理
  } finally {
    loading.value = false;
  }
}

function openCreateDialog(parentId = 0) {
  editingNode.value = null;
  form.parentId = parentId;
  form.name = "";
  form.sortNum = 0;
  dialogVisible.value = true;
}

function openEditDialog(node: FolderNode) {
  editingNode.value = node;
  form.parentId = node.parentId;
  form.name = node.name;
  form.sortNum = 0;
  dialogVisible.value = true;
}

async function handleSubmit() {
  if (!formRef.value) return;
  if (props.knowledgeBaseId === null) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  try {
    if (editingNode.value) {
      const data: UpdateFolderRequest = {
        parentId: form.parentId,
        name: form.name,
        sortNum: form.sortNum
      };
      await updateFolder(props.knowledgeBaseId, editingNode.value.id, data);
      message.success("更新成功");
    } else {
      const data: CreateFolderRequest = {
        parentId: form.parentId || undefined,
        name: form.name,
        sortNum: form.sortNum || undefined
      };
      await createFolder(props.knowledgeBaseId, data);
      message.success("创建成功");
    }
    dialogVisible.value = false;
    fetchTree();
  } catch {
    // 错误提示已由请求层处理
  }
}

async function handleDelete(node: FolderNode) {
  if (props.knowledgeBaseId === null) return;
  await ElMessageBox.confirm(
    `确定删除目录"${node.name}"吗？子目录将一并删除。`,
    "删除确认",
    { type: "warning" }
  );
  try {
    await deleteFolder(props.knowledgeBaseId, node.id);
    message.success("删除成功");
    fetchTree();
  } catch {
    // 错误提示已由请求层处理
  }
}

const treeProps = {
  children: "children",
  label: "name"
};

/**
 * 目录树在知识库详情加载完成后才请求。
 *
 * 使用 currentBase 作为触发条件（而非 knowledgeBaseId），
 * 确保：1) 知识库上下文已就绪；2) 请求序号与当前上下文一致。
 */
watch(
  () => knowledgeStore.currentBase,
  (base) => {
    if (base && base.id === props.knowledgeBaseId) {
      fetchTree();
    }
  },
  { immediate: true }
);
</script>

<template>
  <div class="detail-folders">
    <div class="toolbar">
      <el-button
        v-auth="'knowledge:folder:create'"
        type="primary"
        :icon="Plus"
        @click="openCreateDialog(0)"
      >
        新建根目录
      </el-button>
    </div>

    <el-tree
      ref="treeRef"
      v-loading="loading"
      :data="knowledgeStore.folderTree"
      :props="treeProps"
      default-expand-all
      node-key="id"
      class="folder-tree"
    >
      <template #default="{ node, data }">
        <span class="tree-node">
          <el-icon><FolderOpened /></el-icon>
          <span class="node-label">{{ node.label }}</span>
          <span class="node-actions">
            <el-button
              v-auth="'knowledge:folder:create'"
              link
              type="primary"
              :icon="Plus"
              @click.stop="openCreateDialog(data.id)"
            >
              子目录
            </el-button>
            <el-button
              v-auth="'knowledge:folder:update'"
              link
              type="primary"
              :icon="Edit"
              @click.stop="openEditDialog(data)"
            >
              编辑
            </el-button>
            <el-button
              v-auth="'knowledge:folder:delete'"
              link
              type="danger"
              :icon="Delete"
              @click.stop="handleDelete(data)"
            >
              删除
            </el-button>
          </span>
        </span>
      </template>
    </el-tree>

    <!-- 创建/编辑目录对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingNode ? '编辑目录' : '新建目录'"
      width="400px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入目录名称" maxlength="128" />
        </el-form-item>
        <el-form-item label="父目录 ID" prop="parentId">
          <el-input-number v-model="form.parentId" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="排序" prop="sortNum">
          <el-input-number v-model="form.sortNum" :min="0" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.detail-folders {
  padding: 16px 0;
}

.toolbar {
  margin-bottom: 16px;
}

.folder-tree {
  min-height: 200px;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;

  .node-label {
    flex: 1;
  }

  .node-actions {
    display: none;
    gap: 4px;
  }

  &:hover .node-actions {
    display: flex;
  }
}
</style>
