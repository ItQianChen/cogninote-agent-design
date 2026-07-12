<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { AlertTriangle, BrainCircuit, ChevronRight, Copy, Database, FolderSync, GitBranch, RotateCcw, ScanText } from 'lucide-vue-next'
import KnowledgeHealthIssueDetailDialog from './knowledge-health-issue-detail-dialog.vue'
import {
  confirmRebuildAllIndex,
  confirmReparseFolder,
  confirmSyncFolder
} from '../composables/use-knowledge-maintenance-confirm'
import { useKnowledgeHealthIssueIgnore } from '../composables/use-knowledge-health-issue-ignore'
import { useKnowledgeFoldersStore } from '../stores/knowledge-folders'
import { useKnowledgeHealthStore } from '../stores/knowledge-health'
import { useKnowledgeMaintenanceStore } from '../stores/knowledge-maintenance'
import { useOcrSettingsStore } from '../stores/ocr-settings'
import { useSearchStore } from '../stores/search'
import { formatTime } from '../utils/formatters'
import { failureProgressLabel } from '../utils/document-failures'
import { buildIssueCategories, issueMetaText } from '../utils/knowledge-health-issues'

const knowledgeStore = useKnowledgeFoldersStore()
const healthStore = useKnowledgeHealthStore()
const maintenanceStore = useKnowledgeMaintenanceStore()
const ocrSettingsStore = useOcrSettingsStore()
const searchStore = useSearchStore()
const isIssueDetailDialogOpen = ref(false)
const selectedIssueSection = ref(null)
const ISSUE_CATEGORY_ICONS = {
  retrieval: Database,
  capability: BrainCircuit,
  graph: GitBranch,
  'content-risk': AlertTriangle
}
const { ignoredIssueKeys, restoreAllIgnoredIssues } = useKnowledgeHealthIssueIgnore()

const currentFolder = computed(() =>
  (healthStore.health?.folders || []).find((folder) => folder.id === healthStore.selectedFolderId) || null
)
const drawerTitle = computed(() => currentFolder.value?.displayName ? `${currentFolder.value.displayName} · 问答诊断` : '问答可用性诊断')
const folderHealth = computed(() => healthStore.folderHealth)
// 后端按问题来源拆分文档列表，前端只负责分区展示，不在这里重新推导健康状态。
const problemSections = computed(() => [
  {
    key: 'failed',
    title: '可能搜不到：解析失败',
    action: 'SYNC_FOLDER',
    items: folderHealth.value?.failedDocuments || []
  },
  {
    key: 'unindexed',
    title: '可能搜不到：未进入索引',
    action: 'REPAIR_INDEX',
    items: folderHealth.value?.unindexedDocuments || []
  },
  {
    key: 'missing',
    title: '需要同步：本地文件缺失',
    action: 'SYNC_FOLDER',
    items: folderHealth.value?.missingLocalFiles || []
  },
  {
    key: 'stale',
    title: '需要同步：疑似已变化',
    action: 'SYNC_FOLDER',
    items: folderHealth.value?.staleLocalFiles || []
  },
  {
    key: 'new-local',
    title: '需要同步：本地新增',
    action: 'SYNC_FOLDER',
    items: folderHealth.value?.newLocalFiles || []
  }
].filter((section) => section.items.length))
const issueCount = computed(() => folderHealth.value?.issues?.reduce((total, issue) => total + issue.count, 0) || 0)
const canRepairFolder = computed(() => Boolean(currentFolder.value?.enabled && healthStore.selectedFolderId))
const globalIssues = computed(() => (healthStore.health?.issues || []).filter((issue) => issue.scopeType === 'ALL' && !issue.scopeId))
const hasFolderSyncIssues = computed(() => problemSections.value.some((section) => section.action === 'SYNC_FOLDER'))
const hasFolderIndexIssues = computed(() => problemSections.value.some((section) => section.action === 'REPAIR_INDEX'))
const hasFolderOcrIssues = computed(() => Boolean(
  folderHealth.value?.issues?.some((issue) => issue.code === 'PDF_OCR_REQUIRED')
))
const systemProblemSections = computed(() =>
  buildIssueCategories(globalIssues.value, ignoredIssueKeys.value).map((section) => ({
    ...section,
    icon: ISSUE_CATEGORY_ICONS[section.key] || AlertTriangle
  }))
)
const hasIndexIssue = computed(() =>
  systemProblemSections.value.some((section) =>
    section.activeIssues.some((issue) => issue.code === 'INDEX_INCONSISTENT')
  )
)
const hasEmbeddingIssue = computed(() =>
  systemProblemSections.value.some((section) =>
    section.activeIssues.some((issue) => issue.code === 'EMBEDDING_UNCONFIGURED')
  )
)
const ignoredGlobalIssueCount = computed(() =>
  systemProblemSections.value.reduce((total, section) => total + section.ignoredCount, 0)
)
const selectedFolderRun = computed(() => maintenanceStore.activeRunForFolder(healthStore.selectedFolderId))

onMounted(() => {
  ocrSettingsStore.fetchSettings()
})

// issue.action 是后端建议；抽屉只映射到同步、重建、配置这类显式且可恢复的操作。
async function syncSelectedFolder() {
  if (!healthStore.selectedFolderId) {
    return
  }
  if (!await confirmSyncFolder(currentFolder.value)) {
    return
  }
  await knowledgeStore.syncFolder(healthStore.selectedFolderId)
}

async function reparseSelectedFolder() {
  if (!healthStore.selectedFolderId) {
    return
  }
  if (!await confirmReparseFolder(currentFolder.value)) {
    return
  }
  await knowledgeStore.reparseFolder(healthStore.selectedFolderId)
}

async function rebuildGlobalIndex() {
  if (!await confirmRebuildAllIndex()) {
    return
  }
  await searchStore.rebuildIndex()
}

async function repairSelectedFolderIndex() {
  if (!healthStore.selectedFolderId) {
    return
  }
  await knowledgeStore.repairFolderIndex(healthStore.selectedFolderId)
}

async function copyPath(path) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(path)
    } else {
      // Tauri WebView 或旧浏览器可能没有 Clipboard API，保留 DOM fallback 保证本地路径可复制。
      fallbackCopy(path)
    }
    ElMessage.success('路径已复制')
  } catch {
    ElMessage.error('路径复制失败')
  }
}

function fallbackCopy(text) {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  document.execCommand('copy')
  document.body.removeChild(textarea)
}

function openIssueSection(section) {
  selectedIssueSection.value = section
  isIssueDetailDialogOpen.value = true
}
</script>

<template>
  <el-drawer
    v-model="healthStore.isDrawerOpen"
    class="knowledge-health-drawer"
    size="520px"
    :title="drawerTitle"
  >
    <div v-if="currentFolder" class="knowledge-health-drawer__folder">
      <strong>{{ currentFolder.status }}</strong>
      <span>{{ issueCount }} 个问题</span>
      <p class="path-text">{{ currentFolder.folderPath }}</p>
    </div>

    <div class="knowledge-health-drawer__actions">
      <el-button
        v-if="hasFolderOcrIssues && ocrSettingsStore.available"
        :disabled="!canRepairFolder || Boolean(selectedFolderRun)"
        :loading="selectedFolderRun?.status === 'RUNNING' || selectedFolderRun?.status === 'CANCELLING'"
        @click="reparseSelectedFolder"
      >
        <ScanText aria-hidden="true" />
        <span>{{ selectedFolderRun ? '维护中' : '重新解析' }}</span>
      </el-button>
      <RouterLink
        v-else-if="hasFolderOcrIssues"
        class="knowledge-header-link"
        :to="{ name: 'settings', query: { item: 'ocr' } }"
      >
        <ScanText aria-hidden="true" />
        <span>配置视觉模型</span>
      </RouterLink>
      <el-button
        v-if="hasFolderSyncIssues"
        :disabled="!canRepairFolder || Boolean(selectedFolderRun)"
        :loading="selectedFolderRun?.status === 'RUNNING' || selectedFolderRun?.status === 'CANCELLING'"
        @click="syncSelectedFolder"
      >
        <FolderSync aria-hidden="true" />
        <span>{{ selectedFolderRun ? '维护中' : '同步/重试' }}</span>
      </el-button>
      <el-button
        v-if="hasFolderIndexIssues"
        :disabled="!canRepairFolder || Boolean(selectedFolderRun)"
        :loading="selectedFolderRun?.status === 'RUNNING' || selectedFolderRun?.status === 'CANCELLING'"
        @click="repairSelectedFolderIndex"
      >
        <RotateCcw aria-hidden="true" />
        <span>{{ selectedFolderRun ? '维护中' : '补写索引' }}</span>
      </el-button>
      <el-button
        v-if="hasIndexIssue"
        :loading="searchStore.isRebuildingIndex"
        :disabled="maintenanceStore.hasActiveRun"
        @click="rebuildGlobalIndex"
      >
        <Database aria-hidden="true" />
        <span>重建全库索引</span>
      </el-button>
      <RouterLink
        v-if="hasEmbeddingIssue"
        class="knowledge-header-link"
        :to="{ name: 'settings', query: { item: 'model-embedding' } }"
      >
        <BrainCircuit aria-hidden="true" />
        <span>配置向量模型</span>
      </RouterLink>
      <el-button v-if="ignoredGlobalIssueCount" text @click="restoreAllIgnoredIssues">
        恢复忽略
      </el-button>
    </div>

    <el-alert
      v-if="healthStore.error"
      class="settings-inline-alert"
      type="error"
      :title="healthStore.error"
      :closable="false"
      show-icon
    />

    <p v-if="healthStore.isLoadingFolder" class="panel-message">正在读取目录诊断...</p>
    <section v-else-if="folderHealth?.issues?.length" class="knowledge-issue-list">
      <article v-for="issue in folderHealth.issues" :key="issue.code" class="knowledge-issue-item">
        <strong>{{ issue.message }}</strong>
        <span>{{ issueMetaText(issue) }}</span>
      </article>
    </section>
    <p v-if="!healthStore.isLoadingFolder && !problemSections.length && !systemProblemSections.length" class="panel-message">
      当前没有影响问答可用性的诊断问题。
    </p>

    <section
      v-for="section in systemProblemSections"
      :key="section.key"
      class="knowledge-problem-section knowledge-problem-section--system"
    >
      <button
        :class="['knowledge-health-drawer__category', `knowledge-health-drawer__category--${section.tone}`, { 'is-ignored': !section.activeCount }]"
        type="button"
        @click="openIssueSection(section)"
      >
        <div>
          <component :is="section.icon" aria-hidden="true" />
          <div>
            <strong>{{ section.title }}</strong>
            <span>{{ section.subtitle }}</span>
            <small v-if="section.ruleSummary">{{ section.ruleSummary }}</small>
          </div>
        </div>
        <em>
          {{ section.activeCount ? `${section.activeCount} 项` : '已忽略' }}
          <ChevronRight aria-hidden="true" />
        </em>
      </button>
    </section>

    <section
      v-for="section in problemSections"
      :key="section.key"
      class="knowledge-problem-section"
    >
      <h4>{{ section.title }}</h4>
      <article
        v-for="document in section.items"
        :key="document.documentId"
        class="knowledge-problem-item"
      >
        <div>
          <strong>{{ document.fileName }}</strong>
          <p class="path-text">{{ document.sourcePath }}</p>
          <span>{{ document.message }}</span>
          <span v-if="failureProgressLabel(document.lastFailure)">
            {{ failureProgressLabel(document.lastFailure) }}
          </span>
          <em>更新 {{ formatTime(document.updatedAt) }}</em>
        </div>
        <el-button text aria-label="复制文件路径" @click="copyPath(document.sourcePath)">
          <Copy aria-hidden="true" />
        </el-button>
      </article>
    </section>

    <section v-if="folderHealth?.runs?.length" class="knowledge-run-list">
      <h4>最近维护</h4>
      <article v-for="run in folderHealth.runs" :key="run.id" class="knowledge-run-item">
        <strong>{{ run.operation }}</strong>
        <span>{{ run.status }}</span>
        <em>{{ formatTime(run.completedAt) }} · {{ run.durationMs }} ms</em>
      </article>
    </section>

    <KnowledgeHealthIssueDetailDialog
      v-model="isIssueDetailDialogOpen"
      :section="selectedIssueSection"
    />
  </el-drawer>
</template>
