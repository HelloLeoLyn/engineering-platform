<script setup lang="ts">
// Console Business Modules (V06-WORK-005): module modeling + Field Designer.
// Create via Manual / MySQL Import / Excel Import → Field Designer → Contract
// (module manifest YAML) → Generate through the existing pipeline.
import { onMounted, reactive, ref } from 'vue';
import { consoleApi, type FieldDef, type ModuleRecord, type MySqlConn } from '../api/console';

const modules = ref<ModuleRecord[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const drawer = ref(false);
const saving = ref(false);
const result = ref<{ status: string; files?: number; outputDir?: string; errors?: unknown[] } | null>(null);

// ---- module form (Manual) ----
const form = reactive({
  id: '',
  name: '',
  table: '',
  entity: '',
  description: '',
  features: ['list', 'search', 'create', 'edit', 'detail', 'disable'] as string[],
  enterprise: { permissions: true, dataScope: true, menu: true, dictionary: true, operationLog: true } as Record<string, boolean>,
});

// ---- Field Designer ----
const fields = ref<FieldDef[]>([]);

function addField(): void {
  fields.value.push({
    name: 'field' + (fields.value.length + 1),
    type: 'string',
    required: false,
    primaryKey: false,
    unique: false,
    length: 100,
    listVisible: true,
    searchable: false,
    formVisible: true,
    detailVisible: true,
  });
}

function removeField(idx: number): void {
  fields.value.splice(idx, 1);
}

function moveField(idx: number, dir: -1 | 1): void {
  const to = idx + dir;
  if (to < 0 || to >= fields.value.length) return;
  const tmp = fields.value[idx];
  fields.value[idx] = fields.value[to];
  fields.value[to] = tmp;
}

const fieldTypes = ['string', 'text', 'integer', 'long', 'decimal', 'boolean', 'date', 'datetime'];

// ---- MySQL Import ----
const mysql = reactive<MySqlConn & { table: string; tables: string[] }>({
  host: '127.0.0.1', port: 3306, database: '', username: 'root', password: '', table: '', tables: [],
});
const mysqlStep = ref<'conn' | 'tables'>('conn');
const mysqlTesting = ref(false);
const mysqlMsg = ref('');

async function testConnection(): Promise<void> {
  mysqlTesting.value = true;
  mysqlMsg.value = '';
  try {
    const r = await consoleApi.mysqlTest({ ...mysql });
    mysqlMsg.value = r.ok ? 'Connection OK' : 'Connection failed';
    if (r.ok) mysqlStep.value = 'tables';
  } catch (e) {
    mysqlMsg.value = String(e);
  } finally {
    mysqlTesting.value = false;
  }
}

async function loadTables(): Promise<void> {
  try {
    const r = await consoleApi.mysqlTables({ ...mysql });
    mysql.tables = r.tables;
  } catch (e) {
    mysqlMsg.value = String(e);
  }
}

async function importTable(): Promise<void> {
  try {
    const r = await consoleApi.mysqlImport({ ...mysql }, mysql.table);
    fields.value = r.fields.map((f) => ({ ...f, label: f.name, listVisible: true, searchable: false, formVisible: true, detailVisible: true }));
    drawer.value = false;
  } catch (e) {
    mysqlMsg.value = String(e);
  }
}

// ---- Excel Import ----
const excelMsg = ref('');
const excelInput = ref<HTMLInputElement | null>(null);

async function onExcelFile(ev: Event): Promise<void> {
  const file = (ev.target as HTMLInputElement).files?.[0];
  if (!file) return;
  try {
    const r = await consoleApi.excelImport(file);
    // row 0 = headers; rows after = field definitions
    const rows = r.rows.slice(1);
    const idx = (h: string) => r.rows[0].indexOf(h);
    fields.value = rows
      .filter((row) => row.length > 0 && row[0]?.trim())
      .map((row) => {
        const f: FieldDef = {
          name: row[idx('field')] ?? row[idx('column')] ?? '',
          type: row[idx('type')] || 'string',
          required: row[idx('required')] === 'true',
          primaryKey: row[idx('primaryKey')] === 'true',
          unique: row[idx('unique')] === 'true',
          length: row[idx('length')] ? Number(row[idx('length')]) : undefined,
          comment: row[idx('comment')] || undefined,
          searchable: row[idx('searchable')] === 'true',
          listVisible: row[idx('listVisible')] !== 'false',
          formVisible: row[idx('formVisible')] !== 'false',
          detailVisible: row[idx('detailVisible')] !== 'false',
          dictionary: row[idx('dictionary')] || undefined,
          label: row[idx('label')] || row[idx('field')],
        };
        return f;
      });
    excelMsg.value = `Imported ${fields.value.length} fields`;
  } catch (e) {
    excelMsg.value = String(e);
  } finally {
    if (excelInput.value) excelInput.value.value = '';
  }
}

function downloadTemplate(): void {
  window.open('/api/modules/import/excel/template', '_blank');
}

// ---- manifest assembly (existing Business Module Contract) ----
// NOTE: `business` lives at the TOP level of the module manifest (same shape
// as the v06 fixtures) — BusinessModuleResolver reads manifest.business.
function buildManifest(): Record<string, unknown> {
  return {
    schemaVersion: 1,
    module: {
      id: form.id,
      name: form.name,
      version: '1.0.0',
      type: 'business',
      description: form.description || 'Console-created business module',
    },
    compatibility: { platformVersion: '0.6' },
    business: {
      table: form.table || form.id.replace(/-/g, '_'),
      entity: {
        name: form.entity || form.name,
        fields: fields.value.map((f, i) => {
          const m: Record<string, unknown> = { name: f.name, type: f.type };
          if (f.required) m.required = true;
          if (f.primaryKey) m.primaryKey = true;
          if (f.unique) m.unique = true;
          if (f.length) m.length = f.length;
          if (f.precision) m.precision = f.precision;
          if (f.scale) m.scale = f.scale;
          if (f.defaultValue) m.defaultValue = f.defaultValue;
          if (f.comment) m.comment = f.comment;
          if (f.dictionary) { m.semantic = 'dictionary'; m.dictionary = f.dictionary; }
          const fe: Record<string, unknown> = { label: f.label || f.name, order: i };
          if (f.listVisible !== undefined) fe.listVisible = f.listVisible;
          if (f.searchable) fe.searchable = true;
          if (f.formVisible !== undefined) fe.formVisible = f.formVisible;
          if (f.detailVisible !== undefined) fe.detailVisible = f.detailVisible;
          m.frontend = fe;
          return m;
        }),
      },
      features: form.features,
      enterprise: form.enterprise,
      frontend: { route: '/' + form.id, label: form.name },
    },
  };
}

async function saveModule(): Promise<void> {
  if (!form.id || !form.name) {
    error.value = 'module id and name are required';
    return;
  }
  saving.value = true;
  error.value = null;
  result.value = null;
  try {
    await consoleApi.saveModule(buildManifest());
    await load();
    drawer.value = false;
  } catch (e) {
    error.value = String(e);
  } finally {
    saving.value = false;
  }
}

async function generateModule(id: string): Promise<void> {
  result.value = null;
  try {
    const r = await consoleApi.generateModule(id);
    result.value = r;
    await load();
  } catch (e) {
    result.value = { status: 'FAILED', errors: [{ message: String(e) }] };
  }
}

async function removeModule(id: string): Promise<void> {
  try {
    await consoleApi.deleteModule(id);
    await load();
  } catch (e) {
    error.value = String(e);
  }
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    modules.value = await consoleApi.modules();
  } catch (e) {
    error.value = String(e);
  } finally {
    loading.value = false;
  }
}

function errorMsg(e: unknown): string {
  return (e as { message?: string }).message ?? String(e);
}

onMounted(load);
</script>

<template>
  <div class="modules-page" data-testid="business-modules">
    <div class="page-head">
      <div>
        <p class="page-eyebrow">Engineering Platform</p>
        <h1 class="page-title">Business Modules</h1>
        <p class="page-desc">Model modules visually — the Field Designer produces the existing Business Module Contract, then generates through the existing pipeline.</p>
      </div>
      <el-button type="primary" data-testid="btn-create-module" @click="drawer = true">Create Module</el-button>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />

    <div class="panel">
      <el-table v-loading="loading" :data="modules" empty-text="No modules yet — create your first business module">
        <el-table-column prop="id" label="Module ID" min-width="160" />
        <el-table-column prop="name" label="Name" min-width="140" />
        <el-table-column label="Status" width="120">
          <template #default="{ row }">
            <span class="status-pill" :class="row.status === 'GENERATED' ? 'is-ok' : 'is-ready'">{{ row.status }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Contract" min-width="200">
          <template #default="{ row }">
            <el-tooltip :content="row.yaml || ''" placement="top">
              <span class="yaml-hint">{{ (row.yaml ?? '').split('\n').slice(0, 3).join(' · ') }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="Actions" width="220">
          <template #default="{ row }">
            <el-button size="small" type="primary" link data-testid="btn-generate" @click="generateModule(row.id)">Generate</el-button>
            <el-button size="small" link type="danger" @click="removeModule(row.id)">Delete</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="result" class="result-panel" :class="result.status === 'SUCCESS' ? 'is-ok' : 'is-fail'" data-testid="module-result">
      <template v-if="result.status === 'SUCCESS'">
        <h4 class="result-title">Module generated</h4>
        <p>Files: {{ result.files }} · Output: {{ result.outputDir }}</p>
      </template>
      <template v-else>
        <h4 class="result-title">Generation failed</h4>
        <div v-for="(e, i) in (result.errors ?? [])" :key="i" class="result-error">
          <span class="error-msg">{{ errorMsg(e) }}</span>
        </div>
      </template>
    </div>

    <!-- Create Module drawer -->
    <el-drawer v-model="drawer" title="Create Business Module" size="720px">
      <el-tabs>
        <el-tab-pane label="Manual">
          <el-form label-width="130px" class="module-form">
            <el-form-item label="Module ID" required>
              <el-input v-model="form.id" placeholder="e.g. customer-lite" data-testid="input-module-id" />
            </el-form-item>
            <el-form-item label="Module name" required>
              <el-input v-model="form.name" placeholder="e.g. CustomerLite" data-testid="input-module-name" />
            </el-form-item>
            <el-form-item label="Table name">
              <el-input v-model="form.table" placeholder="defaults to module id with underscores" />
            </el-form-item>
            <el-form-item label="Entity name">
              <el-input v-model="form.entity" placeholder="defaults to module name" />
            </el-form-item>
            <el-form-item label="Description">
              <el-input v-model="form.description" type="textarea" :rows="2" />
            </el-form-item>

            <el-form-item label="Features">
              <el-checkbox-group v-model="form.features">
                <el-checkbox v-for="f in ['list','search','create','edit','detail','disable']" :key="f" :value="f" :label="f" />
              </el-checkbox-group>
            </el-form-item>
            <el-form-item label="Enterprise">
              <el-checkbox-group v-model="form.enterprise">
                <el-checkbox v-for="e in ['permissions','dataScope','menu','dictionary','operationLog']" :key="e" :value="e" :label="e" />
              </el-checkbox-group>
            </el-form-item>

            <el-divider content-position="left">Field Designer</el-divider>
            <div class="field-toolbar">
              <el-button size="small" type="primary" plain @click="addField">+ Add field</el-button>
              <span class="field-count">{{ fields.length }} fields</span>
            </div>
            <el-table :data="fields" size="small" class="field-table" data-testid="field-designer">
              <el-table-column label="#" width="50">
                <template #default="{ $index }">{{ $index + 1 }}</template>
              </el-table-column>
              <el-table-column label="Field name" min-width="120">
                <template #default="{ row }"><el-input v-model="row.name" size="small" /></template>
              </el-table-column>
              <el-table-column label="Type" width="110">
                <template #default="{ row }">
                  <el-select v-model="row.type" size="small">
                    <el-option v-for="t in fieldTypes" :key="t" :value="t" :label="t" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="Req" width="60">
                <template #default="{ row }"><el-checkbox v-model="row.required" /></template>
              </el-table-column>
              <el-table-column label="PK" width="60">
                <template #default="{ row }"><el-checkbox v-model="row.primaryKey" /></template>
              </el-table-column>
              <el-table-column label="Uniq" width="60">
                <template #default="{ row }"><el-checkbox v-model="row.unique" /></template>
              </el-table-column>
              <el-table-column label="Len" width="70">
                <template #default="{ row }"><el-input-number v-model="row.length" :min="1" size="small" controls-position="right" /></template>
              </el-table-column>
              <el-table-column label="Label" min-width="100">
                <template #default="{ row }"><el-input v-model="row.label" size="small" /></template>
              </el-table-column>
              <el-table-column label="Search" width="60">
                <template #default="{ row }"><el-checkbox v-model="row.searchable" /></template>
              </el-table-column>
              <el-table-column label="Dict" width="110">
                <template #default="{ row }"><el-input v-model="row.dictionary" size="small" placeholder="code" /></template>
              </el-table-column>
              <el-table-column label="Ops" width="110">
                <template #default="{ row, $index }">
                  <el-button size="small" text @click="moveField($index, -1)">↑</el-button>
                  <el-button size="small" text @click="moveField($index, 1)">↓</el-button>
                  <el-button size="small" text type="danger" @click="removeField($index)">✕</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-form>
          <div class="drawer-actions">
            <el-button @click="drawer = false">Cancel</el-button>
            <el-button type="primary" :loading="saving" data-testid="btn-save-module" @click="saveModule">Save Module</el-button>
          </div>
        </el-tab-pane>

        <el-tab-pane label="MySQL Import">
          <el-form label-width="120px">
            <el-form-item label="Host"><el-input v-model="mysql.host" /></el-form-item>
            <el-form-item label="Port"><el-input-number v-model="mysql.port" :min="1" :max="65535" /></el-form-item>
            <el-form-item label="Database"><el-input v-model="mysql.database" /></el-form-item>
            <el-form-item label="Username"><el-input v-model="mysql.username" /></el-form-item>
            <el-form-item label="Password"><el-input v-model="mysql.password" type="password" show-password /></el-form-item>
            <el-form-item>
              <el-button :loading="mysqlTesting" @click="testConnection">Test Connection</el-button>
              <el-button @click="loadTables">Load Tables</el-button>
            </el-form-item>
            <el-form-item v-if="mysqlMsg" :label="mysqlStep === 'tables' ? '' : 'Status'">
              <span :class="mysqlMsg.includes('OK') ? 'ok-text' : 'err-text'">{{ mysqlMsg }}</span>
            </el-form-item>
            <el-form-item v-if="mysql.tables.length" label="Table">
              <el-select v-model="mysql.table" filterable placeholder="select table" style="width: 100%">
                <el-option v-for="t in mysql.tables" :key="t" :value="t" :label="t" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="mysql.table">
              <el-button type="primary" data-testid="btn-mysql-import" @click="importTable">Import Table → Field Designer</el-button>
            </el-form-item>
          </el-form>
          <p class="security-note">Password is used only for schema introspection — never written to contract, logs, or generated files.</p>
        </el-tab-pane>

        <el-tab-pane label="Excel Import">
          <div class="excel-pane">
            <p>Upload a .xlsx with the template header: column / field / type / label / required / primaryKey / unique / length / comment (+ optional searchable / listVisible / formVisible / detailVisible / dictionary).</p>
            <div class="excel-actions">
              <el-button @click="downloadTemplate">Download template</el-button>
              <input ref="excelInput" type="file" accept=".xlsx" style="display:none" data-testid="excel-input" @change="onExcelFile" />
              <el-button type="primary" @click="excelInput?.click()">Upload .xlsx</el-button>
            </div>
            <span v-if="excelMsg" class="ok-text">{{ excelMsg }}</span>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<style scoped>
.modules-page {
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
.panel {
  padding: var(--ep-space-4);
  background: var(--ep-color-surface);
  border-radius: var(--ep-radius-xl);
  box-shadow: var(--ep-shadow-sm);
}
.status-pill {
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 999px;
}
.status-pill.is-ok {
  background: var(--ep-accent-emerald-soft);
  color: var(--ep-accent-emerald);
}
.status-pill.is-ready {
  background: var(--ep-accent-indigo-soft);
  color: var(--ep-accent-indigo);
}
.yaml-hint {
  font-size: 12px;
  color: var(--ep-color-text-muted);
  font-family: monospace;
}
.result-panel {
  margin-top: var(--ep-space-4);
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
.result-error {
  font-size: 13px;
  color: var(--ep-color-text-secondary);
}
.module-form {
  max-width: none;
}
.field-toolbar {
  display: flex;
  align-items: center;
  gap: var(--ep-space-3);
  margin-bottom: var(--ep-space-3);
}
.field-count {
  font-size: 12px;
  color: var(--ep-color-text-muted);
}
.field-table {
  margin-bottom: var(--ep-space-4);
}
.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--ep-space-3);
  padding-top: var(--ep-space-4);
  border-top: 1px solid var(--ep-color-border);
}
.security-note {
  font-size: 12px;
  color: var(--ep-color-text-muted);
  margin-top: var(--ep-space-3);
}
.excel-pane p {
  color: var(--ep-color-text-secondary);
  line-height: 1.6;
}
.excel-actions {
  display: flex;
  gap: var(--ep-space-3);
  margin: var(--ep-space-4) 0;
}
.ok-text {
  color: var(--ep-accent-emerald);
  font-size: 13px;
}
.err-text {
  color: var(--ep-accent-rose);
  font-size: 13px;
}
</style>
