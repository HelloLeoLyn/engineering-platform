<script setup lang="ts">
// Console Overview (V06-WORK-004): platform's own dashboard — real Console data.
import { onMounted, ref } from 'vue';
import { consoleApi, type OverviewResponse } from '../api/console';

const data = ref<OverviewResponse | null>(null);
const error = ref<string | null>(null);

onMounted(async () => {
  try {
    data.value = await consoleApi.overview();
  } catch (e) {
    error.value = String(e);
  }
});

const kpis = [
  { label: 'Projects', key: 'projects' as const, accent: 'indigo', marker: 'P' },
  { label: 'Generated modules', key: 'generatedModules' as const, accent: 'sky', marker: 'M' },
  { label: 'Certified templates', key: 'certifiedTemplates' as const, accent: 'emerald', marker: 'T' },
];
</script>

<template>
  <div class="overview" data-testid="console-overview">
    <div class="page-head">
      <div>
        <p class="page-eyebrow">Engineering Platform</p>
        <h1 class="page-title">Console Overview</h1>
        <p class="page-desc">The platform itself — projects, modules and templates generated through the existing pipeline.</p>
      </div>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />

    <div v-if="data" class="kpi-row">
      <div v-for="k in kpis" :key="k.label" class="kpi-card" :class="'accent-' + k.accent" data-testid="overview-kpi">
        <div class="kpi-icon">{{ k.marker }}</div>
        <div class="kpi-body">
          <p class="kpi-label">{{ k.label }}</p>
          <p class="kpi-value">{{ data[k.key] }}</p>
        </div>
      </div>
    </div>

    <div v-if="data && data.recentProjects.length" class="panel" data-testid="recent-projects">
      <div class="panel-head">
        <h3 class="panel-title">Recent projects</h3>
        <router-link to="/projects" class="panel-link">View all</router-link>
      </div>
      <div class="project-row" v-for="p in data.recentProjects" :key="p.id ?? p.name">
        <span class="project-dot" />
        <span class="project-name">{{ p.name }}</span>
        <span class="project-profile">{{ p.profile }}</span>
        <span class="project-modules">{{ (p.modules ?? []).join(', ') || '—' }}</span>
        <span class="project-status" :class="p.status === 'SUCCESS' ? 'is-ok' : ''">{{ p.status ?? '—' }}</span>
      </div>
    </div>

    <div v-if="data && !data.recentProjects.length" class="panel">
      <div class="panel-head"><h3 class="panel-title">Recent projects</h3></div>
      <p class="empty-hint">No projects generated yet — open <router-link to="/builder">Project Builder</router-link> to create one.</p>
    </div>
  </div>
</template>

<style scoped>
.overview {
  display: grid;
  gap: var(--ep-space-5);
}
.page-head {
  padding: var(--ep-space-2) 0 var(--ep-space-3);
}
.page-eyebrow {
  margin: 0 0 4px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--ep-accent-indigo);
}
.page-title {
  margin: 0;
  font-size: var(--ep-font-size-page);
  font-weight: 700;
  color: var(--ep-color-text);
  line-height: 1.25;
}
.page-desc {
  margin: 8px 0 0;
  color: var(--ep-color-text-secondary);
}
.kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--ep-space-4);
}
.kpi-card {
  display: flex;
  align-items: flex-start;
  gap: var(--ep-space-4);
  padding: var(--ep-space-5);
  background: var(--ep-color-surface);
  border-radius: var(--ep-radius-xl);
  box-shadow: var(--ep-shadow-sm);
}
.kpi-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  border-radius: var(--ep-radius-lg);
  font-weight: 700;
  color: var(--ep-accent-indigo);
  background: var(--ep-accent-indigo-soft);
}
.kpi-card.accent-sky .kpi-icon { color: var(--ep-accent-sky); background: var(--ep-accent-sky-soft); }
.kpi-card.accent-emerald .kpi-icon { color: var(--ep-accent-emerald); background: var(--ep-accent-emerald-soft); }
.kpi-label {
  margin: 0;
  font-size: 13px;
  color: var(--ep-color-text-secondary);
  font-weight: 500;
}
.kpi-value {
  margin: 4px 0 0;
  font-size: var(--ep-font-size-metric);
  font-weight: 700;
  color: var(--ep-color-text);
  line-height: 1.25;
}
.panel {
  padding: var(--ep-space-5);
  background: var(--ep-color-surface);
  border-radius: var(--ep-radius-xl);
  box-shadow: var(--ep-shadow-sm);
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--ep-space-4);
}
.panel-title {
  margin: 0;
  font-size: var(--ep-font-size-section);
  font-weight: 600;
}
.panel-link {
  font-size: 13px;
  color: var(--ep-color-primary);
  text-decoration: none;
}
.project-row {
  display: flex;
  align-items: center;
  gap: var(--ep-space-3);
  padding: var(--ep-space-3) 0;
  border-top: 1px solid var(--ep-color-border);
  font-size: 14px;
}
.project-row:first-of-type {
  border-top: none;
}
.project-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--ep-accent-indigo);
  flex-shrink: 0;
}
.project-name {
  font-weight: 600;
  min-width: 160px;
}
.project-profile {
  color: var(--ep-color-text-secondary);
  min-width: 110px;
}
.project-modules {
  color: var(--ep-color-text-muted);
  flex: 1;
}
.project-status {
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--ep-color-surface-tinted);
  color: var(--ep-color-text-secondary);
}
.project-status.is-ok {
  background: var(--ep-accent-emerald-soft);
  color: var(--ep-accent-emerald);
}
.empty-hint {
  color: var(--ep-color-text-muted);
}
</style>
