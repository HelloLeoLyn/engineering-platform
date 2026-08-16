<script setup lang="ts">
// Console Project Builder (V06-WORK-004) — 6-step wizard.
// UI state → Project Contract (buildContract) → existing validation → generate.
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { consoleApi, type MetaResponse } from '../api/console';
import { buildContract, defaultBuilderState, type BuilderState } from '../utils/contract';

const router = useRouter();
const meta = ref<MetaResponse | null>(null);
const metaError = ref<string | null>(null);
const step = ref(0);
const state = ref<BuilderState>(defaultBuilderState());
const yamlPreview = ref('');
const generating = ref(false);
const progress = ref<string[]>([]);
const result = ref<{ status: string; files?: number; outputDir?: string; modules?: string[]; errors?: unknown[] } | null>(null);

const steps = [
  'Basic Information',
  'Application Profile',
  'Technology Stack',
  'Frontend Template',
  'Business Modules',
  'Review & Generate',
];

const certifiedProfiles = computed(() =>
  (meta.value?.applicationProfiles ?? []).filter((p) => p.status === 'certified'));
const reservedProfiles = computed(() =>
  (meta.value?.applicationProfiles ?? []).filter((p) => p.status !== 'certified'));
const certifiedStacks = computed(() =>
  (meta.value?.stackProfiles ?? []).filter((s) => s.status === 'certified'));
const certifiedTemplates = computed(() =>
  (meta.value?.frontendTemplates ?? []).filter((t) => t.status === 'certified'));
const reservedTemplates = computed(() =>
  (meta.value?.frontendTemplates ?? []).filter((t) => t.status !== 'certified'));

const canNext = computed(() => {
  switch (step.value) {
    case 0: return state.value.projectName.trim().length > 0 && state.value.projectId.trim().length > 0;
    case 1: return state.value.applicationProfile.length > 0;
    case 2: return state.value.stackProfile.length > 0;
    case 3: return state.value.frontendTemplate.length > 0;
    case 4: return state.value.modules.length > 0;
    default: return true;
  }
});

const contract = computed(() => buildContract(state.value));

onMounted(async () => {
  try {
    meta.value = await consoleApi.meta();
  } catch (e) {
    metaError.value = String(e);
  }
});

function next(): void {
  if (step.value < 5) step.value++;
  if (step.value === 5) refreshPreview();
}

function back(): void {
  if (step.value > 0) step.value--;
}

function toggleModule(id: string): void {
  const idx = state.value.modules.indexOf(id);
  if (idx >= 0) state.value.modules.splice(idx, 1);
  else state.value.modules.push(id);
}

async function refreshPreview(): Promise<void> {
  try {
    const r = await consoleApi.preview(contract.value);
    yamlPreview.value = r.yaml;
  } catch (e) {
    yamlPreview.value = '# preview failed: ' + e;
  }
}

async function generate(): Promise<void> {
  generating.value = true;
  progress.value = ['Preparing', 'Validating', 'Resolving', 'Planning', 'Generating'];
  result.value = null;
  try {
    const validate = await consoleApi.validate(contract.value);
    if (!validate.valid) {
      result.value = {
        status: 'FAILED',
        errors: validate.errors ?? [{ category: 'Invalid Project Configuration', message: 'validation failed' }],
      };
      progress.value = ['Preparing', 'Validating'];
      return;
    }
    // coarse-grained: resolve+plan+generate run in one existing pipeline call
    const location = state.value.outputLocation.trim() ||
      `/home/administrator/workspace/engineering-platform/console/console-data/generated/${state.value.projectId}`;
    const gen = await consoleApi.generate({ contract: contract.value, location });
    result.value = gen;
    progress.value = gen.status === 'SUCCESS'
      ? ['Preparing', 'Validating', 'Resolving', 'Planning', 'Generating', 'Completed']
      : ['Preparing', 'Validating', 'Resolving', 'Planning', 'Generating', 'Failed'];
  } catch (e) {
    result.value = { status: 'FAILED', errors: [{ category: 'Generation Conflict', message: String(e) }] };
  } finally {
    generating.value = false;
  }
}

function goProjects(): void {
  router.push({ path: '/projects' });
}

function errorCategory(e: unknown): string {
  return (e as { category?: string }).category ?? 'Error';
}

function errorMessage(e: unknown): string {
  return (e as { message?: string }).message ?? String(e);
}
</script>

<template>
  <div class="builder" data-testid="project-builder">
    <div class="page-head">
      <div>
        <p class="page-eyebrow">Engineering Platform</p>
        <h1 class="page-title">Project Builder</h1>
        <p class="page-desc">Configure a project visually — the wizard produces a Project Contract the existing generator executes.</p>
      </div>
    </div>

    <el-alert v-if="metaError" type="error" :title="metaError" show-icon :closable="false" />

    <el-steps :active="step" finish-status="success" align-center class="builder-steps">
      <el-step v-for="s in steps" :key="s" :title="s" />
    </el-steps>

    <!-- Step 0: Basic Information -->
    <div v-if="step === 0" class="step-panel" data-testid="step-basic">
      <h3 class="step-title">Basic Information</h3>
      <el-form label-width="140px" class="step-form">
        <el-form-item label="Project name" required>
          <el-input v-model="state.projectName" placeholder="e.g. Acme ERP" data-testid="input-project-name" />
        </el-form-item>
        <el-form-item label="Project ID" required>
          <el-input v-model="state.projectId" placeholder="e.g. acme-erp (a-z0-9-)" data-testid="input-project-id" />
        </el-form-item>
        <el-form-item label="Description">
          <el-input v-model="state.description" type="textarea" :rows="2" placeholder="Optional project description" />
        </el-form-item>
        <el-form-item label="Base package">
          <el-input v-model="state.basePackage" placeholder="com.acme.core" />
        </el-form-item>
        <el-form-item label="Output location">
          <el-input v-model="state.outputLocation" placeholder="Optional — defaults to console-data/generated/<id>" />
        </el-form-item>
      </el-form>
    </div>

    <!-- Step 1: Application Profile -->
    <div v-else-if="step === 1" class="step-panel" data-testid="step-profile">
      <h3 class="step-title">Application Profile</h3>
      <div class="card-grid">
        <div
          v-for="p in certifiedProfiles"
          :key="p.id"
          class="choice-card is-certified"
          :class="{ 'is-selected': state.applicationProfile === p.id }"
          data-testid="profile-card"
          @click="state.applicationProfile = p.id"
        >
          <span class="chip is-certified">Certified</span>
          <h4 class="choice-title">{{ p.id }}</h4>
          <p class="choice-desc">{{ p.description }}</p>
        </div>
        <div v-for="p in reservedProfiles" :key="p.id" class="choice-card is-reserved" data-testid="profile-reserved">
          <span class="chip is-reserved">Coming Soon</span>
          <h4 class="choice-title">{{ p.id }}</h4>
          <p class="choice-desc">{{ p.description }}</p>
        </div>
      </div>
    </div>

    <!-- Step 2: Technology Stack -->
    <div v-else-if="step === 2" class="step-panel" data-testid="step-stack">
      <h3 class="step-title">Technology Stack</h3>
      <div class="card-grid">
        <div
          v-for="s in certifiedStacks"
          :key="s.id"
          class="choice-card is-certified stack-card"
          :class="{ 'is-selected': state.stackProfile === s.id }"
          data-testid="stack-card"
          @click="state.stackProfile = s.id"
        >
          <span class="chip is-certified">Certified</span>
          <h4 class="choice-title">{{ s.id }}</h4>
          <p class="choice-desc">{{ s.description }}</p>
          <div class="stack-cols">
            <div class="stack-col">
              <p class="stack-col-title">Backend</p>
              <span v-for="v in ['Java 25', 'Spring Boot', 'Maven', 'MyBatis-Plus', 'Flyway', 'MySQL', 'REST', 'Jakarta Validation']" :key="v" class="stack-tag">{{ v }}</span>
            </div>
            <div class="stack-col">
              <p class="stack-col-title">Frontend</p>
              <span v-for="v in ['Vue 3', 'TypeScript', 'Vite', 'Pinia', 'Element Plus', 'EP UI']" :key="v" class="stack-tag">{{ v }}</span>
            </div>
            <div class="stack-col">
              <p class="stack-col-title">Testing</p>
              <span v-for="v in ['JUnit', 'Vitest', 'Playwright Golden Path']" :key="v" class="stack-tag">{{ v }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Step 3: Frontend Template -->
    <div v-else-if="step === 3" class="step-panel" data-testid="step-template">
      <h3 class="step-title">Frontend Template</h3>
      <div class="card-grid">
        <div
          v-for="t in certifiedTemplates"
          :key="t.id"
          class="choice-card is-certified"
          :class="{ 'is-selected': state.frontendTemplate === t.id }"
          data-testid="template-card"
          @click="state.frontendTemplate = t.id"
        >
          <span class="chip is-certified">Certified</span>
          <h4 class="choice-title">{{ t.id }}</h4>
          <p class="choice-desc">{{ t.description }}</p>
        </div>
        <div v-for="t in reservedTemplates" :key="t.id" class="choice-card is-reserved" data-testid="template-reserved">
          <span class="chip is-reserved">Coming Soon</span>
          <h4 class="choice-title">{{ t.id }}</h4>
          <p class="choice-desc">{{ t.description }}</p>
        </div>
      </div>
    </div>

    <!-- Step 4: Business Modules -->
    <div v-else-if="step === 4" class="step-panel" data-testid="step-modules">
      <h3 class="step-title">Business Modules</h3>
      <p class="step-hint">Sourced from the platform module registry / capabilities — select at least one.</p>
      <div class="card-grid">
        <div
          v-for="m in meta?.modules ?? []"
          :key="m.id"
          class="choice-card module-card"
          :class="{ 'is-selected': state.modules.includes(m.id) }"
          data-testid="module-card"
          @click="toggleModule(m.id)"
        >
          <span class="chip is-module">{{ m.kind === 'capability' ? 'Capability' : 'Module' }}</span>
          <h4 class="choice-title">{{ m.id }}</h4>
          <p class="choice-desc">{{ m.description }}</p>
        </div>
      </div>
    </div>

    <!-- Step 5: Review -->
    <div v-else class="step-panel review-panel" data-testid="step-review">
      <h3 class="step-title">Review &amp; Generate</h3>
      <div class="review-grid">
        <div class="review-summary">
          <div class="summary-row"><span class="summary-label">Project</span><span class="summary-value">{{ state.projectName }} ({{ state.projectId }})</span></div>
          <div class="summary-row"><span class="summary-label">Application Profile</span><span class="summary-value">{{ state.applicationProfile }}</span></div>
          <div class="summary-row"><span class="summary-label">Stack</span><span class="summary-value">{{ state.stackProfile }}</span></div>
          <div class="summary-row"><span class="summary-label">Frontend</span><span class="summary-value">{{ state.frontendTemplate }}</span></div>
          <div class="summary-row"><span class="summary-label">Modules</span><span class="summary-value">{{ state.modules.join(', ') }}</span></div>
          <div class="summary-row"><span class="summary-label">Output</span><span class="summary-value">{{ state.outputLocation || 'console-data/generated/' + state.projectId }}</span></div>
        </div>
        <div class="yaml-panel">
          <div class="yaml-head">
            <span>Project Contract Preview (project.yaml)</span>
            <el-button size="small" text @click="refreshPreview">Refresh</el-button>
          </div>
          <pre class="yaml-pre" data-testid="yaml-preview">{{ yamlPreview || '# click Generate to render contract…' }}</pre>
        </div>
      </div>

      <div v-if="progress.length && generating" class="progress-panel" data-testid="generate-progress">
        <el-steps :active="progress.length" align-center>
          <el-step v-for="p in progress" :key="p" :title="p" />
        </el-steps>
      </div>

      <div v-if="result" class="result-panel" :class="result.status === 'SUCCESS' ? 'is-ok' : 'is-fail'" data-testid="generate-result">
        <template v-if="result.status === 'SUCCESS'">
          <h4 class="result-title">Project generated</h4>
          <p>Files: {{ result.files }} · Modules: {{ (result.modules ?? []).join(', ') }}</p>
          <p class="result-loc">Location: {{ result.outputDir }}</p>
          <el-button type="primary" @click="goProjects">View projects</el-button>
        </template>
        <template v-else>
          <h4 class="result-title">Generation failed</h4>
          <div v-for="(e, i) in (result.errors ?? [])" :key="i" class="result-error">
            <span class="error-cat">{{ errorCategory(e) }}</span>
            <span class="error-msg">{{ errorMessage(e) }}</span>
          </div>
        </template>
      </div>
    </div>

    <div class="builder-actions">
      <el-button v-if="step > 0" @click="back">Back</el-button>
      <el-button v-if="step < 5" type="primary" :disabled="!canNext" data-testid="btn-next" @click="next">Next</el-button>
      <el-button v-if="step === 5" type="primary" :loading="generating" :disabled="generating" data-testid="btn-generate" @click="generate">
        Generate Project
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.builder {
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
  line-height: 1.25;
}
.page-desc {
  margin: 8px 0 0;
  color: var(--ep-color-text-secondary);
}
.builder-steps {
  padding: var(--ep-space-4);
  background: var(--ep-color-surface);
  border-radius: var(--ep-radius-lg);
  box-shadow: var(--ep-shadow-sm);
}
.step-panel {
  padding: var(--ep-space-5);
  background: var(--ep-color-surface);
  border-radius: var(--ep-radius-xl);
  box-shadow: var(--ep-shadow-sm);
}
.step-title {
  margin: 0 0 var(--ep-space-4);
  font-size: var(--ep-font-size-section);
  font-weight: 600;
}
.step-hint {
  margin: 0 0 var(--ep-space-4);
  color: var(--ep-color-text-secondary);
  font-size: 13px;
}
.step-form {
  max-width: 640px;
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--ep-space-4);
}
.choice-card {
  position: relative;
  padding: var(--ep-space-4);
  border-radius: var(--ep-radius-lg);
  background: var(--ep-color-surface-muted);
  border: 1px solid transparent;
  cursor: pointer;
  transition: border-color var(--ep-transition-fast), box-shadow var(--ep-transition-fast), background var(--ep-transition-fast);
}
.choice-card:hover {
  background: var(--ep-color-surface-hover);
}
.choice-card.is-selected {
  border-color: var(--ep-color-primary);
  background: var(--ep-color-primary-soft);
  box-shadow: var(--ep-shadow-sm);
}
.choice-card.is-reserved {
  cursor: not-allowed;
  opacity: 0.55;
}
.chip {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
  margin-bottom: var(--ep-space-2);
}
.chip.is-certified {
  background: var(--ep-accent-emerald-soft);
  color: var(--ep-accent-emerald);
}
.chip.is-reserved {
  background: var(--ep-color-surface-tinted);
  color: var(--ep-color-text-muted);
}
.chip.is-module {
  background: var(--ep-accent-indigo-soft);
  color: var(--ep-accent-indigo);
}
.choice-title {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
  color: var(--ep-color-text);
}
.choice-desc {
  margin: 0;
  font-size: 13px;
  color: var(--ep-color-text-secondary);
  line-height: 1.5;
}
.stack-card {
  grid-column: 1 / -1;
}
.stack-cols {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--ep-space-4);
  margin-top: var(--ep-space-3);
}
.stack-col-title {
  margin: 0 0 var(--ep-space-2);
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--ep-color-text-muted);
}
.stack-tag {
  display: inline-block;
  font-size: 12px;
  padding: 3px 10px;
  margin: 0 6px 6px 0;
  border-radius: 999px;
  background: var(--ep-color-surface-tinted);
  color: var(--ep-color-text-secondary);
}
.review-grid {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: var(--ep-space-4);
}
@media (max-width: 900px) {
  .review-grid {
    grid-template-columns: 1fr;
  }
}
.review-summary {
  display: flex;
  flex-direction: column;
  gap: var(--ep-space-3);
}
.summary-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--ep-space-3);
  border-radius: var(--ep-radius-base);
  background: var(--ep-color-surface-tinted);
}
.summary-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--ep-color-text-muted);
}
.summary-value {
  font-size: 14px;
  font-weight: 500;
  color: var(--ep-color-text);
}
.yaml-panel {
  min-width: 0;
}
.yaml-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: var(--ep-space-2);
}
.yaml-pre {
  margin: 0;
  padding: var(--ep-space-4);
  border-radius: var(--ep-radius-lg);
  background: #1b2137;
  color: #d5d9f0;
  font-size: 12px;
  line-height: 1.5;
  overflow: auto;
  max-height: 420px;
}
.progress-panel {
  margin-top: var(--ep-space-5);
  padding: var(--ep-space-4);
  border-radius: var(--ep-radius-lg);
  background: var(--ep-color-surface-tinted);
}
.result-panel {
  margin-top: var(--ep-space-5);
  padding: var(--ep-space-5);
  border-radius: var(--ep-radius-lg);
}
.result-panel.is-ok {
  background: var(--ep-accent-emerald-soft);
}
.result-panel.is-fail {
  background: var(--ep-accent-rose-soft);
}
.result-title {
  margin: 0 0 var(--ep-space-2);
  font-size: var(--ep-font-size-section);
  font-weight: 600;
}
.result-loc {
  font-size: 13px;
  color: var(--ep-color-text-secondary);
}
.result-error {
  display: flex;
  gap: var(--ep-space-2);
  margin-bottom: 4px;
  font-size: 13px;
}
.error-cat {
  font-weight: 600;
  color: var(--ep-color-danger);
  white-space: nowrap;
}
.error-msg {
  color: var(--ep-color-text-secondary);
}
.builder-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--ep-space-3);
}
</style>
