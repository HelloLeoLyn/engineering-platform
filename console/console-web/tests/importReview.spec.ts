// V07-WORK-005 — Import Review pure logic tests.
import { describe, expect, it } from 'vitest';
import type { ImportCandidate, ImportDraft } from '../src/api/console';
import {
  acceptAllDecisions,
  confirmedCount,
  confirmable,
  describe as describeCandidate,
  effectiveStatus,
  groupByType,
  type DecisionMap,
} from '../src/utils/importReview';

function cand(partial: Partial<ImportCandidate>): ImportCandidate {
  return {
    id: 'x',
    type: 'FIELD',
    status: 'DETECTED',
    source: 'DATABASE_COLUMN',
    moduleId: 'm',
    table: 't',
    payload: {},
    ...partial,
  };
}

const draft: ImportDraft = {
  table: 'purchase_order',
  moduleId: 'purchase-order',
  entity: 'PurchaseOrder',
  fields: [],
  candidates: [
    cand({ id: 'po.id.field', type: 'FIELD', status: 'DETECTED', payload: { name: 'id', type: 'long', primaryKey: true } }),
    cand({ id: 'po.supplier_id.reference', type: 'REFERENCE', status: 'DETECTED', source: 'DATABASE_FK', payload: { field: 'supplier_id', targetModule: 'supplier', valueField: 'id' } }),
    cand({ id: 'po.supplier_id.relation', type: 'RELATION', status: 'DETECTED', source: 'DATABASE_FK', payload: { name: 'supplier', type: 'MANY_TO_ONE', targetModule: 'supplier' } }),
    cand({ id: 'po.reverse.items.relation', type: 'RELATION', status: 'SUGGESTED', source: 'DATABASE_FK', payload: { name: 'items', type: 'ONE_TO_MANY', targetModule: 'purchase-order-item', mappedBy: 'purchase_order_id', composition: false } }),
    cand({ id: 'po.total_amount.semantic.money', type: 'SEMANTIC', status: 'SUGGESTED', source: 'TYPE_HEURISTIC', payload: { field: 'total_amount', semantic: 'money' } }),
  ],
};

describe('effectiveStatus', () => {
  it('keeps original status until a decision is made', () => {
    expect(effectiveStatus(draft.candidates[0], {})).toBe('DETECTED');
    expect(effectiveStatus(draft.candidates[3], {})).toBe('SUGGESTED');
  });
  it('accept → CONFIRMED, ignore → IGNORED', () => {
    const c = draft.candidates[0];
    expect(effectiveStatus(c, { [c.id]: 'accept' })).toBe('CONFIRMED');
    expect(effectiveStatus(c, { [c.id]: 'ignore' })).toBe('IGNORED');
  });
});

describe('groupByType', () => {
  it('groups candidates into the four types', () => {
    const g = groupByType(draft.candidates);
    expect(g.FIELD).toHaveLength(1);
    expect(g.REFERENCE).toHaveLength(1);
    expect(g.RELATION).toHaveLength(2);
    expect(g.SEMANTIC).toHaveLength(1);
  });
});

describe('confirmable', () => {
  it('blocks unresolved candidates', () => {
    const c = cand({ type: 'REFERENCE', unresolved: true, payload: { targetModule: 'supplier' } });
    expect(confirmable(c, ['supplier'])).toBe(false);
  });
  it('blocks reference/relation when target not in project modules', () => {
    const c = cand({ type: 'RELATION', payload: { targetModule: 'ghost' } });
    expect(confirmable(c, ['supplier', 'product'])).toBe(false);
    expect(confirmable(c, ['supplier', 'ghost'])).toBe(true);
    // FIELD candidates are always confirmable
    expect(confirmable(draft.candidates[0], [])).toBe(true);
  });
});

describe('confirmedCount / acceptAllDecisions', () => {
  it('counts only CONFIRMED', () => {
    expect(confirmedCount([draft], {})).toBe(0);
    const decisions: DecisionMap = { 'po.id.field': 'accept', 'po.supplier_id.reference': 'accept' };
    expect(confirmedCount([draft], decisions)).toBe(2);
  });
  it('acceptAll skips non-confirmable (unresolved/ghost target)', () => {
    const withGhost = {
      ...draft,
      candidates: [
        ...draft.candidates,
        cand({ id: 'po.ghost_id.reference', type: 'REFERENCE', payload: { targetModule: 'ghost' } }),
      ],
    };
    const d = acceptAllDecisions([withGhost], ['supplier', 'purchase-order-item']);
    expect(d['po.id.field']).toBe('accept');
    expect(d['po.supplier_id.reference']).toBe('accept');
    expect(d['po.ghost_id.reference']).toBeUndefined(); // ghost target skipped
  });
});

describe('describe', () => {
  it('renders readable summaries', () => {
    expect(describeCandidate(draft.candidates[0])).toContain('id');
    expect(describeCandidate(draft.candidates[1])).toContain('supplier');
    expect(describeCandidate(draft.candidates[3])).toContain('ONE_TO_MANY');
    expect(describeCandidate(draft.candidates[4])).toContain('money');
  });
});
