<script setup>
import SearchResults from './search-results.vue'
import SegmentedControl from './segmented-control.vue'
import StatGrid from './stat-grid.vue'
import { useKnowledgeFoldersStore } from '../stores/knowledge-folders'
import { SEARCH_MODES, useSearchStore } from '../stores/search'
import { formatTime } from '../utils/formatters'

const knowledgeStore = useKnowledgeFoldersStore()
const searchStore = useSearchStore()

async function rebuildAll() {
  await searchStore.rebuildIndex()
  await knowledgeStore.fetchFolders()
}
</script>

<template>
  <section class="knowledge-pane knowledge-pane--search" aria-label="知识库检索测试">
    <header class="knowledge-pane__header">
      <div>
        <p class="eyebrow">检索测试</p>
        <h3>索引与搜索</h3>
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

<!--    <el-alert-->
<!--      class="settings-inline-alert retrieval-upgrade-alert"-->
<!--      type="info"-->
<!--      title="当前索引支持中文分词、代码标识符和流程图节点检索。升级后请先执行“全量重建”。"-->
<!--      :closable="false"-->
<!--      show-icon-->
<!--    />-->

    <StatGrid
      :items="[
        { label: '已索引文档', value: searchStore.indexStatus?.indexedDocumentCount ?? '-' },
        { label: '未索引文档', value: searchStore.indexStatus?.unindexedDocumentCount ?? '-' },
        { label: '索引 chunks', value: searchStore.indexStatus?.indexedChunkCount ?? '-' },
        { label: '向量模型', value: searchStore.indexStatus?.embeddingConfigured ? '已启用' : '未启用' }
      ]"
    />

    <section class="index-toolbar">
      <div class="index-path-panel">
        <span>索引目录</span>
        <p class="path-text path-text--index">{{ searchStore.indexStatus?.indexPath || '索引目录读取中...' }}</p>
        <p class="muted-text">最后索引：{{ formatTime(searchStore.indexStatus?.lastIndexedAt) }}</p>
      </div>
    </section>

    <el-alert
      v-if="searchStore.indexError"
      class="settings-inline-alert"
      type="error"
      :title="searchStore.indexError"
      :closable="false"
      show-icon
    />

    <div v-if="searchStore.rebuildResult" class="result-strip result-strip--three">
      <span>索引文档 {{ searchStore.rebuildResult.indexedDocumentCount }}</span>
      <span>索引 chunks {{ searchStore.rebuildResult.indexedChunkCount }}</span>
      <span>耗时 {{ searchStore.rebuildResult.durationMs }} ms</span>
    </div>

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
