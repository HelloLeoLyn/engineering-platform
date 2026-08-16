<script setup lang="ts">
// Console Build & Run (V06-WORK-006).
// Generate → Environment Preflight → Build → Start → Status → Logs → Open → Stop/Restart.
// All runtime operations delegate to the generated project's Runtime Recipe
// (scripts/dev-start.sh | dev-stop.sh | dev-status.sh) — no second start logic.
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { consoleApi, type ProjectRecord, type PreflightResponse, type RuntimeStatusResponse } from '../api/console';

const route = useRoute();

const projects = ref<ProjectRecord[]>([]);
const location = ref('');
const loadingProjects = ref(false);
const projectError = ref<string | null>(null);

// ---- Environment ----
const preflight = ref<PreflightResponse | null>(null);
const preflightLoading = ref(false);

// ---- Build ----
const buildState = ref<'IDLE' | 'QUEUED' | 'RUNNING' | 'PASS' | 'FAIL' | 'UNKNOWN'>('IDLE');
const buildTarget = ref('');
const buildDurationMs = ref(0);
const buildExitCode = ref<number | null>(null);
const buildLog = ref('');
const buildPollTimer = ref<number | null>(null);

// ---- Runtime ----
const runtime = ref<RuntimeStatusResponse | null>(null);
const runtimeLoading = ref(false);
const runtimeMsg = ref<string | null>(null);
const runtimePollTimer = ref<number | null>(null);

// ---- Logs ----
const logTarget = ref('backend');
const logLines = ref(100);
const logs = ref<string[]>([]);
const logExists = ref(false);
const logTotal = ref(0);
const logLoading = ref(false);
const autoRefresh = ref(false);
const autoRefreshTimer = ref<number | null>(null);

async function loadProjects(): Promise<void> {
  loadingProjects.value = true;
  projectError.value = null;
  try {
    projects.value = await consoleApi.projects();
    const q = route.query.project as string | undefined;
    if (q && projects.value.some(p => p.location === q)) {
      location.value = q;
    } else if (projects.value.length > 0 && projects.value[0].location) {
      location.value = projects.value[0].location!;
    }
    if (location.value) {
      refreshAll();
    }
  } catch (e) {
    projectError.value = String(e);
  } finally {
    loadingProjects.value = false;
  }
}

function onProjectChange(): void {
  buildState.value = 'IDLE';
  preflight.value = null;
  runtime.value = null;
  logs.value = [];
  stopPolls();
  if (location.value) refreshAll();
}

async function refreshAll(): Promise<void> {
  await Promise.all([runPreflight(), fetchRuntime(), fetchLogs()]);
}

// ---- Preflight ----
async function runPreflight(): Promise<void> {
  if (!location.value) return;
  preflightLoading.value = true;
  try {
    preflight.value = await consoleApi.preflight(location.value);
  } catch (e) {
    preflight.value = null;
    projectError.value = String(e);
  } finally {
    preflightLoading.value = false;
  }
}

function preflightStatus(name: string): string {
  const c = preflight.value?.checks.find(x => x.name === name);
  return c?.status ?? 'UNKNOWN';
}

function checkDetail(name: string): string {
  const c = preflight.value?.checks.find(x => x.name === name);
  if (!c) return '—';
  return String(c.message ?? c.detected ?? '—');
}

function checkDetected(name: string): string {
  const c = preflight.value?.checks.find(x => x.name === name);
  return c?.detected ? String(c.detected) : '—';
}

// ---- Build ----
async function startBuild(target: string): Promise<void> {
  if (!location.value) return;
  stopBuildPoll();
  buildTarget.value = target;
  buildState.value = 'QUEUED';
  buildLog.value = '';
  try {
    await consoleApi.build(location.value, target);
    pollBuild();
  } catch (e) {
    buildState.value = 'FAIL';
    buildLog.value = String(e);
  }
}

function pollBuild(): void {
  stopBuildPoll();
  buildPollTimer.value = window.setInterval(async () => {
    if (!location.value) return;
    try {
      const s = await consoleApi.buildStatus(location.value, buildTarget.value);
      buildState.value = s.state;
      buildDurationMs.value = s.durationMs ?? 0;
      buildExitCode.value = s.exitCode ?? null;
      if (s.log) buildLog.value = s.log;
      if (s.state === 'PASS' || s.state === 'FAIL' || s.state === 'UNKNOWN') {
        stopBuildPoll();
      }
    } catch {
      stopBuildPoll();
    }
  }, 1500);
}

function stopBuildPoll(): void {
  if (buildPollTimer.value !== null) {
    window.clearInterval(buildPollTimer.value);
    buildPollTimer.value = null;
  }
}

// ---- Runtime ----
async function fetchRuntime(): Promise<void> {
  if (!location.value) return;
  runtimeLoading.value = true;
  try {
    runtime.value = await consoleApi.runtimeStatus(location.value);
  } catch (e) {
    runtime.value = null;
    projectError.value = String(e);
  } finally {
    runtimeLoading.value = false;
  }
}

async function doStart(): Promise<void> {
  if (!location.value) return;
  runtimeMsg.value = null;
  try {
    const r = await consoleApi.runtimeStart(location.value, 'all');
    runtimeMsg.value = r.status + (r.message ? ' — ' + r.message.slice(0, 200) : '');
    pollRuntime();
  } catch (e) {
    runtimeMsg.value = String(e);
  }
}

async function doStop(): Promise<void> {
  if (!location.value) return;
  runtimeMsg.value = null;
  try {
    const r = await consoleApi.runtimeStop(location.value);
    runtimeMsg.value = r.status + (r.message ? ' — ' + r.message.slice(0, 200) : '');
    stopRuntimePoll();
    await fetchRuntime();
  } catch (e) {
    runtimeMsg.value = String(e);
  }
}

async function doRestart(): Promise<void> {
  if (!location.value) return;
  runtimeMsg.value = null;
  try {
    const r = await consoleApi.runtimeRestart(location.value, 'all');
    runtimeMsg.value = r.status + (r.message ? ' — ' + r.message.slice(0, 200) : '');
    pollRuntime();
  } catch (e) {
    runtimeMsg.value = String(e);
  }
}

function pollRuntime(): void {
  stopRuntimePoll();
  runtimePollTimer.value = window.setInterval(async () => {
    await fetchRuntime();
    if (runtime.value?.overall === 'RUNNING' && runtime.value?.backend.status === 'RUNNING-READY'
        && runtime.value?.frontend.status === 'RUNNING-READY') {
      stopRuntimePoll();
    }
  }, 2500);
}

function stopRuntimePoll(): void {
  if (runtimePollTimer.value !== null) {
    window.clearInterval(runtimePollTimer.value);
    runtimePollTimer.value = null;
  }
}

function openApp(side: 'backend' | 'frontend'): void {
  const url = runtime.value?.[side]?.url;
  if (url) window.open(url, '_blank');
}

// ---- Logs ----
async function fetchLogs(): Promise<void> {
  if (!location.value) return;
  logLoading.value = true;
  try {
    const r = await consoleApi.runtimeLogs(location.value, logTarget.value, logLines.value);
    logExists.value = r.exists;
    logTotal.value = r.totalLines ?? 0;
    logs.value = r.lines ?? [];
  } catch (e) {
    logs.value = ['ERROR: ' + String(e)];
  } finally {
    logLoading.value = false;
  }
}

function clearLogView(): void {
  logs.value = [];
  logExists.value = false;
  logTotal.value = 0;
}

function toggleAutoRefresh(): void {
  if (autoRefresh.value) {
    autoRefreshTimer.value = window.setInterval(fetchLogs, 3000);
  } else if (autoRefreshTimer.value !== null) {
    window.clearInterval(autoRefreshTimer.value);
    autoRefreshTimer.value = null;
  }
}

function stopPolls(): void {
  stopBuildPoll();
  stopRuntimePoll();
  if (autoRefreshTimer.value !== null) {
    window.clearInterval(autoRefreshTimer.value);
    autoRefreshTimer.value = null;
  }
}

function statusClass(s: string): string {
  switch (s) {
    case 'PASS': case 'READY': case 'RUNNING': case 'RUNNING-READY': return 'is-ok';
    case 'WARNING': case 'BLOCKED': case 'STALE': return 'is-warn';
    case 'FAIL': return 'is-fail';
    case 'QUEUED': case 'RUNNING-OP': return 'is-run';
    default: return 'is-unknown';
  }
}

onMounted(loadProjects);
onBeforeUnmount(stopPolls);
</script>

<template>
  <div class="build-run" data-testid="console-build-run">
    <div class="page-head">
      <div>
        <p class="page-eyebrow">Engineering Platform</p>
        <h1 class="page-title">Build &amp; Run</h1>
        <p class="page-desc">Generate → Preflight → Build → Start → Logs → Open → Stop/Restart. All runtime actions go through the generated project's Runtime Recipe.</p>
      </div>
      <el-select v-model="location" placeholder="Select a generated project" class="project-picker" filterable @change="onProjectChange">
        <el-option v-for="p in projects" :key="p.location" :label="p.name + ' — ' + p.location" :value="p.location!" />
      </el-select>
    </div>

    <el-alert v-if="projectError" type="error" :title="projectError" show-icon :closable="false" />

    <template v-if="location">
      <!-- Environment -->
      <section class="panel">
        <div class="panel-head">
          <h2>Environment</h2>
          <div class="panel-actions">
            <button class="btn-ghost" :disabled="preflightLoading" @click="runPreflight">
              {{ preflightLoading ? 'Checking…' : 'Preflight' }}
            </button>
            <span v-if="preflight" class="status-pill" :class="statusClass(preflight.overall)">{{ preflight.overall }}</span>
          </div>
        </div>
        <div class="env-grid">
          <div class="env-item">
            <span class="env-name">Java</span>
            <span class="status-pill" :class="statusClass(preflightStatus('Java'))">{{ preflightStatus('Java') }}</span>
            <span class="env-detail">{{ checkDetail('Java') }}</span>
          </div>
          <div class="env-item">
            <span class="env-name">Maven</span>
            <span class="status-pill" :class="statusClass(preflightStatus('Maven'))">{{ preflightStatus('Maven') }}</span>
            <span class="env-detail">{{ checkDetected('Maven') }}</span>
          </div>
          <div class="env-item">
            <span class="env-name">Node</span>
            <span class="status-pill" :class="statusClass(preflightStatus('Node'))">{{ preflightStatus('Node') }}</span>
            <span class="env-detail">{{ checkDetected('Node') }}</span>
          </div>
          <div class="env-item">
            <span class="env-name">pnpm</span>
            <span class="status-pill" :class="statusClass(preflightStatus('pnpm'))">{{ preflightStatus('pnpm') }}</span>
            <span class="env-detail">{{ checkDetected('pnpm') }}</span>
          </div>
          <div class="env-item">
            <span class="env-name">Database</span>
            <span class="status-pill" :class="statusClass(preflightStatus('Database'))">{{ preflightStatus('Database') }}</span>
            <span class="env-detail">{{ checkDetail('Database') }}</span>
          </div>
          <div class="env-item">
            <span class="env-name">Runtime Recipe</span>
            <span class="status-pill" :class="statusClass(preflightStatus('Runtime Recipe'))">{{ preflightStatus('Runtime Recipe') }}</span>
            <span class="env-detail">{{ checkDetail('Runtime Recipe') }}</span>
          </div>
        </div>
      </section>

      <!-- Build -->
      <section class="panel">
        <div class="panel-head">
          <h2>Build</h2>
          <div class="panel-actions">
            <button class="btn-primary btn-sm" @click="startBuild('all')">Build All</button>
            <button class="btn-ghost btn-sm" @click="startBuild('backend')">Build Backend</button>
            <button class="btn-ghost btn-sm" @click="startBuild('frontend')">Build Frontend</button>
          </div>
        </div>
        <div class="build-bar">
          <span class="status-pill" :class="statusClass(buildState)">{{ buildState }}</span>
          <span v-if="buildTarget" class="build-target">target: {{ buildTarget }}</span>
          <span v-if="buildDurationMs > 0" class="build-meta">{{ (buildDurationMs / 1000).toFixed(1) }}s</span>
          <span v-if="buildExitCode !== null" class="build-meta">exit: {{ buildExitCode }}</span>
        </div>
        <pre v-if="buildLog" class="log-view">{{ buildLog.slice(-6000) }}</pre>
      </section>

      <!-- Runtime -->
      <section class="panel">
        <div class="panel-head">
          <h2>Runtime</h2>
          <div class="panel-actions">
            <button class="btn-primary btn-sm" @click="doStart">Start</button>
            <button class="btn-ghost btn-sm" @click="doRestart">Restart</button>
            <button class="btn-danger btn-sm" @click="doStop">Stop</button>
            <button class="btn-ghost btn-sm" :disabled="!runtime?.frontend?.url" @click="openApp('frontend')">Open Application</button>
          </div>
        </div>
        <div v-if="runtimeMsg" class="runtime-msg">{{ runtimeMsg }}</div>
        <div class="runtime-grid">
          <div class="env-item">
            <span class="env-name">Backend</span>
            <span class="status-pill" :class="statusClass(runtime?.backend?.status ?? 'STOPPED')">{{ runtime?.backend?.status ?? 'STOPPED' }}</span>
            <span class="env-detail">{{ runtime?.backend?.url ? runtime.backend.url : 'no URL' }} <a v-if="runtime?.backend?.url" :href="runtime.backend.url" target="_blank">open</a></span>
          </div>
          <div class="env-item">
            <span class="env-name">Frontend</span>
            <span class="status-pill" :class="statusClass(runtime?.frontend?.status ?? 'STOPPED')">{{ runtime?.frontend?.status ?? 'STOPPED' }}</span>
            <span class="env-detail">{{ runtime?.frontend?.url ? runtime.frontend.url : 'no URL' }} <a v-if="runtime?.frontend?.url" :href="runtime.frontend.url" target="_blank">open</a></span>
          </div>
        </div>
        <div class="runtime-refresh">
          <button class="btn-ghost btn-sm" :disabled="runtimeLoading" @click="fetchRuntime">Refresh status</button>
        </div>
      </section>

      <!-- Logs -->
      <section class="panel">
        <div class="panel-head">
          <h2>Logs</h2>
          <div class="panel-actions">
            <el-select v-model="logTarget" class="log-target" @change="fetchLogs">
              <el-option label="Backend" value="backend" />
              <el-option label="Frontend" value="frontend" />
              <el-option label="Build" value="build" />
            </el-select>
            <el-select v-model="logLines" class="log-lines" @change="fetchLogs">
              <el-option label="50" :value="50" />
              <el-option label="100" :value="100" />
              <el-option label="300" :value="300" />
            </el-select>
            <button class="btn-ghost btn-sm" @click="fetchLogs">Refresh</button>
            <label class="auto-refresh">
              <input v-model="autoRefresh" type="checkbox" @change="toggleAutoRefresh" /> auto
            </label>
            <button class="btn-ghost btn-sm" @click="clearLogView">Clear view</button>
          </div>
        </div>
        <div v-if="logExists" class="log-meta">{{ logTotal }} lines total</div>
        <pre v-if="logs.length" class="log-view" data-testid="runtime-log-view">{{ logs.join('\n') }}</pre>
        <div v-else class="log-empty">{{ logLoading ? 'Loading…' : 'No log output yet.' }}</div>
      </section>
    </template>

    <div v-else class="panel empty-panel">Select a generated project above to inspect environment, build, run and logs.</div>
  </div>
</template>

<style scoped>
.build-run {
  display: grid;
  gap: var(--ep-space-5);
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ep-space-4);
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
.project-picker {
  width: 420px;
}
.panel {
  padding: var(--ep-space-4);
  background: var(--ep-color-surface);
  border-radius: var(--ep-radius-xl);
  box-shadow: var(--ep-shadow-sm);
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--ep-space-3);
}
.panel-head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
}
.panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.env-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: var(--ep-space-3);
}
.env-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  background: var(--ep-color-surface-tinted);
  border-radius: var(--ep-radius-lg);
}
.env-name {
  font-weight: 600;
  font-size: 13px;
  min-width: 96px;
}
.env-detail {
  font-size: 12px;
  color: var(--ep-color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.env-detail a {
  color: var(--ep-accent-indigo);
  text-decoration: none;
  margin-left: 4px;
}
.status-pill {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 10px;
  border-radius: 999px;
  white-space: nowrap;
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
.status-pill.is-fail {
  background: var(--ep-accent-rose-soft);
  color: var(--ep-accent-rose);
}
.status-pill.is-run {
  background: var(--ep-accent-indigo-soft);
  color: var(--ep-accent-indigo);
}
.status-pill.is-unknown {
  background: var(--ep-color-surface-tinted);
  color: var(--ep-color-text-secondary);
}
.btn-primary {
  display: inline-flex;
  align-items: center;
  padding: 8px 16px;
  border-radius: var(--ep-radius-base);
  background: var(--ep-color-primary);
  color: #fff;
  font-weight: 600;
  font-size: 13px;
  border: none;
  cursor: pointer;
  box-shadow: var(--ep-shadow-sm);
  transition: background var(--ep-transition-fast);
}
.btn-primary:hover {
  background: var(--ep-color-primary-hover);
}
.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-ghost {
  display: inline-flex;
  align-items: center;
  padding: 8px 14px;
  border-radius: var(--ep-radius-base);
  background: var(--ep-color-surface-tinted);
  color: var(--ep-color-text);
  font-weight: 600;
  font-size: 13px;
  border: 1px solid var(--ep-color-border);
  cursor: pointer;
  transition: background var(--ep-transition-fast);
}
.btn-ghost:hover {
  background: var(--ep-accent-indigo-soft);
}
.btn-ghost:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-danger {
  display: inline-flex;
  align-items: center;
  padding: 8px 14px;
  border-radius: var(--ep-radius-base);
  background: var(--ep-accent-rose-soft);
  color: var(--ep-accent-rose);
  font-weight: 600;
  font-size: 13px;
  border: 1px solid var(--ep-accent-rose);
  cursor: pointer;
  transition: background var(--ep-transition-fast);
}
.btn-danger:hover {
  background: var(--ep-accent-rose);
  color: #fff;
}
.btn-sm {
  padding: 6px 12px;
  font-size: 12px;
}
.build-bar,
.runtime-refresh {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: var(--ep-space-2);
}
.build-target,
.build-meta,
.runtime-msg,
.log-meta {
  font-size: 12px;
  color: var(--ep-color-text-secondary);
}
.log-view {
  max-height: 320px;
  overflow: auto;
  margin: var(--ep-space-2) 0 0;
  padding: 12px 14px;
  background: #16181f;
  color: #d7dbe6;
  border-radius: var(--ep-radius-lg);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
.log-empty {
  padding: 20px;
  text-align: center;
  color: var(--ep-color-text-secondary);
  font-size: 13px;
}
.runtime-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: var(--ep-space-3);
  margin-bottom: var(--ep-space-2);
}
.auto-refresh {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--ep-color-text-secondary);
  cursor: pointer;
}
.log-target {
  width: 130px;
}
.log-lines {
  width: 90px;
}
.empty-panel {
  padding: 40px;
  text-align: center;
  color: var(--ep-color-text-secondary);
}
</style>
