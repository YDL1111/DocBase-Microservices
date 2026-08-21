<script setup lang="ts">
/**
 * 知识库列表页。
 * 功能：查询、分页、创建、编辑、删除。
 * 权限控制：按钮级权限由后端返回的 permissions 决定（v-auth），
 * 列表数据由后端按当前用户权限过滤。
 */
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import type { FormInstance, FormRules } from "element-plus";
import { ElMessageBox } from "element-plus";
import { Plus, Edit, Delete, View } from "@element-plus/icons-vue";
import { listKnowledgeBases, createKnowledgeBase, updateKnowledgeBase, deleteKnowledgeBase } from "@/api/knowledge";
import { useKnowledgeStoreHook } from "@/store/modules/knowledge";
import { message } from "@/utils/message";
import type { KnowledgeBase, CreateKnowledgeBaseRequest, UpdateKnowledgeBaseRequest } from "@/api/types";

const router = useRouter();
const knowledgeStore = useKnowledgeStoreHook();

const loading = ref(false);
const dialogVisible = ref(false);
const editingBase = ref<KnowledgeBase | null>(null);
const formRef = ref<FormInstance>();

const pagination = reactive({
  current: 1,
  size: 10
});

const form = reactive<CreateKnowledgeBaseRequest>({
  name: "",
  description: "",
  visibility: 3
});

const rules: FormRules = {
  name: [
    { required: true, message: "请输入知识库名称", trigger: "blur" },
    { max: 128, message: "名称不超过 128 个字符", trigger: "blur" }
  ],
  description: [{ max: 512, message: "描述不超过 512 个字符", trigger: "blur" }]
};

async function fetchList() {
  loading.value = true;
  knowledgeStore.setListLoading(true);
  try {
    const res = await listKnowledgeBases({
      current: pagination.current,
      size: pagination.size
    });
    knowledgeStore.setBaseList(res.records, res.total);
  } catch {
    // 错误提示已由请求层处理
  } finally {
    loading.value = false;
    knowledgeStore.setListLoading(false);
  }
}

function handlePageChange(page: number) {
  pagination.current = page;
  fetchList();
}

function handleSizeChange(size: number) {
  pagination.size = size;
  pagination.current = 1;
  fetchList();
}

function openCreateDialog() {
  editingBase.value = null;
  form.name = "";
  form.description = "";
  form.visibility = 3;
  dialogVisible.value = true;
}

function openEditDialog(base: KnowledgeBase) {
  editingBase.value = base;
  form.name = base.name;
  form.description = base.description;
  form.visibility = base.visibility;
  dialogVisible.value = true;
}

async function handleSubmit() {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  try {
    if (editingBase.value) {
      const data: UpdateKnowledgeBaseRequest = {
        name: form.name,
        description: form.description,
        visibility: form.visibility
      };
      await updateKnowledgeBase(editingBase.value.id, data);
      message.success("更新成功");
    } else {
      await createKnowledgeBase({
        name: form.name,
        description: form.description,
        visibility: form.visibility
      });
      message.success("创建成功");
    }
    dialogVisible.value = false;
    fetchList();
  } catch {
    // 错误提示已由请求层处理
  }
}

async function handleDelete(base: KnowledgeBase) {
  await ElMessageBox.confirm(
    `确定删除知识库"${base.name}"吗？删除后不可恢复。`,
    "删除确认",
    { type: "warning" }
  );
  try {
    await deleteKnowledgeBase(base.id);
    message.success("删除成功");
    // 若删除的是当前页最后一条，回退到上一页
    if (knowledgeStore.baseList.length === 1 && pagination.current > 1) {
      pagination.current -= 1;
    }
    fetchList();
  } catch {
    // 错误提示已由请求层处理
  }
}

function goToDetail(base: KnowledgeBase) {
  router.push(`/knowledge/${base.id}`);
}

onMounted(() => {
  fetchList();
});
</script>

<template>
  <div class="knowledge-list">
    <div class="toolbar">
      <h2>知识库</h2>
      <el-button
        v-auth="'knowledge:base:create'"
        type="primary"
        :icon="Plus"
        @click="openCreateDialog"
      >
        新建知识库
      </el-button>
    </div>

    <!-- 空状态 -->
    <el-empty
      v-if="!loading && knowledgeStore.baseList.length === 0"
      description="暂无知识库，点击右上角创建"
    />

    <!-- 列表 -->
    <el-table
      v-loading="loading"
      :data="knowledgeStore.baseList"
      stripe
      style="width: 100%"
    >
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="description" label="描述" min-width="250" show-overflow-tooltip />
      <el-table-column prop="visibility" label="可见性" width="100">
        <template #default="{ row }">
          {{ row.visibility === 3 ? "公开" : row.visibility === 2 ? "部门" : "私有" }}
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="180" />
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button
            v-auth="'knowledge:base:list'"
            link
            type="primary"
            :icon="View"
            @click="goToDetail(row)"
          >
            查看详情
          </el-button>
          <el-button
            v-auth="'knowledge:base:update'"
            link
            type="primary"
            :icon="Edit"
            @click="openEditDialog(row)"
          >
            编辑
          </el-button>
          <el-button
            v-auth="'knowledge:base:delete'"
            link
            type="danger"
            :icon="Delete"
            @click="handleDelete(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      v-if="knowledgeStore.total > 0"
      class="pagination"
      layout="total, sizes, prev, pager, next, jumper"
      :total="knowledgeStore.total"
      :current-page="pagination.current"
      :page-size="pagination.size"
      :page-sizes="[10, 20, 50]"
      @current-change="handlePageChange"
      @size-change="handleSizeChange"
    />

    <!-- 创建/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingBase ? '编辑知识库' : '新建知识库'"
      width="500px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入知识库名称" maxlength="128" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="请输入描述"
            maxlength="512"
          />
        </el-form-item>
        <el-form-item label="可见性" prop="visibility">
          <el-select v-model="form.visibility" style="width: 100%">
            <el-option :value="1" label="私有" />
            <el-option :value="2" label="部门" />
            <el-option :value="3" label="公开" />
          </el-select>
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
.knowledge-list {
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

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
