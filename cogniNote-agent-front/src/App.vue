<script setup>
import { computed, onMounted, ref } from 'vue'

const modules = [
  {
    name: '对话',
    description: 'RAG 问答入口，后续用于提问、流式回答和引用来源展示。',
    state: '待实现'
  },
  {
    name: '知识库',
    description: '本地文件夹导入、文档解析、SQLite 保存、Lucene 索引和检索。',
    state: '可检索'
  },
  {
    name: '模型配置',
    description: '后续通过 Spring AI 抽象配置对话模型和 Embedding 模型。',
    state: '待实现'
  },
  {
    name: '系统设置',
    description: '管理数据目录、索引目录、Top K 和混合检索权重。',
    state: '待实现'
  }
]

const searchModes = [
  { label: '关键词', value: 'KEYWORD' },
  { label: '向量', value: 'VECTOR' },
  { label: '混合', value: 'HYBRID' }
]

const systemStatus = ref(null)
const indexStatus = ref(null)
const documents = ref([])
const ingestResult = ref(null)
const rebuildResult = ref(null)
const searchResult = ref(null)
const isLoadingStatus = ref(true)
const isLoadingIndexStatus = ref(false)
const isLoadingDocuments = ref(false)
const isIngesting = ref(false)
const isRebuildingIndex = ref(false)
const isSearching = ref(false)
const statusError = ref('')
const indexError = ref('')
const documentError = ref('')
const searchError = ref('')
const folderPath = ref('')
const recursive = ref(true)
const searchQuery = ref('')
const searchMode = ref('KEYWORD')
const searchTopK = ref(8)

const connectionLabel = computed(() => {
  if (isLoadingStatus.value) {
    return '连接中'
  }

  return statusError.value ? '未连接' : '已连接'
})

const connectionClass = computed(() => ({
  'status-pill': true,
  'status-pill--loading': isLoadingStatus.value,
  'status-pill--error': Boolean(statusError.value),
  'status-pill--ok': !isLoadingStatus.value && !statusError.value
}))

const documentStats = computed(() => {
  const parsed = documents.value.filter((document) => document.status === 'PARSED').length
  const failed = documents.value.filter((document) => document.status === 'FAILED').length
  const chunks = documents.value.reduce((total, document) => total + document.chunkCount, 0)

  return { parsed, failed, chunks }
})

async function fetchJson(url, options = {}) {
  const response = await fetch(url, options)
  const payload = await response.json().catch(() => null)

  if (!response.ok) {
    throw new Error(payload?.message || `HTTP ${response.status}`)
  }

  return payload
}

async function fetchSystemStatus() {
  isLoadingStatus.value = true
  statusError.value = ''

  try {
    systemStatus.value = await fetchJson('/api/system/status')
  } catch (error) {
    systemStatus.value = null
    // Keep this text explicit so first-run users know the backend service is the missing piece.
    statusError.value = `后端服务暂不可用：${error.message}`
  } finally {
    isLoadingStatus.value = false
  }
}

async function fetchDocuments() {
  isLoadingDocuments.value = true
  documentError.value = ''

  try {
    documents.value = await fetchJson('/api/documents')
  } catch (error) {
    documents.value = []
    documentError.value = `文档列表读取失败：${error.message}`
  } finally {
    isLoadingDocuments.value = false
  }
}

async function fetchIndexStatus() {
  isLoadingIndexStatus.value = true
  indexError.value = ''

  try {
    indexStatus.value = await fetchJson('/api/index/status')
  } catch (error) {
    indexStatus.value = null
    indexError.value = `索引状态读取失败：${error.message}`
  } finally {
    isLoadingIndexStatus.value = false
  }
}

async function ingestDocuments() {
  const trimmedFolderPath = folderPath.value.trim()
  if (!trimmedFolderPath) {
    documentError.value = '请输入要导入的本地目录路径'
    return
  }

  isIngesting.value = true
  ingestResult.value = null
  documentError.value = ''

  try {
    ingestResult.value = await fetchJson('/api/documents/ingest', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        folderPath: trimmedFolderPath,
        recursive: recursive.value
      })
    })
    await fetchDocuments()
    await fetchIndexStatus()
  } catch (error) {
    documentError.value = `导入失败：${error.message}`
  } finally {
    isIngesting.value = false
  }
}

async function deleteDocument(id) {
  documentError.value = ''

  try {
    await fetchJson(`/api/documents/${id}`, { method: 'DELETE' })
    await fetchDocuments()
    await fetchIndexStatus()
    if (searchResult.value?.hits?.length) {
      await searchKnowledge()
    }
  } catch (error) {
    documentError.value = `删除索引记录失败：${error.message}`
  }
}

async function rebuildIndex() {
  isRebuildingIndex.value = true
  rebuildResult.value = null
  indexError.value = ''

  try {
    rebuildResult.value = await fetchJson('/api/index/rebuild', { method: 'POST' })
    await fetchDocuments()
    await fetchIndexStatus()
  } catch (error) {
    indexError.value = `重建索引失败：${error.message}`
  } finally {
    isRebuildingIndex.value = false
  }
}

async function searchKnowledge() {
  const query = searchQuery.value.trim()
  if (!query) {
    searchError.value = '请输入检索关键词'
    return
  }

  isSearching.value = true
  searchResult.value = null
  searchError.value = ''

  try {
    searchResult.value = await fetchJson('/api/search', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        query,
        mode: searchMode.value,
        topK: Number(searchTopK.value)
      })
    })
  } catch (error) {
    searchError.value = `检索失败：${error.message}`
  } finally {
    isSearching.value = false
  }
}

function formatFileSize(size) {
  if (size < 1024) {
    return `${size} B`
  }

  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`
  }

  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function formatTime(timestamp) {
  if (!timestamp) {
    return '-'
  }

  return new Date(timestamp).toLocaleString()
}

onMounted(() => {
  fetchSystemStatus()
  fetchDocuments()
  fetchIndexStatus()
})
</script>

<template>
  <main class="app-shell">
    <section class="hero-panel">
      <div class="hero-copy">
        <p class="eyebrow">本地个人知识库智能体</p>
        <h1>CogniNote Agent</h1>
        <p class="subtitle">
          第三阶段进入 Lucene 检索闭环：SQLite 保存知识片段，Lucene 提供关键词、向量和混合检索。
        </p>
      </div>

      <aside class="system-card" aria-label="系统状态">
        <div class="panel-header">
          <span>后端连接</span>
          <span :class="connectionClass">{{ connectionLabel }}</span>
        </div>

        <dl v-if="systemStatus" class="status-list">
          <div>
            <dt>应用</dt>
            <dd>{{ systemStatus.appName }}</dd>
          </div>
          <div>
            <dt>版本</dt>
            <dd>{{ systemStatus.version }}</dd>
          </div>
          <div>
            <dt>状态</dt>
            <dd>{{ systemStatus.status }}</dd>
          </div>
          <div>
            <dt>数据目录</dt>
            <dd class="path-text">{{ systemStatus.dataDir }}</dd>
          </div>
        </dl>

        <p v-else class="panel-message">
          {{ isLoadingStatus ? '正在读取系统状态...' : statusError }}
        </p>

        <button class="primary-button" type="button" :disabled="isLoadingStatus" @click="fetchSystemStatus">
          刷新状态
        </button>
      </aside>
    </section>

    <section class="module-grid" aria-label="功能入口">
      <article v-for="module in modules" :key="module.name" class="module-card">
        <div class="module-card__top">
          <h2>{{ module.name }}</h2>
          <span>{{ module.state }}</span>
        </div>
        <p>{{ module.description }}</p>
      </article>
    </section>

    <section class="knowledge-panel" aria-label="知识库管理">
      <div class="knowledge-header">
        <div>
          <p class="eyebrow">知识库管理</p>
          <h2>本地文档与检索索引</h2>
        </div>
        <div class="header-actions">
          <button class="secondary-button" type="button" :disabled="isLoadingIndexStatus" @click="fetchIndexStatus">
            刷新索引
          </button>
          <button class="secondary-button" type="button" :disabled="isLoadingDocuments" @click="fetchDocuments">
            刷新列表
          </button>
        </div>
      </div>

      <div class="index-status-grid" aria-label="索引状态">
        <div>
          <span>已索引文档</span>
          <strong>{{ indexStatus?.indexedDocumentCount ?? '-' }}</strong>
        </div>
        <div>
          <span>未索引文档</span>
          <strong>{{ indexStatus?.unindexedDocumentCount ?? '-' }}</strong>
        </div>
        <div>
          <span>索引 chunks</span>
          <strong>{{ indexStatus?.indexedChunkCount ?? '-' }}</strong>
        </div>
        <div>
          <span>Embedding</span>
          <strong>{{ indexStatus?.embeddingConfigured ? '已启用' : '未启用' }}</strong>
        </div>
      </div>

      <div class="index-toolbar">
        <div>
          <p class="path-text">{{ indexStatus?.indexPath || '索引目录读取中...' }}</p>
          <p class="muted-text">最后索引：{{ formatTime(indexStatus?.lastIndexedAt) }}</p>
        </div>
        <button class="primary-button" type="button" :disabled="isRebuildingIndex" @click="rebuildIndex">
          {{ isRebuildingIndex ? '重建中...' : '重建索引' }}
        </button>
      </div>

      <p v-if="indexError" class="error-message">{{ indexError }}</p>

      <div v-if="rebuildResult" class="result-strip result-strip--three">
        <span>索引文档 {{ rebuildResult.indexedDocumentCount }}</span>
        <span>索引 chunks {{ rebuildResult.indexedChunkCount }}</span>
        <span>耗时 {{ rebuildResult.durationMs }} ms</span>
      </div>

      <form class="ingest-form" @submit.prevent="ingestDocuments">
        <label class="field">
          <span>本地目录路径</span>
          <input
            v-model="folderPath"
            type="text"
            placeholder="例如 D:/notes 或 C:/Users/you/Documents/Notes"
            autocomplete="off"
          />
        </label>

        <label class="checkbox-field">
          <input v-model="recursive" type="checkbox" />
          <span>递归扫描子目录</span>
        </label>

        <button class="primary-button" type="submit" :disabled="isIngesting">
          {{ isIngesting ? '导入中...' : '导入目录' }}
        </button>
      </form>

      <form class="search-form" @submit.prevent="searchKnowledge">
        <label class="field">
          <span>检索内容</span>
          <input
            v-model="searchQuery"
            type="text"
            placeholder="输入关键词或问题片段"
            autocomplete="off"
          />
        </label>

        <div class="segmented-control" role="group" aria-label="检索模式">
          <button
            v-for="mode in searchModes"
            :key="mode.value"
            type="button"
            :class="{ active: searchMode === mode.value }"
            @click="searchMode = mode.value"
          >
            {{ mode.label }}
          </button>
        </div>

        <label class="field field--small">
          <span>Top K</span>
          <input v-model="searchTopK" type="number" min="1" max="50" />
        </label>

        <button class="primary-button" type="submit" :disabled="isSearching">
          {{ isSearching ? '检索中...' : '搜索' }}
        </button>
      </form>

      <p v-if="documentError" class="error-message">{{ documentError }}</p>
      <p v-if="searchError" class="error-message">{{ searchError }}</p>

      <div v-if="ingestResult" class="result-strip">
        <span>扫描 {{ ingestResult.scannedCount }}</span>
        <span>解析 {{ ingestResult.parsedCount }}</span>
        <span>跳过 {{ ingestResult.skippedCount }}</span>
        <span>失败 {{ ingestResult.failedCount }}</span>
      </div>

      <div class="stats-row" aria-label="文档统计">
        <div>
          <strong>{{ documents.length }}</strong>
          <span>文档记录</span>
        </div>
        <div>
          <strong>{{ documentStats.parsed }}</strong>
          <span>解析成功</span>
        </div>
        <div>
          <strong>{{ documentStats.chunks }}</strong>
          <span>文本块</span>
        </div>
        <div>
          <strong>{{ documentStats.failed }}</strong>
          <span>失败记录</span>
        </div>
      </div>

      <div v-if="searchResult" class="search-results">
        <div class="section-title-line">
          <h3>检索结果</h3>
          <span>{{ searchResult.mode }} / {{ searchResult.hits.length }} hits</span>
        </div>

        <p v-if="searchResult.hits.length === 0" class="panel-message">没有命中文档片段。</p>

        <article v-for="hit in searchResult.hits" v-else :key="hit.chunkId" class="search-hit">
          <div class="search-hit__top">
            <h4>{{ hit.fileName }}</h4>
            <span>{{ hit.score.toFixed(3) }}</span>
          </div>
          <p class="path-text">{{ hit.sourcePath }}</p>
          <p class="hit-preview">{{ hit.preview }}</p>
          <div class="document-meta">
            <span v-if="hit.heading">标题：{{ hit.heading }}</span>
            <span v-if="hit.pageNumber">页码：{{ hit.pageNumber }}</span>
            <span v-if="hit.keywordScore !== null">BM25 {{ hit.keywordScore.toFixed(3) }}</span>
            <span v-if="hit.vectorScore !== null">Vector {{ hit.vectorScore.toFixed(3) }}</span>
          </div>
        </article>
      </div>

      <div class="document-list">
        <p v-if="isLoadingDocuments" class="panel-message">正在读取文档列表...</p>
        <p v-else-if="documents.length === 0" class="panel-message">还没有导入文档。</p>

        <article v-for="document in documents" v-else :key="document.id" class="document-row">
          <div class="document-main">
            <div class="document-title-line">
              <h3>{{ document.fileName }}</h3>
              <span :class="['status-chip', `status-chip--${document.status.toLowerCase()}`]">
                {{ document.status }}
              </span>
            </div>
            <p class="path-text">{{ document.sourcePath }}</p>
            <div class="document-meta">
              <span>{{ document.fileType }}</span>
              <span>{{ formatFileSize(document.fileSize) }}</span>
              <span>{{ document.chunkCount }} chunks</span>
              <span>索引 {{ formatTime(document.indexedAt) }}</span>
              <span>{{ formatTime(document.updatedAt) }}</span>
            </div>
          </div>
          <button class="text-button" type="button" @click="deleteDocument(document.id)">删除记录</button>
        </article>
      </div>
    </section>
  </main>
</template>

<style scoped>
:global(*) {
  box-sizing: border-box;
}

:global(body) {
  min-width: 320px;
  margin: 0;
  color: #172033;
  background: #f5f7fb;
  font-family:
    Inter,
    "Microsoft YaHei",
    "PingFang SC",
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    "Segoe UI",
    sans-serif;
}

:global(button),
:global(input) {
  font: inherit;
}

.app-shell {
  width: min(1120px, calc(100% - 32px));
  margin: 0 auto;
  padding: 40px 0;
}

.hero-panel {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(320px, 0.75fr);
  gap: 24px;
  align-items: stretch;
}

.hero-copy {
  display: flex;
  min-height: 320px;
  flex-direction: column;
  justify-content: center;
  padding: 42px;
  border: 1px solid #d9e1ef;
  border-radius: 8px;
  background: #ffffff;
}

.eyebrow {
  margin: 0 0 14px;
  color: #2b6f61;
  font-size: 14px;
  font-weight: 700;
}

h1 {
  max-width: 720px;
  margin: 0;
  color: #101828;
  font-size: 48px;
  line-height: 1.08;
}

.subtitle {
  max-width: 680px;
  margin: 20px 0 0;
  color: #526071;
  font-size: 18px;
  line-height: 1.7;
}

.system-card,
.module-card,
.knowledge-panel {
  border: 1px solid #d9e1ef;
  border-radius: 8px;
  background: #ffffff;
}

.system-card {
  display: flex;
  flex-direction: column;
  min-height: 320px;
  padding: 24px;
}

.panel-header,
.module-card__top,
.knowledge-header,
.section-title-line,
.search-hit__top,
.document-title-line {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.panel-header {
  color: #101828;
  font-size: 18px;
  font-weight: 700;
}

.status-pill {
  min-width: 72px;
  padding: 6px 10px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 700;
  text-align: center;
}

.status-pill--loading {
  color: #715300;
  background: #fff2bf;
}

.status-pill--ok {
  color: #0f513f;
  background: #d7f4e8;
}

.status-pill--error {
  color: #842029;
  background: #f8d7da;
}

.status-list {
  display: grid;
  gap: 16px;
  margin: 24px 0;
}

.status-list div {
  min-width: 0;
}

.status-list dt {
  margin-bottom: 4px;
  color: #667085;
  font-size: 13px;
}

.status-list dd {
  margin: 0;
  color: #182230;
  font-weight: 700;
  line-height: 1.5;
}

.path-text {
  overflow-wrap: anywhere;
  font-family:
    "Cascadia Mono",
    "SFMono-Regular",
    Consolas,
    monospace;
  font-size: 13px;
}

.panel-message {
  margin: 24px 0;
  color: #526071;
  line-height: 1.6;
}

.primary-button,
.secondary-button,
.text-button {
  min-height: 44px;
  border-radius: 6px;
  cursor: pointer;
  transition:
    background 160ms ease,
    border-color 160ms ease,
    color 160ms ease,
    opacity 160ms ease;
}

.primary-button {
  border: 0;
  color: #ffffff;
  background: #1f6f68;
}

.primary-button:hover:not(:disabled),
.primary-button:focus-visible {
  background: #175b55;
}

.secondary-button {
  border: 1px solid #bdd2ca;
  color: #1f6f68;
  background: #f6fbf9;
}

.secondary-button:hover:not(:disabled),
.secondary-button:focus-visible {
  border-color: #1f6f68;
}

.text-button {
  flex: 0 0 auto;
  border: 0;
  color: #9a3412;
  background: transparent;
}

.text-button:hover,
.text-button:focus-visible {
  color: #7c2d12;
  background: #fff3ed;
}

.primary-button:focus-visible,
.secondary-button:focus-visible,
.text-button:focus-visible,
input:focus-visible {
  outline: 3px solid #9ee6d8;
  outline-offset: 2px;
}

.primary-button:disabled,
.secondary-button:disabled {
  cursor: not-allowed;
  opacity: 0.65;
}

.system-card .primary-button {
  width: 100%;
  margin-top: auto;
}

.module-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-top: 24px;
}

.module-card {
  min-height: 168px;
  padding: 20px;
}

.module-card h2 {
  margin: 0;
  color: #101828;
  font-size: 20px;
}

.module-card span,
.status-chip {
  flex: 0 0 auto;
  padding: 4px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.module-card span {
  color: #44546a;
  background: #eef2f7;
}

.module-card p {
  margin: 18px 0 0;
  color: #526071;
  line-height: 1.6;
}

.knowledge-panel {
  margin-top: 24px;
  padding: 24px;
}

.knowledge-header h2 {
  margin: 0;
  color: #101828;
  font-size: 28px;
}

.header-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.index-status-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 22px;
}

.index-status-grid div {
  padding: 16px;
  border: 1px solid #dfe8ee;
  border-radius: 8px;
  background: #f7fbfc;
}

.index-status-grid span,
.muted-text {
  color: #526071;
  font-size: 13px;
}

.index-status-grid strong {
  display: block;
  margin-top: 8px;
  color: #101828;
  font-size: 24px;
}

.index-toolbar {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-top: 16px;
  padding: 14px 16px;
  border: 1px solid #e1e7ef;
  border-radius: 8px;
  background: #ffffff;
}

.index-toolbar p {
  margin: 0;
}

.muted-text {
  margin-top: 6px;
}

.ingest-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 14px;
  align-items: end;
  margin-top: 22px;
}

.search-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 96px auto;
  gap: 14px;
  align-items: end;
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid #e1e7ef;
}

.field {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.field span,
.checkbox-field {
  color: #475467;
  font-size: 14px;
  font-weight: 700;
}

.field input {
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid #cfd8e6;
  border-radius: 6px;
  color: #182230;
  background: #ffffff;
}

.field--small input {
  text-align: center;
}

.checkbox-field {
  display: flex;
  min-height: 44px;
  gap: 8px;
  align-items: center;
  white-space: nowrap;
}

.checkbox-field input {
  width: 18px;
  height: 18px;
  accent-color: #1f6f68;
}

.error-message {
  margin: 16px 0 0;
  color: #842029;
  line-height: 1.6;
}

.result-strip,
.stats-row {
  display: grid;
  gap: 12px;
}

.result-strip {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 18px;
}

.result-strip--three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.result-strip span {
  padding: 10px 12px;
  border-radius: 6px;
  color: #18413d;
  background: #e7f5f1;
  font-weight: 700;
  text-align: center;
}

.stats-row {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 20px;
}

.stats-row div {
  padding: 16px;
  border: 1px solid #e1e7ef;
  border-radius: 8px;
  background: #f8fafc;
}

.stats-row strong {
  display: block;
  color: #101828;
  font-size: 26px;
}

.stats-row span {
  color: #526071;
  font-size: 13px;
  font-weight: 700;
}

.segmented-control {
  display: flex;
  min-height: 44px;
  overflow: hidden;
  border: 1px solid #bdd2ca;
  border-radius: 6px;
  background: #ffffff;
}

.segmented-control button {
  min-width: 72px;
  border: 0;
  border-right: 1px solid #d7e2de;
  color: #31514d;
  background: transparent;
  cursor: pointer;
}

.segmented-control button:last-child {
  border-right: 0;
}

.segmented-control button.active {
  color: #ffffff;
  background: #1f6f68;
}

.search-results {
  display: grid;
  gap: 12px;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid #e1e7ef;
}

.section-title-line h3 {
  margin: 0;
  color: #101828;
  font-size: 20px;
}

.section-title-line span {
  color: #526071;
  font-size: 13px;
  font-weight: 700;
}

.search-hit {
  padding: 16px;
  border: 1px solid #e1e7ef;
  border-radius: 8px;
  background: #fbfcfe;
}

.search-hit__top h4 {
  margin: 0;
  color: #101828;
  font-size: 17px;
}

.search-hit__top span {
  padding: 4px 8px;
  border-radius: 999px;
  color: #18413d;
  background: #dff2ee;
  font-size: 12px;
  font-weight: 800;
}

.hit-preview {
  margin: 10px 0;
  color: #354152;
  line-height: 1.7;
}

.document-list {
  display: grid;
  gap: 12px;
  margin-top: 20px;
}

.document-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  border: 1px solid #e1e7ef;
  border-radius: 8px;
  background: #ffffff;
}

.document-main {
  min-width: 0;
}

.document-title-line {
  justify-content: flex-start;
}

.document-title-line h3 {
  margin: 0;
  color: #101828;
  font-size: 17px;
}

.document-main p {
  margin: 8px 0;
  color: #526071;
}

.document-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  color: #667085;
  font-size: 13px;
}

.status-chip--parsed {
  color: #0f513f;
  background: #d7f4e8;
}

.status-chip--failed {
  color: #842029;
  background: #f8d7da;
}

.status-chip--skipped {
  color: #715300;
  background: #fff2bf;
}

@media (max-width: 900px) {
  .hero-panel,
  .module-grid,
  .ingest-form,
  .search-form,
  .index-status-grid,
  .result-strip,
  .stats-row {
    grid-template-columns: 1fr;
  }

  .hero-copy {
    min-height: auto;
  }

  .document-row {
    align-items: stretch;
    flex-direction: column;
  }
}

@media (max-width: 640px) {
  .app-shell {
    width: min(100% - 24px, 1120px);
    padding: 24px 0;
  }

  .hero-copy,
  .system-card,
  .knowledge-panel {
    padding: 22px;
  }

  h1 {
    font-size: 36px;
  }

  .subtitle {
    font-size: 16px;
  }

  .knowledge-header {
    align-items: stretch;
    flex-direction: column;
  }

  .index-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .header-actions {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .segmented-control {
    width: 100%;
  }

  .segmented-control button {
    flex: 1;
  }
}
</style>
