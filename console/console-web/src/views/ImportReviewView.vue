<script setup lang="ts">
// V07-WORK-005 — Import Review.
// External Metadata → Candidate → Human Confirmation → Business Module Contract V2.
// MySQL multi-table discovery / Excel discovery produce module drafts with
// candidates (FIELD / REFERENCE / RELATION / SEMANTIC). The user reviews each
// candidate: Accept / Edit / Ignore. Only CONFIRMED candidates may enter the
// formal contract — resolution happens via the backend review/resolve endpoint
// (never client-side manifest assembly).
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { consoleApi, type ImportCandidate, type ImportDraft, type MySqlConn } from '../api/console';
import {
  acceptAllDecisions,
  confirmable,
  describe,
  effectiveStatus,
  groupByType,
  STATUS_LABEL,
  TYPE_LABEL,
  type DecisionMap,
  type EditMap,
} from '../utils/importReview';
import { parseManifest, type ModuleBuilderState } from '../utils/moduleContract';

const router = useRouter();

// ---- MySQL connection ----
const mysql = reactive<MySqlConn & { tables: string[] }>({
  host: '127.0.0.1', port: 3306, database: 'ep_import_proof', username: 'root', password: '123456', tables: [],
});
const mysqlMsg = ref('');
const mysqlBusy = ref(false);

// ---- selection & mapping ----
const selectedTables = ref<string[]>([]);
const mapping = reactive<Record<string, { moduleId: string; entity: string }>>({});

// ---- discovery result ----
const drafts = ref<ImportDraft[]>([]);
const discovered = ref(false);
const discoverBusy = ref(false);
const error = ref<string | null>(null);

// ---- review state ----
const decisions = ref<DecisionMap>({});
const edits = ref<EditMap>({});
const knownTargets = ref<string[]>([]); // imported drafts + existing project modules

// ---- edit dialog ----
const editCandidate = ref<ImportCandidate | null>(null);
const editDraftTable = ref('');
const editPayload = ref<Record<string, unknown>>({});
const editDialogOpen = ref(false);
// SEMANTIC enum values editor (one line per value,label)
const enumValuesText = ref('');

// ---- Excel ----
const excelModuleId = ref('excel-module');
const excelEntity = ref('ExcelModule');
const excelMsg = ref('');
const excelInput = ref<HTMLInputElement | null>(null);

const activeTab = ref('mysql');

async function fetchTargets(): Promise<string[]> {
  try {
    const res = await fetch('/api/modules/targets');
    if (!res.ok) return [];
    const list = (await res.json()) as { id: string }[];
    return list.map((t) => t.id);
  } catch {
    return [];
  }
}

onMounted(async () => {
  knownTargets.value = await fetchTargets();
});

// ---- MySQL flow ----
async function testConnection(): Promise<void> {
  mysqlBusy.value = true;
  mysqlMsg.value = '';
  try {
    const r = await consoleApi.mysqlTest({ ...mysql });
    mysqlMsg.value = r.ok ? 'Connection OK' : 'Connection failed';
  } catch (e) {
    mysqlMsg.value = String(e);
  } finally {
    mysqlBusy.value = false;
  }
}

async function loadTables(): Promise<void> {
  mysqlBusy.value = true;
  mysqlMsg.value = '';
  try {
    const r = await consoleApi.mysqlTables({ ...mysql });
    mysql.tables = r.tables;
    selectedTables.value = [];
    // derive default table → module mapping
    for (const t of r.tables) {
      mapping[t] = { moduleId: t.replace(/_/g, '-'), entity: toPascal(t) };
    }
    mysqlMsg.value = `${r.tables.length} tables loaded`;
  } catch (e) {
    mysqlMsg.value = String(e);
  } finally {
    mysqlBusy.value = false;
  }
}

function toPascal(s: string): string {
  return s.split('_').filter(Boolean).map((p) => p[0].toUpperCase() + p.slice(1)).join('');
}

async function discover(): Promise<void> {
  error.value = null;
  if (selectedTables.value.length === 0) {
    error.value = 'Select at least one table';
    return;
  }
  discoverBusy.value = true;
  try {
    const mappingObj: Record<string, { moduleId: string; entity: string }> = {};
    for (const t of selectedTables.value) mappingObj[t] = { ...mapping[t] };
    const r = await consoleApi.mysqlDiscover({ ...mysql }, selectedTables.value, mappingObj);
    drafts.value = r.drafts;
    discovered.value = true;
    decisions.value = {};
    edits.value = {};
    // imported module ids become known targets for confirmability
    knownTargets.value = [...new Set([...knownTargets.value, ...r.drafts.map((d) => d.moduleId)])];
  } catch (e) {
    error.value = String(e);
  } finally {
    discoverBusy.value = false;
  }
}

// ---- Excel flow ----
async function onExcelFile(ev: Event): Promise<void> {
  const file = (ev.target as HTMLInputElement).files?.[0];
  if (!file) return;
  excelMsg.value = '';
  try {
    const r = await consoleApi.excelDiscover(file, excelModuleId.value, excelEntity.value);
    drafts.value = [r.draft];
    discovered.value = true;
    decisions.value = {};
    edits.value = {};
    knownTargets.value = [...new Set([...knownTargets.value, r.draft.moduleId])];
    activeTab.value = 'excel';
    excelMsg.value = 'Excel parsed → review candidates below';
  } catch (e) {
    excelMsg.value = String(e);
  } finally {
    if (excelInput.value) excelInput.value.value = '';
  }
}

// ---- review actions ----
function accept(c: ImportCandidate): void {
  decisions.value = { ...decisions.value, [c.id]: 'accept' };
}
function ignore(c: ImportCandidate): void {
  decisions.value = { ...decisions.value, [c.id]: 'ignore' };
}
function openEdit(c: ImportCandidate, table: string): void {
  editCandidate.value = c;
  editDraftTable.value = table;
  editPayload.value = JSON.parse(JSON.stringify(c.payload ?? {}));
  // prefill enum values editor from existing payload (if any)
  const existing = (editPayload.value.enumValues ?? editPayload.value.values) as unknown;
  enumValuesText.value = Array.isArray(existing)
    ? (existing as { value?: string; label?: string }[])
        .map((v) => (v.label ? `${v.value},${v.label}` : String(v.value ?? '')))
        .join('\n')
    : '';
  editDialogOpen.value = true;
}
function saveEdit(): void {
  if (!editCandidate.value) return;
  const id = editCandidate.value.id;
  const payload = { ...editPayload.value };
  // SEMANTIC enum: parse the textarea into [{value,label}] entries
  if (editCandidate.value.type === 'SEMANTIC' && payload.semantic === 'enum') {
    const entries = enumValuesText.value
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        const comma = line.indexOf(',');
        if (comma > 0) {
          return { value: line.slice(0, comma).trim(), label: line.slice(comma + 1).trim() };
        }
        return { value: line, label: line };
      });
    if (entries.length > 0) payload.enumValues = entries;
    delete payload.values;
  }
  // REFERENCE: parse comma-separated search fields
  if (editCandidate.value.type === 'REFERENCE') {
    const text = String(payload.searchFieldsText ?? '');
    payload.searchFields = text.split(',').map((s) => s.trim()).filter(Boolean);
    delete payload.searchFieldsText;
  }
  edits.value = { ...edits.value, [id]: payload };
  decisions.value = { ...decisions.value, [id]: 'accept' }; // edit implies accept
  editDialogOpen.value = false;
}

function acceptAll(): void {
  decisions.value = { ...decisions.value, ...acceptAllDecisions(drafts.value, knownTargets.value) };
}

function resetReview(): void {
  decisions.value = {};
  edits.value = {};
}

const confirmedTotal = computed(() => {
  let n = 0;
  for (const d of drafts.value) {
    for (const c of d.candidates) {
      if (effectiveStatus(c, decisions.value) === 'CONFIRMED') n++;
    }
  }
  return n;
});
const candidateTotal = computed(() => drafts.value.reduce((n, d) => n + d.candidates.length, 0));

// ---- resolve → Builder hand-off ----
async function openInBuilder(draft: ImportDraft): Promise<void> {
  try {
    const r = await consoleApi.reviewResolve(draft, decisions.value, edits.value);
    const manifest = r.manifest as Record<string, any>;
    const state: ModuleBuilderState = parseManifest(manifest);
    // include imported fields even when no candidate was confirmed (empty contract guard)
    sessionStorage.setItem('module-builder-draft-state', JSON.stringify({ state, table: draft.table }));
    router.push({ path: '/modules/builder', query: { draft: 'state' } });
  } catch (e) {
    error.value = `Resolve failed: ${String(e)}`;
  }
}

function groupOf(draft: ImportDraft) {
  return groupByType(draft.candidates);
}

const groups = [
  { key: 'FIELD', label: 'Detected Fields', hint: 'DETECTED — database column facts' },
  { key: 'REFERENCE', label: 'Detected References', hint: 'DETECTED — real FK; labelField/searchFields need your confirmation' },
  { key: 'RELATION', label: 'Detected Relations', hint: 'FK relations are DETECTED; reverse ONE_TO_MANY are SUGGESTED' },
  { key: 'SEMANTIC', label: 'Suggested Semantics', hint: 'SUGGESTED — heuristic only, never auto-confirmed' },
] as const;

function statusClass(status: string): string {
  return 'st-' + status.toLowerCase();
}
</script>

<template>
  <div class="review-page" data-testid="import-review">
    <div class="page-head">
      <div>
        <p class="page-eyebrow">Engineering Platform · Import Review</p>
        <h1 class="page-title">Import Review</h1>
        <p class="page-desc">
          External Metadata → <strong>Candidate</strong> → Human Confirmation → Business Module Contract V2.
          Only <strong>CONFIRMED</strong> candidates enter the contract — nothing is written automatically.
        </p>
      </div>
      <div class="head-actions">
        <el-button @click="router.push('/modules')">Back</el-button>
      </div>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" style="margin-bottom: 16px" />

    <el-tabs v-model="activeTab">
      <!-- ================= MySQL ================= -->
      <el-tab-pane label="MySQL Import" name="mysql">
        <el-form label-width="120px" class="conn-form">
          <div class="conn-grid">
            <el-form-item label="Host"><el-input v-model="mysql.host" /></el-form-item>
            <el-form-item label="Port"><el-input-number v-model="mysql.port" :min="1" :max="65535" /></el-form-item>
            <el-form-item label="Database"><el-input v-model="mysql.database" /></el-form-item>
            <el-form-item label="Username"><el-input v-model="mysql.username" /></el-form-item>
            <el-form-item label="Password"><el-input v-model="mysql.password" type="password" show-password /></el-form-item>
          </div>
          <el-form-item>
            <el-button :loading="mysqlBusy" data-testid="btn-test-conn" @click="testConnection">Test Connection</el-button>
            <el-button :loading="mysqlBusy" data-testid="btn-load-tables" @click="loadTables">Load Tables</el-button>
          </el-form-item>
          <el-form-item v-if="mysqlMsg">
            <span :class="mysqlMsg.includes('OK') || mysqlMsg.includes('loaded') ? 'ok-text' : 'err-text'">{{ mysqlMsg }}</span>
          </el-form-item>
        </el-form>

        <div v-if="mysql.tables.length" class="table-select" data-testid="table-select">
          <h3 class="block-title">Select tables to import</h3>
          <p class="block-hint">Multiple related tables create cross-module relation candidates (e.g. purchase_order.supplier_id → supplier.id).</p>
          <el-checkbox-group v-model="selectedTables" class="table-check-grid">
            <el-checkbox v-for="t in mysql.tables" :key="t" :value="t" :label="t" class="table-check" />
          </el-checkbox-group>
          <div v-if="selectedTables.length" class="mapping-grid">
            <div v-for="t in selectedTables" :key="t" class="mapping-card">
              <span class="mapping-table">{{ t }}</span>
              <el-input v-model="mapping[t].moduleId" size="small" placeholder="module id">
                <template #prepend>module</template>
              </el-input>
              <el-input v-model="mapping[t].entity" size="small" placeholder="entity">
                <template #prepend>entity</template>
              </el-input>
            </div>
          </div>
          <el-button type="primary" :loading="discoverBusy" data-testid="btn-discover" style="margin-top: 12px" @click="discover">
            Import & Discover ({{ selectedTables.length }} tables)
          </el-button>
          <p class="security-note">Password is used only for schema introspection (information_schema) — never written to contract, candidates, logs, or generated files.</p>
        </div>
      </el-tab-pane>

      <!-- ================= Excel ================= -->
      <el-tab-pane label="Excel Import" name="excel">
        <div class="excel-pane">
          <p>Upload a .xlsx with the V0.7 template (optional columns: referenceTarget / referenceValueField / referenceLabelField / relationType / relationTarget / mappedBy / composition). Explicit input is marked DETECTED_FROM_EXPLICIT_INPUT but still requires review.</p>
          <div class="excel-row">
            <el-form label-width="90px" inline>
              <el-form-item label="Module ID"><el-input v-model="excelModuleId" style="width: 180px" /></el-form-item>
              <el-form-item label="Entity"><el-input v-model="excelEntity" style="width: 180px" /></el-form-item>
            </el-form>
          </div>
          <div class="excel-actions">
            <el-button @click="window.open('/api/modules/import/excel/template-v2', '_blank')">Download V0.7 template</el-button>
            <el-button @click="window.open('/api/modules/import/excel/template', '_blank')">Download V0.6 template</el-button>
            <input ref="excelInput" type="file" accept=".xlsx" style="display:none" data-testid="excel-input" @change="onExcelFile" />
            <el-button type="primary" @click="excelInput?.click()">Upload .xlsx</el-button>
          </div>
          <span v-if="excelMsg" class="ok-text">{{ excelMsg }}</span>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ================= Review ================= -->
    <div v-if="discovered && drafts.length" class="review-area" data-testid="review-area">
      <div class="review-head">
        <div>
          <h2 class="block-title">Review Candidates</h2>
          <p class="block-hint">
            <span class="status-count">Confirmed: <strong class="ok-text">{{ confirmedTotal }}</strong> / {{ candidateTotal }}</span>
            — Accept / Edit / Ignore each candidate. Only CONFIRMED candidates are serialized into the contract.
          </p>
        </div>
        <div class="head-actions">
          <el-button size="small" @click="resetReview">Reset</el-button>
          <el-button size="small" type="primary" plain data-testid="btn-accept-all" @click="acceptAll">Accept all confirmable</el-button>
        </div>
      </div>

      <div v-for="draft in drafts" :key="draft.table" class="draft-card" :data-testid="`draft-${draft.table}`">
        <div class="draft-head">
          <span class="draft-table">{{ draft.table }}</span>
          <el-tag size="small" type="info">{{ draft.moduleId }}</el-tag>
          <el-tag size="small">{{ draft.entity }}</el-tag>
          <el-button size="small" type="primary" class="builder-btn" data-testid="btn-open-builder" @click="openInBuilder(draft)">
            Open in Builder →
          </el-button>
        </div>

        <div v-for="g in groups" :key="g.key" class="cand-group" :data-testid="`group-${g.key.toLowerCase()}`">
          <div class="cand-group-head">
            <span class="cand-group-label">{{ g.label }}</span>
            <span class="cand-group-count">{{ groupOf(draft)[g.key].length }}</span>
            <span class="cand-group-hint">{{ g.hint }}</span>
          </div>
          <div v-if="groupOf(draft)[g.key].length === 0" class="cand-empty">—</div>
          <div v-for="c in groupOf(draft)[g.key]" :key="c.id" class="cand-row" :data-testid="`cand-${c.id}`">
            <span class="cand-type" :class="'tp-' + c.type.toLowerCase()">{{ TYPE_LABEL[c.type] }}</span>
            <span class="cand-desc">{{ describe(c) }}</span>
            <span class="cand-source">{{ c.source }}</span>
            <span class="cand-status" :class="statusClass(effectiveStatus(c, decisions))">
              {{ STATUS_LABEL[effectiveStatus(c, decisions)] }}
            </span>
            <span v-if="c.note" class="cand-note" :title="c.note">{{ c.note.length > 60 ? c.note.slice(0, 60) + '…' : c.note }}</span>
            <span class="cand-ops">
              <el-button size="small" type="success" plain :disabled="!confirmable(c, knownTargets)" data-testid="btn-accept" @click="accept(c)">Accept</el-button>
              <el-button size="small" data-testid="btn-edit" @click="openEdit(c, draft.table)">Edit</el-button>
              <el-button size="small" type="danger" plain data-testid="btn-ignore" @click="ignore(c)">Ignore</el-button>
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- ================= Edit dialog ================= -->
    <el-dialog v-model="editDialogOpen" :title="`Edit ${editCandidate ? TYPE_LABEL[editCandidate.type] : ''} candidate`" width="560px" data-testid="edit-dialog">
      <el-form v-if="editCandidate" label-width="160px" size="small">
        <template v-if="editCandidate.type === 'FIELD'">
          <el-form-item label="Name"><el-input v-model="editPayload.name" /></el-form-item>
          <el-form-item label="Type">
            <el-select v-model="editPayload.type">
              <el-option v-for="t in ['string','text','integer','long','decimal','boolean','date','datetime']" :key="t" :value="t" :label="t" />
            </el-select>
          </el-form-item>
          <el-form-item label="Required"><el-switch v-model="editPayload.required" /></el-form-item>
          <el-form-item label="Primary key"><el-switch v-model="editPayload.primaryKey" /></el-form-item>
          <el-form-item label="Unique"><el-switch v-model="editPayload.unique" /></el-form-item>
          <el-form-item label="Length"><el-input-number v-model="editPayload.length" :min="1" /></el-form-item>
          <el-form-item label="Comment"><el-input v-model="editPayload.comment" /></el-form-item>
        </template>

        <template v-else-if="editCandidate.type === 'REFERENCE'">
          <el-form-item label="Field"><el-input :model-value="String(editPayload.field ?? '')" disabled /></el-form-item>
          <el-form-item label="Target module" required>
            <el-select v-model="editPayload.targetModule" filterable allow-create style="width: 100%">
              <el-option v-for="t in knownTargets" :key="t" :value="t" :label="t" />
            </el-select>
          </el-form-item>
          <el-form-item label="Value field"><el-input v-model="editPayload.valueField" /></el-form-item>
          <el-form-item label="Label field"><el-input v-model="editPayload.labelField" placeholder="required — DB cannot decide this" /></el-form-item>
          <el-form-item label="Search fields"><el-input v-model="editPayload.searchFieldsText" placeholder="comma separated" /></el-form-item>
        </template>

        <template v-else-if="editCandidate.type === 'RELATION'">
          <el-form-item label="Name"><el-input v-model="editPayload.name" /></el-form-item>
          <el-form-item label="Type">
            <el-select v-model="editPayload.type">
              <el-option value="MANY_TO_ONE" label="MANY_TO_ONE" />
              <el-option value="ONE_TO_MANY" label="ONE_TO_MANY" />
              <el-option value="ONE_TO_ONE" label="ONE_TO_ONE" />
            </el-select>
          </el-form-item>
          <el-form-item label="Target module" required>
            <el-select v-model="editPayload.targetModule" filterable allow-create style="width: 100%">
              <el-option v-for="t in knownTargets" :key="t" :value="t" :label="t" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="editPayload.type === 'MANY_TO_ONE' || editPayload.type === 'ONE_TO_ONE'" label="Local field">
            <el-input v-model="editPayload.localField" />
          </el-form-item>
          <el-form-item v-if="editPayload.type === 'MANY_TO_ONE' || editPayload.type === 'ONE_TO_ONE'" label="Target field">
            <el-input v-model="editPayload.targetField" />
          </el-form-item>
          <el-form-item v-if="editPayload.type === 'ONE_TO_MANY'" label="Mapped by">
            <el-input v-model="editPayload.mappedBy" />
          </el-form-item>
          <el-form-item label="Required"><el-switch v-model="editPayload.required" /></el-form-item>
          <el-form-item label="Composition">
            <el-switch v-model="editPayload.composition" />
            <span class="compose-hint">composition=true must be confirmed by a human — the database cannot prove it</span>
          </el-form-item>
        </template>

        <template v-else-if="editCandidate.type === 'SEMANTIC'">
          <el-form-item label="Field"><el-input :model-value="String(editPayload.field ?? '')" disabled /></el-form-item>
          <el-form-item label="Semantic">
            <el-select v-model="editPayload.semantic">
              <el-option value="money" label="money" />
              <el-option value="enum" label="enum" />
              <el-option value="reference" label="reference" />
              <el-option value="dictionary" label="dictionary" />
              <el-option value="status" label="status" />
              <el-option value="none" label="none" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="editPayload.semantic === 'enum'" label="Enum values">
            <el-input
              v-model="enumValuesText"
              type="textarea"
              :rows="4"
              placeholder="one per line: VALUE,Label — e.g.&#10;DRAFT,Draft&#10;CONFIRMED,Confirmed"
            />
          </el-form-item>
          <el-form-item v-if="editPayload.semantic === 'reference'" label="Target module">
            <el-input v-model="editPayload.targetModule" placeholder="module id (e.g. supplier)" />
          </el-form-item>
          <el-form-item label="Reason"><el-input :model-value="String(editPayload.reason ?? '')" disabled type="textarea" :rows="2" /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="editDialogOpen = false">Cancel</el-button>
        <el-button type="primary" data-testid="btn-save-edit" @click="saveEdit">Save (accept)</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.review-page {
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
  line-height: 1.6;
}
.head-actions {
  display: flex;
  gap: var(--ep-space-3);
}
.conn-form {
  max-width: 860px;
}
.conn-grid {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 0 var(--ep-space-4);
}
.block-title {
  margin: 0 0 8px;
  font-size: var(--ep-font-size-section);
  font-weight: 700;
}
.block-hint {
  margin: 0 0 var(--ep-space-3);
  color: var(--ep-color-text-muted);
  font-size: 13px;
  line-height: 1.6;
}
.table-select,
.review-area {
  padding: var(--ep-space-4);
  background: var(--ep-color-surface);
  border-radius: var(--ep-radius-xl);
  box-shadow: var(--ep-shadow-sm);
}
.table-check-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--ep-space-2);
}
.table-check {
  padding: 10px 12px;
  border: 1px solid var(--ep-color-border);
  border-radius: var(--ep-radius-lg);
  margin-right: 0 !important;
}
.mapping-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--ep-space-3);
  margin-top: var(--ep-space-4);
}
.mapping-card {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  border: 1px dashed var(--ep-color-border);
  border-radius: var(--ep-radius-lg);
}
.mapping-table {
  font-family: monospace;
  font-size: 12px;
  color: var(--ep-accent-indigo);
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
  margin: var(--ep-space-3) 0;
}
.excel-row {
  margin-top: var(--ep-space-2);
}
.ok-text {
  color: var(--ep-accent-emerald);
  font-size: 13px;
}
.err-text {
  color: var(--ep-accent-rose);
  font-size: 13px;
}
.review-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--ep-space-4);
}
.status-count {
  font-size: 13px;
  color: var(--ep-color-text-secondary);
}
.draft-card {
  margin-bottom: var(--ep-space-4);
  padding: var(--ep-space-4);
  border: 1px solid var(--ep-color-border);
  border-radius: var(--ep-radius-lg);
}
.draft-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: var(--ep-space-3);
}
.draft-table {
  font-family: monospace;
  font-weight: 700;
  font-size: 14px;
}
.builder-btn {
  margin-left: auto;
}
.cand-group {
  margin-bottom: var(--ep-space-3);
}
.cand-group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.cand-group-label {
  font-weight: 700;
  font-size: 13px;
}
.cand-group-count {
  font-size: 11px;
  background: var(--ep-color-surface-tinted);
  border-radius: 999px;
  padding: 1px 8px;
}
.cand-group-hint {
  font-size: 11px;
  color: var(--ep-color-text-muted);
}
.cand-empty {
  color: var(--ep-color-text-muted);
  font-size: 12px;
  padding: 4px 0;
}
.cand-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--ep-color-border);
  flex-wrap: wrap;
}
.cand-type {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  width: 76px;
  text-align: center;
}
.tp-field { background: var(--ep-accent-indigo-soft); color: var(--ep-accent-indigo); }
.tp-reference { background: var(--ep-accent-emerald-soft); color: var(--ep-accent-emerald); }
.tp-relation { background: var(--ep-accent-amber-soft); color: var(--ep-accent-amber); }
.tp-semantic { background: var(--ep-accent-rose-soft); color: var(--ep-accent-rose); }
.cand-desc {
  font-family: monospace;
  font-size: 12px;
  min-width: 220px;
}
.cand-source {
  font-size: 11px;
  color: var(--ep-color-text-muted);
  background: var(--ep-color-surface-tinted);
  border-radius: 4px;
  padding: 1px 6px;
}
.cand-status {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
}
.st-detected { background: var(--ep-accent-indigo-soft); color: var(--ep-accent-indigo); }
.st-suggested { background: var(--ep-accent-amber-soft); color: var(--ep-accent-amber); }
.st-confirmed { background: var(--ep-accent-emerald-soft); color: var(--ep-accent-emerald); }
.st-ignored { background: var(--ep-color-surface-tinted); color: var(--ep-color-text-muted); }
.cand-note {
  font-size: 11px;
  color: var(--ep-color-text-muted);
  flex: 1;
  min-width: 160px;
}
.cand-ops {
  display: flex;
  gap: 4px;
  margin-left: auto;
}
.compose-hint {
  font-size: 11px;
  color: var(--ep-accent-amber);
  margin-left: 8px;
}
</style>
