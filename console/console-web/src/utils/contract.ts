// Project Contract assembly (V06-WORK-004).
// Pure function: wizard state → Project Contract V2 (the SAME shape the
// existing generator consumes). No second contract model invented here.

export interface BuilderState {
  projectName: string;
  projectId: string;
  description: string;
  outputLocation: string;
  basePackage: string;
  applicationProfile: string;
  stackProfile: string;
  frontendTemplate: string;
  modules: string[];
}

export interface ContractModule {
  id: string;
}

export function buildContract(state: BuilderState): Record<string, unknown> {
  const modules: string[] = [];
  const capabilities: ContractModule[] = [];

  // Business modules: registry-driven. Product → product-reference capability
  // (+ frontend-product-reference); Supplier → supplier generic module.
  for (const m of state.modules) {
    if (m === 'product-reference') {
      capabilities.push({ id: 'product-reference' });
      capabilities.push({ id: 'frontend-product-reference' });
    } else {
      modules.push(m);
    }
  }

  return {
    schemaVersion: 1,
    project: {
      id: state.projectId || 'console-project',
      name: state.projectName || 'Console Project',
      version: '1.0.0',
      basePackage: state.basePackage || 'com.acme.core',
      groupId: 'com.acme',
      artifactId: state.projectId || 'console-project',
      description: state.description || undefined,
    },
    platform: { id: 'engineering-platform' },
    application: { profile: state.applicationProfile || 'enterprise' },
    stack: { profile: state.stackProfile || 'enterprise-java25' },
    frontends: [{ id: 'admin', template: state.frontendTemplate || 'enterprise-admin' }],
    modules,
    capabilities: [
      { id: 'web' },
      { id: 'validation' },
      { id: 'exception-handling' },
      { id: 'platform-core' },
      { id: 'authentication' },
      { id: 'rbac' },
      { id: 'organization' },
      { id: 'data-permission' },
      { id: 'menu' },
      { id: 'dictionary' },
      { id: 'operation-log' },
      ...capabilities,
      { id: 'frontend-shell' },
      { id: 'frontend-auth' },
      { id: 'frontend-permission' },
      { id: 'frontend-enterprise-management' },
      // V06-WORK-006: generated projects always carry the Runtime Recipe
      // (dev-start / dev-stop / dev-status + .runtime state contract).
      { id: 'runtime-recipe' },
    ],
    quality: { minimum: 'Q2' },
  };
}

export function defaultBuilderState(): BuilderState {
  return {
    projectName: '',
    projectId: '',
    description: '',
    outputLocation: '',
    basePackage: 'com.acme.core',
    applicationProfile: '',
    stackProfile: '',
    frontendTemplate: '',
    modules: [],
  };
}
