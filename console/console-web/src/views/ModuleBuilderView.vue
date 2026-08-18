<script setup lang="ts">
// Business Module Builder 2.0 (V07-WORK-004).
// 7 sections: Basic / Fields / Relations / Features / Enterprise / Frontend /
// Contract Preview. Pure state → Contract V2 manifest (buildManifest) — the
// SAME manifest the existing generator consumes. No second contract model.
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { consoleApi, type FieldDef } from '../api/console';
import {
  allowedUis,
  buildManifest,
  defaultField,
  defaultModuleBuilderState,
  parseManifest,
  suggestUi,
  validateModuleState,
  type DataType,
  type EnumValueDesign,
  type FieldDesign,
  type ModuleBuilderState,
  type RelationDesign,
  type Semantic,
  type UiComponent,
} from '../utils/moduleContract';

const route = useRoute();
const router = useRouter();
const editingId = route.params.id ? String(route.params.id) : undefined;

const state = ref<ModuleBuilderState>(defaultModuleBuilderState());
const loading = ref(false);
const saving = ref(false);
const savedOk = ref(false);
const error = ref<string | null>(null);
const activeTab = ref('basic');
const issues = computed(() => validateModuleState(state.value));
const issuesBy = (section: string) => issues.value.filter((i) => i.section === section);

// reference targets: module catalog (id + fields) for the Reference Designer
const targets = ref<{ id: string; fields: { name: string; type: string }[] }[]>([]);
const targetFieldsOf = (target: string) => targets.value.find((t) => t.id === target)?.fields ?? [];

// ---- data types / semantics / ui ----
const dataTypes: DataType[] = ['string', 'text', 'integer', 'long', 'decimal', 'boolean', 'date', 'datetime'];
const semantics: Semantic[] = ['none', 'money', 'enum', 'dictionary', 'status', 'reference', 'image', 'file', 'richtext'];
const relationTypes = ['MANY_TO_ONE', 'ONE_TO_MANY', 'ONE_TO_ONE', 'MANY_TO_MANY'];
const featuresOptions = ['list', 'search', 'create', 'edit', 'detail', 'enable', 'disable'];
const enterpriseOptions = ['permissions', 'dataScope', 'menu', 'dictionary', 'operationLog'];

// ---- preview (must come from the SAME serialization the save uses) ----
const yamlPreview = ref('');
const previewError = ref<string | null>(null);

async function refreshPreview(): Promise<void> {
  try {
    const manifest = buildManifest(state.value);
    const r = await consoleApi.preview(manifest as Record<string, unknown>);
    yamlPreview.value = r.yaml;
    previewError.value = null;
  } catch (e) {
    previewError.value = String(e);
  }
}

watch(
  () => state.value,
  () => {
    if (activeTab.value === 'preview') void refreshPreview();
  },
  { deep: true },
);

// ---- load ----
const DRAFT_KEY = 'module-builder-draft-fields';

onMounted(async () => {
  loading.value = true;
  try {
    const meta = await consoleApi.meta();
    const catalogIds = (meta.modules ?? []).map((m) => m.id);
    const tg = await fetchTargets();
    targets.value = tg;
    // keep registry modules without stored fields as selectable targets
    for (const id of catalogIds) {
      if (!targets.value.some((t) => t.id === id)) {
        targets.value.push({ id, fields: [] });
      }
    }
    // MySQL / Excel import hand-off: draft fields → Builder field list
    if (route.query.draft === '1') {
      const raw = sessionStorage.getItem(DRAFT_KEY);
      sessionStorage.removeItem(DRAFT_KEY);
      if (raw) {
        try {
          const draft = JSON.parse(raw) as FieldDef[];
          state.value.fields = draft.map((f, i) => ({
            ...defaultField(i),
            name: f.name || 'field' + (i + 1),
            type: (['string', 'text', 'integer', 'long', 'decimal', 'boolean', 'date', 'datetime'].includes(String(f.type))
              ? String(f.type)
              : 'string') as DataType,
            semantic: f.dictionary ? 'dictionary' : 'none',
            dictionary: f.dictionary,
            required: f.required === true,
            primaryKey: f.primaryKey === true,
            unique: f.unique === true,
            length: f.length ?? undefined,
            precision: f.precision ?? undefined,
            scale: f.scale ?? undefined,
            defaultValue: f.defaultValue,
            comment: f.comment,
            listVisible: f.listVisible !== false,
            searchable: f.searchable === true,
            formVisible: f.formVisible !== false,
            detailVisible: f.detailVisible !== false,
            order: i,
          }));
          state.value.fields.forEach((f) => (f.ui = suggestUi(f.type, f.semantic)));
          activeTab.value = 'fields';
        } catch {
          // malformed draft → ignore
        }
      }
    }
    // V07-WORK-005: Import Review hand-off — full Builder state (fields + relations + semantics)
    if (route.query.draft === 'state') {
      const raw = sessionStorage.getItem('module-builder-draft-state');
      sessionStorage.removeItem('module-builder-draft-state');
      if (raw) {
        try {
          const parsed = JSON.parse(raw) as { state: ModuleBuilderState; table?: string };
          if (parsed.state && Array.isArray(parsed.state.fields)) {
            state.value = parsed.state;
            if (parsed.table && !state.value.table) state.value.table = parsed.table;
            activeTab.value = 'fields';
          }
        } catch {
          // malformed state → ignore
        }
      }
    }
    if (editingId) {
      const c = await consoleApi.moduleContract(editingId);
      state.value = parseManifest(c.manifest as Record<string, any>);
    }
    await refreshPreview();
  } catch (e) {
    error.value = String(e);
  } finally {
    loading.value = false;
  }
});

async function fetchTargets(): Promise<{ id: string; fields: { name: string; type: string }[] }[]> {
  try {
    const res = await fetch('/api/modules/targets');
    if (!res.ok) return [];
    return (await res.json()) as { id: string; fields: { name: string; type: string }[] }[];
  } catch {
    return [];
  }
}

// ---- save ----
async function save(): Promise<void> {
  error.value = null;
  savedOk.value = false;
  const manifest = buildManifest(state.value);
  try {
    const check = await consoleApi.moduleValidate(manifest);
    if (!check.valid) {
      error.value = (check.errors ?? []).map((e) => `${e.category}: ${e.message}`).join('\n');
      return;
    }
    await consoleApi.saveModule(manifest);
    savedOk.value = true;
    await refreshPreview();
  } catch (e) {
    error.value = String(e);
  }
}

// ---- fields ----
const selectedField = ref<FieldDesign | null>(null);

function addField(): void {
  const f = defaultField(state.value.fields.length);
  state.value.fields.push(f);
  selectedField.value = f;
  activeTab.value = 'fields';
}

function removeField(idx: number): void {
  state.value.fields.splice(idx, 1);
  if (selectedField.value && state.value.fields.indexOf(selectedField.value) < 0) selectedField.value = null;
}

function moveField(idx: number, dir: -1 | 1): void {
  const to = idx + dir;
  if (to < 0 || to >= state.value.fields.length) return;
  const arr = state.value.fields;
  [arr[idx], arr[to]] = [arr[to], arr[idx]];
  arr.forEach((f, i) => (f.order = i));
}

function onFieldTypeChange(f: FieldDesign): void {
  f.ui = suggestUi(f.type, f.semantic);
}

function onFieldSemanticChange(f: FieldDesign): void {
  f.ui = suggestUi(f.type, f.semantic);
  if (f.semantic === 'enum' && !f.enumValues) f.enumValues = [];
  if (f.semantic === 'reference' && !f.reference) {
    f.reference = { target: '', valueField: 'id', labelField: 'name', searchFields: [] };
  }
}

function addEnumValue(f: FieldDesign): void {
  if (!f.enumValues) f.enumValues = [];
  f.enumValues.push({ value: '', label: '' });
}

function removeEnumValue(f: FieldDesign, idx: number): void {
  f.enumValues?.splice(idx, 1);
}

function moveEnumValue(f: FieldDesign, idx: number, dir: -1 | 1): void {
  const to = idx + dir;
  if (!f.enumValues) return;
  if (to < 0 || to >= f.enumValues.length) return;
  [f.enumValues[idx], f.enumValues[to]] = [f.enumValues[to], f.enumValues[idx]];
}

// ---- relations ----
const selectedRelation = ref<RelationDesign | null>(null);

function addRelation(): void {
  const r: RelationDesign = {
    name: 'rel' + (state.value.relations.length + 1),
    type: 'MANY_TO_ONE',
    target: '',
    localField: '',
    targetField: 'id',
    required: false,
    composition: false,
  };
  state.value.relations.push(r);
  selectedRelation.value = r;
}

function removeRelation(idx: number): void {
  state.value.relations.splice(idx, 1);
}

function moveRelation(idx: number, dir: -1 | 1): void {
  const to = idx + dir;
  if (to < 0 || to >= state.value.relations.length) return;
  const arr = state.value.relations;
  [arr[idx], arr[to]] = [arr[to], arr[idx]];
}

// master/detail: ONE_TO_MANY + composition → detail module lifecycle owned by master
function isMasterDetail(r: RelationDesign): boolean {
  return r.type === 'ONE_TO_MANY' && r.composition;
}

// dependency summary
const dependencySummary = computed(() => {
  const refs: string[] = [];
  const compositions: string[] = [];
  for (const f of state.value.fields) {
    if (f.semantic === 'reference' && f.reference?.target) refs.push(f.reference.target);
  }
  for (const r of state.value.relations) {
    if (r.target) {
      if (isMasterDetail(r)) compositions.push(r.target);
      else refs.push(r.target);
    }
  }
  return { refs: [...new Set(refs)], compositions: [...new Set(compositions)] };
});

// ---- helpers ----
function labelOf(f: FieldDesign): string {
  return f.name || '?';
}

function goBack(): void {
  router.push({ path: '/modules' });
}
</script>

<template>
  <div class="builder-page" data-testid="module-builder">
    <div class="page-head">
      <div>
        <p class="page-eyebrow">Engineering Platform · Business Module Builder 2.0</p>
        <h1 class="page-title">{{ editingId ? `Edit Module — ${editingId}` : 'New Business Module' }}</h1>
        <p class="page-desc">Visual modeling → the existing Business Module Contract V2. No YAML required.</p>
      </div>
      <div class="head-actions">
        <el-button @click="goBack">Cancel</el-button>
        <el-button type="primary" :loading="saving" data-testid="builder-save" @click="save">
          {{ editingId ? 'Save Module' : 'Create Module' }}
        </el-button>
      </div>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" style="margin-bottom: 16px" />
    <el-alert v-if="savedOk" type="success" title="Module saved — contract is ready for generation" show-icon :closable="false" style="margin-bottom: 16px" />

    <div class="builder-layout">
      <!-- section nav (7 sections — not one long form) -->
      <div class="section-nav" data-testid="builder-sections">
        <button
          v-for="(s, i) in [
            { key: 'basic', label: 'Basic', icon: 'InfoFilled' },
            { key: 'fields', label: 'Fields', icon: 'Grid' },
            { key: 'relations', label: 'Relations', icon: 'Share' },
            { key: 'features', label: 'Features', icon: 'Operation' },
            { key: 'enterprise', label: 'Enterprise', icon: 'OfficeBuilding' },
            { key: 'frontend', label: 'Frontend', icon: 'Monitor' },
            { key: 'preview', label: 'Contract Preview', icon: 'Document' },
          ]"
          :key="s.key"
          class="section-nav-item"
          :class="{ 'is-active': activeTab === s.key, 'has-issue': issuesBy(s.key === 'preview' ? 'basic' : s.key).length > 0 && s.key !== 'preview' }"
          @click="activeTab = s.key"
        >
          <span class="section-idx">{{ i + 1 }}</span>
          <span>{{ s.label }}</span>
          <span v-if="s.key === 'fields'" class="section-count">{{ state.fields.length }}</span>
          <span v-else-if="s.key === 'relations'" class="section-count">{{ state.relations.length }}</span>
        </button>
      </div>

      <!-- section content -->
      <div class="section-panel" data-testid="builder-panel">
        <!-- 1. Basic -->
        <div v-if="activeTab === 'basic'" class="section">
          <h2 class="section-title">Basic Information</h2>
          <el-form label-width="160px" class="basic-form">
            <el-form-item label="Module ID" required>
              <el-input v-model="state.id" placeholder="e.g. purchase-order (lowercase, hyphens)" data-testid="input-basic-id" />
            </el-form-item>
            <el-form-item label="Module name" required>
              <el-input v-model="state.name" placeholder="e.g. PurchaseOrder" data-testid="input-basic-name" />
            </el-form-item>
            <el-form-item label="Table name">
              <el-input v-model="state.table" placeholder="defaults to module id with underscores" />
            </el-form-item>
            <el-form-item label="Entity name">
              <el-input v-model="state.entityName" placeholder="defaults to module name" />
            </el-form-item>
            <el-form-item label="Version">
              <el-input v-model="state.version" style="width: 160px" />
            </el-form-item>
            <el-form-item label="Description">
              <el-input v-model="state.description" type="textarea" :rows="2" />
            </el-form-item>
          </el-form>
          <div v-if="issuesBy('basic').length" class="issue-box">
            <p v-for="(it, i) in issuesBy('basic')" :key="i" class="issue-line">⚠ {{ it.message }}</p>
          </div>
        </div>

        <!-- 2. Fields (Fields Designer 2.0) -->
        <div v-if="activeTab === 'fields'" class="section">
          <div class="section-head-row">
            <h2 class="section-title">Fields Designer 2.0</h2>
            <el-button size="small" type="primary" plain data-testid="add-field" @click="addField">+ Add field</el-button>
          </div>
          <p class="section-hint">Three layers per field: Data Type · Business Semantic · UI Component. The UI is suggested from semantic/type and stays adjustable.</p>

          <el-table :data="state.fields" size="small" class="field-table" highlight-current-row @current-change="(row: FieldDesign | null) => (selectedField = row)">
            <el-table-column label="#" width="46">
              <template #default="{ $index }">{{ $index + 1 }}</template>
            </el-table-column>
            <el-table-column label="Field name" min-width="130">
              <template #default="{ row }"><el-input v-model="row.name" size="small" /></template>
            </el-table-column>
            <el-table-column label="Data Type" width="120">
              <template #default="{ row }">
                <el-select v-model="row.type" size="small" @change="onFieldTypeChange(row)">
                  <el-option v-for="t in dataTypes" :key="t" :value="t" :label="t" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="Semantic" width="130">
              <template #default="{ row }">
                <el-select v-model="row.semantic" size="small" @change="onFieldSemanticChange(row)">
                  <el-option v-for="t in semantics" :key="t" :value="t" :label="t" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="UI Component" width="150">
              <template #default="{ row }">
                <el-select v-model="row.ui" size="small">
                  <el-option v-for="u in allowedUis(row.type, row.semantic)" :key="u" :value="u" :label="u" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="Req" width="52">
              <template #default="{ row }"><el-checkbox v-model="row.required" /></template>
            </el-table-column>
            <el-table-column label="Search" width="64">
              <template #default="{ row }"><el-checkbox v-model="row.searchable" /></template>
            </el-table-column>
            <el-table-column label="Ops" width="110">
              <template #default="{ row, $index }">
                <el-button size="small" text @click="moveField($index, -1)">↑</el-button>
                <el-button size="small" text @click="moveField($index, 1)">↓</el-button>
                <el-button size="small" text type="danger" @click="removeField($index)">✕</el-button>
              </template>
            </el-table-column>
          </el-table>

          <!-- field detail config -->
          <div v-if="selectedField" class="field-detail" data-testid="field-detail">
            <h3 class="detail-title">Field configuration — {{ labelOf(selectedField) }}</h3>
            <el-form label-width="150px" class="detail-form" size="small">
              <div class="detail-grid">
                <el-form-item label="Field name"><el-input v-model="selectedField.name" /></el-form-item>
                <el-form-item label="Column name"><el-input v-model="selectedField.column" placeholder="defaults to field name" /></el-form-item>
                <el-form-item label="Label"><el-input v-model="selectedField.name" disabled :placeholder="selectedField.name" /></el-form-item>
                <el-form-item label="Required"><el-switch v-model="selectedField.required" /></el-form-item>
                <el-form-item label="Primary key"><el-switch v-model="selectedField.primaryKey" /></el-form-item>
                <el-form-item label="Unique"><el-switch v-model="selectedField.unique" /></el-form-item>
                <el-form-item label="Length"><el-input-number v-model="selectedField.length" :min="1" controls-position="right" /></el-form-item>
                <el-form-item label="Precision"><el-input-number v-model="selectedField.precision" :min="1" controls-position="right" /></el-form-item>
                <el-form-item label="Scale"><el-input-number v-model="selectedField.scale" :min="0" controls-position="right" /></el-form-item>
                <el-form-item label="Default"><el-input v-model="selectedField.defaultValue" /></el-form-item>
                <el-form-item label="Comment"><el-input v-model="selectedField.comment" /></el-form-item>
                <el-form-item label="Placeholder"><el-input v-model="selectedField.placeholder" /></el-form-item>
              </div>
              <div class="detail-check-grid">
                <el-form-item label="List visible"><el-switch v-model="selectedField.listVisible" /></el-form-item>
                <el-form-item label="Searchable"><el-switch v-model="selectedField.searchable" /></el-form-item>
                <el-form-item label="Form visible"><el-switch v-model="selectedField.formVisible" /></el-form-item>
                <el-form-item label="Detail visible"><el-switch v-model="selectedField.detailVisible" /></el-form-item>
                <el-form-item label="Sortable"><el-switch v-model="selectedField.sortable" /></el-form-item>
              </div>
            </el-form>

            <!-- Enum Designer -->
            <div v-if="selectedField.semantic === 'enum'" class="sub-designer" data-testid="enum-designer">
              <h4 class="detail-title">Enum Values</h4>
              <el-table :data="selectedField.enumValues ?? []" size="small">
                <el-table-column label="Value" min-width="140">
                  <template #default="{ row }"><el-input v-model="row.value" size="small" placeholder="e.g. DRAFT" /></template>
                </el-table-column>
                <el-table-column label="Label" min-width="140">
                  <template #default="{ row }"><el-input v-model="row.label" size="small" placeholder="e.g. Draft" /></template>
                </el-table-column>
                <el-table-column label="Ops" width="110">
                  <template #default="{ $index }">
                    <el-button size="small" text @click="moveEnumValue(selectedField as FieldDesign, $index, -1)">↑</el-button>
                    <el-button size="small" text @click="moveEnumValue(selectedField as FieldDesign, $index, 1)">↓</el-button>
                    <el-button size="small" text type="danger" @click="removeEnumValue(selectedField as FieldDesign, $index)">✕</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-button size="small" type="primary" plain style="margin-top: 8px" @click="addEnumValue(selectedField as FieldDesign)">+ Add value</el-button>
            </div>

            <!-- Reference Designer -->
            <div v-if="selectedField.semantic === 'reference'" class="sub-designer" data-testid="reference-designer">
              <h4 class="detail-title">Reference</h4>
              <el-form label-width="150px" size="small">
                <el-form-item label="Target module" required>
                  <el-select v-model="selectedField.reference!.target" filterable placeholder="select business module" style="width: 100%">
                    <el-option v-for="t in targets" :key="t.id" :value="t.id" :label="t.id" />
                  </el-select>
                </el-form-item>
                <el-form-item label="Value field">
                  <el-select v-model="selectedField.reference!.valueField" filterable allow-create style="width: 100%">
                    <el-option v-for="fld in targetFieldsOf(selectedField.reference!.target)" :key="fld.name" :value="fld.name" :label="fld.name" />
                  </el-select>
                </el-form-item>
                <el-form-item label="Label field">
                  <el-select v-model="selectedField.reference!.labelField" filterable allow-create style="width: 100%">
                    <el-option v-for="fld in targetFieldsOf(selectedField.reference!.target)" :key="fld.name" :value="fld.name" :label="fld.name" />
                  </el-select>
                </el-form-item>
                <el-form-item label="Search fields">
                  <el-select v-model="selectedField.reference!.searchFields" multiple filterable allow-create style="width: 100%">
                    <el-option v-for="fld in targetFieldsOf(selectedField.reference!.target)" :key="fld.name" :value="fld.name" :label="fld.name" />
                  </el-select>
                </el-form-item>
              </el-form>
            </div>

            <!-- Dictionary -->
            <div v-if="selectedField.semantic === 'dictionary'" class="sub-designer" data-testid="dictionary-designer">
              <h4 class="detail-title">Dictionary</h4>
              <el-form label-width="150px" size="small">
                <el-form-item label="Dictionary code" required>
                  <el-input v-model="selectedField.dictionary" placeholder="e.g. supplier_status" />
                </el-form-item>
              </el-form>
            </div>
          </div>
        </div>

        <!-- 3. Relations -->
        <div v-if="activeTab === 'relations'" class="section">
          <div class="section-head-row">
            <h2 class="section-title">Relations Designer</h2>
            <el-button size="small" type="primary" plain data-testid="add-relation" @click="addRelation">+ Add relation</el-button>
          </div>
          <p class="section-hint">MANY_TO_ONE · ONE_TO_MANY (composition → Master/Detail) · ONE_TO_ONE. MANY_TO_MANY is not supported yet.</p>

          <div v-for="(r, i) in state.relations" :key="i" class="relation-card" :data-testid="`relation-${i}`">
            <div class="relation-head">
              <span class="relation-name">{{ r.name || `relation #${i + 1}` }}</span>
              <el-tag v-if="isMasterDetail(r)" type="warning" size="small" data-testid="master-detail-badge">Master / Detail</el-tag>
              <el-tag v-if="r.type === 'MANY_TO_MANY'" type="info" size="small">Coming Soon</el-tag>
              <span class="relation-ops">
                <el-button size="small" text @click="moveRelation(i, -1)">↑</el-button>
                <el-button size="small" text @click="moveRelation(i, 1)">↓</el-button>
                <el-button size="small" text type="danger" @click="removeRelation(i)">✕</el-button>
              </span>
            </div>
            <div class="relation-form">
              <el-form label-width="120px" size="small" inline>
                <el-form-item label="Name">
                  <el-input v-model="r.name" style="width: 160px" />
                </el-form-item>
                <el-form-item label="Type">
                  <el-select v-model="r.type" style="width: 170px" :disabled="r.type === 'MANY_TO_MANY'">
                    <el-option v-for="t in relationTypes" :key="t" :value="t" :label="t" :disabled="t === 'MANY_TO_MANY'" />
                  </el-select>
                </el-form-item>
                <el-form-item label="Target module" required>
                  <el-select v-model="r.target" filterable style="width: 180px">
                    <el-option v-for="t in targets" :key="t.id" :value="t.id" :label="t.id" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="r.type === 'MANY_TO_ONE' || r.type === 'ONE_TO_ONE'" label="Local field">
                  <el-select v-model="r.localField" filterable allow-create style="width: 160px">
                    <el-option v-for="fld in state.fields" :key="fld.name" :value="fld.name" :label="fld.name" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="r.type === 'MANY_TO_ONE' || r.type === 'ONE_TO_ONE'" label="Target field">
                  <el-select v-model="r.targetField" filterable allow-create style="width: 140px">
                    <el-option v-for="fld in targetFieldsOf(r.target)" :key="fld.name" :value="fld.name" :label="fld.name" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="r.type === 'ONE_TO_MANY'" label="Mapped by">
                  <el-select v-model="r.mappedBy" filterable allow-create style="width: 160px">
                    <el-option v-for="fld in targetFieldsOf(r.target)" :key="fld.name" :value="fld.name" :label="fld.name" />
                  </el-select>
                </el-form-item>
                <el-form-item label="Required">
                  <el-switch v-model="r.required" />
                </el-form-item>
                <el-form-item label="Composition">
                  <el-switch v-model="r.composition" />
                </el-form-item>
              </el-form>
              <p v-if="isMasterDetail(r)" class="master-detail-hint" data-testid="master-detail-hint">
                {{ r.target }} lifecycle belongs to {{ state.id || 'this module' }} (Master / Detail).
              </p>
            </div>
          </div>
          <div v-if="state.relations.length === 0" class="empty-hint">No relations yet — add ONE_TO_MANY composition for a Master/Detail pair.</div>

          <!-- Dependency Summary -->
          <div class="dependency-summary" data-testid="dependency-summary">
            <h3 class="detail-title">Module Dependency Summary</h3>
            <div v-if="dependencySummary.refs.length" class="dep-line">
              <span class="dep-label">References:</span>
              <el-tag v-for="t in dependencySummary.refs" :key="t" size="small" type="info" style="margin-right: 6px">→ {{ t }}</el-tag>
            </div>
            <div v-if="dependencySummary.compositions.length" class="dep-line">
              <span class="dep-label">Composition:</span>
              <el-tag v-for="t in dependencySummary.compositions" :key="t" size="small" type="warning" style="margin-right: 6px">→ {{ t }}</el-tag>
            </div>
            <p v-if="!dependencySummary.refs.length && !dependencySummary.compositions.length" class="empty-hint">No dependencies yet.</p>
          </div>
        </div>

        <!-- 4. Features -->
        <div v-if="activeTab === 'features'" class="section">
          <h2 class="section-title">CRUD Features</h2>
          <el-checkbox-group v-model="state.features" class="feature-grid">
            <el-checkbox v-for="f in featuresOptions" :key="f" :value="f" :label="f" class="feature-check" />
          </el-checkbox-group>
        </div>

        <!-- 5. Enterprise -->
        <div v-if="activeTab === 'enterprise'" class="section">
          <h2 class="section-title">Enterprise Features</h2>
          <el-checkbox-group v-model="state.enterprise" class="feature-grid">
            <el-checkbox
              v-for="e in enterpriseOptions"
              :key="e"
              :value="e"
              :label="e"
              class="feature-check"
              :model-value="(state.enterprise as Record<string, boolean>)[e]"
              @update:model-value="(v: boolean | string | number) => ((state.enterprise as Record<string, boolean>)[e] = Boolean(v))"
            />
          </el-checkbox-group>
        </div>

        <!-- 6. Frontend -->
        <div v-if="activeTab === 'frontend'" class="section">
          <h2 class="section-title">Frontend Metadata</h2>
          <el-form label-width="160px">
            <el-form-item label="Route">
              <el-input v-model="state.frontend.route" placeholder="defaults to /module-id" />
            </el-form-item>
            <el-form-item label="Label">
              <el-input v-model="state.frontend.label" placeholder="defaults to module name" />
            </el-form-item>
          </el-form>
        </div>

        <!-- 7. Contract Preview -->
        <div v-if="activeTab === 'preview'" class="section">
          <div class="section-head-row">
            <h2 class="section-title">Contract Preview</h2>
            <div class="head-actions">
              <el-button size="small" @click="refreshPreview">Refresh</el-button>
              <el-button size="small" type="primary" data-testid="preview-save" @click="save">Save Module</el-button>
            </div>
          </div>
          <p class="section-hint">The YAML below is the real serialization of the current Builder state — the same manifest the existing generator consumes.</p>
          <div v-if="issues.length" class="issue-box">
            <p v-for="(it, i) in issues" :key="i" class="issue-line">⚠ [{{ it.section }}] {{ it.message }}</p>
          </div>
          <el-alert v-if="previewError" type="error" :title="previewError" show-icon :closable="false" />
          <pre class="yaml-preview" data-testid="contract-preview">{{ yamlPreview }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.builder-page {
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
.head-actions {
  display: flex;
  gap: var(--ep-space-3);
}
.builder-layout {
  display: grid;
  grid-template-columns: 230px 1fr;
  gap: var(--ep-space-5);
  align-items: start;
}
.section-nav {
  display: grid;
  gap: 6px;
  padding: var(--ep-space-3);
  background: var(--ep-color-surface);
  border-radius: var(--ep-radius-xl);
  box-shadow: var(--ep-shadow-sm);
  position: sticky;
  top: 16px;
}
.section-nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: none;
  border-radius: var(--ep-radius-lg);
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: var(--ep-color-text-secondary);
  text-align: left;
  transition: all 0.15s ease;
}
.section-nav-item:hover {
  background: var(--ep-color-surface-hover);
}
.section-nav-item.is-active {
  background: var(--ep-accent-indigo-soft);
  color: var(--ep-accent-indigo);
}
.section-nav-item.has-issue {
  color: var(--ep-accent-rose);
}
.section-idx {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--ep-color-surface-tinted);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
}
.section-nav-item.is-active .section-idx {
  background: var(--ep-accent-indigo);
  color: #fff;
}
.section-count {
  margin-left: auto;
  font-size: 11px;
  background: var(--ep-color-surface-tinted);
  border-radius: 999px;
  padding: 1px 8px;
}
.section-panel {
  padding: var(--ep-space-5);
  background: var(--ep-color-surface);
  border-radius: var(--ep-radius-xl);
  box-shadow: var(--ep-shadow-sm);
  min-height: 520px;
}
.section-title {
  margin: 0 0 var(--ep-space-3);
  font-size: var(--ep-font-size-section);
  font-weight: 700;
}
.section-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--ep-space-3);
}
.section-head-row .section-title {
  margin: 0;
}
.section-hint {
  margin: 0 0 var(--ep-space-4);
  color: var(--ep-color-text-muted);
  font-size: 13px;
  line-height: 1.6;
}
.basic-form {
  max-width: 640px;
}
.field-table {
  margin-bottom: var(--ep-space-4);
}
.field-detail {
  margin-top: var(--ep-space-4);
  padding: var(--ep-space-4);
  border: 1px solid var(--ep-color-border);
  border-radius: var(--ep-radius-lg);
  background: var(--ep-color-surface-muted);
}
.detail-title {
  margin: 0 0 var(--ep-space-3);
  font-size: 14px;
  font-weight: 700;
}
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 var(--ep-space-4);
}
.detail-check-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 0 var(--ep-space-3);
  margin-top: var(--ep-space-2);
}
.sub-designer {
  margin-top: var(--ep-space-4);
  padding-top: var(--ep-space-3);
  border-top: 1px dashed var(--ep-color-border);
}
.relation-card {
  margin-bottom: var(--ep-space-3);
  padding: var(--ep-space-4);
  border: 1px solid var(--ep-color-border);
  border-radius: var(--ep-radius-lg);
}
.relation-head {
  display: flex;
  align-items: center;
  gap: var(--ep-space-3);
  margin-bottom: var(--ep-space-3);
}
.relation-name {
  font-weight: 700;
  font-size: 14px;
}
.relation-ops {
  margin-left: auto;
}
.relation-form .el-form-item {
  margin-bottom: 8px;
}
.master-detail-hint {
  margin: 8px 0 0;
  padding: 8px 12px;
  border-radius: var(--ep-radius-md);
  background: var(--ep-accent-amber-soft);
  color: var(--ep-accent-amber);
  font-size: 13px;
}
.empty-hint {
  color: var(--ep-color-text-muted);
  font-size: 13px;
  padding: var(--ep-space-4) 0;
}
.dependency-summary {
  margin-top: var(--ep-space-4);
  padding: var(--ep-space-4);
  border: 1px solid var(--ep-color-border);
  border-radius: var(--ep-radius-lg);
  background: var(--ep-color-surface-muted);
}
.dep-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.dep-label {
  font-weight: 600;
  font-size: 13px;
}
.feature-grid {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ep-space-3);
}
.feature-check {
  width: 140px;
  padding: 12px;
  border: 1px solid var(--ep-color-border);
  border-radius: var(--ep-radius-lg);
  margin-right: 0 !important;
}
.issue-box {
  margin-top: var(--ep-space-3);
  padding: var(--ep-space-3) var(--ep-space-4);
  border-radius: var(--ep-radius-lg);
  background: var(--ep-accent-rose-soft);
}
.issue-line {
  margin: 0 0 4px;
  color: var(--ep-accent-rose);
  font-size: 13px;
}
.yaml-preview {
  margin: var(--ep-space-4) 0 0;
  padding: var(--ep-space-4);
  border-radius: var(--ep-radius-lg);
  background: #171b2e;
  color: #d6dae8;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  overflow: auto;
  max-height: 560px;
  white-space: pre;
}
</style>
