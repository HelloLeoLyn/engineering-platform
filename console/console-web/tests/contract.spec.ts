// Project Builder targeted tests (V06-WORK-004)
import { describe, it, expect } from 'vitest';
import { buildContract, defaultBuilderState, type BuilderState } from '../src/utils/contract';

describe('buildContract', () => {
  it('produces Project Contract V2 with certified enterprise defaults', () => {
    const state: BuilderState = {
      ...defaultBuilderState(),
      projectName: 'Acme ERP',
      projectId: 'acme-erp',
      applicationProfile: 'enterprise',
      stackProfile: 'enterprise-java25',
      frontendTemplate: 'enterprise-admin',
      modules: ['supplier'],
    };
    const c = buildContract(state);
    expect(c.schemaVersion).toBe(1);
    expect((c.project as Record<string, unknown>).id).toBe('acme-erp');
    expect((c.application as Record<string, unknown>).profile).toBe('enterprise');
    expect((c.stack as Record<string, unknown>).profile).toBe('enterprise-java25');
    expect((c.frontends as { template: string }[])[0].template).toBe('enterprise-admin');
    expect(c.modules).toContain('supplier');
  });

  it('maps Product to product-reference + frontend-product-reference capabilities', () => {
    const state: BuilderState = {
      ...defaultBuilderState(),
      modules: ['product-reference'],
    };
    const c = buildContract(state);
    expect(c.modules).not.toContain('product-reference');
    const caps = c.capabilities as { id: string }[];
    expect(caps.map((x) => x.id)).toContain('product-reference');
    expect(caps.map((x) => x.id)).toContain('frontend-product-reference');
  });

  it('keeps base platform capabilities in order', () => {
    const c = buildContract(defaultBuilderState());
    const ids = (c.capabilities as { id: string }[]).map((x) => x.id);
    expect(ids[0]).toBe('web');
    expect(ids).toContain('platform-core');
    expect(ids).toContain('frontend-enterprise-management');
  });
});
