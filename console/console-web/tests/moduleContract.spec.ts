// Business Module Builder 2.0 targeted tests (V07-WORK-004).
// Pure function tests: Builder state → Contract V2 manifest → back to state
// (round-trip) + UX-level validation rules. No DOM, no API calls.
import { describe, it, expect } from 'vitest';
import {
  buildManifest,
  defaultModuleBuilderState,
  parseManifest,
  suggestUi,
  validateModuleState,
  type ModuleBuilderState,
} from '../src/utils/moduleContract';

describe('buildManifest', () => {
  it('produces Contract V2 module manifest with business section', () => {
    const state: ModuleBuilderState = {
      ...defaultModuleBuilderState(),
      id: 'purchase-order',
      name: 'PurchaseOrder',
      entityName: 'PurchaseOrder',
      fields: [
        {
          name: 'orderNo',
          type: 'string',
          semantic: 'none',
          ui: 'Input',
          required: true,
          primaryKey: false,
          unique: true,
          length: 64,
          listVisible: true,
          searchable: true,
          formVisible: true,
          detailVisible: true,
          sortable: false,
          order: 0,
        },
        {
          name: 'supplierId',
          type: 'integer',
          semantic: 'reference',
          ui: 'ReferenceSelect',
          required: true,
          primaryKey: false,
          unique: false,
          listVisible: true,
          searchable: false,
          formVisible: true,
          detailVisible: true,
          sortable: false,
          order: 1,
          reference: { target: 'supplier', valueField: 'id', labelField: 'name', searchFields: ['code', 'name'] },
        },
        {
          name: 'status',
          type: 'string',
          semantic: 'enum',
          ui: 'StatusSelect',
          required: false,
          primaryKey: false,
          unique: false,
          listVisible: true,
          searchable: false,
          formVisible: true,
          detailVisible: true,
          sortable: false,
          order: 2,
          enumValues: [
            { value: 'DRAFT', label: 'Draft' },
            { value: 'CONFIRMED', label: 'Confirmed' },
          ],
        },
        {
          name: 'totalAmount',
          type: 'decimal',
          semantic: 'money',
          ui: 'MoneyInput',
          required: false,
          primaryKey: false,
          unique: false,
          precision: 14,
          scale: 2,
          listVisible: true,
          searchable: false,
          formVisible: true,
          detailVisible: true,
          sortable: false,
          order: 3,
        },
      ],
      relations: [
        { name: 'items', type: 'ONE_TO_MANY', target: 'purchase-order-item', mappedBy: 'purchaseOrderId', required: false, composition: true },
      ],
    };
    const m = buildManifest(state) as Record<string, any>;
    expect(m.schemaVersion).toBe(1);
    expect((m.module as Record<string, unknown>).id).toBe('purchase-order');
    const biz = m.business as Record<string, any>;
    expect(biz.table).toBe('purchase_order');
    expect(biz.entity.name).toBe('PurchaseOrder');
    expect(biz.features).toContain('list');
    expect(biz.enterprise.permissions).toBe(true);
    // reference field → structured reference
    const supplier = biz.entity.fields.find((f: any) => f.name === 'supplierId');
    expect(supplier.semantic).toBe('reference');
    expect(supplier.reference).toEqual({ target: 'supplier', valueField: 'id', labelField: 'name', searchFields: ['code', 'name'] });
    // enum field → structured enum values
    const status = biz.entity.fields.find((f: any) => f.name === 'status');
    expect(status.semantic).toBe('enum');
    expect(status.enum.values).toHaveLength(2);
    // money semantic → contract type money
    const amount = biz.entity.fields.find((f: any) => f.name === 'totalAmount');
    expect(amount.type).toBe('money');
    expect(amount.precision).toBe(14);
    // relations → structured relations
    expect(biz.relations).toEqual([
      { name: 'items', type: 'ONE_TO_MANY', target: 'purchase-order-item', mappedBy: 'purchaseOrderId', composition: true },
    ]);
  });

  it('defaults table from id when table empty', () => {
    const state = { ...defaultModuleBuilderState(), id: 'customer-lite' };
    const m = buildManifest(state) as Record<string, any>;
    expect((m.business as Record<string, any>).table).toBe('customer_lite');
  });
});

describe('parseManifest (round-trip)', () => {
  it('V0.6 module without relations/reference parses normally', () => {
    const manifest = {
      schemaVersion: 1,
      module: { id: 'customer-lite', name: 'CustomerLite', version: '1.0.0', type: 'business' },
      compatibility: { platformVersion: '0.6' },
      business: {
        table: 'customer_lite',
        entity: {
          name: 'CustomerLite',
          fields: [
            { name: 'code', type: 'string', required: true, length: 50 },
            { name: 'status', type: 'string', semantic: 'dictionary', dictionary: 'customer_status' },
          ],
        },
        features: ['list', 'search', 'create', 'edit', 'detail', 'disable'],
        enterprise: { permissions: true, dataScope: true, menu: true, dictionary: true, operationLog: true },
        frontend: { route: '/customer-lite', label: 'CustomerLite' },
      },
    };
    const s = parseManifest(manifest);
    expect(s.id).toBe('customer-lite');
    expect(s.fields).toHaveLength(2);
    expect(s.fields[1].semantic).toBe('dictionary');
    expect(s.fields[1].dictionary).toBe('customer_status');
    expect(s.relations).toHaveLength(0);
    expect(s.features).toContain('edit');
  });

  it('contract → state → contract keeps semantics', () => {
    const state: ModuleBuilderState = {
      ...defaultModuleBuilderState(),
      id: 'purchase-order',
      name: 'PurchaseOrder',
      entityName: 'PurchaseOrder',
      fields: [
        {
          name: 'supplierId', type: 'integer', semantic: 'reference', ui: 'ReferenceSelect',
          required: true, primaryKey: false, unique: false, listVisible: true, searchable: false,
          formVisible: true, detailVisible: true, sortable: false, order: 0,
          reference: { target: 'supplier', valueField: 'id', labelField: 'name', searchFields: ['code'] },
        },
        {
          name: 'status', type: 'string', semantic: 'enum', ui: 'StatusSelect',
          required: false, primaryKey: false, unique: false, listVisible: true, searchable: false,
          formVisible: true, detailVisible: true, sortable: false, order: 1,
          enumValues: [{ value: 'DRAFT', label: 'Draft' }],
        },
      ],
      relations: [
        { name: 'items', type: 'ONE_TO_MANY', target: 'purchase-order-item', mappedBy: 'purchaseOrderId', required: false, composition: true },
      ],
    };
    const manifest = buildManifest(state);
    const back = parseManifest(manifest);
    expect(back.id).toBe('purchase-order');
    expect(back.fields).toHaveLength(2);
    expect(back.fields[0].semantic).toBe('reference');
    expect(back.fields[0].reference?.target).toBe('supplier');
    expect(back.fields[0].reference?.searchFields).toEqual(['code']);
    expect(back.fields[1].semantic).toBe('enum');
    expect(back.fields[1].enumValues).toEqual([{ value: 'DRAFT', label: 'Draft' }]);
    expect(back.relations).toHaveLength(1);
    expect(back.relations[0]).toMatchObject({ name: 'items', type: 'ONE_TO_MANY', composition: true });
    // V0.6 frontend visibility survives
    expect(back.fields[0].listVisible).toBe(true);
    expect(back.fields[0].formVisible).toBe(true);
  });
});

describe('suggestUi', () => {
  it('maps semantic/type to default UI components', () => {
    expect(suggestUi('decimal', 'money')).toBe('MoneyInput');
    expect(suggestUi('integer', 'reference')).toBe('ReferenceSelect');
    expect(suggestUi('string', 'enum')).toBe('StatusSelect');
    expect(suggestUi('string', 'dictionary')).toBe('DictionarySelect');
    expect(suggestUi('boolean', 'none')).toBe('Switch');
    expect(suggestUi('date', 'none')).toBe('DatePicker');
    expect(suggestUi('string', 'none')).toBe('Input');
  });
});

describe('validateModuleState (UX-level)', () => {
  it('flags missing id/name and empty fields', () => {
    const issues = validateModuleState(defaultModuleBuilderState());
    expect(issues.some((i) => i.section === 'basic')).toBe(true);
    expect(issues.some((i) => i.section === 'fields')).toBe(true);
  });

  it('flags reference missing target and enum missing values', () => {
    const state: ModuleBuilderState = {
      ...defaultModuleBuilderState(),
      id: 'po',
      name: 'PO',
      fields: [
        {
          name: 'a', type: 'integer', semantic: 'reference', ui: 'ReferenceSelect',
          required: false, primaryKey: false, unique: false, listVisible: true, searchable: false,
          formVisible: true, detailVisible: true, sortable: false, order: 0,
          reference: { target: '', valueField: 'id', labelField: 'name', searchFields: [] },
        },
        {
          name: 'b', type: 'string', semantic: 'enum', ui: 'StatusSelect',
          required: false, primaryKey: false, unique: false, listVisible: true, searchable: false,
          formVisible: true, detailVisible: true, sortable: false, order: 1,
          enumValues: [],
        },
      ],
    };
    const issues = validateModuleState(state);
    expect(issues.some((i) => i.message.includes('reference.target'))).toBe(true);
    expect(issues.some((i) => i.message.includes('enum value'))).toBe(true);
  });

  it('flags MANY_TO_MANY as unsupported and missing mappedBy', () => {
    const state: ModuleBuilderState = {
      ...defaultModuleBuilderState(),
      id: 'po',
      name: 'PO',
      fields: [{ name: 'a', type: 'string', semantic: 'none', ui: 'Input', required: false, primaryKey: false, unique: false, listVisible: true, searchable: false, formVisible: true, detailVisible: true, sortable: false, order: 0 }],
      relations: [
        { name: 'r1', type: 'MANY_TO_MANY', target: 'x', required: false, composition: false },
        { name: 'r2', type: 'ONE_TO_MANY', target: 'y', required: false, composition: false },
      ],
    };
    const issues = validateModuleState(state);
    expect(issues.some((i) => i.message.includes('MANY_TO_MANY'))).toBe(true);
    expect(issues.some((i) => i.message.includes('mappedBy'))).toBe(true);
  });
});
