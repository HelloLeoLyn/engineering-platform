// V07-WORK-005 — Import Review pure logic.
// Groups candidates for the review UI, applies Accept/Edit/Ignore decisions,
// and tells whether a candidate may be confirmed. No Vue dependencies —
// unit-testable.

import type { CandidateStatus, CandidateType, ImportCandidate, ImportDraft } from '../api/console';

export const STATUS_LABEL: Record<CandidateStatus, string> = {
  DETECTED: 'Detected',
  SUGGESTED: 'Suggested',
  CONFIRMED: 'Confirmed',
  IGNORED: 'Ignored',
};

export const TYPE_LABEL: Record<CandidateType, string> = {
  FIELD: 'Field',
  REFERENCE: 'Reference',
  RELATION: 'Relation',
  SEMANTIC: 'Semantic',
};

export type DecisionMap = Record<string, 'accept' | 'ignore'>;
export type EditMap = Record<string, Record<string, unknown>>;

export interface CandidateReviewState {
  decisions: DecisionMap;
  edits: EditMap;
}

export function defaultReviewState(): CandidateReviewState {
  return { decisions: {}, edits: {} };
}

/** Effective status of a candidate after user decisions (original status preserved for display). */
export function effectiveStatus(c: ImportCandidate, decisions: DecisionMap): CandidateStatus {
  const d = decisions[c.id];
  if (d === 'accept') return 'CONFIRMED';
  if (d === 'ignore') return 'IGNORED';
  return c.status; // DETECTED / SUGGESTED — still under review
}

/** Group candidates by type, preserving discovery order. */
export function groupByType(candidates: ImportCandidate[]): Record<CandidateType, ImportCandidate[]> {
  const groups: Record<CandidateType, ImportCandidate[]> = {
    FIELD: [],
    REFERENCE: [],
    RELATION: [],
    SEMANTIC: [],
  };
  for (const c of candidates) {
    groups[c.type]?.push(c);
  }
  return groups;
}

/** Human-readable one-line summary of a candidate payload. */
export function describe(c: ImportCandidate): string {
  const p = c.payload ?? {};
  switch (c.type) {
    case 'FIELD':
      return `${String(p.name ?? '?')} : ${String(p.type ?? 'string')}${p.primaryKey ? ' (PK)' : ''}${p.required ? ' (required)' : ''}`;
    case 'REFERENCE':
      return `${String(p.field ?? '?')} → ${String(p.targetModule ?? '?')} (value=${String(p.valueField ?? 'id')}${p.labelField ? `, label=${p.labelField}` : ''})`;
    case 'RELATION':
      return `${String(p.name ?? '?')} ${String(p.type ?? '?')} → ${String(p.targetModule ?? '?')}${p.mappedBy ? ` (mappedBy=${p.mappedBy})` : ''}${p.composition ? ' [composition]' : ''}`;
    case 'SEMANTIC':
      return `${String(p.field ?? '?')} → ${String(p.semantic ?? '?')}`;
    default:
      return c.id;
  }
}

/**
 * Can this candidate be confirmed? unresolved targets cannot (target must
 * exist in project modules). Heuristic semantics need human-provided data:
 *   enum     → requires enumValues (the DB heuristic can never provide them)
 *   reference → requires targetModule (a bare *_id has no target)
 * money is safe to confirm (type=money needs no extra values).
 */
export function confirmable(c: ImportCandidate, knownTargets: string[], edits: EditMap = {}): boolean {
  if (c.unresolved) return false;
  if (c.type === 'REFERENCE' || c.type === 'RELATION') {
    const target = String(c.payload?.targetModule ?? '');
    if (target && knownTargets.length > 0 && !knownTargets.includes(target)) {
      return false; // target not in current project modules — cannot confirm
    }
  }
  if (c.type === 'SEMANTIC') {
    const semantic = String(c.payload?.semantic ?? '');
    if (semantic === 'enum') {
      // enum values must come from the human (Edit), never from heuristics
      const edited = edits[c.id] ?? {};
      const values = edited.enumValues ?? edited.values ?? c.payload?.enumValues;
      return Array.isArray(values) && values.length > 0;
    }
    if (semantic === 'reference') {
      const edited = edits[c.id] ?? {};
      const target = edited.targetModule ?? c.payload?.targetModule;
      return Boolean(target && String(target).trim());
    }
  }
  return true;
}

/** Count of candidates currently marked CONFIRMED. */
export function confirmedCount(drafts: ImportDraft[], decisions: DecisionMap): number {
  let n = 0;
  for (const d of drafts) {
    for (const c of d.candidates) {
      if (effectiveStatus(c, decisions) === 'CONFIRMED') n++;
    }
  }
  return n;
}

/** Build decisions for "accept everything confirmable" (bulk helper). */
export function acceptAllDecisions(drafts: ImportDraft[], knownTargets: string[], edits: EditMap = {}): DecisionMap {
  const decisions: DecisionMap = {};
  for (const d of drafts) {
    for (const c of d.candidates) {
      if (confirmable(c, knownTargets, edits)) decisions[c.id] = 'accept';
    }
  }
  return decisions;
}
