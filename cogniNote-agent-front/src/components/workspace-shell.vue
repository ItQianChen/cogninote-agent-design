<script setup>
import WorkspaceRail from './workspace-rail.vue'
import ContextSidebar from './context-sidebar.vue'
import { useLayoutStore } from '../stores/layout'

const layoutStore = useLayoutStore()
</script>

<template>
  <main
    class="workspace-shell"
    :class="{ 'workspace-shell--context-collapsed': layoutStore.isContextSidebarCollapsed }"
  >
    <WorkspaceRail />
    <ContextSidebar />
    <section class="workspace-main" aria-label="主工作区">
      <slot />
    </section>
  </main>
</template>
