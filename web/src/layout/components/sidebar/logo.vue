<script setup lang="ts">
defineProps<{ collapse: boolean }>();
</script>

<template>
  <div class="logo-container">
    <span v-if="!collapse" class="logo-title">DocBase</span>
    <span v-else class="logo-title-mini">DB</span>
  </div>
</template>

<style lang="scss" scoped>
.logo-container {
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #002140;
  color: #fff;
  font-weight: 600;
  flex-shrink: 0;
}

.logo-title {
  font-size: 18px;
  letter-spacing: 1px;
}

.logo-title-mini {
  font-size: 20px;
}
</style>
