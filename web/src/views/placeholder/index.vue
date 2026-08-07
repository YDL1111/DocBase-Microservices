<script setup lang="ts">
defineOptions({ name: "PlaceholderView" });
</script>

<template>
  <div class="placeholder">
    <el-result icon="info" title="功能建设中" sub-title="该业务页面将在后续阶段迁移">
      <template #extra>
        <el-button type="primary" @click="$router.push('/home')">
          返回首页
        </el-button>
      </template>
    </el-result>
  </div>
</template>

<style lang="scss" scoped>
.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
}
</style>
