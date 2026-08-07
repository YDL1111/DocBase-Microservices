<script setup lang="ts">
/**
 * 知识库概览标签页。
 * 展示知识库基本信息。
 * knowledgeBaseId 由父组件通过 props 传入，不自行获取。
 */
import { computed } from "vue";
import { useKnowledgeStoreHook } from "@/store/modules/knowledge";

const props = defineProps<{ knowledgeBaseId: number | null }>();

const knowledgeStore = useKnowledgeStoreHook();

const base = computed(() => knowledgeStore.currentBase);
</script>

<template>
  <div v-if="base" class="overview">
    <el-descriptions :column="2" border>
      <el-descriptions-item label="名称">{{ base.name }}</el-descriptions-item>
      <el-descriptions-item label="ID">{{ base.id }}</el-descriptions-item>
      <el-descriptions-item label="可见性">
        {{ base.visibility === 3 ? "公开" : base.visibility === 2 ? "部门" : "私有" }}
      </el-descriptions-item>
      <el-descriptions-item label="状态">
        {{ base.status === 1 ? "启用" : "停用" }}
      </el-descriptions-item>
      <el-descriptions-item label="描述" :span="2">
        {{ base.description || "—" }}
      </el-descriptions-item>
      <el-descriptions-item label="创建时间">{{ base.createdAt }}</el-descriptions-item>
      <el-descriptions-item label="更新时间">{{ base.updatedAt }}</el-descriptions-item>
    </el-descriptions>
  </div>
</template>

<style lang="scss" scoped>
.overview {
  padding: 16px 0;
}
</style>
