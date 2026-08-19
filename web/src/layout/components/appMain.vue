<template>
  <div class="app-main">
    <router-view />
  </div>
</template>

<style lang="scss" scoped>
.app-main {
  flex: 1;
  overflow: auto;
  padding: 22px 24px 28px;
  background: #f4f7fa;
}

@media (max-width: 767px) {
  .app-main {
    padding: 14px 12px 20px;
  }
}
</style>
