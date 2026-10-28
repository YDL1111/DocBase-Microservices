<template>
  <div class="app-main">
    <router-view v-slot="{ Component }">
      <transition name="route-fade" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </div>
</template>

<style lang="scss" scoped>
.app-main {
  flex: 1;
  overflow: auto;
  margin: 0 18px 18px 0;
  padding: 28px 30px 36px;
  border: 1px solid var(--docbase-line-soft);
  border-radius: 16px;
  background: var(--docbase-surface);
}

@media (max-width: 767px) {
  .app-main {
    margin: 0 8px 8px;
    padding: 16px 14px 24px;
  }
}
</style>
