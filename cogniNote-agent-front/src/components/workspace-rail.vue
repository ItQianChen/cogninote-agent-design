<script setup>
import { computed } from 'vue'
import { Activity, Library, MessageSquareText, PanelLeftClose, PanelLeftOpen, Settings2 } from 'lucide-vue-next'
import { APP_BRAND_MARK, APP_DISPLAY_NAME } from '../config/brand'
import { useLayoutStore } from '../stores/layout'
import { useSystemStore } from '../stores/system'

const layoutStore = useLayoutStore()
const systemStore = useSystemStore()

/**
 * 全局工作区导航栏。
 *
 * <p>状态灯只反映后端 API 连接情况，不代表模型或知识库索引健康。</p>
 */
const navItems = [
  { name: 'chat', label: '对话', icon: MessageSquareText },
  { name: 'knowledge', label: '知识库', icon: Library },
  { name: 'settings', label: '设置', icon: Settings2 }
]

const statusState = computed(() => {
  if (systemStore.isLoading) {
    return 'loading'
  }
  return systemStore.error ? 'error' : 'ok'
})
const statusTitle = computed(() => `后端${systemStore.connectionLabel}`)
</script>

<template>
  <aside class="workspace-rail" aria-label="全局导航">
    <RouterLink class="workspace-rail__brand" :to="{ name: 'chat' }" :aria-label="`打开${APP_DISPLAY_NAME}对话`">
      <span>{{ APP_BRAND_MARK }}</span>
    </RouterLink>

    <nav class="workspace-rail__nav" aria-label="主要模块">
      <RouterLink
        v-for="item in navItems"
        :key="item.name"
        class="workspace-rail__button"
        :to="{ name: item.name }"
        :title="item.label"
        :aria-label="item.label"
      >
        <component :is="item.icon" aria-hidden="true" />
      </RouterLink>
    </nav>

    <div class="workspace-rail__bottom">
      <button
        class="workspace-rail__button"
        type="button"
        :title="layoutStore.contextSidebarToggleTitle"
        :aria-label="layoutStore.contextSidebarToggleTitle"
        :aria-expanded="!layoutStore.isContextSidebarCollapsed"
        aria-controls="workspace-context-sidebar"
        @click="layoutStore.toggleContextSidebar"
      >
        <PanelLeftOpen v-if="layoutStore.isContextSidebarCollapsed" aria-hidden="true" />
        <PanelLeftClose v-else aria-hidden="true" />
      </button>

      <span
        class="workspace-rail__status"
        :class="`workspace-rail__status--${statusState}`"
        :title="statusTitle"
        role="status"
        :aria-label="statusTitle"
      >
        <Activity aria-hidden="true" />
      </span>
    </div>
  </aside>
</template>
