<script setup lang="ts">
// Generic placeholder page (V06-WORK-004): Business Modules / Templates /
// Build & Run / Settings are first-version placeholders.
import { useRoute } from 'vue-router';

const route = useRoute();
const title = String(route.meta?.title ?? 'Coming soon');
</script>

<template>
  <div class="placeholder" data-testid="placeholder-view">
    <div class="ph-card">
      <span class="ph-icon">🚧</span>
      <h2 class="ph-title">{{ title }}</h2>
      <p class="ph-desc">
        This area is a placeholder in the first Console release. It will be
        implemented in a later work package — the existing generator pipeline
        remains the single source of truth.
      </p>
      <router-link to="/builder" class="ph-link">Open Project Builder →</router-link>
    </div>
  </div>
</template>

<style scoped>
.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
}
.ph-card {
  text-align: center;
  padding: var(--ep-space-6);
  background: var(--ep-color-surface);
  border-radius: var(--ep-radius-xl);
  box-shadow: var(--ep-shadow-sm);
  max-width: 480px;
}
.ph-icon {
  font-size: 36px;
}
.ph-title {
  margin: var(--ep-space-3) 0 0;
  font-size: var(--ep-font-size-page);
  font-weight: 700;
}
.ph-desc {
  margin: var(--ep-space-3) 0 0;
  color: var(--ep-color-text-secondary);
  line-height: 1.6;
}
.ph-link {
  display: inline-block;
  margin-top: var(--ep-space-4);
  color: var(--ep-color-primary);
  font-weight: 600;
  text-decoration: none;
}
</style>
