<script setup lang="ts">
/**
 * 知识库详情页。
 * 包含四个标签页：概览、成员、文档分类、文档。
 *
 * 关键设计：
 *  - knowledgeBaseId 仅来自路由参数（props），不信任外部输入；
 *  - 进入页面时加载知识库详情、目录树、成员列表；
 *  - 切换回此页面时（从其他知识库切回），通过 watch 检测 id 变化重新加载；
 *  - 所有子组件通过 props 接收 knowledgeBaseId，不自行拼接。
 */
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowLeft, Collection } from "@element-plus/icons-vue";
import { getKnowledgeBase } from "@/api/knowledge";
import { useKnowledgeStoreHook } from "@/store/modules/knowledge";
import { message } from "@/utils/message";
import type { KnowledgeBase } from "@/api/types";
import DetailOverview from "./components/detail-overview.vue";
import DetailMembers from "./components/detail-members.vue";
import DetailFolders from "./components/detail-folders.vue";
import DetailDocuments from "./components/detail-documents.vue";

const route = useRoute();
const router = useRouter();
const knowledgeStore = useKnowledgeStoreHook();

/**
 * 从路由参数获取知识库 ID（唯一可信来源）。
 * 严格验证：必须为正安全整数，否则为 null（页面进入 404）。
 */
const knowledgeBaseId = computed<number | null>(() => {
  const raw = route.params.id;
  const num = Number(raw);
  if (!Number.isInteger(num) || num <= 0 || !Number.isSafeInteger(num)) {
    return null;
  }
  return num;
});

const currentTab = ref("overview");
const loading = ref(false);

const visibilityMeta = computed(() => {
  const value = knowledgeStore.currentBase?.visibility;
  if (value === 3) return { label: "公开", type: "success" as const };
  if (value === 2) return { label: "部门可见", type: "warning" as const };
  return { label: "私有", type: "info" as const };
});

async function loadBaseInfo() {
  if (knowledgeBaseId.value === null) {
    message.error("无效的知识库 ID");
    router.replace("/error/404");
    return;
  }
  loading.value = true;
  // 捕获当前上下文的序号，用于写入时校验
  const seq = knowledgeStore.getRequestSeq();
  try {
    const base: KnowledgeBase = await getKnowledgeBase(knowledgeBaseId.value);
    // 使用 seq 校验，不递增序号（避免影响并发子请求）
    knowledgeStore.setCurrentBase(base, seq);
  } catch {
    message.error("知识库不存在或无权访问");
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  if (route.name !== "KnowledgeDetail") return;
  // 进入页面时开始新上下文（递增序号 + 清空旧状态）
  if (knowledgeBaseId.value !== null) {
    knowledgeStore.beginBaseContext(knowledgeBaseId.value);
    loadBaseInfo();
  }
});

// 路由参数变化时（从其他知识库切回）重新加载
watch(
  () => knowledgeBaseId.value,
  (newId, oldId) => {
    // Leaving the detail page clears route params before unmount. Only treat a
    // missing ID as invalid while this detail route is still the active target.
    if (route.name !== "KnowledgeDetail") return;
    if (newId !== null && newId !== oldId) {
      // 开始新上下文：递增序号 + 清空旧状态
      knowledgeStore.beginBaseContext(newId);
      currentTab.value = "overview";
      loadBaseInfo();
    } else if (newId === null) {
      router.replace("/error/404");
    }
  }
);
</script>

<template>
  <div class="knowledge-detail">
    <div v-if="knowledgeBaseId === null" class="invalid-id">
      <el-result icon="error" title="404" sub-title="无效的知识库 ID" />
    </div>

    <div v-else v-loading="loading" class="detail-container">
      <button class="back-button" type="button" @click="router.push('/knowledge')">
        <el-icon><ArrowLeft /></el-icon> 返回知识库
      </button>
      <header v-if="knowledgeStore.currentBase" class="detail-header">
        <div class="detail-brand"><el-icon><Collection /></el-icon></div>
        <div class="detail-title">
          <span class="detail-eyebrow">KNOWLEDGE WORKSPACE</span>
          <div class="detail-title__row">
            <h1>{{ knowledgeStore.currentBase.name }}</h1>
            <el-tag :type="visibilityMeta.type" effect="light">{{ visibilityMeta.label }}</el-tag>
          </div>
          <p class="description">{{ knowledgeStore.currentBase.description || "管理这个知识空间中的文档、分类与协作成员。" }}</p>
        </div>
        <span class="detail-id">SPACE / {{ knowledgeBaseId }}</span>
      </header>

      <div class="detail-workspace">
        <el-tabs v-model="currentTab" class="detail-tabs">
          <el-tab-pane label="概览" name="overview"><DetailOverview :knowledge-base-id="knowledgeBaseId" /></el-tab-pane>
          <el-tab-pane label="文档" name="documents"><DetailDocuments :knowledge-base-id="knowledgeBaseId" /></el-tab-pane>
          <el-tab-pane label="文档分类" name="folders"><DetailFolders :knowledge-base-id="knowledgeBaseId" /></el-tab-pane>
          <el-tab-pane label="成员协作" name="members"><DetailMembers :knowledge-base-id="knowledgeBaseId" /></el-tab-pane>
        </el-tabs>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.knowledge-detail { min-height: 100%; }
.back-button { display: inline-flex; align-items: center; gap: 7px; margin: 0 0 16px; padding: 5px 0; color: var(--docbase-muted); font: inherit; font-size: 13px; background: none; border: 0; cursor: pointer; }
.back-button:hover { color: var(--docbase-accent); }
.detail-header { position: relative; display: flex; align-items: center; gap: 22px; min-height: 160px; padding: 30px 34px; overflow: hidden; color: var(--docbase-ink); background: linear-gradient(110deg, var(--docbase-accent-soft), var(--docbase-surface)); border: 1px solid var(--docbase-line); border-radius: var(--docbase-radius-lg); box-shadow: var(--docbase-shadow-sm); }
.detail-header::after { position: absolute; top: 0; bottom: 0; left: 0; width: 3px; content: ""; background: var(--docbase-amber); }
.detail-brand { width: 58px; height: 58px; display: flex; align-items: center; justify-content: center; flex: 0 0 58px; color: var(--docbase-accent); font-size: 25px; background: var(--docbase-surface); border: 1px solid var(--docbase-line); border-radius: 12px; }
.detail-title { position: relative; z-index: 1; min-width: 0; }
.detail-eyebrow { color: var(--docbase-amber); font-size: 12px; font-weight: 700; letter-spacing: 1.8px; }
.detail-title__row { display: flex; align-items: center; gap: 12px; margin-top: 6px; }
.detail-title h1 { margin: 0; overflow: hidden; font-size: 26px; font-weight: 700; letter-spacing: -.7px; text-overflow: ellipsis; white-space: nowrap; }
.description { max-width: 680px; margin: 10px 0 0; color: var(--docbase-text); font-size: 13px; line-height: 1.7; }
.detail-id { position: absolute; top: 25px; right: 28px; color: var(--docbase-muted); font-family: var(--docbase-font-mono); font-size: 12px; letter-spacing: 1px; }
.detail-workspace { margin-top: 18px; padding: 4px 26px 24px; background: var(--docbase-surface); border: 1px solid var(--docbase-line-soft); border-radius: var(--docbase-radius-lg); box-shadow: var(--docbase-shadow-sm); }
.detail-tabs { margin-top: 0; }
.detail-tabs :deep(.el-tabs__header) { margin-bottom: 25px; }
.detail-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: var(--docbase-line-soft); }
.detail-tabs :deep(.el-tabs__item) { height: 55px; padding: 0 22px; color: var(--docbase-muted); font-size: 13px; }
.detail-tabs :deep(.el-tabs__item:first-child) { padding-left: 3px; padding-right: 27px; }
.detail-tabs :deep(.el-tabs__item.is-active) { color: var(--docbase-accent); font-weight: 650; }
.detail-tabs :deep(.el-tabs__active-bar) { height: 3px; border-radius: 3px 3px 0 0; }
@media (max-width: 700px) {
  .detail-header { align-items: flex-start; min-height: 200px; padding: 25px 22px; }
  .detail-brand { width: 48px; height: 48px; flex-basis: 48px; font-size: 21px; border-radius: 14px; }
  .detail-title h1 { font-size: 22px; }
  .detail-title__row { align-items: flex-start; flex-direction: column; gap: 7px; }
  .detail-id { display: none; }
  .detail-workspace { padding: 3px 17px 18px; }
  .detail-tabs :deep(.el-tabs__item) { padding: 0 12px; }
  .detail-tabs :deep(.el-tabs__item:first-child) { padding-right: 12px; }
}
</style>
