import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layouts/MainLayout.vue'
import HexgentPage from '../pages/HexgentPage.vue'
import WorkspacePage from '../pages/WorkspacePage.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/hexgent' },
    {
      path: '/',
      component: MainLayout,
      children: [
        { path: 'hexgent', component: HexgentPage },
        { path: 'workspace', component: WorkspacePage },
        { path: 'workspace/documents/:documentCode', component: WorkspacePage },
      ],
    },
  ],
})

export default router
