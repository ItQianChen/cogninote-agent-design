<script setup>
import { computed, onMounted, ref } from 'vue'
import { FolderOpen, Network, RefreshCw, Search, Settings2 } from 'lucide-vue-next'
import KnowledgeFolderPanel from './knowledge-folder-panel.vue'
import KnowledgeGraphPanel from './knowledge-graph-panel.vue'
import KnowledgeSearchPanel from './knowledge-search-panel.vue'
import { useKnowledgeFoldersStore } from '../stores/knowledge-folders'
import { useModelConfigStore } from '../stores/model-config'
import { useSearchStore } from '../stores/search'

const knowledgeStore = useKnowledgeFoldersStore()
const searchStore = useSearchStore()
const modelConfigStore = useModelConfigStore()
const activePanel = ref('folders')

const panelOptions = [
  { id: 'folders', label: '资料管理', icon: FolderOpen },
  { id: 'search', label: '检索测试', icon: Search },
  { id: 'graph', label: '知识图谱', icon: Network }
]

const embeddingReady = computed(() => {
  const config = modelConfigStore.activeEmbeddingConfig
  return Boolean(config?.apiKeyConfigured && config?.modelName)
})
const isRefreshing = computed(() => knowledgeStore.isLoading || searchStore.isLoadingIndexStatus)
const compactSummary = computed(() => {
  const indexed = searchStore.indexStatus?.indexedDocumentCount ?? 0
  return `${knowledgeStore.stats.folderCount} 个目录 · ${knowledgeStore.stats.documentCount} 个文档 · ${indexed} 个已索引`
})

onMounted(() => {
  void Promise.all([
    knowledgeStore.ensureFoldersLoaded(),
    searchStore.ensureIndexStatusLoaded(),
    modelConfigStore.ensureModelConfigLoaded()
  ])
})

async function refreshWorkbench() {
  await Promise.all([
    knowledgeStore.fetchFolders(),
    searchStore.fetchIndexStatus(),
    modelConfigStore.ensureModelConfigLoaded()
  ])
}
</script>

<template>
  <section class="knowledge-workbench">
    <header class="knowledge-workbench__header">
      <div>
        <p class="eyebrow">知识库工作区</p>
        <h2>知识库</h2>
        <p class="subtitle">{{ compactSummary }}</p>
      </div>

      <div class="knowledge-workbench__actions">
        <button class="secondary-button" type="button" :disabled="isRefreshing" @click="refreshWorkbench">
          <RefreshCw aria-hidden="true" />
          <span>刷新</span>
        </button>
        <RouterLink class="secondary-button" :to="{ name: 'settings', query: { item: 'model-embedding' } }">
          <Settings2 aria-hidden="true" />
          <span>向量模型</span>
        </RouterLink>
      </div>
    </header>

    <nav class="knowledge-workbench-tabs" aria-label="知识库工作区视图">
      <button
        v-for="option in panelOptions"
        :key="option.id"
        type="button"
        :class="{ active: activePanel === option.id }"
        @click="activePanel = option.id"
      >
        <component :is="option.icon" aria-hidden="true" />
        <span>{{ option.label }}</span>
      </button>
    </nav>

    <el-alert
      v-if="activePanel === 'search' && !embeddingReady"
      class="settings-inline-alert"
      type="warning"
      title="尚未配置可用向量模型；知识库仍可使用关键词检索，向量/混合检索会受限。"
      :closable="false"
      show-icon
    />

    <div class="knowledge-workbench__panel">
      <KnowledgeFolderPanel v-if="activePanel === 'folders'" />
      <KnowledgeSearchPanel v-else-if="activePanel === 'search'" />
      <KnowledgeGraphPanel v-else />
    </div>
  </section>
</template>
