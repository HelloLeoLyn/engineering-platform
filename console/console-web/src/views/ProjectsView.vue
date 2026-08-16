<script setup lang="ts">
// Console Projects (V06-WORK-004 + V06-WORK-006): filesystem-backed project
// metadata list with minimal runtime status (Running/Stopped) and a link into
// the Build & Run developer loop. No complex state machine here.
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { consoleApi, type ProjectRecord, type RuntimeStatusResponse } from '../api/console';

const router = useRouter();
const rows = ref<ProjectRecord[]>([]);
const runtimeStates = ref<Record<string, string>>({});
const loading = ref(false);
const error = ref<string | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    rows.value = await consoleApi.projects();
    // minimal runtime status per project (lightweight, best-effort)
    const states: Record<string, string> = {};
    await Promise.all(
      rows.value
        .filter(p => p.location)
        .map(async p => {
          try {
            const r: RuntimeStatusResponse = await consoleApi.runtimeStatus(p.location!);
            states[p.location!] = r.overall;
          } catch {
            states[p.location!] = 'UNKNOWN';
          }
        }),
    );
    runtimeStates.value = states;
  } catch (e) {
    error.value = String(e);
  } finally {
    loading.value = false;
  }
}

function openBuildRun(location: string | undefined): void {
  if (!location) return;
  router.push({ path: '/build-run', query: { project: location } });
}

function runtimeClass(s: string): string {
  if (s === 'RUNNING') return 'is-ok';
  if (s === 'STOPPED') return 'is-muted';
  return 'is-warn';
}

onMounted(load);
</script>

<template>
  <div class="projects" data-testid="console-projects">
    <div class="page-head">
      <div>
        <p class="page-eyebrow">Engineering Platform</p>
        <h1 class="page-title">Projects</h1>
        <p class="page-desc">Generated projects — metadata is filesystem-backed. Runtime status is read live from each project's .runtime state.</p>
      </div>
      <router-link to="/builder" class="btn-primary">New project</router-link>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />

    <div class="panel">
      <el-table v-loading="loading" :data="rows" empty-text="No projects yet">
        <el-table-column prop="name" label="Name" min-width="150" />
        <el-table-column prop="profile" label="Profile" width="110" />
        <el-table-column prop="stack" label="Stack" width="150" />
        <el-table-column label="Modules" min-width="170">
          <template #default="{ row }">{{ (row.modules ?? []).join(', ') || '—' }}</template>
        </el-table-column>
        <el-table-column label="Runtime" width="110">
          <template #default="{ row }">
            <span class="status-pill" :class="runtimeClass(runtimeStates[row.location] ?? 'UNKNOWN')">
              {{ runtimeStates[row.location] ?? 'UNKNOWN' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Status" width="100">
          <template #default="{ row }">
            <span class="status-pill" :class="row.status === 'SUCCESS' ? 'is-ok' : ''">{{ row.status ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Actions" width="140" fixed="right">
          <template #default="{ row }">
            <button class="btn-ghost btn-sm" @click="openBuildRun(row.location)">Build &amp; Run</button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.projects {
  display: grid;
  gap: var(--ep-space-5);
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
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
  line-height: 1.25;
}
.page-desc {
  margin: 8px 0 0;
  color: var(--ep-color-text-secondary);
}
.btn-primary {
  display: inline-flex;
  align-items: center;
  padding: 9px 18px;
  border-radius: var(--ep-radius-base);
  background: var(--ep-color-primary);
  color: #fff;
  font-weight: 600;
  font-size: 14px;
  text-decoration: none;
  box-shadow: var(--ep-shadow-sm);
  transition: background var(--ep-transition-fast);
}
.btn-primary:hover {
  background: var(--ep-color-primary-hover);
}
.panel {
  padding: var(--ep-space-4);
  background: var(--ep-color-surface);
  border-radius: var(--ep-radius-xl);
  box-shadow: var(--ep-shadow-sm);
}
.status-pill {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--ep-color-surface-tinted);
  color: var(--ep-color-text-secondary);
}
.status-pill.is-ok {
  background: var(--ep-accent-emerald-soft);
  color: var(--ep-accent-emerald);
}
.status-pill.is-warn {
  background: var(--ep-accent-orange-soft);
  color: var(--ep-accent-orange);
}
.status-pill.is-muted {
  background: var(--ep-color-surface-tinted);
  color: var(--ep-color-text-secondary);
}
.btn-ghost {
  display: inline-flex;
  align-items: center;
  padding: 6px 12px;
  border-radius: var(--ep-radius-base);
  background: var(--ep-color-surface-tinted);
  color: var(--ep-color-text);
  font-weight: 600;
  font-size: 12px;
  border: 1px solid var(--ep-color-border);
  cursor: pointer;
  transition: background var(--ep-transition-fast);
}
.btn-ghost:hover {
  background: var(--ep-accent-indigo-soft);
}
</style>
