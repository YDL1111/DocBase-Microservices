<script setup lang="ts">
/**
 * DocBase 品牌首页（公开访问）。
 *
 * 四个章节：首页（浅灰绿）/ 产品功能（暖白）/ 智能问答（浅绿）/ 关于项目（深墨绿）。
 *  - 首屏以产品名 DocBase 为主标题，下方紧接文档管理窗口 + 问答面板的叠层预览；
 *  - 产品功能：左侧选择功能，右侧切换对应的界面演示；
 *  - 智能问答：可点击的交互演示（示例问题 → 回答 → 引用 → 原文高亮），数据为示例；
 *  - 关于项目：真实技术构成与有效入口，深色收尾。
 *
 * 滚动：桌面端使用 CSS scroll-snap（proximity，宽松吸附），移动端与
 * prefers-reduced-motion 下退回普通纵向滚动。
 *
 * 注意：hash 路由模式下不能使用 href="#xxx" 锚点（会被当作路由），
 * 因此导航滚动统一走 scrollToSection。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { isAuthenticated } from "@/utils/auth";
import AuthDialog from "./components/auth-dialog.vue";

const router = useRouter();
const previewItems = [
  { key: 'home', title: '工作台', path: '/home' },
  { key: 'docs', title: '文档管理', path: '/knowledge' },
  { key: 'search', title: '知识检索', path: '/search' },
  { key: 'chat', title: '智能问答', path: '/ai/chat' },
  { key: 'roles', title: '权限管理', path: '/system/roles' }
];
const previewKey = ref('docs');
function selectPreview(key: string) {
  previewKey.value = key;
}
const previewItem = computed(() => previewItems.find(item => item.key === previewKey.value)!);
const pendingPath = ref('/home');
function openProduct(path: string) {
  pendingPath.value = path;
  if (authed.value) router.push(path);
  else openAuth('login', path);
}
const authed = ref(false);
const authVisible = ref(false);
const authMode = ref<"login" | "register">("login");

/** 打开登录/注册弹窗 */
function openAuth(mode: "login" | "register", destination = "/home") {
  pendingPath.value = destination;
  authMode.value = mode;
  authVisible.value = true;
}

/** “进入工作台”：已登录直达工作台，未登录先登录 */
function enterWorkspace() {
  openProduct("/home");
}

/** 登录/注册成功后进入工作台 */
function onAuthSuccess() {
  authVisible.value = false;
  authed.value = true;
  router.push(pendingPath.value);
}

const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/** 页内导航：平滑滚动到章节（scroll-padding 保证固定导航不遮挡标题） */
function scrollToSection(id: string) {
  document.getElementById(id)?.scrollIntoView?.({
    behavior: reduceMotion ? "auto" : "smooth",
    block: "start"
  });
}

/* ===== 导航当前章节高亮 + 章节进入动效 + 滚动吸附 ===== */
const sectionIds = ["top", "features", "retrieval", "about"];
const activeSection = ref("top");
/** 是否启用 JS 控制的入场（禁用时所有内容直接可见） */
const jsReveal = ref(false);
let sectionIO: IntersectionObserver | null = null;
let revealIO: IntersectionObserver | null = null;

onMounted(() => {
  authed.value = isAuthenticated();
  // 滚动吸附挂在 <html> 上，离开首页时移除，避免影响其他页面
  document.documentElement.classList.add("landing-snap");

  if ("IntersectionObserver" in window) {
    // 当前章节：占据视口中部区域的章节视为激活
    sectionIO = new IntersectionObserver(
      entries => {
        for (const e of entries) {
          if (e.isIntersecting) activeSection.value = e.target.id;
        }
      },
      { rootMargin: "-40% 0px -55% 0px" }
    );
    for (const id of sectionIds) {
      const el = document.getElementById(id);
      if (el) sectionIO.observe(el);
    }
    // 章节关键元素入场（只播放一次）
    if (!reduceMotion) {
      jsReveal.value = true;
      revealIO = new IntersectionObserver(
        entries => {
          for (const e of entries) {
            if (e.isIntersecting) {
              e.target.classList.add("in");
              revealIO!.unobserve(e.target);
            }
          }
        },
        { rootMargin: "0px 0px -8% 0px" }
      );
      document.querySelectorAll(".landing .rv").forEach(el => revealIO!.observe(el));
    }
  }
});

onBeforeUnmount(() => {
  document.documentElement.classList.remove("landing-snap");
  sectionIO?.disconnect();
  revealIO?.disconnect();
});

/* ===== 首屏产品预览（静态示意，不读取真实文档） ===== */
interface DemoDoc {
  badge: string;
  badgeClass: string;
  name: string;
  time: string;
}
const demoDocs: DemoDoc[] = [
  { badge: "PDF", badgeClass: "is-pdf", name: "产品设计规范.pdf", time: "2024-10-24 14:32" },
  { badge: "PDF", badgeClass: "is-pdf", name: "项目部署指南.pdf", time: "2024-10-22 10:18" },
  { badge: "W", badgeClass: "is-word", name: "团队协作手册.docx", time: "2024-10-18 16:03" },
  { badge: "MD", badgeClass: "is-md", name: "接口说明.md", time: "2024-10-16 11:20" }
];

/* ===== 产品功能：功能切换演示 ===== */
type FeatureKey = "archive" | "search" | "perm";
const features: { key: FeatureKey; title: string; desc: string }[] = [
  { key: "archive", title: "文档归档", desc: "PDF、Word、Excel、PPT 与 Markdown 集中入库，目录分类与版本更新后自动完成索引。" },
  { key: "search", title: "知识检索", desc: "按关键词或语义查找文档内容，结果片段直接标注出处与页码。" },
  { key: "perm", title: "访问权限", desc: "公开、部门与私有三种可见范围，谁可以看哪份文档一目了然。" }
];
const activeFeature = ref<FeatureKey>("archive");

/* ===== 智能问答：交互演示数据（示例，自洽对应） ===== */
interface DemoQA {
  q: string;
  lead: string;
  quote: string;
  citeDoc: string;
  citePage: string;
  citeBadge: string;
  citeBadgeClass: string;
  sourceParas: { t: string; hit?: boolean; head?: boolean }[];
}
const demoQAs: DemoQA[] = [
  {
    q: "如何完成项目部署？",
    lead: "完成环境配置与服务初始化后，即可启动项目。",
    quote: "首先，按照文档中的步骤完成服务器环境配置，然后执行初始化脚本，最后启动服务并检查运行状态。",
    citeDoc: "项目部署指南.pdf",
    citePage: "第 12 页",
    citeBadge: "PDF",
    citeBadgeClass: "is-pdf",
    sourceParas: [
      { t: "3.2 服务初始化", head: true },
      { t: "在启动服务之前，请确认服务器已完成基础环境配置，包括运行时、数据库与对象存储的连接信息。" },
      { t: "首先，按照文档中的步骤完成服务器环境配置，然后执行初始化脚本，最后启动服务并检查运行状态。", hit: true },
      { t: "初始化脚本只需执行一次；重复执行时会自动跳过已完成的步骤，不会影响已有数据。" }
    ]
  },
  {
    q: "谁能看到部门知识库里的文档？",
    lead: "只有该部门范围内的成员可以看到，权限在检索阶段就会生效。",
    quote: "知识库可见范围分为公开、部门与私有三种。部门知识库仅对本部门成员开放，无权限的文档不会进入检索结果。",
    citeDoc: "团队协作手册.docx",
    citePage: "第 3 节",
    citeBadge: "W",
    citeBadgeClass: "is-word",
    sourceParas: [
      { t: "3. 知识库的可见范围", head: true },
      { t: "每个知识库在创建时需要选择可见范围：公开、部门或私有。创建后可以随时由管理员调整。" },
      { t: "知识库可见范围分为公开、部门与私有三种。部门知识库仅对本部门成员开放，无权限的文档不会进入检索结果。", hit: true },
      { t: "私有知识库仅创建者与管理员可见，适合存放尚未定稿的文档。" }
    ]
  },
  {
    q: "支持上传哪些格式的文档？",
    lead: "支持 PDF、Word、Excel、PPT 与 Markdown 等常用格式。",
    quote: "文档入库时会按格式分别解析，保留标题层级、页码与表格结构，解析完成后自动建立索引。",
    citeDoc: "产品设计规范.pdf",
    citePage: "第 2 章",
    citeBadge: "PDF",
    citeBadgeClass: "is-pdf",
    sourceParas: [
      { t: "2. 文档格式与解析", head: true },
      { t: "知识库面向团队常见的办公文档设计，上传时无需手动转换格式。" },
      { t: "文档入库时会按格式分别解析，保留标题层级、页码与表格结构，解析完成后自动建立索引。", hit: true },
      { t: "解析失败时会给出具体原因，修正后重新上传即可。" }
    ]
  }
];

const activeQA = ref(-1);
const sourceOpen = ref(false);
const typedQuestion = ref("");
const demoHint = ref(false);
const currentQA = computed(() => (activeQA.value >= 0 ? demoQAs[activeQA.value] : null));

/** 选择一个示例问题：展示回答，等待用户点击引用 */
function ask(i: number) {
  activeQA.value = i;
  sourceOpen.value = false;
  demoHint.value = false;
  typedQuestion.value = demoQAs[i].q;
}

/** 去掉标点后的文本，用于示例问题的宽松匹配 */
function norm(s: string) {
  return s.replace(/[？?，,。、\s]/g, "");
}

/** 输入与示例问题的相似度：包含关系，或共享任一二字片段（演示用的宽松匹配） */
function similar(a: string, b: string) {
  if (!a || !b) return false;
  if (a.includes(b) || b.includes(a)) return true;
  for (let i = 0; i < a.length - 1; i++) {
    if (b.includes(a.slice(i, i + 2))) return true;
  }
  return false;
}

/** 输入框提问：匹配到示例问题则演示，否则如实提示演示范围 */
function submitQuestion() {
  const t = norm(typedQuestion.value.trim());
  if (!t) return;
  const i = demoQAs.findIndex(d => similar(t, norm(d.q)));
  if (i >= 0) ask(i);
  else demoHint.value = true;
}

/** 打开引用：原文面板切换为对应文档，并滚动到高亮段落 */
const sourceBody = ref<HTMLElement>();
function openCitation() {
  sourceOpen.value = true;
  nextTick(() => {
    const box = sourceBody.value;
    const hit = box?.querySelector(".is-hit") as HTMLElement | null;
    if (box && hit) {
      const top = hit.offsetTop - box.clientHeight / 2 + hit.clientHeight;
      box.scrollTo?.({ top: Math.max(0, top), behavior: reduceMotion ? "auto" : "smooth" });
    }
  });
}

/* ===== 关于项目：与仓库一致的技术构成 ===== */
const modules = [
  { name: "Gateway", role: "统一入口、认证、限流与链路标识" },
  { name: "IAM", role: "登录注册、用户、角色、组织与权限计算" },
  { name: "Knowledge", role: "知识库、目录、文档与可见范围" },
  { name: "Ingest", role: "异步入库任务、状态机与可靠投递" },
  { name: "Chat", role: "会话管理、多知识库绑定与权限编排" },
  { name: "RAG", role: "Python 服务：解析、向量检索与流式生成" },
  { name: "Web", role: "Vue 3 管理端与 AI 对话界面" }
];
const infra = ["MySQL", "Redis", "RabbitMQ", "MinIO", "Chroma", "Nacos"];
</script>

<template>
  <main class="landing" :class="{ 'js-rv': jsReveal }">
    <!-- ===== 固定顶部导航 ===== -->
    <header class="land-nav">
      <button
        type="button"
        class="brand"
        :class="{ on: activeSection === 'top' }"
        :aria-current="activeSection === 'top' ? 'page' : undefined"
        @click="scrollToSection('top')"
      >
        <svg class="brand-mark" viewBox="0 0 32 32" aria-hidden="true">
          <rect width="32" height="32" rx="7" fill="#167553" />
          <path d="M9 8.5h9.2c2.6 0 4.8 2.1 4.8 4.8V24h-9.2a4.8 4.8 0 0 1-4.8-4.8V8.5Z" fill="#f3efe2" />
          <path d="M13 13.2h6M13 16.6h6M13 20h4" stroke="#167553" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        <span class="brand-name">DocBase</span>
      </button>
      <nav class="nav-links" aria-label="页面导航">
        <button
          v-for="s in [
            { id: 'features', label: '产品功能' },
            { id: 'retrieval', label: '智能问答' },
            { id: 'about', label: '关于项目' }
          ]"
          :key="s.id"
          type="button"
          :class="{ on: activeSection === s.id }"
          @click="scrollToSection(s.id)"
        >
          {{ s.label }}
        </button>
      </nav>
      <div class="nav-actions">
        <button v-if="!authed" type="button" class="nav-login" @click="openAuth('login')">登录</button>
        <button type="button" class="btn btn-primary btn-nav" @click="enterWorkspace">
          进入工作台
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 12 12 4M6.5 4H12v5.5" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round" /></svg>
        </button>
      </div>
    </header>

    <!-- ===== 第一章：首页（浅灰绿） ===== -->
    <section id="top" class="hero snap">
      <div class="land-wash wash-a" aria-hidden="true" />
      <div class="land-wash wash-b" aria-hidden="true" />
      <div class="hero-grid" aria-hidden="true" />
      <div class="document-atmosphere" aria-hidden="true">
        <div class="ambient-sheet sheet-left"><span class="sheet-format">PDF</span><i class="sheet-title" /><i /><i /><i class="sheet-short" /><span class="sheet-scan" /></div>
        <div class="ambient-sheet sheet-right"><span class="sheet-format">DOC</span><i class="sheet-title" /><i /><i /><i class="sheet-short" /><span class="sheet-scan" /></div>
        <svg class="filing-path" viewBox="0 0 1200 260" preserveAspectRatio="none"><path d="M200 140 C260 140 220 240 370 240 M1000 130 C940 130 980 240 830 240" /></svg>
      </div>

      <div class="hero-head">
        <h1 class="hero-lockup reveal" style="--d: .05s">
          <svg class="hero-mark" viewBox="0 0 32 32" aria-hidden="true">
            <rect width="32" height="32" rx="7" fill="#167553" />
            <path d="M9 8.5h9.2c2.6 0 4.8 2.1 4.8 4.8V24h-9.2a4.8 4.8 0 0 1-4.8-4.8V8.5Z" fill="#f3efe2" />
            <path d="M13 13.2h6M13 16.6h6M13 20h4" stroke="#167553" stroke-width="1.6" stroke-linecap="round" />
          </svg>
          DocBase
        </h1>
        <p class="hero-purpose reveal" style="--d: .18s">团队文档管理与知识检索</p>
        <div class="hero-cta reveal" style="--d: .3s">
          <button type="button" class="btn btn-primary btn-lg" @click="enterWorkspace">
            进入工作台
            <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 8h10M9 3.5 13.5 8 9 12.5" stroke="currentColor" stroke-width="1.7" fill="none" stroke-linecap="round" stroke-linejoin="round" /></svg>
          </button>
          <button type="button" class="btn btn-ghost btn-lg" @click="scrollToSection('features')">查看功能</button>
        </div>
      </div>

      <!-- 产品预览：文档管理窗口 + 问答面板叠层（静态示意） -->
      <div class="hero-stage reveal" style="--d: .42s">
        <div class="showcase is-exploring">
          <div class="doc-window">
            <div class="doc-window-bar">
              <span class="doc-window-brand">
                <svg viewBox="0 0 32 32" aria-hidden="true"><rect width="32" height="32" rx="7" fill="#167553" /><path d="M9 8.5h9.2c2.6 0 4.8 2.1 4.8 4.8V24h-9.2a4.8 4.8 0 0 1-4.8-4.8V8.5Z" fill="#f3efe2" /></svg>
                DocBase
              </span>
              <button type="button" class="doc-upload" @click="openProduct('/knowledge')">
                <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 11V3.5M4.8 6.6 8 3.4l3.2 3.2M3 13.5h10" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round" stroke-linejoin="round" /></svg>
                上传文档
              </button>
            </div>
            <div class="doc-window-body">
              <aside class="doc-side" aria-label="产品预览导航">
<button type="button" class="doc-side-item" :class="{ 'is-active': previewKey === 'home' }" :aria-pressed="previewKey === 'home'" @click="selectPreview('home')"><svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 6.5 8 2l5.5 4.5V13a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1V6.5Z" stroke="currentColor" stroke-width="1.3" fill="none" stroke-linejoin="round" /></svg>工作台</button>
<button type="button" class="doc-side-item" :class="{ 'is-active': previewKey === 'docs' }" :aria-pressed="previewKey === 'docs'" @click="selectPreview('docs')"><svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3 2.5h7L13 5.5v8a1 1 0 0 1-1 1H3.5a1 1 0 0 1-1-1v-10a1 1 0 0 1 1-1Z" stroke="currentColor" stroke-width="1.3" fill="none" stroke-linejoin="round" /><path d="M9.5 2.5v3h3.5" stroke="currentColor" stroke-width="1.3" fill="none" stroke-linejoin="round" /></svg>文档管理</button>
<button type="button" class="doc-side-item" :class="{ 'is-active': previewKey === 'search' }" :aria-pressed="previewKey === 'search'" @click="selectPreview('search')"><svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="7" cy="7" r="4.2" stroke="currentColor" stroke-width="1.3" fill="none" /><path d="m10.2 10.2 3 3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" /></svg>知识检索</button>
<button type="button" class="doc-side-item" :class="{ 'is-active': previewKey === 'chat' }" :aria-pressed="previewKey === 'chat'" @click="selectPreview('chat')"><svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.8 9.6 6 14 7.6 9.6 9.2 8 13.4 6.4 9.2 2 7.6 6.4 6 8 1.8Z" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linejoin="round" /></svg>智能问答</button>
<button type="button" class="doc-side-item" :class="{ 'is-active': previewKey === 'roles' }" :aria-pressed="previewKey === 'roles'" @click="selectPreview('roles')"><svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.8 13.2 4v4c0 3.2-2.2 5.3-5.2 6.4C5 13.3 2.8 11.2 2.8 8V4L8 1.8Z" stroke="currentColor" stroke-width="1.3" fill="none" stroke-linejoin="round" /><path d="m5.6 7.8 1.7 1.7 3-3.2" stroke="currentColor" stroke-width="1.3" fill="none" stroke-linecap="round" stroke-linejoin="round" /></svg>权限管理</button>
</aside>
              <div :key="previewKey" class="doc-main preview-change" aria-live="polite">
<template v-if="previewKey === 'docs'">
                <h2>团队知识库</h2>
                <div class="doc-toolbar">
                  <span class="doc-search">
                    <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="7" cy="7" r="4.2" stroke="currentColor" stroke-width="1.3" fill="none" /><path d="m10.2 10.2 3 3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" /></svg>
                    搜索文档、内容或关键词…
                  </span>
                  <span class="doc-filter">
                    全部类型
                    <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m4.5 6.5 3.5 3.5 3.5-3.5" stroke="currentColor" stroke-width="1.4" fill="none" stroke-linecap="round" stroke-linejoin="round" /></svg>
                  </span>
                </div>
                <div class="doc-table">
                  <div class="doc-row doc-row-head">
                    <span>文档名称</span><span>状态</span><span>更新时间</span>
                  </div>
                  <div v-for="doc in demoDocs" :key="doc.name" class="doc-row">
                    <span class="doc-name">
                      <i class="doc-badge" :class="doc.badgeClass">{{ doc.badge }}</i>
                      {{ doc.name }}
                    </span>
                    <span><i class="doc-status"><b />已索引</i></span>
                    <span class="doc-time">{{ doc.time }}</span>
                  </div>
                </div>
</template>
<template v-else>
  <div class="preview-heading"><h2>{{ previewItem.title }}</h2><span>示例预览</span></div>
  <template v-if="previewKey === 'home'">
    <p class="preview-intro">最近的文档与正在进行的工作</p>
    <div class="preview-stats"><div><strong>24</strong>团队文档</div><div><strong>3</strong>知识库</div><div><strong>2</strong>入库任务</div></div>
    <div class="preview-line" v-for="doc in demoDocs.slice(0, 2)" :key="doc.name"><span>{{ doc.name }}</span><span>最近更新</span></div>
  </template>
  <template v-else-if="previewKey === 'search'">
    <p class="preview-intro">在知识库内搜索文档与内容</p>
    <div class="preview-query">⌕ 项目部署</div>
    <div class="preview-result"><strong>项目部署指南.pdf</strong><p>环境配置、初始化脚本与服务启动流程。</p><span>产品知识库 · 第 12 页</span></div>
    <button type="button" class="preview-link" @click="ask(0); scrollToSection('retrieval')">体验智能问答与原文引用 →</button>
  </template>
  <template v-else-if="previewKey === 'chat'">
    <p class="preview-intro">AI 对话 · 已选择团队知识库</p>
    <div class="preview-query">如何完成项目部署？</div>
    <div class="preview-result"><strong>先准备环境，再初始化服务。</strong><p>按照部署指南配置数据库与对象存储，执行初始化脚本，再启动服务。</p></div>
    <button type="button" class="preview-link" @click="ask(0); openCitation(); scrollToSection('retrieval')">查看引用 · 项目部署指南，第 12 页 ↗</button>
  </template>
  <template v-else>
    <p class="preview-intro">角色管理 · 页面及操作权限由角色分配</p>
    <div class="preview-line"><strong>角色</strong><strong>权限范围</strong></div>
    <div class="preview-line"><span>管理员</span><span>系统管理与业务配置</span></div>
    <div class="preview-line"><span>普通成员</span><span>授权知识库与 AI 对话</span></div>
    <p class="preview-intro">实际可见内容以当前账号的授权为准。</p>
  </template>
</template>
<button type="button" class="preview-open" @click="openProduct(previewItem.path)">打开{{ previewKey === 'search' ? '知识库检索' : previewItem.title }} →</button>
              </div>
            </div>
          </div>

        </div>
        <p class="stage-caption">产品界面示意 · 示例数据 · 点击左侧菜单切换预览</p>
      </div>
    </section>

    <!-- ===== 第二章：产品功能（暖白） ===== -->
    <section id="features" class="snap section-block features">
      <div class="section-head rv">
        <h2 class="section-title">产品功能</h2>
        <p class="section-sub">归档、查找与共享，在同一个工作空间完成。</p>
      </div>
      <div class="feat-layout rv">
        <div class="feat-list" role="tablist" aria-label="产品功能">
          <button
            v-for="f in features"
            :key="f.key"
            type="button"
            role="tab"
            :aria-selected="activeFeature === f.key"
            class="feat-item"
            :class="{ on: activeFeature === f.key }"
            @click="activeFeature = f.key"
          >
            <span class="feat-item-title">{{ f.title }}</span>
            <span class="feat-item-desc">{{ f.desc }}</span>
          </button>
        </div>

        <div class="feat-panel">
          <!-- 文档归档 -->
          <div v-if="activeFeature === 'archive'" key="archive" class="fd fd-anim">
            <div class="fd-crumbs">团队知识库 <i>/</i> 产品设计</div>
            <div class="fd-cols">
              <ul class="fd-tree">
                <li><span>全部文档</span><b>24</b></li>
                <li class="on"><span>产品设计</span><b>8</b></li>
                <li><span>工程文档</span><b>9</b></li>
                <li><span>团队制度</span><b>7</b></li>
              </ul>
              <div class="fd-table">
                <div class="fd-row fd-row-head"><span>名称</span><span>状态</span><span>更新时间</span></div>
                <div class="fd-row on">
                  <span class="fd-name"><i class="doc-badge is-pdf">PDF</i>产品设计规范.pdf</span>
                  <span><i class="tag-ok"><b />已索引</i></span><span class="fd-time">10-24 14:32</span>
                </div>
                <div class="fd-row">
                  <span class="fd-name"><i class="doc-badge is-pdf">PDF</i>界面交互说明.pdf</span>
                  <span><i class="tag-ok"><b />已索引</i></span><span class="fd-time">10-23 09:41</span>
                </div>
                <div class="fd-row">
                  <span class="fd-name"><i class="doc-badge is-pdf">PDF</i>品牌视觉手册.pdf</span>
                  <span><i class="tag-ing"><b />索引中</i></span><span class="fd-time">10-27 11:05</span>
                </div>
                <div class="fd-row">
                  <span class="fd-name"><i class="doc-badge is-word">W</i>产品术语表.docx</span>
                  <span><i class="tag-ok"><b />已索引</i></span><span class="fd-time">10-19 16:20</span>
                </div>
              </div>
            </div>
            <p class="fd-note">上传后自动解析并建立索引，目录与版本在左侧直接维护。</p>
          </div>

          <!-- 知识检索 -->
          <div v-else-if="activeFeature === 'search'" key="search" class="fd fd-anim">
            <div class="fd-searchbar">
              <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="7" cy="7" r="4.2" stroke="currentColor" stroke-width="1.3" fill="none" /><path d="m10.2 10.2 3 3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" /></svg>
              <span class="fd-query">部署</span>
              <span class="fd-count">找到 12 条相关片段</span>
            </div>
            <div class="fd-result">
              <p class="fd-result-head"><i class="doc-badge is-pdf">PDF</i>项目部署指南.pdf <i class="fd-loc">第 12 页</i></p>
              <p class="fd-snippet">…完成服务器环境配置后执行初始化脚本，<mark>部署</mark>服务并检查运行状态，确认各进程正常后…</p>
            </div>
            <div class="fd-result">
              <p class="fd-result-head"><i class="doc-badge is-md">MD</i>接口说明.md <i class="fd-loc">第 3 节</i></p>
              <p class="fd-snippet">…服务<mark>部署</mark>完成后，通过统一网关暴露接口，内部服务之间不直接对外开放…</p>
            </div>
            <div class="fd-result">
              <p class="fd-result-head"><i class="doc-badge is-word">W</i>团队协作手册.docx <i class="fd-loc">第 5 节</i></p>
              <p class="fd-snippet">…测试环境的<mark>部署</mark>由值周同学负责，每周五下班前完成并同步到群公告…</p>
            </div>
            <p class="fd-note">按语义与关键词匹配，结果可直接定位到原文位置。</p>
          </div>

          <!-- 访问权限 -->
          <div v-else key="perm" class="fd fd-anim">
            <div class="fd-table fd-perm-table">
              <div class="fd-row fd-row-head"><span>文档</span><span>可见范围</span><span>可见成员</span></div>
              <div class="fd-row">
                <span class="fd-name"><i class="doc-badge is-pdf">PDF</i>产品设计规范.pdf</span>
                <span><i class="scope scope-pub">公开</i></span><span class="fd-time">全体成员</span>
              </div>
              <div class="fd-row">
                <span class="fd-name"><i class="doc-badge is-word">W</i>团队协作手册.docx</span>
                <span><i class="scope scope-dep">部门 · 产品组</i></span><span class="fd-time">产品组 12 人</span>
              </div>
              <div class="fd-row">
                <span class="fd-name"><i class="doc-badge is-pdf">PDF</i>项目部署指南.pdf</span>
                <span><i class="scope scope-dep">部门 · 工程组</i></span><span class="fd-time">工程组 9 人</span>
              </div>
              <div class="fd-row">
                <span class="fd-name"><i class="doc-badge is-md">MD</i>接口说明.md</span>
                <span><i class="scope scope-prv">私有</i></span><span class="fd-time">仅管理员</span>
              </div>
            </div>
            <p class="fd-note">可见范围在检索阶段同步生效，无权限的文档不会进入问答结果。</p>
          </div>
        </div>
      </div>
    </section>

    <!-- ===== 第三章：智能问答（浅绿，交互演示） ===== -->
    <section id="retrieval" class="snap section-block retrieval">
      <div class="section-head rv">
        <h2 class="section-title">智能问答</h2>
        <p class="section-sub">选个问题试试，再点击引用查看原文。</p>
      </div>

      <div class="qa-demo rv">
        <span class="demo-tag">交互演示 · 示例数据</span>
        <div class="qa-demo-grid">
          <!-- 问答面板 -->
          <div class="qdp qdp-qa">
            <div class="qdp-head">提问</div>
            <div class="q-chips" role="group" aria-label="示例问题">
              <button
                v-for="(item, i) in demoQAs"
                :key="item.q"
                type="button"
                class="q-chip"
                :class="{ on: i === activeQA }"
                @click="ask(i)"
              >
                {{ item.q }}
              </button>
            </div>
            <form class="q-input" @submit.prevent="submitQuestion">
              <input v-model="typedQuestion" type="text" placeholder="输入问题，或选择上方示例" aria-label="输入问题" />
              <button type="submit" class="q-send" aria-label="提问">
                <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 8h10M9 3.5 13.5 8 9 12.5" stroke="currentColor" stroke-width="1.7" fill="none" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </button>
            </form>
            <p v-if="demoHint" class="q-hint">演示环境只准备了上方三个示例问题，点击即可体验。</p>

            <div class="q-stage">
              <p v-if="!currentQA" class="q-empty">选择一个示例问题，查看回答与引用来源。</p>
              <div v-else :key="activeQA" class="q-answer">
                <p class="q-bubble">{{ currentQA.q }}</p>
                <p class="q-lead">{{ currentQA.lead }}</p>
                <blockquote class="q-quote">{{ currentQA.quote }}</blockquote>
                <button type="button" class="q-cite" @click="openCitation">
                  <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 1.8h5.5L12 4.3v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-10.4a1 1 0 0 1 1-1Z" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linejoin="round" /><path d="M9.5 1.8v2.6H12" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linejoin="round" /></svg>
                  {{ currentQA.citeDoc }} · {{ currentQA.citePage }}
                  <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4.5 11.5 11.5 4.5M7 4.5h4.5V9" stroke="currentColor" stroke-width="1.3" fill="none" stroke-linecap="round" stroke-linejoin="round" /></svg>
                </button>
              </div>
            </div>
          </div>

          <!-- 原文面板 -->
          <div class="qdp qdp-src">
            <div class="qdp-head">原文出处</div>
            <div v-if="!sourceOpen" class="src-empty">
              <svg viewBox="0 0 40 40" aria-hidden="true"><path d="M10 4h14l7 7v25a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linejoin="round" /><path d="M24 4v7h7" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linejoin="round" /><path d="M15 18h11M15 23h11M15 28h7" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" /></svg>
              <strong>从答案回到原文</strong>
              <p>点击回答中的引用，即可查看对应段落。</p>
              <div class="src-skeleton" aria-hidden="true"><i /><i /><i /></div>
            </div>
            <template v-else>
              <div class="src-doc-head">
                <i class="doc-badge" :class="currentQA!.citeBadgeClass">{{ currentQA!.citeBadge }}</i>
                <span class="src-doc-name">{{ currentQA!.citeDoc }}</span>
                <span class="src-doc-page">{{ currentQA!.citePage }}</span>
              </div>
              <div ref="sourceBody" class="src-body">
                <p
                  v-for="(p, i) in currentQA!.sourceParas"
                  :key="i"
                  :class="{ 'is-hit': p.hit, 'is-head': p.head }"
                >
                  {{ p.t }}
                </p>
              </div>
            </template>
          </div>
        </div>

        <details class="retrieval-more">
          <summary>查看检索链路：问题如何变成可追溯的回答</summary>
          <ol class="r-steps">
            <li><b>01</b><div><strong>问题改写</strong><span>结合会话上下文补全指代，让问题更完整。</span></div></li>
            <li><b>02</b><div><strong>权限内召回</strong><span>先计算当前用户可见的文档集合，只在其中做向量召回。</span></div></li>
            <li><b>03</b><div><strong>去重与排序</strong><span>MMR 排序保留既相关又不重复的片段。</span></div></li>
            <li><b>04</b><div><strong>生成与溯源</strong><span>流式生成回答，并附上可回跳原文的引用来源。</span></div></li>
          </ol>
        </details>
      </div>
    </section>

    <!-- ===== 第四章：关于项目（深墨绿收尾） ===== -->
    <section id="about" class="snap section-block about">
      <div class="about-grid rv">
        <div class="about-copy">
          <p class="about-label">关于项目</p>
          <h2 class="about-title">让每一份文档，<br />成为可用的知识。</h2>
          <p class="about-desc">
            DocBase 是面向企业内部知识沉淀、文档共享与智能检索场景的开源平台。
            系统以 Java 微服务承载业务与权限边界，以 Python 服务完成文档解析、
            向量检索与流式生成，让分散的文档成为可检索、可回答的团队知识。
          </p>
          <div class="about-actions">
            <button type="button" class="btn btn-light btn-lg" @click="enterWorkspace">
              进入工作台
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 8h10M9 3.5 13.5 8 9 12.5" stroke="currentColor" stroke-width="1.7" fill="none" stroke-linecap="round" stroke-linejoin="round" /></svg>
            </button>
            <a class="btn btn-dark-ghost btn-lg" href="https://github.com/YDL1111/DocBase-Microservices" target="_blank" rel="noopener noreferrer">
              源码仓库
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4.5 11.5 11.5 4.5M7 4.5h4.5V9" stroke="currentColor" stroke-width="1.4" fill="none" stroke-linecap="round" stroke-linejoin="round" /></svg>
            </a>
            <a class="btn btn-dark-ghost btn-lg" href="https://github.com/YDL1111/DocBase-Microservices/tree/main/docs" target="_blank" rel="noopener noreferrer">
              项目文档
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4.5 11.5 11.5 4.5M7 4.5h4.5V9" stroke="currentColor" stroke-width="1.4" fill="none" stroke-linecap="round" stroke-linejoin="round" /></svg>
            </a>
          </div>
        </div>

        <div class="about-modules">
          <p class="about-modules-title">系统构成</p>
          <ul class="module-list">
            <li v-for="m in modules" :key="m.name">
              <span class="module-name">{{ m.name }}</span>
              <span class="module-role">{{ m.role }}</span>
            </li>
          </ul>
          <p class="infra-line">
            <span class="infra-label">基础设施</span>
            <span v-for="x in infra" :key="x" class="infra-chip">{{ x }}</span>
          </p>
        </div>
      </div>
      <footer class="land-footer">Copyright © 2024-2026 DocBase All Rights Reserved.</footer>
    </section>

    <AuthDialog v-model="authVisible" :initial-mode="authMode" @success="onAuthSuccess" />
  </main>
</template>

<style>
/* 滚动吸附只作用于首页（离开时移除类名）：宽松吸附，不拦截滚动 */
html.landing-snap {
  scroll-snap-type: y proximity;
  /* 固定导航高度 + 余量，保证吸附/导航定位时标题不被遮挡 */
  scroll-padding-top: 76px;
}
@media (max-width: 900px), (max-height: 620px), (prefers-reduced-motion: reduce) {
  html.landing-snap { scroll-snap-type: none; }
}
</style>

<style lang="scss" scoped>
/* ===== 品牌首页：暖白 × 浅灰绿 × 深青绿，安静精致的知识产品气质 ===== */
.landing {
  --land-teal: #167553;
  --land-teal-deep: #105c41;
  --land-teal-soft: #dff3e7;
  --land-ink: #21241d;
  --land-text: #454a40;
  --land-muted: #6d7263;
  --land-line: #d9e1da;
  --land-serif: "Noto Serif SC", "Source Han Serif SC", "Songti SC", "STSong", "SimSun", serif;
  --land-sans: "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;

  position: relative;
  min-width: 320px;
  min-height: 100vh;
  overflow-x: hidden;
  color: var(--land-ink);
  background: #e7f3e9;
  overflow: clip;
  font-family: var(--land-sans);
}
.landing button, .landing input { font-family: inherit; }
.landing :focus-visible { outline: 2px solid var(--land-teal); outline-offset: 4px; }

/* ===== 固定顶部导航 ===== */
.land-nav {
  position: fixed; top: 0; left: 0; right: 0; z-index: 50;
  display: flex; align-items: center; gap: 32px;
  height: 60px; padding: 0 32px;
  background: rgba(250, 251, 248, 0.92);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(33, 36, 29, 0.08);
}
.brand { display: inline-flex; align-items: center; gap: 9px; padding: 0; background: none; border: 0; cursor: pointer; }
.brand-mark { width: 27px; height: 27px; border-radius: 7px; }
.brand-name { color: var(--land-ink); font-size: 19px; font-weight: 650; letter-spacing: -0.7px; transition: color .18s ease; }
.brand.on .brand-name { color: var(--land-teal); }

.nav-links { display: flex; align-items: center; gap: 6px; margin-left: 18px; }
.nav-links button {
  position: relative; padding: 6px 10px;
  color: var(--land-text); font-size: 13px;
  background: none; border: 0; border-radius: 7px; cursor: pointer;
  transition: color .18s ease, background-color .18s ease;
}
.nav-links button:hover { color: var(--land-teal); }
.nav-links button.on { color: var(--land-teal); font-weight: 650; background: rgba(23, 94, 76, 0.09); }
.nav-actions { display: flex; align-items: center; gap: 18px; margin-left: auto; }
.nav-login { padding: 4px 2px; color: var(--land-text); font-size: 13px; background: none; border: 0; cursor: pointer; transition: color .18s ease; }
.nav-login:hover { color: var(--land-teal); }

/* ===== 按钮 ===== */
.btn { display: inline-flex; align-items: center; justify-content: center; gap: 7px; border-radius: 8px; font-weight: 600; cursor: pointer; text-decoration: none; transition: transform .18s ease, box-shadow .18s ease, background-color .18s ease, border-color .18s ease; }
.btn svg { width: 14px; height: 14px; }
.btn-primary { color: #fafbf8; background: var(--land-teal); border: 1px solid var(--land-teal); box-shadow: 0 2px 4px rgba(16, 72, 58, 0.10); }
.btn-primary:hover { background: var(--land-teal-deep); border-color: var(--land-teal-deep); transform: translateY(-1px); }
.btn-ghost { color: var(--land-ink); background: rgba(255, 255, 255, 0.66); border: 1px solid var(--land-line); }
.btn-ghost:hover { border-color: #c4c1ad; transform: translateY(-1px); }
.btn-light { color: var(--land-teal-deep); background: #f2f0e4; border: 1px solid #f2f0e4; }
.btn-light:hover { background: #fff; transform: translateY(-1px); }
.btn-dark-ghost { color: #dde3d4; background: transparent; border: 1px solid rgba(240, 242, 230, 0.3); }
.btn-dark-ghost:hover { border-color: rgba(240, 242, 230, 0.6); color: #fff; transform: translateY(-1px); }
.btn-nav { height: 34px; padding: 0 14px; font-size: 13px; border-radius: 7px; }
.btn-lg { height: 44px; padding: 0 22px; font-size: 14px; }

/* ===== 章节骨架 ===== */
.snap { scroll-snap-align: start; }
.section-block { position: relative; min-height: 100svh; display: flex; flex-direction: column; justify-content: center; padding: clamp(96px, 11vh, 132px) clamp(24px, 4vw, 64px) 72px; }
.section-block > .section-head, .section-block > .feat-layout, .section-block > .qa-demo, .section-block > .about-grid, .section-block > .land-footer { width: 100%; max-width: 1320px; }
.section-head { max-width: 1120px; margin: 0 auto 36px; }
.section-title { margin: 0; font-family: var(--land-serif); font-size: clamp(28px, 3.3vw, 44px); font-weight: 700; letter-spacing: 0.02em; }
.section-sub { margin: 12px 0 0; color: #626c60; font-size: 14px; line-height: 1.8; }

/* ===== 第一章：首页（浅灰绿） ===== */
.hero {
  position: relative;
  min-height: 100svh;
  box-sizing: border-box;
  padding-top: 60px; /* 固定导航高度 */
  background: #e7f3e9;
  overflow: clip;
}
.land-wash { position: absolute; pointer-events: none; border-radius: 50%; filter: blur(55px); animation: ambient-drift 22s ease-in-out infinite alternate; }
.wash-a { top: -160px; left: -180px; width: 620px; height: 540px; background: radial-gradient(circle, rgba(123, 158, 133, 0.48), transparent 68%); }
.wash-b { top: 420px; right: -260px; width: 700px; height: 620px; background: radial-gradient(circle, rgba(165, 180, 133, 0.42), transparent 66%); }
/* A quiet drafting grid and slow daylight movement behind the product. */
.wash-b { animation-duration: 28s; animation-delay: -12s; animation-direction: alternate-reverse; }
.hero-grid { position: absolute; inset: 60px 0 0; pointer-events: none; background-image: linear-gradient(rgba(64, 96, 76, .075) 1px, transparent 1px), linear-gradient(90deg, rgba(64, 96, 76, .075) 1px, transparent 1px); background-size: 64px 64px; mask-image: radial-gradient(ellipse at 50% 34%, transparent 18%, #000 75%); opacity: .65; }
@keyframes ambient-drift { from { transform: translate3d(0, 0, 0) scale(1); opacity: .75; } to { transform: translate3d(100px, 45px, 0) scale(1.16); opacity: 1; } }
@media (max-width: 640px) { .hero-grid { background-size: 48px 48px; opacity: .4; } .land-wash { animation: none; } }
@media (prefers-reduced-motion: reduce) { .land-wash { animation: none; } }
.document-atmosphere { position: absolute; inset: 65px 0 auto; height: 230px; pointer-events: none; }
.ambient-sheet { position: absolute; top: 30px; width: 104px; height: 132px; padding: 17px 15px; border: 1px solid rgba(48, 102, 77, .22); border-radius: 7px 20px 7px 7px; background: rgba(250, 249, 239, .66); box-shadow: 6px 7px 0 -2px #e4e9db, 6px 7px 0 -1px rgba(48, 102, 77, .16); animation: sheet-float 6s ease-in-out infinite alternate; overflow: hidden; }
.sheet-left { left: max(7%, calc(50% - 450px)); --sheet-angle: -12deg; }
.sheet-right { right: max(7%, calc(50% - 450px)); --sheet-angle: 12deg; animation-delay: -3s; }
.sheet-format { display: block; color: #44745c; font-size: 12px; font-weight: 700; letter-spacing: .1em; margin-bottom: 16px; }
.ambient-sheet i { display: block; height: 3px; margin-top: 7px; background: rgba(63, 106, 79, .2); border-radius: 2px; }
.ambient-sheet .sheet-title { width: 55%; height: 5px; background: rgba(63, 106, 79, .34); }
.ambient-sheet .sheet-short { width: 70%; }
.sheet-scan { position: absolute; left: 8px; right: 8px; top: 18px; height: 18px; background: linear-gradient(transparent, rgba(70, 143, 105, .2)); border-bottom: 1px solid rgba(44, 117, 80, .45); animation: sheet-scan 4s ease-in-out infinite; }
.sheet-right .sheet-scan { animation-delay: -2s; }
.filing-path { position: absolute; inset: 0; width: 100%; height: 100%; fill: none; stroke: rgba(52, 113, 80, .26); stroke-width: 1.5; stroke-dasharray: 5 9; animation: filing-flow 3s linear infinite; }
@keyframes sheet-float { from { transform: translateY(0) rotate(var(--sheet-angle)); } to { transform: translateY(-12px) rotate(calc(var(--sheet-angle) + 3deg)); } }
@keyframes sheet-scan { 0%, 100% { transform: translateY(0); opacity: 0; } 15%, 75% { opacity: 1; } 85% { transform: translateY(90px); opacity: 0; } }
@keyframes filing-flow { to { stroke-dashoffset: -28; } }
@media (max-width: 760px) { .document-atmosphere { display: none; } }
@media (prefers-reduced-motion: reduce) { .ambient-sheet, .sheet-scan, .filing-path { animation: none; } .sheet-scan { display: none; } .ambient-sheet { transform: rotate(var(--sheet-angle)); } }
.land-plant { position: absolute; left: -34px; top: 40%; width: 220px; opacity: .45; filter: blur(1px); pointer-events: none; }
.land-stones { position: absolute; right: -60px; top: 46%; width: 300px; height: 220px; pointer-events: none; opacity: .42; }
.stone { position: absolute; display: block; border-radius: 10px; background: linear-gradient(160deg, #e6e5d8, #cbccb9); box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.55); }
.stone-a { right: 130px; bottom: 90px; width: 110px; height: 130px; transform: rotate(-4deg); }
.stone-b { right: 40px; bottom: 30px; width: 150px; height: 100px; background: linear-gradient(160deg, #dfddcd, #c2c2af); }

.hero-head { position: relative; z-index: 2; max-width: 920px; margin: 0 auto; padding: clamp(32px, 5vh, 56px) 32px 0; text-align: center; }
.hero-lockup {
  display: inline-flex; align-items: center; gap: 18px;
  margin: 0;
  font-family: var(--land-sans);
  font-size: clamp(46px, 5.6vw, 76px);
  font-weight: 650; line-height: 1.12; letter-spacing: -0.055em;
}
.hero-mark { flex: none; width: 0.69em; height: 0.69em; border-radius: 0.15em; }
.hero-purpose { margin: 16px 0 0; color: #536155; font-size: 17px; letter-spacing: 0.06em; }
.hero-cta { display: flex; justify-content: center; gap: 12px; margin-top: 24px; }

/* 产品预览 */
.hero-stage { position: relative; z-index: 2; width: 100%; max-width: 1560px; box-sizing: border-box; margin: 0 auto; padding: 36px clamp(24px, 4vw, 64px) 40px; }
.showcase { position: relative; /* 菜单可交互，数据保持示例 */ }
.stage-caption { margin: 18px 0 0; color: var(--land-muted); font-size: 11px; letter-spacing: 0.1em; text-align: center; }

.doc-window {
  width: min(880px, 88%);
  overflow: hidden;
  background: #fbfaf5;
  border: 1px solid rgba(33, 36, 29, 0.07);
  border-radius: 14px;
  box-shadow: 0 1px 2px rgba(33, 36, 29, 0.04), 0 30px 60px rgba(38, 46, 38, 0.13);
}
.doc-window-bar { display: flex; align-items: center; justify-content: space-between; padding: 13px 18px; border-bottom: 1px solid var(--land-line); }
.doc-window-brand { display: inline-flex; align-items: center; gap: 7px; color: var(--land-ink); font-size: 13px; font-weight: 700; }
.doc-window-brand svg { width: 18px; height: 18px; border-radius: 5px; }
.doc-upload { display: inline-flex; align-items: center; gap: 5px; padding: 6px 11px; color: #fafbf8; font-size: 11px; font-weight: 600; background: var(--land-teal); border-radius: 6px; }
.doc-upload svg { width: 12px; height: 12px; }

.doc-window-body { display: flex; height: 360px; }
.doc-side { flex: none; width: 168px; padding: 16px 12px; border-right: 1px solid var(--land-line); background: #eef2ef; }
.doc-side-item { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; padding: 8px 10px; color: var(--land-muted); font-size: 12px; border-radius: 7px; }
.doc-side-item svg { width: 14px; height: 14px; }
.doc-side-item.is-active { color: var(--land-teal); font-weight: 650; background: var(--land-teal-soft); }

.doc-main { flex: 1; min-width: 0; min-height: 0; overflow: auto; scrollbar-gutter: stable; padding: 20px 22px 18px; text-align: left; }
.doc-main h2 { margin: 0 0 14px; font-size: 17px; font-weight: 700; letter-spacing: -0.2px; }
.doc-toolbar { display: flex; gap: 10px; margin-bottom: 12px; }
.doc-search { flex: 1; display: inline-flex; align-items: center; gap: 7px; min-width: 0; padding: 8px 12px; color: var(--land-muted); font-size: 11px; background: #fff; border: 1px solid var(--land-line); border-radius: 7px; }
.doc-search svg { flex: none; width: 12px; height: 12px; }
.doc-filter { display: inline-flex; align-items: center; gap: 5px; padding: 8px 11px; color: var(--land-text); font-size: 11px; background: #fff; border: 1px solid var(--land-line); border-radius: 7px; }
.doc-filter svg { width: 11px; height: 11px; }

.doc-row { display: grid; grid-template-columns: minmax(0, 1fr) 92px 118px; align-items: center; gap: 10px; padding: 11px 4px; font-size: 11.5px; border-bottom: 1px solid #e6ebe7; }
.doc-row:last-child { border-bottom: 0; }
.doc-row-head { padding: 4px 0 8px; color: var(--land-muted); font-size: 10.5px; border-bottom: 1px solid var(--land-line); }
.doc-name { display: inline-flex; align-items: center; gap: 9px; min-width: 0; color: var(--land-ink); font-weight: 550; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.doc-badge { flex: none; min-width: 26px; padding: 3px 4px; color: #fff; font-size: 8.5px; font-weight: 700; font-style: normal; text-align: center; border-radius: 4px; }
.doc-badge.is-pdf { background: #c0503f; }
.doc-badge.is-word { background: #3f6a9e; }
.doc-badge.is-md { background: #6b7264; }
.doc-status { display: inline-flex; align-items: center; gap: 5px; padding: 3px 9px; color: #2c6a4e; font-size: 10.5px; font-style: normal; background: #e4eee4; border-radius: 20px; }
.doc-status b { width: 5px; height: 5px; background: #2c7a5b; border-radius: 50%; }
.doc-time { color: var(--land-muted); font-size: 10.5px; }

/* 知识问答卡片（前景，阴影重于背景窗口） */
.qa-card {
  position: absolute; right: 0; top: 56px; z-index: 3;
  width: min(348px, 40%);
  padding: 16px 18px 14px;
  text-align: left;
  background: #fdfcf8;
  border: 1px solid rgba(33, 36, 29, 0.09);
  border-radius: 14px;
  box-shadow: 0 3px 6px rgba(33, 36, 29, 0.06), 0 36px 70px rgba(38, 46, 38, 0.22);
}
.qa-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 13px; }
.qa-title { display: inline-flex; align-items: center; gap: 6px; color: var(--land-ink); font-size: 12.5px; font-weight: 700; }
.qa-title svg { width: 13px; height: 13px; color: var(--land-teal); }
.qa-question { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 12px; }
.qa-avatar { flex: none; display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; color: var(--land-muted); background: #efeee0; border-radius: 50%; }
.qa-avatar svg { width: 13px; height: 13px; }
.qa-question p { margin: 0; padding: 7px 11px; color: var(--land-ink); font-size: 12px; background: #f2f1e5; border-radius: 9px; }
.qa-answer { display: flex; align-items: flex-start; gap: 8px; }
.qa-spark { flex: none; display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; color: var(--land-teal); background: var(--land-teal-soft); border-radius: 50%; }
.qa-spark svg { width: 12px; height: 12px; }
.qa-lead { margin: 2px 0 9px; color: var(--land-ink); font-size: 12px; font-weight: 600; line-height: 1.6; }
.qa-answer blockquote { margin: 0 0 10px; padding: 9px 11px; color: var(--land-text); font-size: 11px; line-height: 1.75; background: #edefe3; border-left: 2px solid #b6c4ac; border-radius: 0 8px 8px 0; }
.qa-cite { display: inline-flex; align-items: center; gap: 5px; padding: 5px 9px; color: var(--land-teal); font-size: 10.5px; font-weight: 600; background: #ebf0e6; border: 1px solid #d9e2d3; border-radius: 6px; }
.qa-cite svg { width: 11px; height: 11px; }
.qa-foot { margin-top: 12px; padding-top: 10px; border-top: 1px solid #e6ebe7; }
.qa-note { display: inline-flex; align-items: center; gap: 5px; color: var(--land-muted); font-size: 10.5px; }
.qa-note svg { width: 11px; height: 11px; }

.stage-badge {
  position: absolute; left: -14px; bottom: 34px; z-index: 4;
  display: inline-flex; align-items: center; gap: 7px;
  padding: 9px 14px;
  color: var(--land-ink); font-size: 12px; font-weight: 600;
  background: #fdfcf8;
  border: 1px solid rgba(33, 36, 29, 0.07);
  border-radius: 10px;
  box-shadow: 0 12px 30px rgba(38, 46, 38, 0.14);
}
.stage-badge svg { width: 15px; height: 15px; }

/* ===== 第二章：产品功能（暖白） ===== */
.features { background: #f5f3ea; }
.feat-layout { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 40px; max-width: 1120px; margin: 0 auto; align-items: start; }
.feat-list { display: flex; flex-direction: column; gap: 4px; }
.feat-item {
  position: relative; display: block; width: 100%;
  padding: 15px 16px 15px 20px; text-align: left;
  background: none; border: 0; border-radius: 10px; cursor: pointer;
  transition: background-color .18s ease;
}
.feat-item::before {
  content: ""; position: absolute; left: 0; top: 16px; bottom: 16px;
  width: 3px; border-radius: 2px; background: transparent;
  transition: background-color .18s ease;
}
.feat-item:hover { background: rgba(23, 94, 76, 0.05); }
.feat-item.on { background: #fff; box-shadow: 0 1px 2px rgba(33, 36, 29, 0.04), 0 10px 26px rgba(38, 46, 38, 0.08); }
.feat-item.on::before { background: var(--land-teal); }
.feat-item-title { display: block; color: var(--land-ink); font-size: 15px; font-weight: 700; }
.feat-item.on .feat-item-title { color: var(--land-teal); }
.feat-item-desc { display: block; margin-top: 6px; color: var(--land-muted); font-size: 12.5px; line-height: 1.7; }

.feat-panel {
  min-height: 366px;
  padding: 26px 28px 20px;
  background: #fbfaf4;
  border: 1px solid var(--land-line);
  border-radius: 14px;
}
.fd-anim { animation: fd-swap .3s ease; }
@keyframes fd-swap { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: translateY(0); } }

.fd-crumbs { margin-bottom: 14px; color: var(--land-muted); font-size: 12px; }
.fd-crumbs i { margin: 0 8px; color: #c4c1ad; font-style: normal; }
.fd-cols { display: grid; grid-template-columns: 132px minmax(0, 1fr); gap: 18px; }
.fd-tree { margin: 0; padding: 0; list-style: none; }
.fd-tree li { display: flex; align-items: center; justify-content: space-between; margin-bottom: 2px; padding: 8px 10px; color: var(--land-text); font-size: 12px; border-radius: 7px; }
.fd-tree li.on { color: var(--land-teal); font-weight: 650; background: var(--land-teal-soft); }
.fd-tree b { color: var(--land-muted); font-size: 10.5px; font-weight: 500; }
.fd-tree li.on b { color: var(--land-teal); }

.fd-row { display: grid; grid-template-columns: minmax(0, 1fr) 96px 92px; align-items: center; gap: 10px; padding: 10px 6px; font-size: 11.5px; border-bottom: 1px solid #e6ebe7; }
.fd-row:last-child { border-bottom: 0; }
.fd-row.on { background: #eef2ef; border-radius: 7px; }
.fd-row-head { padding: 2px 6px 8px; color: var(--land-muted); font-size: 10.5px; border-bottom: 1px solid var(--land-line); }
.fd-name { display: inline-flex; align-items: center; gap: 8px; min-width: 0; color: var(--land-ink); font-weight: 550; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.fd-time { color: var(--land-muted); font-size: 10.5px; }
.tag-ok, .tag-ing { display: inline-flex; align-items: center; gap: 5px; padding: 3px 9px; font-size: 10.5px; font-style: normal; border-radius: 20px; }
.tag-ok { color: #2c6a4e; background: #e4eee4; }
.tag-ok b { width: 5px; height: 5px; background: #2c7a5b; border-radius: 50%; }
.tag-ing { color: #8a6414; background: #f4ead0; }
.tag-ing b { width: 5px; height: 5px; background: #b07d2a; border-radius: 50%; }

.fd-searchbar { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; padding: 11px 14px; background: #fff; border: 1px solid var(--land-teal); border-radius: 9px; box-shadow: 0 0 0 3px rgba(23, 94, 76, 0.1); }
.fd-searchbar svg { width: 13px; height: 13px; color: var(--land-muted); }
.fd-query { color: var(--land-ink); font-size: 13px; font-weight: 600; }
.fd-count { margin-left: auto; color: var(--land-muted); font-size: 11px; }
.fd-result { padding: 12px 4px; border-bottom: 1px solid #e6ebe7; }
.fd-result:last-of-type { border-bottom: 0; }
.fd-result-head { display: flex; align-items: center; gap: 8px; margin: 0 0 6px; color: var(--land-ink); font-size: 12px; font-weight: 650; }
.fd-loc { color: var(--land-muted); font-size: 10.5px; font-style: normal; font-weight: 400; }
.fd-snippet { margin: 0; color: var(--land-text); font-size: 12px; line-height: 1.8; }
.fd-snippet mark { padding: 1px 3px; color: var(--land-teal-deep); background: #d9e8d4; border-radius: 3px; }

.fd-perm-table .fd-row { grid-template-columns: minmax(0, 1fr) 118px 96px; }
.scope { display: inline-flex; align-items: center; padding: 3px 9px; font-size: 10.5px; font-style: normal; border-radius: 20px; }
.scope-pub { color: #2c6a4e; background: #e4eee4; }
.scope-dep { color: #8a6414; background: #f4ead0; }
.scope-prv { color: #565040; background: #e9e6d6; }

.fd-note { margin: 16px 0 0; padding-top: 14px; color: var(--land-muted); font-size: 11.5px; border-top: 1px dashed var(--land-line); }

/* ===== 第三章：智能问答（浅绿） ===== */
.retrieval { background: #def0e3; }
.retrieval .section-sub { color: #5c6350; }
.qa-demo { position: relative; max-width: 1120px; margin: 0 auto; }
.demo-tag {
  position: absolute; top: -14px; left: 24px; z-index: 2;
  padding: 4px 12px;
  color: #f4f6ec; font-size: 11px; font-weight: 600; letter-spacing: 0.06em;
  background: var(--land-teal); border-radius: 20px;
}
.qa-demo-grid { display: grid; grid-template-columns: minmax(0, 7fr) minmax(0, 5fr); gap: 22px; }
.qdp {
  background: #fbfaf4;
  border: 1px solid rgba(33, 36, 29, 0.09);
  border-radius: 14px;
  box-shadow: 0 1px 2px rgba(33, 36, 29, 0.04), 0 24px 50px rgba(38, 46, 38, 0.1);
}
.qdp-head { padding: 14px 20px 12px; color: var(--land-muted); font-size: 11px; font-weight: 700; letter-spacing: 0.18em; border-bottom: 1px solid var(--land-line); }
.qdp-qa { padding-bottom: 20px; }
.q-chips { display: flex; flex-wrap: wrap; gap: 8px; padding: 16px 20px 0; }
.q-chip {
  padding: 7px 13px;
  color: var(--land-text); font-size: 12px;
  background: #fff; border: 1px solid var(--land-line); border-radius: 20px; cursor: pointer;
  transition: border-color .18s ease, color .18s ease, background-color .18s ease;
}
.q-chip:hover { border-color: var(--land-teal); color: var(--land-teal); }
.q-chip.on { color: #f4f6ec; background: var(--land-teal); border-color: var(--land-teal); }
.q-input { display: flex; gap: 8px; margin: 14px 20px 0; }
.q-input input {
  flex: 1; min-width: 0; height: 40px; padding: 0 14px;
  color: var(--land-ink); font-size: 13px;
  background: #fff; border: 1px solid var(--land-line); border-radius: 9px; outline: none;
  transition: border-color .18s ease, box-shadow .18s ease;
}
.q-input input:focus { border-color: var(--land-teal); box-shadow: 0 0 0 3px rgba(23, 94, 76, 0.12); }
.q-send {
  flex: none; display: inline-flex; align-items: center; justify-content: center;
  width: 40px; height: 40px;
  color: #f4f6ec; background: var(--land-teal);
  border: 0; border-radius: 9px; cursor: pointer;
  transition: background-color .18s ease;
}
.q-send:hover { background: var(--land-teal-deep); }
.q-send svg { width: 15px; height: 15px; }
.q-hint { margin: 10px 20px 0; color: #8a6414; font-size: 11.5px; }

.q-stage { min-height: 252px; padding: 16px 20px 0; }
.q-empty { margin: 40px 0 0; color: var(--land-muted); font-size: 12.5px; text-align: center; }
.q-answer > * { animation: qa-rise .45s cubic-bezier(.22, .61, .36, 1) backwards; }
.q-answer > *:nth-child(2) { animation-delay: .1s; }
.q-answer > *:nth-child(3) { animation-delay: .2s; }
.q-answer > *:nth-child(4) { animation-delay: .3s; }
@keyframes qa-rise { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
.q-bubble { display: inline-block; margin: 0 0 12px; padding: 8px 13px; color: var(--land-ink); font-size: 12.5px; background: #eef0e2; border-radius: 10px; }
.q-lead { margin: 0 0 10px; color: var(--land-ink); font-size: 13.5px; font-weight: 650; line-height: 1.65; }
.q-quote { margin: 0 0 12px; padding: 10px 13px; color: var(--land-text); font-size: 12px; line-height: 1.8; background: #edefe3; border-left: 2px solid #a9bfa0; border-radius: 0 9px 9px 0; }
.q-cite {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 7px 12px;
  color: var(--land-teal); font-size: 12px; font-weight: 650;
  background: #ebf0e6; border: 1px solid #cfdcc8; border-radius: 8px; cursor: pointer;
  transition: background-color .18s ease, border-color .18s ease;
}
.q-cite:hover { background: #e0e9da; border-color: #b9cbb0; }
.q-cite svg { width: 12px; height: 12px; }

.qdp-src { display: flex; flex-direction: column; min-height: 380px; min-width: 0; }
.src-empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px; padding: 30px; color: #a2a794; text-align: center; }
.src-empty > svg { flex: none; width: 42px; height: 42px; color: #73917b; }
.src-empty strong { color: #536155; font-size: 14px; font-weight: 600; }
.src-skeleton { display: grid; gap: 8px; width: min(180px, 80%); margin-top: 6px; }
.src-skeleton i { height: 4px; border-radius: 4px; background: #e3e8dc; }
.src-skeleton i:nth-child(2) { width: 86%; }
.src-skeleton i:last-child { width: 62%; }
.src-empty p { margin: 0; color: var(--land-muted); font-size: 12px; line-height: 1.9; }
.src-doc-head { display: flex; align-items: center; gap: 9px; padding: 14px 20px; border-bottom: 1px solid var(--land-line); }
.src-doc-name { color: var(--land-ink); font-size: 12.5px; font-weight: 700; }
.src-doc-page { margin-left: auto; color: var(--land-muted); font-size: 11px; }
.src-body { max-height: 300px; overflow-y: auto; padding: 16px 20px 20px; }
.src-body p { margin: 0 0 12px; padding: 8px 10px; color: var(--land-text); font-size: 12px; line-height: 1.85; border-radius: 7px; }
.src-body p.is-head { padding: 0 10px; color: var(--land-ink); font-size: 13px; font-weight: 700; }
.src-body p.is-hit {
  color: var(--land-teal-deep);
  background: #dcead2;
  box-shadow: inset 2px 0 0 var(--land-teal);
  animation: hit-in .5s ease;
}
@keyframes hit-in { from { background: transparent; box-shadow: inset 2px 0 0 transparent; } }

.retrieval-more { max-width: 1120px; margin: 22px auto 0; }
.retrieval-more summary {
  display: inline-flex; align-items: center;
  padding: 8px 0;
  color: var(--land-text); font-size: 12.5px; font-weight: 600; cursor: pointer;
}
.retrieval-more summary:hover { color: var(--land-teal); }
.r-steps { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin: 14px 0 0; padding: 0; list-style: none; }
.r-steps li { display: flex; align-items: flex-start; gap: 10px; padding: 14px 15px; background: rgba(255, 255, 255, 0.55); border: 1px solid rgba(33, 36, 29, 0.08); border-radius: 10px; }
.r-steps b { color: var(--land-teal); font-size: 11px; font-weight: 700; letter-spacing: 0.05em; }
.r-steps strong { display: block; color: var(--land-ink); font-size: 12.5px; font-weight: 650; }
.r-steps span { color: var(--land-muted); font-size: 11px; line-height: 1.7; }

/* ===== 第四章：关于项目（深墨绿） ===== */
.about { background: #173a2f; color: #e7f3e9; padding-bottom: 0; }
.about-grid { display: grid; grid-template-columns: minmax(0, 5fr) minmax(0, 6fr); gap: 56px; max-width: 1120px; margin: 0 auto; align-items: start; }
.about-label { margin: 0 0 14px; color: #93a393; font-size: 12px; font-weight: 700; letter-spacing: 0.22em; }
.about-title { margin: 0; font-family: var(--land-serif); font-size: clamp(26px, 3vw, 36px); font-weight: 700; line-height: 1.5; letter-spacing: 0.02em; color: #f0f2e6; }
.about-desc { margin: 22px 0 0; color: #b3bda9; font-size: 13.5px; line-height: 2; }
.about-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 34px; }

.about-modules { padding: 26px 28px; background: rgba(240, 242, 230, 0.05); border: 1px solid rgba(240, 242, 230, 0.14); border-radius: 14px; }
.about-modules-title { margin: 0 0 14px; color: #93a393; font-size: 11px; font-weight: 700; letter-spacing: 0.2em; }
.module-list { margin: 0; padding: 0; list-style: none; }
.module-list li { display: flex; align-items: baseline; gap: 14px; padding: 9px 0; border-bottom: 1px solid rgba(240, 242, 230, 0.1); }
.module-list li:last-child { border-bottom: 0; }
.module-name { flex: none; width: 96px; color: #eef0e2; font-family: var(--docbase-font-mono); font-size: 12.5px; font-weight: 650; }
.module-role { color: #c3ccbb; font-size: 12.5px; line-height: 1.7; }
.infra-line { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 18px 0 0; padding-top: 16px; border-top: 1px solid rgba(240, 242, 230, 0.1); }
.infra-label { margin-right: 4px; color: #93a393; font-size: 11px; font-weight: 700; letter-spacing: 0.14em; }
.infra-chip { padding: 3px 10px; color: #cdd5c2; font-size: 11px; border: 1px solid rgba(240, 242, 230, 0.18); border-radius: 20px; }

.land-footer { max-width: 1120px; margin: 72px auto 0; padding: 22px 0 26px; color: #7e8c7e; font-size: 11px; text-align: center; border-top: 1px solid rgba(240, 242, 230, 0.12); }

/* ===== 入场动效 ===== */
.reveal { animation: land-rise .68s cubic-bezier(.22, .61, .36, 1) backwards; animation-delay: var(--d, 0s); }
@keyframes land-rise { from { opacity: 0; transform: translateY(18px); } to { opacity: 1; transform: translateY(0); } }
/* 章节关键元素入场：仅在 JS 启用时先隐藏，避免无 JS 时不可见 */
.js-rv .rv { opacity: 0; transform: translateY(16px); transition: opacity .6s ease, transform .6s cubic-bezier(.22, .61, .36, 1); }
.js-rv .rv.in { opacity: 1; transform: none; }

/* ===== 响应式 ===== */
@media (max-width: 1080px) {
  .qa-card { top: 40px; width: min(330px, 44%); }
  .doc-side { width: 150px; }
  .feat-layout { grid-template-columns: 280px minmax(0, 1fr); gap: 28px; }
}
@media (min-width: 901px) and (max-height: 800px) {
  .hero-head { padding-top: 28px; }
  .hero-lockup { font-size: 62px; }
  .hero-purpose { margin-top: 12px; }
  .hero-cta { margin-top: 20px; }
  .hero-stage { padding-top: 28px; padding-bottom: 28px; }
}
/* Larger desktop canvases need larger content, not just wider margins. */
@media (min-width: 1440px) and (min-height: 801px) {
 .hero { display: flex; flex-direction: column; }
 .hero-head { padding-top: clamp(40px, 6vh, 80px); }
 .hero-lockup { font-size: clamp(80px, 5vw, 100px); }
 .hero-purpose { font-size: 20px; margin-top: 20px; }
 .hero-cta { margin-top: 28px; }
 .hero-cta .btn-lg { height: 50px; padding: 0 28px; font-size: 16px; }
 .hero-stage { flex: 1; display: flex; flex-direction: column; justify-content: center; padding-top: 40px; padding-bottom: 32px; }
 .doc-window-bar { min-height: 64px; }
 .doc-window-body { height: 420px; }
 .doc-side { width: 200px; padding: 20px 14px; }
 .doc-side-item { font-size: 14px; padding: 12px; }
 .doc-main { padding: 26px 28px 22px; }
 .doc-main h2 { font-size: 22px; margin-bottom: 18px; }
 .doc-search, .doc-filter, .doc-upload, .doc-window-brand { font-size: 14px; }
 .doc-row { font-size: 14px; padding: 15px 4px; grid-template-columns: minmax(0, 1fr) 115px 150px; }
 .doc-row-head, .doc-time { font-size: 12px; }
 .preview-open, .stage-caption { font-size: 13px; }
 .ambient-sheet { width: 120px; height: 150px; }
 .sheet-left { left: 14%; }
 .sheet-right { right: 14%; }
}
@media (max-width: 900px) {
  .nav-links { display: none; }
  .hero-head { padding-top: 48px; }
  .hero-stage { padding-bottom: 64px; }
  /* 小屏：问答卡片不再叠加，跟随窗口下方 */
  .showcase { display: flex; flex-direction: column; align-items: center; gap: 18px; }
  .doc-window { width: 100%; }
  .qa-card { position: static; width: min(420px, 100%); }
  .stage-badge { left: 8px; bottom: auto; top: -14px; }
  .land-plant, .land-stones { opacity: .3; }
  .section-block { padding: 76px 20px 72px; }
  .feat-layout { grid-template-columns: 1fr; }
  .feat-list { flex-direction: row; overflow-x: auto; padding-bottom: 4px; }
  .feat-item { flex: none; width: 220px; }
  .qa-demo-grid { grid-template-columns: 1fr; }
  .r-steps { grid-template-columns: repeat(2, 1fr); }
  .about-grid { grid-template-columns: 1fr; gap: 36px; }
}
@media (max-width: 640px) {
  .land-nav { padding: 0 16px; gap: 16px; }
  .hero-head { padding: 40px 20px 0; }
  .hero-lockup { gap: 12px; }
  .hero-purpose { font-size: 15px; letter-spacing: 0.04em; }
  .hero-cta { flex-wrap: wrap; }
  .hero-stage { padding: 40px 16px 56px; }
  .doc-side { display: none; }
  .doc-window-body { height: 420px; }
  .doc-row { grid-template-columns: minmax(0, 1fr) 92px; }
  .doc-time, .doc-row-head span:last-child { display: none; }
  .fd-cols { grid-template-columns: 1fr; }
  .fd-tree { display: flex; gap: 4px; overflow-x: auto; }
  .fd-tree li { flex: none; }
  .feat-panel { min-height: 350px; padding: 20px 16px; }
  .fd-row { grid-template-columns: minmax(0, 1fr) 70px; gap: 6px; }
  .fd-time, .fd-row-head > :last-child { display: none; }
  .fd-perm-table .fd-row { grid-template-columns: minmax(0, 1fr) 90px 70px; font-size: 11px; }
  .fd-perm-table .fd-row-head > :last-child { display: block; }
  .fd-name { white-space: normal; overflow-wrap: anywhere; }
  .q-chips { padding: 16px 16px 0; }
  .q-input { margin-left: 16px; margin-right: 16px; }
  .q-stage { padding-left: 16px; padding-right: 16px; min-height: 220px; }
  .qdp-src { min-height: 300px; }
  .src-doc-name { min-width: 0; overflow-wrap: anywhere; }
  .src-doc-page { flex: none; }
  .r-steps { grid-template-columns: 1fr; }
  .about-actions .btn-lg { flex: 1 1 100%; }
}
/* Preview motion stays short and only runs in response to a selection. */
.doc-side-item { width: 100%; border: 0; background: transparent; text-align: left; cursor: pointer; transition: background-color .2s, color .2s, transform .2s; }
.doc-side-item:hover { background: #e6ebe0; transform: translateX(2px); }
.doc-upload { border: 0; cursor: pointer; }
.preview-change { animation: preview-in .32s ease both; }
@keyframes preview-in { from { opacity: .3; transform: translateY(7px); } to { opacity: 1; transform: translateY(0); } }
.preview-heading { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; }
.preview-heading > span, .preview-result > span { color: var(--land-muted); font-size: 11px; }
.preview-intro { color: var(--land-muted); font-size: 12px; margin: 0 0 18px; line-height: 1.7; }
.preview-stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 16px; }
.preview-stats > div { padding: 12px; border: 1px solid var(--land-line); border-radius: 8px; font-size: 11px; color: var(--land-muted); }
.preview-stats strong { display: block; color: var(--land-ink); font-size: 24px; margin-bottom: 5px; }
.preview-line { display: flex; justify-content: space-between; gap: 16px; padding: 13px 0; border-bottom: 1px solid var(--land-line); font-size: 12px; }
.preview-query { padding: 12px 14px; background: var(--land-teal-soft); border-radius: 8px; font-size: 13px; margin-bottom: 16px; }
.preview-result { font-size: 13px; line-height: 1.8; }
.preview-result p { margin: 8px 0; color: var(--land-muted); }
.preview-link, .preview-open { border: 0; background: transparent; color: var(--land-teal); cursor: pointer; font-size: 12px; padding: 10px 0; }
.preview-open { display: block; margin-top: 10px; font-weight: 650; }
.is-exploring .doc-window { width: 100%; }
.btn svg { transition: transform .2s ease; }
.btn:hover svg { transform: translateX(3px); }
@media (max-width: 640px) {
 .doc-window-body { flex-direction: column; flex-wrap: nowrap; }
 .doc-side { display: flex; width: 100%; overflow-x: auto; padding: 8px; border-right: 0; border-bottom: 1px solid var(--land-line); }
 .doc-side-item { width: auto; flex: none; padding: 8px; }
 .doc-side-item svg { display: none; }
}
@media (prefers-reduced-motion: reduce) {
 .preview-change { animation: none; }
 .doc-side-item, .btn svg { transition: none; }
 .doc-side-item:hover, .btn:hover svg { transform: none; }
}
@media (prefers-reduced-motion: reduce) {
  .reveal { opacity: 1; animation: none; }
  .js-rv .rv { opacity: 1; transform: none; transition: none; }
  .fd-anim, .q-answer > *, .src-body p.is-hit { animation: none; }
  .btn, .feat-item, .q-chip, .q-cite, .nav-links button { transition: none; }
  .btn:hover { transform: none; }
}
</style>
