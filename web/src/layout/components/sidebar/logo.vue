<script setup lang="ts">
defineProps<{ collapse: boolean }>();
</script>

<template>
  <div class="logo-container">
    <router-link class="logo-link" to="/home" title="DocBase">
      <span class="logo-mark">
        <svg class="logo-image" viewBox="0 0 32 32" aria-hidden="true"><rect width="32" height="32" rx="7" fill="#175e4c"/><path d="M10 8h12v16H10z" fill="#f3efe2"/><path d="M13 12h6M13 16h6M13 20h4" stroke="#175e4c" stroke-width="1.5"/></svg>
      </span>
      <span v-show="!collapse" class="logo-copy">
        <strong>DocBase</strong>
        <small>团队知识空间</small>
      </span>
    </router-link>
  </div>
</template>

<style lang="scss" scoped>
.logo-container {
  height: 76px;
  display: flex;
  align-items: center;
  flex-shrink: 0;
  overflow: hidden;
}

.logo-link {
  display: flex;
  align-items: center;
  width: 100%;
  height: 100%;
  padding: 0 20px;
  white-space: nowrap;
}

.logo-mark {
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 36px;
  background: transparent;
  border-radius: 9px;
  box-shadow: 0 0 0 1px rgba(244, 241, 233, 0.14);
}

.logo-image {
  width: 26px;
  height: 26px;
}

.logo-copy {
  display: flex;
  flex-direction: column;
  margin-left: 12px;
  overflow: hidden;
}

.logo-copy strong {
  color: #203c2d;
  font-size: 19px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: -0.4px;
}

.logo-copy small {
  margin-top: 5px;
  color: var(--docbase-muted);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: .6px;
}

@media (max-width: 767px) {
  .logo-container {
    height: 88px;
  }
}
</style>
