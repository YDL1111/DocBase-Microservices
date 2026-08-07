<script setup lang="ts">
/**
 * 知识库详情页。
 * 包含四个标签页：概览、成员、目录、文档。
 *
 * 关键设计：
 *  - knowledgeBaseId 仅来自路由参数（props），不信任外部输入；
 *  - 进入页面时加载知识库详情、目录树、成员列表；
 *  - 切换回此页面时（从其他知识库切回），通过 watch 检测 id 变化重新加载；
 *  - 所有子组件通过 props 接收 knowledgeBaseId，不自行拼接。
 */
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
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
    <!-- 无效 ID 不渲染子组件 -->
    <div v-if="knowledgeBaseId === null" class="invalid-id">
      <el-result icon="error" title="404" sub-title="无效的知识库 ID" />
    </div>

    <div v-else v-loading="loading" class="detail-container">
      <div v-if="knowledgeStore.currentBase" class="detail-header">
        <h2>{{ knowledgeStore.currentBase.name }}</h2>
        <p v-if="knowledgeStore.currentBase.description" class="description">
          {{ knowledgeStore.currentBase.description }}
        </p>
      </div>

      <el-tabs v-model="currentTab" class="detail-tabs">
        <el-tab-pane label="概览" name="overview">
          <DetailOverview :knowledge-base-id="knowledgeBaseId" />
        </el-tab-pane>
        <el-tab-pane label="成员" name="members">
          <DetailMembers :knowledge-base-id="knowledgeBaseId" />
        </el-tab-pane>
        <el-tab-pane label="目录" name="folders">
          <DetailFolders :knowledge-base-id="knowledgeBaseId" />
        </el-tab-pane>
        <el-tab-pane label="文档" name="documents">
          <DetailDocuments :knowledge-base-id="knowledgeBaseId" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.knowledge-detail {
  background: #fff;
  padding: 16px;
  border-radius: 4px;
}

.detail-header {
  margin-bottom: 16px;

  h2 {
    margin: 0 0 8px;
    font-size: 20px;
  }

  .description {
    margin: 0;
    color: #606266;
    font-size: 14px;
  }
}

.detail-tabs {
  margin-top: 8px;
}
</style>
