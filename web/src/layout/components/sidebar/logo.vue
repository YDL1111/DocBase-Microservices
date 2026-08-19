<script setup lang="ts">
defineProps<{ collapse: boolean }>();
</script>

<template>
  <div class="logo-container">
    <router-link class="logo-link" to="/home" title="DocBase">
      <img class="logo-image" src="/logo.svg" alt="DocBase" />
      <span v-show="!collapse" class="logo-title">DocBase</span>
    </router-link>
  </div>
</template>

<style lang="scss" scoped>
.logo-container {
  height: var(--docbase-header-height);
  display: flex;
  align-items: center;
  background: #fff;
  border-bottom: 1px solid #e2e8ef;
  flex-shrink: 0;
  overflow: hidden;
}

.logo-link {
  display: flex;
  align-items: center;
  width: 100%;
  height: 100%;
  padding: 0 13px;
  white-space: nowrap;
}

.logo-image {
  width: 31px;
  height: 31px;
  flex: 0 0 auto;
}

.logo-title {
  margin-left: 11px;
  overflow: hidden;
  color: #163a59;
  font-size: 18px;
  font-weight: 700;
  line-height: 32px;
  letter-spacing: 0;
  text-overflow: ellipsis;
}
</style>
