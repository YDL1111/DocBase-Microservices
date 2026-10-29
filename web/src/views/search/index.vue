<script setup lang="ts">
import { isAxiosError } from "axios";
import { computed, ref, onMounted, onBeforeUnmount, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { listKnowledgeBases, searchKnowledge, getDocumentContent } from "@/api/knowledge";
import type { KnowledgeSearchHit } from "@/api/knowledge";
import type { KnowledgeBase } from "@/api/types";
const router = useRouter();
const bases = ref<KnowledgeBase[]>([]);
const baseId = ref<number>();
const query = ref("");
const hits = ref<KnowledgeSearchHit[]>([]);
const loading = ref(false);
const loadingBases = ref(true);
const submitted = ref(false);
const error = ref("");
const baseError = ref("");
const opening = ref<number>();
const expanded = ref<Set<number>>(new Set());
const documentFilter = ref<number>();
const resultDocuments = computed(() => [...new Map(hits.value.map(hit => [hit.documentId, hit])).values()]);
const visibleHits = computed(() => hits.value.filter(hit => !documentFilter.value || hit.documentId === documentFilter.value));
function toggle(index: number) {
  const next = new Set(expanded.value);
  if (next.has(index)) next.delete(index); else next.add(index);
  expanded.value = next;
}

let controller: AbortController | undefined;
let generation = 0;
let disposed = false;
function reset() {
  generation++; controller?.abort(); loading.value = false;
  submitted.value = false; hits.value = []; error.value = ""; expanded.value = new Set(); documentFilter.value = undefined;
}
watch([baseId, query], reset);
watch(documentFilter, () => { expanded.value = new Set(); });
onBeforeUnmount(() => { disposed = true; reset(); });
async function loadBases() {
  loadingBases.value = true; baseError.value = "";
  try {
    const collected: KnowledgeBase[] = [];
    for (let current = 1; ; current++) {
      const page = await listKnowledgeBases({ current, size: 100 });
      if (disposed) return;
      collected.push(...page.records.filter(base => base.status === 1));
      if (!page.records.length || current * 100 >= page.total) break;
    }
    bases.value = collected;
    if (!baseId.value && collected.length) baseId.value = collected[0].id;
  } catch { baseError.value = "知识库加载失败，请重试或确认访问权限。"; }
  finally { loadingBases.value = false; }
}
onMounted(loadBases);
async function search() {
  if (!baseId.value || !query.value.trim()) return;
  controller?.abort(); controller = new AbortController();
  const current = ++generation;
  documentFilter.value = undefined; expanded.value = new Set();
  loading.value = true; submitted.value = true; error.value = ""; hits.value = [];
  try {
    const result = await searchKnowledge(baseId.value, query.value.trim(), controller.signal);
    if (current === generation) hits.value = result;
  } catch (cause: unknown) {
    if (current === generation) error.value = (isAxiosError(cause) ? cause.response?.data?.code : cause instanceof Error ? cause.message : "") === "SEARCH_SCOPE_TOO_LARGE"
      ? "当前知识库可检索文档超过 1000 份，暂不支持完整检索，请选择较小的知识库。"
      : "检索未完成，请稍后重试；也请确认你仍有该知识库的访问权限。";
  } finally { if (current === generation) loading.value = false; }
}
async function openOriginal(hit: KnowledgeSearchHit) {
  if (opening.value !== undefined) return;
  opening.value = hit.documentId;
  try {
    const blob = await getDocumentContent(hit.documentId);
    // Download through the authenticated endpoint; never render arbitrary document HTML in our origin.
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url; link.download = hit.filename || hit.title || "document";
    link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch { ElMessage.error("原文获取失败，请确认文档仍可访问。"); }
  finally { opening.value = undefined; }
}
function location(hit: KnowledgeSearchHit) {
  return [hit.heading, hit.page != null ? `页码 ${hit.page}` : "",
    hit.sheet ? `工作表 ${hit.sheet}` : "", hit.slide != null ? `幻灯片 ${hit.slide}` : ""].filter(Boolean).join(" · ");
}
</script>

<template>
  <section class="page-card knowledge-search">
    <header><span class="eyebrow">KNOWLEDGE SEARCH</span><h1>知识检索</h1>
      <p>在知识库中查找相关文档与原文片段。</p></header>
    <el-alert v-if="baseError" :title="baseError" type="error" :closable="false" show-icon />
    <el-button v-if="baseError" @click="loadBases">重新加载知识库</el-button>
    <form class="search-bar" @submit.prevent="search">
      <el-select v-model="baseId" aria-label="知识库" placeholder="选择知识库" filterable :loading="loadingBases">
        <el-option v-for="base in bases" :key="base.id" :label="base.name" :value="base.id" />
      </el-select>
      <el-input v-model="query" aria-label="检索内容" placeholder="输入关键词或描述你要找的内容" maxlength="500" clearable />
      <el-button type="primary" native-type="submit" :loading="loading" :disabled="!baseId || !query.trim()">检索</el-button>
    </form>
    <p class="scope-note">语义检索 · 仅搜索有权限访问、已发布且索引完成的文档 · 最多返回 10 条相关片段</p>
    <div aria-live="polite" :aria-busy="loading">
      <el-skeleton v-if="loading" :rows="5" animated class="search-loading" />
      <div v-else-if="error" class="search-empty"><h2>暂时无法检索</h2><p>{{ error }}</p><el-button @click="search">重新检索</el-button></div>
      <template v-else-if="submitted && hits.length">
        <p class="result-count">返回 {{ hits.length }} 条相关片段，来自 {{ resultDocuments.length }} 份文档</p>
        <el-select v-model="documentFilter" clearable placeholder="全部结果文档" aria-label="筛选结果文档">
          <el-option v-for="doc in resultDocuments" :key="doc.documentId" :label="doc.title || doc.filename" :value="doc.documentId" />
        </el-select>
        <article v-for="(hit, index) in visibleHits" :key="`${hit.documentId}-${index}`" class="search-result">
          <div class="result-heading"><h2>{{ hit.title || hit.filename }}</h2><span>原文片段 {{ index + 1 }}</span></div>
          <p v-if="location(hit)" class="source-location">{{ location(hit) }}</p>
          <p class="passage" :class="{ collapsed: hit.content.length > 240 && !expanded.has(index) }">{{ hit.content }}</p>
          <el-button v-if="hit.content.length > 240" text type="primary" @click="toggle(index)">{{ expanded.has(index) ? '收起片段' : '展开完整片段' }}</el-button>
          <div class="result-actions"><el-button type="primary" plain :loading="opening === hit.documentId" @click="openOriginal(hit)">下载原文</el-button>
            <el-button text @click="router.push(`/knowledge/${baseId}`)">查看知识库</el-button></div>
        </article>
      </template>
      <div v-else class="search-empty"><h2>{{ submitted ? "没有找到相关片段" : "从一份文档，找到你需要的信息" }}</h2>
        <p>{{ submitted ? "可以换一种说法，或确认文档已经发布并完成索引。" : (!loadingBases && !bases.length && !baseError ? "暂无可检索的知识库，请先创建知识库或联系管理员开通权限。" : "选择知识库，输入内容开始检索。") }}</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.passage.collapsed{display:-webkit-box;-webkit-line-clamp:5;-webkit-box-orient:vertical;overflow:hidden;max-height:9.5em}
.knowledge-search{max-width:1200px;margin:0 auto;padding:32px;background:var(--docbase-surface);color:var(--docbase-ink)}
.eyebrow{font-size:12px;letter-spacing:.12em;color:var(--docbase-accent);font-weight:700}
h1{font-size:28px;margin:10px 0}header p,.scope-note{color:var(--docbase-muted);line-height:1.7}
.search-bar{display:flex;gap:12px;margin-top:28px}.search-bar .el-select{flex:0 0 240px}.search-bar .el-input{flex:1}.scope-note{font-size:13px;margin:12px 0 24px}
.search-empty{text-align:center;padding:72px 20px;color:var(--docbase-muted)}.search-empty h2{font-size:20px;color:var(--docbase-ink)}
.search-loading{padding:28px 0}.result-count{font-size:14px;color:var(--docbase-muted)}
.search-result{padding:24px;margin:16px 0;border:1px solid var(--docbase-line);border-radius:12px;background:white}
.result-heading{display:flex;justify-content:space-between;gap:16px;align-items:baseline}.result-heading h2{font-size:18px;margin:0;overflow-wrap:anywhere}.result-heading span,.source-location{font-size:13px;color:var(--docbase-muted)}
.passage{white-space:pre-wrap;overflow-wrap:anywhere;font-size:15px;line-height:1.9;color:var(--docbase-text);max-height:420px;overflow:auto}.result-actions{display:flex;gap:8px;margin-top:18px}
@media(max-width:700px){.knowledge-search{padding:20px}.search-bar{flex-wrap:wrap}.search-bar .el-select{flex-basis:100%}.result-heading{display:block}.search-result{padding:16px}}
</style>
