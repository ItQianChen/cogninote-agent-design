<script setup>
import { computed, ref, watch } from 'vue'
import {
  Activity,
  AlertTriangle,
  ChevronDown,
  ChevronRight,
  EllipsisVertical,
  FolderOpen,
  FolderPlus,
  FolderSync,
  RefreshCw,
  RotateCcw,
  Search,
  SlidersHorizontal
} from 'lucide-vue-next'
import KnowledgeFolderImportDialog from './knowledge-folder-import-dialog.vue'
import KnowledgeHealthDrawer from './knowledge-health-drawer.vue'
import {
  confirmDeleteKnowledgeFolder,
  confirmDisableFolder,
  confirmRebuildFolderIndex,
  confirmReparseFolder,
  confirmSyncFolder
} from '../composables/use-knowledge-maintenance-confirm'
import { useKnowledgeFoldersStore } from '../stores/knowledge-folders'
import { useKnowledgeHealthStore } from '../stores/knowledge-health'
import { useKnowledgeMaintenanceStore } from '../stores/knowledge-maintenance'
import { formatFileSize, formatTime } from '../utils/formatters'

const knowledgeStore = useKnowledgeFoldersStore()
const knowledgeHealthStore = useKnowledgeHealthStore()
const maintenanceStore = useKnowledgeMaintenanceStore()
const isImportDialogOpen = ref(false)
const folderSearchKeyword = ref('')
const folderStatusFilter = ref('all')
const folderIssueFilter = ref('all')
const folderCurrentPage = ref(1)
const folderPageSize = ref(10)

const HEALTH_STATUS_LABELS = {
  HEALTHY: '可信',
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

const RUN_STATUS_LABELS = {
  QUEUED: '等待中',
  RUNNING: '运行中',
  CANCELLING: '取消中',
  CANCELLED: '已取消',
  COMPLETED: '完成',
  COMPLETED_WITH_WARNINGS: '有失败项',
  FAILED: '失败'
}

const DOCUMENT_STATUS_LABELS = {
  PARSED: '已解析',
  SKIPPED: '已跳过',
  FAILED: '失败',
  OCR_REQUIRED: '需 OCR'
}

const normalizedFolderSearchKeyword = computed(() => normalizeSearchText(folderSearchKeyword.value))
const directoryStats = computed(() => {
  const folders = knowledgeStore.folders
  return {
    total: folders.length,
    enabled: folders.filter((folder) => folder.enabled).length,
    disabled: folders.filter((folder) => !folder.enabled).length,
    issues: folders.filter((folder) => folderIssueCount(folder)).length
  }
})
// 目录快照已在 store 中完整持有；本地筛选分页能避免为轻量列表再引入一套后端查询契约。
const filteredFolders = computed(() => {
  const keyword = normalizedFolderSearchKeyword.value
  const terms = keyword.split(/\s+/).filter(Boolean)
  return knowledgeStore.folders.filter((folder) => {
    const issueCount = folderIssueCount(folder)
    if (folderStatusFilter.value === 'enabled' && !folder.enabled) {
      return false
    }
    if (folderStatusFilter.value === 'disabled' && folder.enabled) {
      return false
    }
    if (folderIssueFilter.value === 'issues' && !issueCount) {
      return false
    }
    if (folderIssueFilter.value === 'clean' && issueCount) {
      return false
    }
    if (!terms.length) {
      return true
    }
    const health = folderHealth(folder)
    const searchableText = normalizeSearchText([
      folder.displayName,
      folder.folderPath,
      folder.enabled ? '已启用 enabled 启用' : '已停用 disabled 停用',
      healthStatusLabel(health?.status),
      health?.newLocalFileCount ? '本地新增 new local added' : '',
      issueCount ? '有问题 issue warning error' : '无问题 clean'
    ].join(' '))
    return terms.every((term) => searchableText.includes(term) || isSubsequence(term, searchableText))
  })
})
const folderTotalCount = computed(() => filteredFolders.value.length)
const folderPageCount = computed(() => Math.max(1, Math.ceil(folderTotalCount.value / folderPageSize.value)))
const pagedFolders = computed(() => {
  const start = (folderCurrentPage.value - 1) * folderPageSize.value
  return filteredFolders.value.slice(start, start + folderPageSize.value)
})
const folderPageStart = computed(() => {
  if (!folderTotalCount.value) {
    return 0
  }
  return (folderCurrentPage.value - 1) * folderPageSize.value + 1
})
const folderPageEnd = computed(() => Math.min(folderCurrentPage.value * folderPageSize.value, folderTotalCount.value))

watch([normalizedFolderSearchKeyword, folderStatusFilter, folderIssueFilter], () => {
  folderCurrentPage.value = 1
})

watch(folderPageSize, () => {
  folderCurrentPage.value = 1
})

watch(folderPageCount, (pageCount) => {
  if (folderCurrentPage.value > pageCount) {
    folderCurrentPage.value = pageCount
  }
})

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

function documentStatusLabel(status) {
  return DOCUMENT_STATUS_LABELS[status] || status || '未知'
}

function folderIssueCount(folder) {
  const health = folderHealth(folder)
  if (!health || !folder.enabled || health.status === 'DISABLED') {
    return 0
  }
  const fileIssueCount = health.failedCount
    + health.unindexedCount
    + health.missingLocalFileCount
    + health.staleLocalFileCount
    + (health.newLocalFileCount || 0)
  return health.status === 'HEALTHY' ? 0 : Math.max(1, fileIssueCount)
}

function normalizeSearchText(value) {
  return String(value || '').trim().toLowerCase()
}

function isSubsequence(term, text) {
  let cursor = 0
  for (const character of term) {
    cursor = text.indexOf(character, cursor)
    if (cursor === -1) {
      return false
    }
    cursor += 1
  }
  return true
}

function runOperationLabel(run) {
  return RUN_OPERATION_LABELS[run?.operation] || run?.operation || ''
}

function runStatusLabel(run) {
  return RUN_STATUS_LABELS[run?.status] || run?.status || '无记录'
}

function runStatusClass(run) {
  return `knowledge-run-status--${String(run?.status || 'unknown').toLowerCase()}`
}

function lastRun(folder) {
  return maintenanceStore.activeRunForFolder(folder.id) || folderHealth(folder)?.lastRun || null
}

function isFolderRunning(folder) {
  return Boolean(maintenanceStore.activeRunForFolder(folder.id))
}

function repairAction(folder) {
  const health = folderHealth(folder)
  if (!health || !folder.enabled) {
    return 'sync'
  }
  if (health.unindexedCount > 0) {
    return 'rebuild'
  }
  return 'sync'
}

function repairLabel(folder) {
  const run = maintenanceStore.activeRunForFolder(folder.id)
  if (run?.status === 'QUEUED') {
    return '已排队'
  }
  if (run?.status === 'CANCELLING') {
    return '取消中'
  }
  if (run?.status === 'RUNNING') {
    return '运行中'
  }
  if (repairAction(folder) === 'rebuild') {
    return '重建索引'
  }
  return folderIssueCount(folder) ? '重试同步' : '同步'
}

async function repairFolder(folder) {
  if (!folder.enabled || isFolderRunning(folder)) {
    return
  }
  if (repairAction(folder) === 'rebuild') {
    if (!await confirmRebuildFolderIndex(folder)) {
      return
    }
    await knowledgeStore.rebuildFolder(folder.id)
    return
  }
  if (!await confirmSyncFolder(folder)) {
    return
  }
  await knowledgeStore.syncFolder(folder.id)
}

async function rebuildFolderIndex(folder) {
  if (!folder?.enabled || isFolderRunning(folder)) {
    return
  }
  if (!await confirmRebuildFolderIndex(folder)) {
    return
  }
  await knowledgeStore.rebuildFolder(folder.id)
}

async function reparseFolder(folder) {
  if (!folder?.enabled || isFolderRunning(folder)) {
    return
  }
  if (!await confirmReparseFolder(folder)) {
    return
  }
  await knowledgeStore.reparseFolder(folder.id)
}

async function toggleFolderEnabled(folder) {
  if (!folder || isFolderRunning(folder)) {
    return
  }
  if (folder.enabled && !await confirmDisableFolder(folder)) {
    return
  }
  await knowledgeStore.toggleFolderEnabled(folder)
}

function clearFilters() {
  folderSearchKeyword.value = ''
  folderStatusFilter.value = 'all'
  folderIssueFilter.value = 'all'
}

async function confirmDeleteFolder(folder) {
  if (!folder || maintenanceStore.isFolderBusy(folder.id)) {
    return
  }
  if (!await confirmDeleteKnowledgeFolder(folder)) {
    return
  }
  await knowledgeStore.deleteFolder(folder.id)
}

async function refreshDirectories() {
  await maintenanceStore.refreshKnowledgeSnapshots()
}

</script>

<template>
  <section class="knowledge-pane knowledge-pane--directories" aria-label="知识库目录管理">
    <section class="knowledge-directory-toolbar" aria-label="目录筛选">
      <label class="knowledge-directory-search">
        <span>模糊搜索</span>
        <el-input
          v-model="folderSearchKeyword"
          clearable
          placeholder="搜索目录名称、路径或状态"
          autocomplete="off"
        >
          <template #prefix>
            <Search aria-hidden="true" />
          </template>
        </el-input>
      </label>

      <label class="knowledge-directory-filter">
        <span>启停状态</span>
        <el-select v-model="folderStatusFilter" aria-label="启停状态">
          <el-option label="全部状态" value="all" />
          <el-option label="已启用" value="enabled" />
          <el-option label="已停用" value="disabled" />
        </el-select>
      </label>

      <label class="knowledge-directory-filter">
        <span>问题状态</span>
        <el-select v-model="folderIssueFilter" aria-label="问题状态">
          <el-option label="全部目录" value="all" />
          <el-option label="有问题" value="issues" />
          <el-option label="无问题" value="clean" />
        </el-select>
      </label>

      <div class="knowledge-directory-toolbar__meta" aria-live="polite">
        <SlidersHorizontal aria-hidden="true" />
        <span>全部 {{ directoryStats.total }}</span>
        <span>启用 {{ directoryStats.enabled }}</span>
        <span>停用 {{ directoryStats.disabled }}</span>
        <span>需处理 {{ directoryStats.issues }}</span>
        <span>{{ folderTotalCount }} 个匹配目录</span>
        <span v-if="folderTotalCount">第 {{ folderPageStart }}-{{ folderPageEnd }} 条</span>
      </div>

      <div class="knowledge-directory-toolbar__actions">
        <el-button @click="openImportDialog">
          <FolderPlus aria-hidden="true" />
          <span>导入目录</span>
        </el-button>
        <el-button :loading="knowledgeStore.isLoading || knowledgeHealthStore.isLoading" @click="refreshDirectories">
          <RefreshCw aria-hidden="true" />
          <span>刷新</span>
        </el-button>
      </div>
    </section>

    <el-alert
      v-if="knowledgeStore.error"
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
      v-if="maintenanceStore.error"
      class="settings-inline-alert"
      type="error"
      :title="maintenanceStore.error"
      :closable="false"
      show-icon
    />

    <section class="knowledge-directory-list" aria-label="目录列表">
      <header class="knowledge-directory-list__head" aria-hidden="true">
        <span>目录</span>
        <span>状态</span>
        <span>规模</span>
        <span>最近维护</span>
        <span>操作</span>
      </header>

      <div class="knowledge-directory-list__body">
        <p v-if="knowledgeStore.isLoading" class="panel-message">正在读取知识库目录...</p>
        <p v-else-if="knowledgeStore.folders.length === 0 && knowledgeStore.unassignedDocuments.length === 0" class="panel-message">
          还没有导入知识库目录。
        </p>
        <div v-else-if="knowledgeStore.folders.length && pagedFolders.length === 0" class="knowledge-folder-empty">
          <strong>没有匹配的目录</strong>
          <p class="muted-text">换一个名称、路径或状态关键词试试。</p>
          <el-button @click="clearFilters">清空筛选</el-button>
        </div>

        <article v-for="folder in pagedFolders" :key="folder.id" class="knowledge-directory-row">
          <div class="knowledge-directory-row__grid">
            <div class="knowledge-directory-row__main">
              <button class="folder-toggle-button" type="button" @click="knowledgeStore.toggleExpanded(folder.id)">
                <ChevronDown v-if="knowledgeStore.isExpanded(folder.id)" aria-hidden="true" />
                <ChevronRight v-else aria-hidden="true" />
                <FolderOpen aria-hidden="true" />
                <span>{{ folder.displayName }}</span>
              </button>
              <p class="path-text">{{ folder.folderPath }}</p>
            </div>

            <div class="knowledge-directory-row__status">
              <span
                v-if="folderHealth(folder)"
                :class="['status-chip', healthStatusClass(folderHealth(folder).status)]"
              >
                {{ healthStatusLabel(folderHealth(folder).status) }}
              </span>
              <span :class="['status-chip', folder.enabled ? 'status-chip--parsed' : 'status-chip--skipped']">
                {{ folder.enabled ? '已启用' : '已停用' }}
              </span>
              <button
                v-if="folderIssueCount(folder)"
                class="knowledge-directory-issue-button"
                type="button"
                @click="knowledgeHealthStore.openFolderIssues(folder.id)"
              >
                <AlertTriangle aria-hidden="true" />
                <span>{{ folderIssueCount(folder) }} 个问题</span>
              </button>
            </div>

            <div class="knowledge-directory-row__numbers">
              <span><strong>{{ folder.documentCount }}</strong> 文档</span>
              <span><strong>{{ folder.chunkCount }}</strong> chunks</span>
              <span v-if="folder.enabled">{{ folder.unindexedCount }} 未索引</span>
              <span v-if="folderHealth(folder)?.missingLocalFileCount">缺失 {{ folderHealth(folder).missingLocalFileCount }}</span>
              <span v-if="folderHealth(folder)?.staleLocalFileCount">变化 {{ folderHealth(folder).staleLocalFileCount }}</span>
              <span v-if="folderHealth(folder)?.newLocalFileCount">新增 {{ folderHealth(folder).newLocalFileCount }}</span>
            </div>

            <div class="knowledge-directory-row__time">
              <div
                v-if="lastRun(folder)"
                :class="['knowledge-run-status', runStatusClass(lastRun(folder))]"
              >
                <Activity aria-hidden="true" />
                <span>{{ runStatusLabel(lastRun(folder)) }}</span>
                <em>{{ lastRun(folder).status === 'QUEUED' ? '排队' : '最近' }}{{ runOperationLabel(lastRun(folder)) }} {{ formatTime(lastRun(folder).completedAt || lastRun(folder).queuedAt || lastRun(folder).startedAt) }}</em>
              </div>
              <span>导入 {{ formatTime(folder.lastIngestedAt) }}</span>
              <span>索引 {{ formatTime(folder.lastIndexedAt) }}</span>
            </div>

            <div class="knowledge-directory-row__actions">
              <el-button
                :disabled="isFolderRunning(folder) || !folder.enabled"
                :loading="isFolderRunning(folder)"
                title="扫描新增、修改和删除的文件，只处理差异"
                @click="repairFolder(folder)"
              >
                <RotateCcw v-if="repairAction(folder) === 'rebuild'" aria-hidden="true" />
                <FolderSync v-else aria-hidden="true" />
                <span>{{ repairLabel(folder) }}</span>
              </el-button>
              <el-button
                :disabled="isFolderRunning(folder)"
                @click="toggleFolderEnabled(folder)"
              >
                {{ folder.enabled ? '停用' : '启用' }}
              </el-button>
              <el-dropdown trigger="click">
                <el-button
                  class="knowledge-directory-more-button"
                  :disabled="isFolderRunning(folder)"
                  aria-label="更多目录操作"
                  title="更多目录操作"
                >
                  <EllipsisVertical aria-hidden="true" />
                </el-button>
                <template #dropdown>
                <el-dropdown-menu>
                    <el-dropdown-item
                      :disabled="!folder.enabled || isFolderRunning(folder)"
                      @click="reparseFolder(folder)"
                    >
                      重新解析目录
                    </el-dropdown-item>
                    <el-dropdown-item
                      :disabled="!folder.enabled || isFolderRunning(folder)"
                      @click="rebuildFolderIndex(folder)"
                    >
                      重建索引
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="folderIssueCount(folder)"
                      @click="knowledgeHealthStore.openFolderIssues(folder.id)"
                    >
                      问题详情
                    </el-dropdown-item>
                    <el-dropdown-item divided class="knowledge-directory-danger-item" @click="confirmDeleteFolder(folder)">
                      删除目录
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>

          <div v-if="knowledgeStore.isExpanded(folder.id)" class="folder-documents">
            <p v-if="folder.documents.length === 0" class="panel-message">该目录下还没有文档记录。</p>
            <article v-for="document in folder.documents" :key="document.id" class="document-row document-row--nested">
              <div class="document-main">
                <div class="document-title-line">
                  <h4>{{ document.fileName }}</h4>
                  <span :class="['status-chip', `status-chip--${document.status.toLowerCase()}`]">
                    {{ documentStatusLabel(document.status) }}
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
            </article>
          </div>
        </article>

        <article v-if="knowledgeStore.unassignedDocuments.length" class="knowledge-directory-row knowledge-directory-row--unassigned">
          <div class="knowledge-directory-row__main">
            <button class="folder-toggle-button" type="button" @click="knowledgeStore.toggleExpanded('unassigned')">
              <ChevronDown v-if="knowledgeStore.isExpanded('unassigned')" aria-hidden="true" />
              <ChevronRight v-else aria-hidden="true" />
              <FolderOpen aria-hidden="true" />
              <span>未归属文档</span>
            </button>
            <p class="hint-message">这些是旧版本导入的散落文档。重新导入对应本地目录后，会自动归属到知识库文件夹。</p>
          </div>
          <div v-if="knowledgeStore.isExpanded('unassigned')" class="folder-documents">
            <article v-for="document in knowledgeStore.unassignedDocuments" :key="document.id" class="document-row document-row--nested">
              <div class="document-main">
                <div class="document-title-line">
                  <h4>{{ document.fileName }}</h4>
                  <span :class="['status-chip', `status-chip--${document.status.toLowerCase()}`]">
                    {{ documentStatusLabel(document.status) }}
                  </span>
                </div>
                <p class="path-text">{{ document.sourcePath }}</p>
                <div class="document-meta">
                  <span>{{ document.fileType }}</span>
                  <span>{{ formatFileSize(document.fileSize) }}</span>
                  <span>{{ document.chunkCount }} chunks</span>
                  <span>索引 {{ formatTime(document.indexedAt) }}</span>
                </div>
              </div>
            </article>
          </div>
        </article>
      </div>

      <footer class="knowledge-directory-footer">
        <el-pagination
          v-model:current-page="folderCurrentPage"
          v-model:page-size="folderPageSize"
          :page-sizes="[5, 10, 20, 50]"
          :total="folderTotalCount"
          background
          layout="total, sizes, prev, pager, next"
        />
      </footer>
    </section>

    <KnowledgeFolderImportDialog v-model="isImportDialogOpen" />
    <KnowledgeHealthDrawer />
  </section>
</template>
