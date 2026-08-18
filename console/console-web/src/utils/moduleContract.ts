// Business Module Builder 2.0 state model (V07-WORK-004).
// Pure functions: Builder UI state ↔ Business Module Contract V2 manifest.
// No second contract model — the manifest produced here is the SAME shape
// the existing generator pipeline consumes (BusinessModuleResolver reads
// manifest.business).

export type DataType =
  | 'string'
  | 'text'
  | 'integer'
  | 'long'
  | 'decimal'
  | 'boolean'
  | 'date'
  | 'datetime';

export type Semantic =
  | 'none'
  | 'money'
  | 'enum'
  | 'dictionary'
  | 'status'
  | 'reference'
  | 'department'
  | 'image'
  | 'file'
  | 'richtext';

export type UiComponent =
  | 'Input'
  | 'Textarea'
  | 'InputNumber'
  | 'Switch'
  | 'DatePicker'
  | 'MoneyInput'
  | 'MoneyText'
  | 'ReferenceSelect'
  | 'StatusSelect'
  | 'DictionarySelect'
  | 'StatusTag'
  | 'ImageUpload'
  | 'FileUpload'
  | 'RichTextEditor'
  | 'Select';

export type RelationType = 'MANY_TO_ONE' | 'ONE_TO_MANY' | 'ONE_TO_ONE' | 'MANY_TO_MANY';

export interface EnumValueDesign {
  value: string;
  label: string;
}

export interface ReferenceDesign {
  target: string;
  valueField: string;
  labelField: string;
  searchFields: string[];
}

export interface FieldDesign {
  name: string;
  column?: string;
  type: DataType;
  semantic: Semantic;
  ui: UiComponent;
  required: boolean;
  primaryKey: boolean;
  unique: boolean;
  length?: number;
  precision?: number;
  scale?: number;
  defaultValue?: string;
  comment?: string;
  placeholder?: string;
  listVisible: boolean;
  searchable: boolean;
  formVisible: boolean;
  detailVisible: boolean;
  sortable: boolean;
  order: number;
  // semantic=enum
  enumValues?: EnumValueDesign[];
  // semantic=dictionary
  dictionary?: string;
  // semantic=reference
  reference?: ReferenceDesign;
}

export interface RelationDesign {
  name: string;
  type: RelationType;
  target: string;
  localField?: string;
  targetField?: string;
  mappedBy?: string;
  required: boolean;
  composition: boolean;
}

export interface ModuleBuilderState {
  id: string;
  name: string;
  description: string;
  version: string;
  table: string;
  entityName: string;
  fields: FieldDesign[];
  relations: RelationDesign[];
  features: string[];
  enterprise: {
    permissions: boolean;
    dataScope: boolean;
    menu: boolean;
    dictionary: boolean;
    operationLog: boolean;
  };
  frontend: { route: string; label: string };
}

// ---- UI component default suggestions (semantic first, then type) ----

export function suggestUi(type: DataType, semantic: Semantic): UiComponent {
  switch (semantic) {
    case 'reference': return 'ReferenceSelect';
    case 'money': return 'MoneyInput';
    case 'enum': return 'StatusSelect';
    case 'dictionary': return 'DictionarySelect';
    case 'status': return 'StatusTag';
    case 'image': return 'ImageUpload';
    case 'file': return 'FileUpload';
    case 'richtext': return 'RichTextEditor';
    case 'none': break;
  }
  switch (type) {
    case 'boolean': return 'Switch';
    case 'date':
    case 'datetime': return 'DatePicker';
    case 'text': return 'Textarea';
    case 'integer':
    case 'long':
    case 'decimal': return 'InputNumber';
    default: return 'Input';
  }
}

/** Allowed UI components for a given (type, semantic) — used to bound the UI select. */
export function allowedUis(type: DataType, semantic: Semantic): UiComponent[] {
  if (semantic === 'reference') return ['ReferenceSelect'];
  if (semantic === 'money') return ['MoneyInput', 'MoneyText'];
  if (semantic === 'enum') return ['StatusSelect', 'Select'];
  if (semantic === 'dictionary') return ['DictionarySelect', 'Select'];
  if (semantic === 'status') return ['StatusTag', 'Select'];
  if (semantic === 'image') return ['ImageUpload'];
  if (semantic === 'file') return ['FileUpload'];
  if (semantic === 'richtext') return ['RichTextEditor'];
  switch (type) {
    case 'boolean': return ['Switch'];
    case 'date':
    case 'datetime': return ['DatePicker'];
    case 'text': return ['Textarea', 'Input'];
    case 'integer':
    case 'long':
    case 'decimal': return ['InputNumber', 'Input'];
    default: return ['Input', 'Textarea'];
  }
}

// ---- defaults ----

export function defaultField(order: number): FieldDesign {
  return {
    name: 'field' + (order + 1),
    type: 'string',
    semantic: 'none',
    ui: 'Input',
    required: false,
    primaryKey: false,
    unique: false,
    length: 100,
    listVisible: true,
    searchable: false,
    formVisible: true,
    detailVisible: true,
    sortable: false,
    order,
  };
}

export function defaultModuleBuilderState(): ModuleBuilderState {
  return {
    id: '',
    name: '',
    description: '',
    version: '1.0.0',
    table: '',
    entityName: '',
    fields: [],
    relations: [],
    features: ['list', 'search', 'create', 'edit', 'detail', 'disable'],
    enterprise: { permissions: true, dataScope: true, menu: true, dictionary: true, operationLog: true },
    frontend: { route: '', label: '' },
  };
}

// ---- Contract V2 manifest assembly ----

export function buildManifest(state: ModuleBuilderState): Record<string, unknown> {
  const table = state.table || state.id.replace(/-/g, '_');
  const fields = state.fields.map((f) => {
    const m: Record<string, unknown> = { name: f.name, type: contractTypeOf(f) };
    if (f.required) m.required = true;
    if (f.primaryKey) m.primaryKey = true;
    if (f.unique) m.unique = true;
    if (f.length) m.length = f.length;
    if (f.precision) m.precision = f.precision;
    if (f.scale) m.scale = f.scale;
    if (f.defaultValue) m.defaultValue = f.defaultValue;
    if (f.comment) m.comment = f.comment;
    // semantic → contract fields
    if (f.semantic === 'reference' && f.reference) {
      m.semantic = 'reference';
      m.reference = {
        target: f.reference.target,
        valueField: f.reference.valueField,
        labelField: f.reference.labelField,
        searchFields: f.reference.searchFields,
      };
    } else if (f.semantic === 'enum') {
      m.semantic = 'enum';
      if (f.enumValues && f.enumValues.length > 0) {
        m.enum = { values: f.enumValues.map((v) => ({ value: v.value, label: v.label })) };
      }
      if (f.defaultValue) m.default = f.defaultValue;
    } else if (f.semantic === 'dictionary') {
      m.semantic = 'dictionary';
      if (f.dictionary) m.dictionary = f.dictionary;
    } else if (f.semantic === 'department') {
      m.semantic = 'department';
    }
    // frontend metadata (V0.6 style: label/order/visibility/searchable)
    const fe: Record<string, unknown> = { label: f.name, order: f.order };
    if (f.listVisible !== undefined) fe.listVisible = f.listVisible;
    if (f.searchable) fe.searchable = true;
    if (f.formVisible !== undefined) fe.formVisible = f.formVisible;
    if (f.detailVisible !== undefined) fe.detailVisible = f.detailVisible;
    if (f.sortable) fe.sortable = true;
    if (f.placeholder) fe.placeholder = f.placeholder;
    m.frontend = fe;
    return m;
  });

  const biz: Record<string, unknown> = {
    table,
    entity: { name: state.entityName || state.name, fields },
  };
  if (state.relations.length > 0) {
    biz.relations = state.relations.map((r) => {
      const rm: Record<string, unknown> = { name: r.name, type: r.type, target: r.target };
      if (r.localField) rm.localField = r.localField;
      if (r.targetField) rm.targetField = r.targetField;
      if (r.mappedBy) rm.mappedBy = r.mappedBy;
      if (r.required) rm.required = true;
      if (r.composition) rm.composition = true;
      return rm;
    });
  }
  if (state.features.length > 0) biz.features = state.features;
  biz.enterprise = {
    permissions: state.enterprise.permissions,
    dataScope: state.enterprise.dataScope,
    menu: state.enterprise.menu,
    dictionary: state.enterprise.dictionary,
    operationLog: state.enterprise.operationLog,
  };
  biz.frontend = {
    route: state.frontend.route || '/' + state.id.replace(/-/g, ''),
    label: state.frontend.label || state.name,
  };

  return {
    schemaVersion: 1,
    module: {
      id: state.id,
      name: state.name,
      version: state.version || '1.0.0',
      type: 'business',
      description: state.description || 'Console-created business module',
    },
    compatibility: { platformVersion: '0.6' },
    business: biz,
  };
}

/** Contract-level field type: semantic money/status/image/file/richtext map to their contract type. */
function contractTypeOf(f: FieldDesign): string {
  switch (f.semantic) {
    case 'money': return 'money';
    case 'status': return 'status';
    case 'image': return 'image';
    case 'file': return 'file';
    case 'richtext': return 'richtext';
    default: return f.type;
  }
}

// ---- Contract → Builder state (round-trip) ----

type Raw = Record<string, any>;

export function parseManifest(manifest: Raw): ModuleBuilderState {
  const module = (manifest.module ?? {}) as Raw;
  const business = (manifest.business ?? {}) as Raw;
  const entity = (business.entity ?? {}) as Raw;
  const rawFields = Array.isArray(entity.fields) ? (entity.fields as Raw[]) : [];
  const rawRelations = Array.isArray(business.relations) ? (business.relations as Raw[]) : [];
  const ent = (business.enterprise ?? {}) as Raw;
  const fe = (business.frontend ?? {}) as Raw;

  const fields: FieldDesign[] = rawFields.map((f, i) => {
    const semantic = semanticOf(f);
    const type = typeOf(f, semantic);
    const rawFe = (f.frontend ?? {}) as Raw;
    const design: FieldDesign = {
      name: String(f.name ?? ''),
      type,
      semantic,
      ui: suggestUi(type, semantic),
      required: f.required === true,
      primaryKey: f.primaryKey === true,
      unique: f.unique === true,
      length: f.length != null ? Number(f.length) : undefined,
      precision: f.precision != null ? Number(f.precision) : undefined,
      scale: f.scale != null ? Number(f.scale) : undefined,
      defaultValue: f.default != null ? String(f.default) : f.defaultValue != null ? String(f.defaultValue) : undefined,
      comment: f.comment != null ? String(f.comment) : undefined,
      placeholder: rawFe.placeholder != null ? String(rawFe.placeholder) : undefined,
      listVisible: rawFe.listVisible !== false,
      searchable: rawFe.searchable === true || rawFe.searchVisible === true,
      formVisible: rawFe.formVisible !== false,
      detailVisible: rawFe.detailVisible !== false,
      sortable: rawFe.sortable === true,
      order: rawFe.order != null ? Number(rawFe.order) : i,
    };
    if (semantic === 'enum' && f.enum != null) {
      const values = Array.isArray((f.enum as Raw).values) ? ((f.enum as Raw).values as Raw[]) : [];
      design.enumValues = values.map((v) => ({
        value: String(v.value ?? ''),
        label: String(v.label ?? v.value ?? ''),
      }));
    }
    if (semantic === 'dictionary' && f.dictionary != null) {
      design.dictionary = String(f.dictionary);
    }
    if (semantic === 'reference' && f.reference != null) {
      const ref = f.reference as Raw;
      design.reference = {
        target: String(ref.target ?? ''),
        valueField: String(ref.valueField ?? 'id'),
        labelField: String(ref.labelField ?? 'name'),
        searchFields: Array.isArray(ref.searchFields) ? ref.searchFields.map(String) : [],
      };
    }
    return design;
  });

  const relations: RelationDesign[] = rawRelations.map((r) => ({
    name: String(r.name ?? ''),
    type: (String(r.type ?? 'MANY_TO_ONE') as RelationType),
    target: String(r.target ?? ''),
    localField: r.localField != null ? String(r.localField) : undefined,
    targetField: r.targetField != null ? String(r.targetField) : undefined,
    mappedBy: r.mappedBy != null ? String(r.mappedBy) : undefined,
    required: r.required === true,
    composition: r.composition === true,
  }));

  return {
    id: String(module.id ?? ''),
    name: String(module.name ?? ''),
    description: String(module.description ?? ''),
    version: String(module.version ?? '1.0.0'),
    table: String(business.table ?? ''),
    entityName: String(entity.name ?? ''),
    fields,
    relations,
    features: Array.isArray(business.features) ? business.features.map(String) : [],
    enterprise: {
      permissions: ent.permissions !== false,
      dataScope: ent.dataScope !== false,
      menu: ent.menu !== false,
      dictionary: ent.dictionary === true,
      operationLog: ent.operationLog === true,
    },
    frontend: {
      route: String(fe.route ?? ''),
      label: String(fe.label ?? ''),
    },
  };
}

function semanticOf(f: Raw): Semantic {
  if (f.semantic === 'reference') return 'reference';
  if (f.semantic === 'enum') return 'enum';
  if (f.semantic === 'dictionary') return 'dictionary';
  if (f.semantic === 'department') return 'department' as Semantic;
  if (f.semantic === 'status') return 'status';
  if (f.semantic && f.semantic !== 'none') return String(f.semantic) as Semantic;
  // type-level semantics (Contract V2: money/status/image/file/richtext are types)
  const t = String(f.type ?? '');
  if (t === 'money') return 'money';
  if (t === 'status') return 'status';
  if (t === 'image') return 'image';
  if (t === 'file') return 'file';
  if (t === 'richtext') return 'richtext';
  return 'none';
}

function typeOf(f: Raw, semantic: Semantic): DataType {
  const t = String(f.type ?? 'string');
  if (semantic === 'money') return t === 'money' ? 'decimal' : (t as DataType);
  if (t === 'money' || t === 'status' || t === 'image' || t === 'file' || t === 'richtext') return 'string';
  return t as DataType;
}

// ---- UX-level validation (frontend; backend Contract Validator is final) ----

export interface BuilderIssue {
  section: 'basic' | 'fields' | 'relations' | 'features';
  message: string;
}

export function validateModuleState(state: ModuleBuilderState): BuilderIssue[] {
  const issues: BuilderIssue[] = [];
  if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(state.id)) {
    issues.push({ section: 'basic', message: 'Module ID must match ^[a-z0-9]+(-[a-z0-9]+)*$' });
  }
  if (!state.name.trim()) issues.push({ section: 'basic', message: 'Module name is required' });
  if (state.fields.length === 0) issues.push({ section: 'fields', message: 'At least one field is required' });
  const names = new Set<string>();
  state.fields.forEach((f, i) => {
    if (!f.name.trim()) issues.push({ section: 'fields', message: `Field #${i + 1} name is required` });
    else if (names.has(f.name)) issues.push({ section: 'fields', message: `Duplicate field name: ${f.name}` });
    else names.add(f.name);
    if (f.semantic === 'reference' && (!f.reference || !f.reference.target)) {
      issues.push({ section: 'fields', message: `Field '${f.name || '#' + (i + 1)}' reference.target is required` });
    }
    if (f.semantic === 'enum' && (!f.enumValues || f.enumValues.length === 0)) {
      issues.push({ section: 'fields', message: `Field '${f.name || '#' + (i + 1)}' requires at least one enum value` });
    }
    if (f.semantic === 'dictionary' && !f.dictionary) {
      issues.push({ section: 'fields', message: `Field '${f.name || '#' + (i + 1)}' requires a dictionary code` });
    }
  });
  const relNames = new Set<string>();
  state.relations.forEach((r, i) => {
    if (!r.name.trim()) issues.push({ section: 'relations', message: `Relation #${i + 1} name is required` });
    else if (relNames.has(r.name)) issues.push({ section: 'relations', message: `Duplicate relation name: ${r.name}` });
    else relNames.add(r.name);
    if (r.type === 'MANY_TO_MANY') {
      issues.push({ section: 'relations', message: `Relation '${r.name || '#' + (i + 1)}': MANY_TO_MANY is not supported yet` });
    }
    if (!r.target.trim()) issues.push({ section: 'relations', message: `Relation '${r.name || '#' + (i + 1)}' target module is required` });
    if (r.type === 'MANY_TO_ONE' || r.type === 'ONE_TO_ONE') {
      if (!r.localField) issues.push({ section: 'relations', message: `Relation '${r.name || '#' + (i + 1)}' requires localField` });
      if (!r.targetField) issues.push({ section: 'relations', message: `Relation '${r.name || '#' + (i + 1)}' requires targetField` });
    }
    if (r.type === 'ONE_TO_MANY' && !r.mappedBy) {
      issues.push({ section: 'relations', message: `Relation '${r.name || '#' + (i + 1)}' requires mappedBy` });
    }
  });
  return issues;
}
