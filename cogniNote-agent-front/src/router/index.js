import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '../views/chat-view.vue'
import KnowledgeView from '../views/knowledge-view.vue'
import SettingsView from '../views/settings-view.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: { name: 'chat' } },
    { path: '/chat', name: 'chat', component: ChatView },
    { path: '/knowledge', name: 'knowledge', component: KnowledgeView },
    { path: '/model-config', name: 'model-config', redirect: { name: 'settings', query: { item: 'model-chat' } } },
    { path: '/settings', name: 'settings', component: SettingsView }
  ]
})
