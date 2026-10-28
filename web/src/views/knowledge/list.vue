<script setup lang="ts">
/** 知识库列表：数据与权限仍由既有 API 和 v-auth 控制。 */
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import type { FormInstance, FormRules } from "element-plus";
import { Plus, Edit, Delete, Refresh, Collection, ArrowRight, Calendar } from "@element-plus/icons-vue";
import { listKnowledgeBases, createKnowledgeBase, updateKnowledgeBase, deleteKnowledgeBase } from "@/api/knowledge";
import { useKnowledgeStoreHook } from "@/store/modules/knowledge";
import { useUserStoreHook } from "@/store/modules/user";
import { message } from "@/utils/message";
import { formatBackendDateTime } from "@/utils/date-time";
import type { KnowledgeBase, CreateKnowledgeBaseRequest, UpdateKnowledgeBaseRequest } from "@/api/types";

const router = useRouter();
const knowledgeStore = useKnowledgeStoreHook();
const userStore = useUserStoreHook();
const loading = ref(false);
const loadError = ref(false);
const submitting = ref(false);
const dialogVisible = ref(false);
const editingBase = ref<KnowledgeBase | null>(null);
const formRef = ref<FormInstance>();
const pagination = reactive({ current: 1, size: 10 });
const form = reactive<CreateKnowledgeBaseRequest>({ name: "", description: "", visibility: 3 });

const rules: FormRules = {
  name: [
    { required: true, message: "请输入知识库名称", trigger: "blur" },
    { max: 128, message: "名称不超过 128 个字符", trigger: "blur" }
  ],
  description: [{ max: 512, message: "描述不超过 512 个字符", trigger: "blur" }]
};

const VISIBILITY_META: Record<number, { label: string; type: "info" | "warning" | "success" }> = {
  1: { label: "私有", type: "info" },
  2: { label: "部门", type: "warning" },
  3: { label: "公开", type: "success" }
};

function visibilityMeta(value: number) {
  return VISIBILITY_META[value] ?? { label: "私有", type: "info" as const };
}

function baseTone(id: number) {
  return `tone-${id % 4}`;
}

async function fetchList() {
  loading.value = true;
  loadError.value = false;
  knowledgeStore.setListLoading(true);
  try {
    const res = await listKnowledgeBases({ current: pagination.current, size: pagination.size });
    knowledgeStore.setBaseList(res.records, res.total);
  } catch {
    loadError.value = true;
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
  if (!formRef.value || submitting.value) return;
  submitting.value = true;
  try {
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;
    if (editingBase.value) {
      const data: UpdateKnowledgeBaseRequest = { name: form.name, description: form.description, visibility: form.visibility };
      await updateKnowledgeBase(editingBase.value.id, data);
      message.success("更新成功");
    } else {
      await createKnowledgeBase({ name: form.name, description: form.description, visibility: form.visibility });
      message.success("创建成功");
    }
    dialogVisible.value = false;
    fetchList();
  } catch {
    // 请求层统一反馈，保留表单供重试。
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(base: KnowledgeBase) {
  try {
    await message.confirmDanger(`确定删除知识库“${base.name}”吗？删除后不可恢复。`);
  } catch {
    return;
  }
  try {
    await deleteKnowledgeBase(base.id);
    message.success("删除成功");
    if (knowledgeStore.baseList.length === 1 && pagination.current > 1) pagination.current -= 1;
    fetchList();
  } catch {
    // 请求层统一反馈。
  }
}

function goToDetail(base: KnowledgeBase) {
  router.push(`/knowledge/${base.id}`);
}

onMounted(fetchList);
</script>

<template>
  <div class="knowledge-list">
    <header class="knowledge-heading">
      <div>
        <span class="heading-eyebrow">KNOWLEDGE COLLECTION</span>
        <h1>知识库</h1>
        <p>把团队文档整理成清晰、可协作的知识空间。</p>
      </div>
      <div class="heading-actions">
        <span v-if="knowledgeStore.total" class="collection-count"><strong>{{ knowledgeStore.total }}</strong> 个知识空间</span>
        <el-button :icon="Refresh" circle aria-label="刷新知识库列表" title="刷新" @click="fetchList" />
        <el-button v-auth="'knowledge:base:create'" type="primary" :icon="Plus" @click="openCreateDialog">新建知识库</el-button>
      </div>
    </header>

    <div v-if="loadError && !loading" class="page-card page-error">
      <p>知识库列表加载失败，请检查网络后重试。</p>
      <el-button type="primary" plain :icon="Refresh" @click="fetchList">重新加载</el-button>
    </div>

    <div v-else-if="!loading && knowledgeStore.baseList.length === 0" class="page-card knowledge-empty">
      <div class="empty-symbol"><el-icon><Collection /></el-icon></div>
      <h2>从第一个知识空间开始</h2>
      <p>创建知识库后，即可上传文档、整理分类并邀请成员协作。</p>
      <el-button v-auth="'knowledge:base:create'" type="primary" :icon="Plus" @click="openCreateDialog">新建第一个知识库</el-button>
    </div>

    <template v-else>
      <div v-loading="loading" class="knowledge-grid">
        <article
          v-for="base in knowledgeStore.baseList"
          :key="base.id"
          class="knowledge-card"
          :class="baseTone(base.id)"
          tabindex="0"
          role="link"
          :aria-label="`打开知识库 ${base.name}`"
          @click="goToDetail(base)"
          @keydown.enter="goToDetail(base)"
        >
          <div class="knowledge-card__top">
            <div class="base-symbol"><el-icon><Collection /></el-icon></div>
            <el-tag :type="visibilityMeta(base.visibility).type" effect="light">{{ visibilityMeta(base.visibility).label }}</el-tag>
          </div>
          <div class="base-content">
            <span class="base-label">KNOWLEDGE SPACE</span>
            <h2>{{ base.name }}</h2>
            <p>{{ base.description || "暂无描述，进入知识库完善内容与文档。" }}</p>
          </div>
          <div class="base-meta"><el-icon><Calendar /></el-icon>创建于 {{ formatBackendDateTime(base.createdAt) }}</div>
          <footer class="knowledge-card__footer">
            <div class="base-actions" @click.stop @keydown.stop>
              <el-button v-auth="'knowledge:base:update'" link :icon="Edit" title="编辑知识库" aria-label="编辑知识库" @click="openEditDialog(base)" />
              <el-button v-auth="'knowledge:base:delete'" link class="delete-action" :icon="Delete" title="删除知识库" aria-label="删除知识库" @click="handleDelete(base)" />
            </div>
            <span class="open-base">进入知识库 <el-icon><ArrowRight /></el-icon></span>
          </footer>
        </article>
      </div>

      <el-pagination
        v-if="knowledgeStore.total > 0"
        class="table-pagination"
        layout="total, sizes, prev, pager, next, jumper"
        :total="knowledgeStore.total"
        :current-page="pagination.current"
        :page-size="pagination.size"
        :page-sizes="[10, 20, 50]"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </template>

    <el-dialog
      v-model="dialogVisible"
      :title="editingBase ? '编辑知识库' : '新建知识库'"
      width="500px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="名称" prop="name"><el-input v-model="form.name" placeholder="例如：产品研发中心" maxlength="128" /></el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="4" placeholder="说明这个知识空间承载的内容与用途" maxlength="512" show-word-limit />
        </el-form-item>
        <el-form-item label="可见性" prop="visibility">
          <el-select v-model="form.visibility" style="width: 100%">
            <el-option :value="1" label="私有 — 仅自己可见" />
            <el-option :value="2" label="部门 — 同组织成员可见" :disabled="!userStore.organizationId" />
            <el-option :value="3" label="公开 — 所有用户可见" />
          </el-select>
          <p v-if="!userStore.organizationId" class="visibility-hint">当前账号尚未分配组织，不能创建部门可见知识库。</p>
          <p v-else class="visibility-hint">部门可见内容仅对当前组织成员开放。</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">{{ editingBase ? "保存修改" : "创建知识库" }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.knowledge-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin: 4px 0 28px;
  padding: 0 3px;
}

.heading-eyebrow {
  color: var(--docbase-amber-strong);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 1.8px;
}

.knowledge-heading h1 {
  margin: 7px 0 0;
  color: var(--docbase-ink);
  font-size: 34px;
  font-weight: 720;
  line-height: 1.2;
  letter-spacing: -1px;
}

.knowledge-heading p {
  margin: 10px 0 0;
  color: var(--docbase-muted);
  font-size: 13px;
}

.heading-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.collection-count {
  margin-right: 8px;
  color: var(--docbase-muted);
  font-size: 13px;
}

.collection-count strong {
  margin-right: 3px;
  color: var(--docbase-ink);
  font-size: 18px;
}

.knowledge-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  min-height: 160px;
}

.knowledge-card {
  --base-color: #26221a;
  position: relative;
  min-height: 280px;
  display: flex;
  flex-direction: column;
  padding: 24px;
  overflow: hidden;
  background: var(--docbase-surface);
  border: 1px solid var(--docbase-line-soft);
  border-radius: var(--docbase-radius-lg);
  box-shadow: var(--docbase-shadow-sm);
  cursor: pointer;
  transition: transform .2s ease, box-shadow .2s ease, border-color .2s ease;
}

.knowledge-card::before {
  position: absolute;
  top: 0;
  left: 24px;
  width: 42px;
  height: 3px;
  content: "";
  background: var(--base-color);
}

.knowledge-card.tone-1 { --base-color: #4f5a5e; }
.knowledge-card.tone-2 { --base-color: #c28f1c; }
.knowledge-card.tone-3 { --base-color: #7a4a36; }

.knowledge-card:hover,
.knowledge-card:focus-visible {
  border-color: var(--docbase-line);
  box-shadow: 0 14px 34px rgba(36, 33, 26, .1);
  outline: none;
  transform: translateY(-3px);
}

/* 卡片入场：阶梯式上浮（backwards 避免锁定 hover transform） */
.knowledge-card { animation: docbase-rise .5s cubic-bezier(.22, .61, .36, 1) backwards; }
.knowledge-card:nth-of-type(1) { animation-delay: .1s; }
.knowledge-card:nth-of-type(2) { animation-delay: .16s; }
.knowledge-card:nth-of-type(3) { animation-delay: .22s; }
.knowledge-card:nth-of-type(4) { animation-delay: .28s; }
.knowledge-card:nth-of-type(5) { animation-delay: .34s; }
.knowledge-card:nth-of-type(6) { animation-delay: .4s; }
.knowledge-card:nth-of-type(n+7) { animation-delay: .46s; }

@media (prefers-reduced-motion: reduce) {
  .knowledge-card { opacity: 1; animation: none; }
}

.knowledge-card__top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.base-symbol {
  width: 46px;
  height: 46px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--base-color);
  font-size: 21px;
  background: var(--docbase-surface-soft);
  border: 1px solid var(--docbase-line-soft);
  border-radius: 10px;
}

.base-content { margin-top: 22px; }
.base-label { color: var(--base-color); font-size: 8px; font-weight: 700; letter-spacing: 1.4px; }
.base-content h2 { margin: 5px 0 0; overflow: hidden; color: var(--docbase-ink); font-size: 19px; font-weight: 700; letter-spacing: -.35px; text-overflow: ellipsis; white-space: nowrap; }
.base-content p { display: -webkit-box; min-height: 42px; margin: 9px 0 0; overflow: hidden; color: var(--docbase-muted); font-size: 13px; line-height: 1.75; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.base-meta { display: flex; align-items: center; gap: 6px; margin-top: 17px; color: var(--docbase-subtle); font-size: 12px; }

.knowledge-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
  padding-top: 15px;
  border-top: 1px solid var(--docbase-line-soft);
}

.base-actions { display: flex; gap: 2px; }
.base-actions .el-button { min-height: 28px; padding: 5px; color: var(--docbase-muted); }
.base-actions .el-button:hover { color: var(--docbase-accent); }
.base-actions .delete-action:hover { color: var(--docbase-danger); }
.open-base { display: inline-flex; align-items: center; gap: 7px; color: var(--docbase-ink); font-size: 13px; font-weight: 600; }
.open-base .el-icon { color: var(--base-color); transition: transform .2s ease; }
.knowledge-card:hover .open-base .el-icon { transform: translateX(3px); }

.knowledge-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 420px; text-align: center; }
.empty-symbol { width: 64px; height: 64px; display: flex; align-items: center; justify-content: center; color: var(--docbase-accent); font-size: 28px; background: var(--docbase-accent-soft); border-radius: 20px; }
.knowledge-empty h2 { margin: 20px 0 0; font-size: 20px; }
.knowledge-empty p { margin: 9px 0 22px; color: var(--docbase-muted); font-size: 13px; }
.visibility-hint { margin: 6px 0 0; color: var(--docbase-subtle); font-size: 13px; line-height: 1.5; }

@media (max-width: 1180px) {
  .knowledge-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 700px) {
  .knowledge-heading { align-items: flex-start; flex-direction: column; }
  .knowledge-heading h1 { font-size: 30px; }
  .heading-actions { width: 100%; }
  .collection-count { margin-right: auto; }
  .knowledge-grid { grid-template-columns: 1fr; }
  .knowledge-card { min-height: 260px; }
}
</style>
