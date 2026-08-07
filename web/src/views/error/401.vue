<script setup lang="ts">
import { useRouter } from "vue-router";

const router = useRouter();
</script>

<template>
  <div class="error-page">
    <el-result icon="warning" title="401" sub-title="登录已过期，请重新登录">
      <template #extra>
        <el-button type="primary" @click="router.push('/login')">
          去登录
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
