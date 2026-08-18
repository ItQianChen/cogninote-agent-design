import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '../views/chat-view.vue'
import KnowledgeView from '../views/knowledge-view.vue'
import SettingsView from '../views/settings-view.vue'

/**
 * 桌面应用路由表。
 *
 * <p>/model-config 是旧入口，继续重定向到模型概览，避免历史书签失效。</p>
 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: { name: 'chat' } },
    { path: '/chat', name: 'chat', component: ChatView },
    { path: '/knowledge', name: 'knowledge', component: KnowledgeView },
    { path: '/model-config', name: 'model-config', redirect: { name: 'settings', query: { item: 'model-overview' } } },
    { path: '/settings', name: 'settings', component: SettingsView }
  ]
})
