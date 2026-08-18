import { createRouter, createWebHistory } from 'vue-router';
import AppLayout from '../layouts/AppLayout.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', name: 'overview', component: () => import('../views/OverviewView.vue'), meta: { title: 'Overview' } },
        { path: 'projects', name: 'projects', component: () => import('../views/ProjectsView.vue'), meta: { title: 'Projects' } },
        { path: 'builder', name: 'builder', component: () => import('../views/ProjectBuilderView.vue'), meta: { title: 'Project Builder' } },
        { path: 'modules', name: 'modules', component: () => import('../views/BusinessModulesView.vue'), meta: { title: 'Business Modules' } },
        { path: 'modules/import-review', name: 'import-review', component: () => import('../views/ImportReviewView.vue'), meta: { title: 'Import Review' } },
        { path: 'modules/builder/:id?', name: 'module-builder', component: () => import('../views/ModuleBuilderView.vue'), meta: { title: 'Module Builder' } },
        { path: 'templates', name: 'templates', component: () => import('../views/PlaceholderView.vue'), meta: { title: 'Templates' } },
        { path: 'build-run', name: 'build-run', component: () => import('../views/BuildRunView.vue'), meta: { title: 'Build & Run' } },
        { path: 'settings', name: 'settings', component: () => import('../views/PlaceholderView.vue'), meta: { title: 'Settings' } },
      ],
    },
  ],
});

export default router;
