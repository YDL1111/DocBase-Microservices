<script setup lang="ts">
import { useRouter } from "vue-router";

const router = useRouter();
</script>

<template>
  <div class="error-page">
    <el-result icon="error" title="403" sub-title="你没有访问该资源的权限">
      <template #extra>
        <el-button type="primary" @click="router.push('/home')">
          返回首页
        </el-button>
      </template>
    </el-result>
  </div>
</template>

<style lang="scss" scoped>
.error-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 70vh;
}
</style>
