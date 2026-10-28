<script setup lang="ts">
/**
 * 知识库概览标签页。
 * 展示知识库基本信息。
 * knowledgeBaseId 由父组件通过 props 传入，不自行获取。
 */
import { computed } from "vue";
import { useKnowledgeStoreHook } from "@/store/modules/knowledge";
import { formatBackendDateTime } from "@/utils/date-time";

const props = defineProps<{ knowledgeBaseId: number | null }>();

const knowledgeStore = useKnowledgeStoreHook();

const base = computed(() => knowledgeStore.currentBase);

const visibilityMeta = computed(() => {
  const value = base.value?.visibility;
  if (value === 3) return { label: "公开", type: "success" as const };
  if (value === 2) return { label: "部门", type: "warning" as const };
  return { label: "私有", type: "info" as const };
});
</script>

<template>
  <div class="overview">
    <!-- 详情尚未加载完成时的骨架屏，避免空白 -->
    <el-skeleton v-if="!base" :rows="5" animated />
    <div v-else class="overview-grid">
      <section class="overview-summary">
        <span class="overview-label">空间信息</span>
        <h2>{{ base.name }}</h2>
        <p>{{ base.description || "暂未添加空间说明。" }}</p>
        <dl class="overview-dates">
          <div><dt>创建时间</dt><dd>{{ formatBackendDateTime(base.createdAt) }}</dd></div>
          <div><dt>最近更新</dt><dd>{{ formatBackendDateTime(base.updatedAt) }}</dd></div>
        </dl>
      </section>
      <section class="overview-access">
        <span class="overview-label">访问与状态</span>
        <dl>
          <div><dt>可见性</dt><dd><el-tag :type="visibilityMeta.type" effect="light">{{ visibilityMeta.label }}</el-tag></dd></div>
          <div><dt>空间状态</dt><dd><el-tag :type="base.status === 1 ? 'success' : 'danger'" effect="light">{{ base.status === 1 ? "启用" : "停用" }}</el-tag></dd></div>
          <div><dt>知识库 ID</dt><dd class="space-number">{{ base.id }}</dd></div>
        </dl>
        <p class="access-note">具体文档的访问权限以授权范围为准。</p>
      </section>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.overview { padding: 4px 0 10px; }
.overview-grid { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(240px, 1fr); gap: 28px; }
.overview-summary, .overview-access { min-width: 0; padding: 26px; border: 1px solid var(--docbase-line-soft); border-radius: 12px; }
.overview-access { background: var(--docbase-surface-soft); }
.overview-label { font-size: 13px; color: var(--docbase-accent); font-weight: 600; }
h2 { margin: 14px 0 10px; font-size: 23px; color: var(--docbase-ink); overflow-wrap: anywhere; }
p { color: var(--docbase-text); line-height: 1.8; overflow-wrap: anywhere; }
dl { margin: 22px 0 0; }
dl > div { display: flex; justify-content: space-between; gap: 20px; padding: 13px 0; border-top: 1px solid var(--docbase-line-soft); }
dt { color: var(--docbase-muted); font-size: 13px; }
dd { margin: 0; color: var(--docbase-ink); font-size: 13px; text-align: right; }
.space-number { font-family: var(--docbase-font-mono); }
.access-note { margin: 14px 0 0; font-size: 13px; color: var(--docbase-muted); }
@media (max-width: 1000px) { .overview-grid { grid-template-columns: 1fr; gap: 16px; } }
@media (max-width: 600px) { .overview-summary, .overview-access { padding: 18px; } .overview-dates > div { flex-direction: column; gap: 6px; } .overview-dates dd { text-align: left; } }
</style>
