// Console API client (V06-WORK-004). Talks to console-server /api endpoints.
export interface MetaResponse {
  applicationProfiles: CatalogItem[];
  stackProfiles: StackItem[];
  frontendTemplates: CatalogItem[];
  modules: CatalogItem[];
  platform: { id: string; name: string; generator: string };
}

export interface CatalogItem {
  id: string;
  description?: string;
  status?: string;
  kind?: string;
}

export interface StackItem extends CatalogItem {
  details?: Record<string, unknown>;
}

export interface OverviewResponse {
  projects: number;
  generatedModules: number;
  certifiedTemplates: number;
  lastGeneration: ProjectRecord | null;
  recentProjects: ProjectRecord[];
}

export interface ProjectRecord {
  id?: string;
  name: string;
  profile?: string;
  stack?: string;
  frontend?: string;
  modules?: string[];
  location?: string;
  lastGenerated?: string;
  status?: string;
  createdAt?: string;
}

export interface GenerateRequest {
  contract: Record<string, unknown>;
  location?: string;
}

export interface GenerateResponse {
  status: 'SUCCESS' | 'FAILED';
  generatedFiles?: number;
  outputDir?: string;
  modules?: string[];
  errors?: { code?: string; category?: string; message?: string }[];
}

export interface ValidateResponse {
  valid: boolean;
  errors?: { category: string; message: string }[];
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  if (!res.ok) {
    throw new Error(`Console API ${res.status}: ${await res.text()}`);
  }
  return (await res.json()) as T;
}

export const consoleApi = {
  meta: () => request<MetaResponse>('/api/meta'),
  overview: () => request<OverviewResponse>('/api/overview'),
  projects: () => request<ProjectRecord[]>('/api/projects'),
  validate: (contract: Record<string, unknown>) =>
    request<ValidateResponse>('/api/validate', { method: 'POST', body: JSON.stringify(contract) }),
  preview: (contract: Record<string, unknown>) =>
    request<{ yaml: string }>('/api/preview', { method: 'POST', body: JSON.stringify(contract) }),
  generate: (req: GenerateRequest) =>
    request<GenerateResponse>('/api/generate', { method: 'POST', body: JSON.stringify(req) }),
  // V06-WORK-005: business modules
  modules: () => request<ModuleRecord[]>('/api/modules'),
  saveModule: (manifest: Record<string, unknown>) =>
    request<ModuleRecord>('/api/modules', { method: 'POST', body: JSON.stringify({ manifest }) }),
  deleteModule: (id: string) =>
    request<{ deleted: string }>(`/api/modules/${id}`, { method: 'DELETE' }),
  generateModule: (id: string, location?: string) =>
    request<GenerateResponse>(`/api/modules/${id}/generate`, {
      method: 'POST',
      body: JSON.stringify({ location }),
    }),
  mysqlTest: (conn: MySqlConn) =>
    request<{ ok: boolean }>('/api/modules/import/mysql/test', { method: 'POST', body: JSON.stringify(conn) }),
  mysqlTables: (conn: MySqlConn) =>
    request<{ tables: string[] }>('/api/modules/import/mysql/tables', { method: 'POST', body: JSON.stringify(conn) }),
  mysqlImport: (conn: MySqlConn, table: string) =>
    request<{ fields: FieldDef[] }>('/api/modules/import/mysql/import', {
      method: 'POST',
      body: JSON.stringify({ ...conn, table }),
    }),
  excelImport: (file: File) => {
    const body = new Blob([file], { type: 'application/octet-stream' });
    return request<{ rows: string[][] }>('/api/modules/import/excel/import', {
      method: 'POST',
      body,
    });
  },
  // V06-WORK-006: Build / Run developer loop (all via Runtime Recipe)
  preflight: (location: string) =>
    request<PreflightResponse>('/api/runtime/preflight', { method: 'POST', body: JSON.stringify({ location }) }),
  build: (location: string, target: string) =>
    request<BuildStarted>('/api/runtime/build', { method: 'POST', body: JSON.stringify({ location, target }) }),
  buildStatus: (location: string, target: string) =>
    request<BuildTaskState>(`/api/runtime/build/status?location=${encodeURIComponent(location)}&target=${target}`),
  runtimeStart: (location: string, target: string) =>
    request<RuntimeActionResult>('/api/runtime/start', { method: 'POST', body: JSON.stringify({ location, target }) }),
  runtimeStop: (location: string) =>
    request<RuntimeActionResult>('/api/runtime/stop', { method: 'POST', body: JSON.stringify({ location }) }),
  runtimeRestart: (location: string, target: string) =>
    request<RuntimeActionResult>('/api/runtime/restart', { method: 'POST', body: JSON.stringify({ location, target }) }),
  runtimeStatus: (location: string) =>
    request<RuntimeStatusResponse>(`/api/runtime/status?location=${encodeURIComponent(location)}`),
  runtimeLogs: (location: string, target: string, lines = 100) =>
    request<LogsResponse>(`/api/runtime/logs?location=${encodeURIComponent(location)}&target=${target}&lines=${lines}`),
  runtimeOpen: (location: string) =>
    request<{ backend: string; frontend: string }>(`/api/runtime/open?location=${encodeURIComponent(location)}`),
};

export interface ModuleRecord {
  id: string;
  name?: string;
  status?: string;
  yaml?: string;
}

// ---- V06-WORK-006: Build / Run types ----

export interface PreflightCheck {
  name: string;
  status: 'READY' | 'WARNING' | 'BLOCKED';
  required?: unknown;
  detected?: unknown;
  message?: string;
}

export interface PreflightResponse {
  checks: PreflightCheck[];
  overall: 'READY' | 'WARNING' | 'BLOCKED';
  projectDir: string;
}

export interface BuildStarted {
  id: string;
  target: string;
  state: 'QUEUED' | 'RUNNING' | 'PASS' | 'FAIL';
}

export interface BuildTaskState {
  id: string;
  target: string;
  state: 'QUEUED' | 'RUNNING' | 'PASS' | 'FAIL' | 'UNKNOWN';
  startedAt?: number;
  durationMs?: number;
  exitCode?: number;
  error?: string;
  log?: string;
}

export interface RuntimeActionResult {
  status: string;
  exitCode?: number;
  message?: string;
  errorCode?: string;
}

export interface SideStatus {
  pid: string;
  port: string;
  url: string;
  status: 'RUNNING-READY' | 'RUNNING' | 'STOPPED' | 'STALE';
}

export interface RuntimeStatusResponse {
  backend: SideStatus;
  frontend: SideStatus;
  overall: 'RUNNING' | 'STOPPED';
}

export interface LogsResponse {
  target: string;
  exists: boolean;
  totalLines?: number;
  lines?: string[];
  error?: string;
}

export interface MySqlConn {
  host: string;
  port: number;
  database: string;
  username: string;
  password: string;
}

export interface FieldDef {
  name: string;
  type: string;
  required?: boolean;
  primaryKey?: boolean;
  unique?: boolean;
  length?: number;
  precision?: number;
  scale?: number;
  defaultValue?: string;
  comment?: string;
  dictionary?: string;
  label?: string;
  listVisible?: boolean;
  searchable?: boolean;
  formVisible?: boolean;
  detailVisible?: boolean;
  order?: number;
}
