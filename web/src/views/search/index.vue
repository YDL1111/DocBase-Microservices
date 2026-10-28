<script setup lang="ts">
import { computed, ref } from "vue";
import { Search, Document } from "@element-plus/icons-vue";

const query = ref("");
const submitted = ref<string | null>(null);
const format = ref("全部格式");
const examples = [
  { name: "项目部署指南.pdf", type: "PDF", section: "第 12 页 · 服务初始化", text: "部署前准备数据库与对象存储连接，执行初始化脚本，再启动服务。" },
  { name: "团队协作手册.docx", type: "Word", section: "第 3 节 · 文档共享", text: "知识库按公开、部门和私有范围管理，成员只能访问获得授权的文档。" },
  { name: "接口说明.md", type: "Markdown", section: "认证与权限", text: "接口请求需要有效的身份凭证，服务端校验账号权限后返回数据。" }
];
const results = computed(() => submitted.value === null ? [] : examples.filter(item =>
  (format.value === "全部格式" || item.type === format.value) &&
  `${item.name}${item.text}${item.section}`.includes(submitted.value!.trim())
));
function search() { submitted.value = query.value; }
function example(text: string) { query.value = text; search(); }
</script>

<template>
  <section class="search-page">
    <header><div><span class="eyebrow">团队知识空间</span><h1>知识检索</h1><p>查找文档中的内容，定位到对应段落。</p></div><el-tag type="info" effect="plain">前端预览</el-tag></header>
    <div class="preview-notice" role="note">真实检索尚未接入。这里仅搜索下方三份固定示例，不会读取或上传团队文档。</div>
    <form class="search-bar" @submit.prevent="search">
      <el-input v-model="query" :prefix-icon="Search" placeholder="输入关键词，例如：部署、权限" aria-label="检索关键词" clearable size="large" />
      <el-select v-model="format" aria-label="文档格式" size="large"><el-option v-for="value in ['全部格式', 'PDF', 'Word', 'Markdown']" :key="value" :label="value" :value="value" /></el-select>
      <el-button type="primary" native-type="submit" size="large">搜索示例</el-button>
    </form>
    <div class="suggestions"><span>试试搜索</span><button v-for="word in ['部署', '权限', '知识库']" :key="word" @click="example(word)">{{ word }}</button></div>
    <div v-if="submitted === null" class="search-empty"><el-icon :size="38"><Search /></el-icon><h2>从一个关键词开始</h2><p>示例包含项目部署指南、团队协作手册与接口说明。</p><span>后续接入真实检索后，这里将展示命中文档和原文片段。</span></div>
    <template v-else><div class="result-count" role="status">找到 {{ results.length }} 条示例结果</div><article v-for="item in results" :key="item.name" class="search-result"><el-icon :size="24"><Document /></el-icon><div><h2>{{ item.name }} <el-tag size="small" type="info">示例</el-tag></h2><p>{{ item.text }}</p><span>{{ item.type }} · {{ item.section }}</span></div></article><el-empty v-if="!results.length" description="示例中没有匹配内容，换个关键词试试" /></template>
  </section>
</template>

<style scoped>
.search-page { max-width: 1200px; margin: 0 auto; }
header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; gap: 20px; }
.eyebrow { color: var(--docbase-accent); font-size: 13px; }
h1 { font-size: 30px; margin: 8px 0; letter-spacing: -.7px; }
header p, .search-empty p { color: var(--docbase-muted); }
.preview-notice { padding: 13px 16px; background: var(--docbase-surface-soft); border-radius: 8px; color: var(--docbase-text); margin-bottom: 24px; font-size: 13px; }
.search-bar { display: flex; gap: 12px; }
.search-bar .el-select { flex: 0 0 150px; }
.suggestions { display: flex; gap: 10px; align-items: center; margin-top: 16px; color: var(--docbase-muted); font-size: 13px; }
.suggestions button { border: 1px solid var(--docbase-line); border-radius: 20px; background: var(--docbase-surface); padding: 5px 14px; color: var(--docbase-accent); cursor: pointer; }
.search-empty { padding: 80px 20px; text-align: center; color: var(--docbase-muted); }
.search-empty h2 { font-size: 20px; color: var(--docbase-ink); }
.search-empty span, .search-result span { font-size: 13px; color: var(--docbase-muted); }
.result-count { margin: 36px 0 12px; color: var(--docbase-muted); }
.search-result { display: flex; gap: 18px; padding: 24px 8px; border-top: 1px solid var(--docbase-line-soft); }
.search-result .el-icon { color: var(--docbase-accent); margin-top: 5px; }
.search-result h2 { font-size: 16px; margin: 0; }
.search-result p { line-height: 1.8; color: var(--docbase-text); }
@media(max-width: 640px) { .search-bar { flex-wrap: wrap; } .search-bar > .el-input { flex: 0 0 100%; } .search-empty { padding: 48px 8px; } }
</style>
