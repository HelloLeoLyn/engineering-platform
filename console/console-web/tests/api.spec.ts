// Console API client tests (V06-WORK-004): validate request/response shapes.
import { describe, it, expect, vi, afterEach } from 'vitest';
import { consoleApi } from '../src/api/console';

describe('consoleApi', () => {
  afterEach(() => vi.restoreAllMocks());

  it('validate returns categorized errors for bad contracts', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      json: async () => ({
        valid: false,
        errors: [{ category: 'Unsupported Application Profile', message: 'not certified' }],
      }),
    } as Response);
    const res = await consoleApi.validate({ application: { profile: 'nope' } } as never);
    expect(res.valid).toBe(false);
    expect(res.errors?.[0].category).toBe('Unsupported Application Profile');
  });

  it('preview returns yaml text', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      json: async () => ({ yaml: 'schemaVersion: 1\n' }),
    } as Response);
    const res = await consoleApi.preview({} as never);
    expect(res.yaml).toContain('schemaVersion');
  });

  it('generate posts contract and returns success shape', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      json: async () => ({ status: 'SUCCESS', generatedFiles: 251, modules: ['supplier'] }),
    } as Response);
    await consoleApi.generate({ contract: { schemaVersion: 1 } as never, location: '/tmp/x' });
    const [url, init] = spy.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/generate');
    expect(String(init.body)).toContain('schemaVersion');
  });
});
