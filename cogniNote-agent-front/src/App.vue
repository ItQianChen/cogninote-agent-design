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
    description: '本地文件夹导入、文档解析、Chunk 保存和索引状态管理。',
    state: '可导入'
  },
  {
    name: '模型配置',
    description: '配置 OpenAI-compatible 对话模型和 Embedding 模型。',
    state: '待实现'
  },
  {
    name: '系统设置',
    description: '管理数据目录、索引目录、Top K 和混合检索权重。',
    state: '待实现'
  }
]

const systemStatus = ref(null)
const documents = ref([])
const ingestResult = ref(null)
const isLoadingStatus = ref(true)
const isLoadingDocuments = ref(false)
const isIngesting = ref(false)
const statusError = ref('')
const documentError = ref('')
const folderPath = ref('')
const recursive = ref(true)

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
  } catch (error) {
    documentError.value = `删除索引记录失败：${error.message}`
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
})
</script>

<template>
  <main class="app-shell">
    <section class="hero-panel">
      <div class="hero-copy">
        <p class="eyebrow">本地个人知识库智能体</p>
        <h1>CogniNote Agent</h1>
        <p class="subtitle">
          第二阶段已进入文档摄入闭环：扫描本地目录，解析 Markdown、TXT、DOCX 和文本型 PDF，并保存到 SQLite。
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
          <h2>本地文档导入</h2>
        </div>
        <button class="secondary-button" type="button" :disabled="isLoadingDocuments" @click="fetchDocuments">
          刷新列表
        </button>
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

      <p v-if="documentError" class="error-message">{{ documentError }}</p>

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

.ingest-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 14px;
  align-items: end;
  margin-top: 22px;
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
}
</style>
