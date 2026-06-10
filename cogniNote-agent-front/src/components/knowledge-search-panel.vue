<script setup>
// knowledge-search-panel 负责知识库检索验证和索引状态展示。
import { computed } from 'vue'
import SearchResults from './search-results.vue'
import SegmentedControl from './segmented-control.vue'
import { useKnowledgeFoldersStore } from '../stores/knowledge-folders'
import { SEARCH_MODES, useSearchStore } from '../stores/search'
import { formatTime } from '../utils/formatters'

const knowledgeStore = useKnowledgeFoldersStore()
const searchStore = useSearchStore()
const indexStatusItems = computed(() => [
  { label: '已索引', value: searchStore.indexStatus?.indexedDocumentCount ?? '-' },
  { label: '未索引', value: searchStore.indexStatus?.unindexedDocumentCount ?? '-' },
  { label: 'Chunks', value: searchStore.indexStatus?.indexedChunkCount ?? '-' },
  { label: '向量', value: searchStore.indexStatus?.embeddingConfigured ? '可用' : '未启用' }
])

/**
 * 重建后同步目录列表，让检索页和资料页共享同一份最新状态。
 */
async function rebuildAll() {
  await searchStore.rebuildIndex()
  await knowledgeStore.fetchFolders()
}
</script>

<template>
  <section class="knowledge-pane knowledge-pane--search" aria-label="知识库检索测试">
    <header class="knowledge-pane__header knowledge-pane__header--compact">
      <div>
        <p class="eyebrow">检索测试</p>
        <h3>搜索</h3>
        <p class="muted-text">验证关键词、向量和混合检索效果。</p>
      </div>
      <div class="header-actions">
        <el-button :loading="searchStore.isLoadingIndexStatus" @click="searchStore.fetchIndexStatus">
          刷新索引
        </el-button>
        <el-button type="primary" :loading="searchStore.isRebuildingIndex" @click="rebuildAll">
          全量重建
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="searchStore.indexError"
      class="settings-inline-alert"
      type="error"
      :title="searchStore.indexError"
      :closable="false"
      show-icon
    />

    <form class="search-form" @submit.prevent="searchStore.searchKnowledge">
      <label class="field field--full">
        <span>检索内容</span>
        <el-input v-model="searchStore.query" placeholder="输入关键词或问题片段" autocomplete="off" />
      </label>

      <div class="search-form__controls">
        <SegmentedControl v-model="searchStore.mode" :options="SEARCH_MODES" label="检索模式" />

        <label class="field field--small">
          <span>Top K</span>
          <el-input-number v-model="searchStore.topK" :min="1" :max="50" controls-position="right" />
        </label>

        <el-button type="primary" native-type="submit" :loading="searchStore.isSearching">
          搜索
        </el-button>
      </div>
    </form>

    <section class="knowledge-inline-status" aria-label="索引摘要">
      <span v-for="item in indexStatusItems" :key="item.label">
        <strong>{{ item.value }}</strong>
        {{ item.label }}
      </span>
      <span>最后索引 {{ formatTime(searchStore.indexStatus?.lastIndexedAt) }}</span>
    </section>

    <details class="knowledge-disclosure">
      <summary>索引目录</summary>
      <p class="path-text path-text--index">{{ searchStore.indexStatus?.indexPath || '索引目录读取中...' }}</p>
    </details>

    <div v-if="searchStore.rebuildResult" class="result-strip result-strip--three">
      <span>索引文档 {{ searchStore.rebuildResult.indexedDocumentCount }}</span>
      <span>索引 chunks {{ searchStore.rebuildResult.indexedChunkCount }}</span>
      <span>耗时 {{ searchStore.rebuildResult.durationMs }} ms</span>
    </div>

    <el-alert
      v-if="searchStore.searchError"
      class="settings-inline-alert"
      type="error"
      :title="searchStore.searchError"
      :closable="false"
      show-icon
    />

    <div class="search-results-scroll">
      <SearchResults :result="searchStore.searchResult" />
    </div>
  </section>
</template>
