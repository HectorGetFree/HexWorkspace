import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layouts/MainLayout.vue'
import HexgentPage from '../pages/HexgentPage.vue'
import WorkspacePage from '../pages/WorkspacePage.vue'
import LoginPage from '../pages/LoginPage.vue'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/hexgent' },
    { path: '/login', component: LoginPage, meta: { guestOnly: true } },
    {
      path: '/',
      component: MainLayout,
      meta: { requiresAuth: true },
      children: [
        { path: 'hexgent', component: HexgentPage },
        { path: 'workspace', component: WorkspacePage },
        { path: 'workspace/documents/:documentCode', component: WorkspacePage },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (auth.initializing) await auth.initializeAuth()
  if (to.meta.requiresAuth && !auth.authenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.guestOnly && auth.authenticated) return '/hexgent'
})

export default router
