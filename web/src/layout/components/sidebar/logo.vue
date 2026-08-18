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
  height: 48px;
  display: flex;
  align-items: center;
  background: #002140;
  flex-shrink: 0;
  overflow: hidden;
}

.logo-link {
  display: flex;
  align-items: center;
  width: 100%;
  height: 100%;
  padding: 0 11px;
  white-space: nowrap;
}

.logo-image {
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
}

.logo-title {
  margin-left: 12px;
  overflow: hidden;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  line-height: 32px;
  letter-spacing: 0.02em;
  text-overflow: ellipsis;
}
</style>
