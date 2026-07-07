<script setup>
import { computed, ref } from 'vue'
import {
  ChevronRight,
  FolderPlus,
  RotateCcw,
  SearchCheck
} from 'lucide-vue-next'
import KnowledgeFolderImportDialog from './knowledge-folder-import-dialog.vue'
import { confirmRebuildAllIndex } from '../composables/use-knowledge-maintenance-confirm'
import { useKnowledgeFoldersStore } from '../stores/knowledge-folders'
import { useKnowledgeHealthStore } from '../stores/knowledge-health'
import { useKnowledgeMaintenanceStore } from '../stores/knowledge-maintenance'
import { useSearchStore } from '../stores/search'
import { formatTime } from '../utils/formatters'

const knowledgeStore = useKnowledgeFoldersStore()
const knowledgeHealthStore = useKnowledgeHealthStore()
const maintenanceStore = useKnowledgeMaintenanceStore()
const searchStore = useSearchStore()
const isImportDialogOpen = ref(false)

const HEALTH_STATUS_LABELS = {
  HEALTHY: '可用',
  WARNING: '需关注',
  ERROR: '需修复',
  DISABLED: '已停用',
  EMPTY: '空目录'
}

const RUN_OPERATION_LABELS = {
  IMPORT: '导入',
  SYNC: '同步',
  REPARSE: '重解析',
  REBUILD_INDEX: '重建',
  REPAIR_INDEX: '补写',
  ENABLE: '启用',
  DISABLE: '停用',
  DELETE: '删除'
}

const healthSummary = computed(() => knowledgeHealthStore.health?.summary || null)
const issueFolders = computed(() =>
  (knowledgeHealthStore.health?.folders || []).filter((folderHealthSummary) => folderHealthIssueCount(folderHealthSummary))
)
const recentFolders = computed(() => knowledgeStore.folders.slice(0, 4))
const healthIssueCount = computed(() =>
  issueFolders.value.length + (knowledgeHealthStore.health?.issues || []).filter((issue) => issue.scopeType === 'ALL' && !issue.scopeId).length
)
const overviewStats = computed(() => [
  { key: 'folders', label: '目录', value: knowledgeStore.stats.folderCount, detail: '已导入本地目录' },
  { key: 'documents', label: '文档', value: knowledgeStore.stats.documentCount, detail: '纳入知识库资料' },
  { key: 'chunks', label: 'Chunks', value: knowledgeStore.stats.chunks, detail: '可检索内容片段' },
  { key: 'failed', label: '解析失败', value: knowledgeStore.stats.failed, detail: '需要重新导入或检查文件' }
])

/**
 * 全量重建会触发高成本索引操作，只在用户明确点击时执行。
 */
async function rebuildAllIndexes() {
  if (!await confirmRebuildAllIndex()) {
    return
  }
  await searchStore.rebuildIndex()
}

function openImportDialog() {
  knowledgeStore.error = ''
  isImportDialogOpen.value = true
}

function folderHealth(folder) {
  return knowledgeHealthStore.folderHealthById.get(folder.id) || null
}

function healthStatusLabel(status) {
  return HEALTH_STATUS_LABELS[status] || status || '未知'
}

function healthStatusClass(status) {
  return `status-chip--health-${String(status || 'unknown').toLowerCase()}`
}

function folderIssueCount(folder) {
  return folderHealthIssueCount(folderHealth(folder))
}

function folderHealthIssueCount(health) {
  if (!health || !health.enabled || health.status === 'DISABLED') {
    return 0
  }
  const fileIssueCount = health.failedCount
    + health.unindexedCount
    + health.missingLocalFileCount
    + health.staleLocalFileCount
    + (health.newLocalFileCount || 0)
  return health.status === 'HEALTHY' ? 0 : Math.max(1, fileIssueCount)
}

function runOperationLabel(run) {
  return RUN_OPERATION_LABELS[run?.operation] || run?.operation || ''
}
</script>

<template>
  <section class="knowledge-pane knowledge-pane--folders" aria-label="知识库资料总览">
    <header class="knowledge-pane__header knowledge-pane__header--compact">
      <div>
        <p class="eyebrow">资料总览</p>
        <h3>知识库总览</h3>
        <p class="muted-text">导入本地资料，查看全局健康状态；目录维护在独立列表中处理。</p>
      </div>
      <div class="header-actions">
        <el-button @click="openImportDialog">
          <FolderPlus aria-hidden="true" />
          <span>导入目录</span>
        </el-button>
        <el-button
          :disabled="maintenanceStore.hasActiveRun"
          :loading="searchStore.isRebuildingIndex"
          @click="rebuildAllIndexes"
        >
          <RotateCcw aria-hidden="true" />
          <span>重建索引</span>
        </el-button>
      </div>
    </header>

    <section class="knowledge-overview-grid" aria-label="知识库统计">
      <article v-for="stat in overviewStats" :key="stat.key">
        <span>{{ stat.label }}</span>
        <strong>{{ stat.value }}</strong>
        <p>{{ stat.detail }}</p>
      </article>
    </section>

    <section v-if="knowledgeHealthStore.health" class="knowledge-directory-entry knowledge-directory-entry--health" aria-label="问答可用性入口">
      <div class="knowledge-directory-entry__main">
        <span :class="['knowledge-directory-entry__icon', healthStatusClass(knowledgeHealthStore.health.status)]">
          <SearchCheck aria-hidden="true" />
        </span>
        <div>
          <p class="eyebrow">问答可用性</p>
          <h4>检查资料能否被问答命中</h4>
          <p class="muted-text">
            {{ healthStatusLabel(knowledgeHealthStore.health.status) }} ·
            {{ healthIssueCount }} 个入口需关注 ·
            Lucene {{ healthSummary?.indexConsistent ? '一致' : '不一致' }} ·
            Embedding {{ healthSummary?.embeddingConfigured ? '可用' : '未配置' }}
          </p>
        </div>
      </div>
      <RouterLink class="knowledge-directory-entry__link" :to="{ name: 'knowledge', query: { panel: 'health' } }">
        <span>打开问答可用性</span>
        <ChevronRight aria-hidden="true" />
      </RouterLink>
    </section>

    <section class="knowledge-directory-entry" aria-label="目录管理入口">
      <div>
        <p class="eyebrow">目录维护</p>
        <h4>用列表管理目录</h4>
        <p class="muted-text">搜索、分页、同步、启停和删除目录都在这里处理，避免总览页被操作细节挤满。</p>
      </div>
      <RouterLink class="knowledge-directory-entry__link" :to="{ name: 'knowledge', query: { panel: 'directories' } }">
        <span>打开目录管理</span>
        <ChevronRight aria-hidden="true" />
      </RouterLink>
    </section>

    <section class="knowledge-folder-snapshot" aria-label="最近目录">
      <header>
        <h4>最近目录</h4>
        <RouterLink :to="{ name: 'knowledge', query: { panel: 'directories' } }">查看全部</RouterLink>
      </header>
      <p v-if="knowledgeStore.isLoading" class="panel-message">正在读取知识库目录...</p>
      <p v-else-if="!knowledgeStore.folders.length" class="panel-message">还没有导入知识库目录。</p>
      <template v-else>
        <article v-for="folder in recentFolders" :key="folder.id" class="knowledge-folder-snapshot__item">
          <div>
            <strong>{{ folder.displayName }}</strong>
            <p class="path-text">{{ folder.folderPath }}</p>
          </div>
          <div class="folder-meta">
            <span :class="['status-chip', folder.enabled ? 'status-chip--parsed' : 'status-chip--skipped']">
              {{ folder.enabled ? '已启用' : '已停用' }}
            </span>
            <span
              v-if="folderHealth(folder)"
              :class="['status-chip', healthStatusClass(folderHealth(folder).status)]"
            >
              {{ healthStatusLabel(folderHealth(folder).status) }}
            </span>
            <span v-if="folderIssueCount(folder)" class="folder-issue-count">问题 {{ folderIssueCount(folder) }}</span>
            <span>{{ folder.documentCount }} 文档</span>
            <span>{{ folder.chunkCount }} chunks</span>
            <span v-if="folderHealth(folder)?.lastRun">
              最近{{ runOperationLabel(folderHealth(folder).lastRun) }} {{ formatTime(folderHealth(folder).lastRun.completedAt) }}
            </span>
          </div>
        </article>
      </template>
    </section>

    <el-alert
      v-if="knowledgeStore.error && !isImportDialogOpen"
      class="settings-inline-alert"
      type="error"
      :title="knowledgeStore.error"
      :closable="false"
      show-icon
    />

    <el-alert
      v-if="knowledgeHealthStore.error"
      class="settings-inline-alert"
      type="error"
      :title="knowledgeHealthStore.error"
      :closable="false"
      show-icon
    />

    <el-alert
      v-if="searchStore.indexError"
      class="settings-inline-alert"
      type="error"
      :title="searchStore.indexError"
      :closable="false"
      show-icon
    />

    <KnowledgeFolderImportDialog v-model="isImportDialogOpen" />
  </section>
</template>
