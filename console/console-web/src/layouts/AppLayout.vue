<script setup lang="ts">
// EP Console App Shell (V06-WORK-004): Sidebar + Topbar.
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();

const nav = [
  { path: '/', label: 'Overview', icon: 'DataBoard' },
  { path: '/projects', label: 'Projects', icon: 'FolderOpened' },
  { path: '/builder', label: 'Project Builder', icon: 'MagicStick' },
  { path: '/modules', label: 'Business Modules', icon: 'Grid' },
  { path: '/templates', label: 'Templates', icon: 'Files' },
  { path: '/build-run', label: 'Build & Run', icon: 'CaretRight' },
  { path: '/settings', label: 'Settings', icon: 'Setting' },
];

const title = computed(() => String(route.meta?.title ?? 'Overview'));
</script>

<template>
  <div class="app-shell">
    <aside class="app-sidebar">
      <div class="app-brand">
        <div class="app-brand-mark">EP</div>
        <div class="app-brand-text">
          <span class="app-brand-name">Engineering Platform</span>
          <span class="app-brand-sub">Console</span>
        </div>
      </div>
      <nav class="app-nav">
        <router-link
          v-for="item in nav"
          :key="item.path"
          :to="item.path"
          class="app-nav-item"
          :class="{ 'is-active': route.path === item.path || (item.path !== '/' && route.path.startsWith(item.path)) }"
        >
          <el-icon class="app-nav-icon"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>
      <div class="app-sidebar-footer">Generator pipeline · v0.1</div>
    </aside>

    <div class="app-main">
      <header class="app-topbar">
        <div class="app-topbar-left">
          <span class="app-context">Workspace</span>
          <span class="app-sep">/</span>
          <span class="app-title">{{ title }}</span>
        </div>
        <div class="app-topbar-right">
          <span class="app-user-avatar">A</span>
          <span class="app-user-name">admin</span>
        </div>
      </header>
      <main class="app-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--ep-color-canvas);
}
.app-sidebar {
  display: flex;
  flex-direction: column;
  width: var(--ep-sidebar-width);
  flex-shrink: 0;
  background: var(--ep-color-canvas-deep);
  border-right: 1px solid var(--ep-color-border);
}
.app-brand {
  display: flex;
  align-items: center;
  gap: var(--ep-space-3);
  height: var(--ep-header-height);
  padding: 0 var(--ep-space-4);
  border-bottom: 1px solid var(--ep-color-border);
}
.app-brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--ep-radius-lg);
  background: linear-gradient(135deg, var(--ep-accent-indigo), var(--ep-accent-violet));
  color: #fff;
  font-weight: 700;
  font-size: 13px;
  box-shadow: var(--ep-shadow-sm);
}
.app-brand-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.app-brand-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--ep-color-text);
  line-height: 1.2;
}
.app-brand-sub {
  font-size: 12px;
  color: var(--ep-color-text-muted);
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.app-nav {
  flex: 1;
  padding: var(--ep-space-3);
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
}
.app-nav-item {
  display: flex;
  align-items: center;
  gap: var(--ep-space-3);
  padding: 10px 12px;
  border-radius: var(--ep-radius-base);
  color: var(--ep-color-text-secondary);
  font-weight: 500;
  font-size: 14px;
  text-decoration: none;
  transition: background var(--ep-transition-fast), color var(--ep-transition-fast);
}
.app-nav-item:hover {
  background: var(--ep-color-surface-hover);
  color: var(--ep-color-text);
}
.app-nav-item.is-active {
  background: var(--ep-color-primary-soft);
  color: var(--ep-color-primary);
  font-weight: 600;
}
.app-nav-icon {
  font-size: 16px;
}
.app-sidebar-footer {
  padding: var(--ep-space-3) var(--ep-space-4);
  font-size: 11px;
  color: var(--ep-color-text-muted);
  border-top: 1px solid var(--ep-color-border);
}
.app-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}
.app-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--ep-header-height);
  padding: 0 var(--ep-space-5);
  background: var(--ep-color-surface-glass);
  backdrop-filter: blur(12px) saturate(1.4);
  border-bottom: 1px solid var(--ep-color-border);
}
.app-topbar-left {
  display: flex;
  align-items: center;
  gap: var(--ep-space-2);
  font-size: 13px;
}
.app-context {
  color: var(--ep-color-text-muted);
}
.app-sep {
  color: var(--ep-color-text-muted);
}
.app-title {
  color: var(--ep-color-text);
  font-weight: 500;
}
.app-topbar-right {
  display: flex;
  align-items: center;
  gap: var(--ep-space-2);
}
.app-user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--ep-radius-pill);
  background: linear-gradient(135deg, var(--ep-accent-sky), var(--ep-accent-cyan));
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}
.app-user-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--ep-color-text);
}
.app-content {
  flex: 1;
  overflow-y: auto;
  padding: var(--ep-space-5);
}
</style>
