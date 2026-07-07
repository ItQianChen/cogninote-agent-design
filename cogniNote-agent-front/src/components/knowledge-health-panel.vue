<script setup>
import { computed, ref } from 'vue'
import {
  Activity,
  AlertTriangle,
  BrainCircuit,
  CheckCircle2,
  ChevronRight,
  Database,
  Eye,
  FolderOpen,
  GitBranch,
  RefreshCw,
  RotateCcw,
  Search,
  SearchCheck,
  ShieldAlert,
  ShieldCheck,
  Trash2
} from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'
import KnowledgeHealthDrawer from './knowledge-health-drawer.vue'
import KnowledgeHealthIssueDetailDialog from './knowledge-health-issue-detail-dialog.vue'
import { confirmRebuildAllIndex } from '../composables/use-knowledge-maintenance-confirm'
import { useKnowledgeHealthIssueIgnore } from '../composables/use-knowledge-health-issue-ignore'
import { useKnowledgeFoldersStore } from '../stores/knowledge-folders'
import { useKnowledgeHealthStore } from '../stores/knowledge-health'
import { useKnowledgeMaintenanceStore } from '../stores/knowledge-maintenance'
import { useSearchStore } from '../stores/search'
import { formatTime } from '../utils/formatters'
import { buildIssueCategories } from '../utils/knowledge-health-issues'

const knowledgeStore = useKnowledgeFoldersStore()
const healthStore = useKnowledgeHealthStore()
const maintenanceStore = useKnowledgeMaintenanceStore()
const searchStore = useSearchStore()
const isRunsDialogOpen = ref(false)
const isRunDetailDialogOpen = ref(false)
const isIssueDetailDialogOpen = ref(false)
const selectedIssueSection = ref(null)
const runPage = ref(1)
const runPageSize = ref(10)
const selectedRunIds = ref([])
const runFilters = ref({
  keyword: '',
  scopeType: '',
  operations: [],
  statuses: [],
  timeRange: []
})

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

const RUN_STATUS_LABELS = {
  QUEUED: '等待中',
  RUNNING: '运行中',
  CANCELLING: '取消中',
  CANCELLED: '已取消',
  COMPLETED: '完成',
  COMPLETED_WITH_WARNINGS: '有失败项',
  FAILED: '失败'
}
const RUNNING_STATUSES = new Set(['RUNNING', 'CANCELLING'])
const TERMINAL_STATUSES = new Set(['CANCELLED', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED'])
const RUN_OPERATION_OPTIONS = Object.entries(RUN_OPERATION_LABELS).map(([value, label]) => ({ value, label }))
const RUN_STATUS_OPTIONS = Object.entries(RUN_STATUS_LABELS).map(([value, label]) => ({ value, label }))
const ISSUE_CATEGORY_ICONS = {
  'file-ingest': ShieldAlert,
  retrieval: Database,
  capability: BrainCircuit,
  graph: GitBranch,
  'content-risk': AlertTriangle
}

const { ignoredIssueKeys, restoreAllIgnoredIssues } = useKnowledgeHealthIssueIgnore()

const healthSummary = computed(() => healthStore.health?.summary || null)
const healthIssues = computed(() => healthStore.health?.issues || [])
const globalIssues = computed(() => healthIssues.value.filter((issue) => issue.scopeType === 'ALL' && !issue.scopeId))
const globalIssueSections = computed(() =>
  buildIssueCategories(globalIssues.value, ignoredIssueKeys.value).map((section) => ({
    ...section,
    icon: ISSUE_CATEGORY_ICONS[section.key] || AlertTriangle
  }))
)
const activeGlobalIssueCount = computed(() =>
  globalIssueSections.value.reduce((total, section) => total + section.activeIssues.length, 0)
)
const ignoredGlobalIssueCount = computed(() =>
  globalIssueSections.value.reduce((total, section) => total + section.ignoredCount, 0)
)
const folderIssues = computed(() =>
  (healthStore.health?.folders || []).filter((folder) => folderHealthIssueCount(folder))
)
const queueRuns = computed(() => {
  const maintenanceRuns = [...maintenanceStore.currentRuns, ...maintenanceStore.queuedRuns]
  if (maintenanceRuns.length) {
    return uniqueRuns(maintenanceRuns)
  }
  return uniqueRuns([...(healthStore.health?.currentRuns || []), ...(healthStore.health?.queuedRuns || [])])
})
const currentRuns = computed(() => queueRuns.value.filter((run) => RUNNING_STATUSES.has(run.status)))
const queuedRuns = computed(() => queueRuns.value
  .filter((run) => run.status === 'QUEUED')
  .sort((left, right) => (left.queuePosition || 0) - (right.queuePosition || 0))
)
const folderDisplayById = computed(() => {
  const entries = new Map()
  const healthFolders = healthStore.health?.folders || []
  healthFolders.forEach((folder) => {
    entries.set(folder.id, {
      name: folder.displayName || folder.folderPath || folder.id,
      path: folder.folderPath || ''
    })
  })
  knowledgeStore.folders.forEach((folder) => {
    entries.set(folder.id, {
      name: folder.displayName || folder.folderPath || folder.id,
      path: folder.folderPath || ''
    })
  })
  return entries
})
const hasIndexIssue = computed(() =>
  globalIssueSections.value.some((section) =>
    section.activeIssues.some((issue) => issue.code === 'INDEX_INCONSISTENT')
  )
)
const totalIssueEntries = computed(() => activeGlobalIssueCount.value + folderIssues.value.length)
const primaryActionLabel = computed(() => {
  if (hasIndexIssue.value) {
    return '重建索引'
  }
  if (totalIssueEntries.value > 0) {
    return '查看问题'
  }
  return '刷新诊断'
})
const showSecondaryRefresh = computed(() => hasIndexIssue.value || totalIssueEntries.value > 0)
const statusIcon = computed(() => (healthStore.health?.status === 'HEALTHY' ? ShieldCheck : ShieldAlert))
const summaryText = computed(() => {
  const summary = healthSummary.value
  if (!summary) {
    return '正在读取知识库问答状态'
  }
  return `${summary.searchableDocumentCount || 0} 个可检索文档 · ${summary.enabledFolderCount || 0} 个启用目录 · ${summary.chunkCount || 0} chunks`
})
const issueMetrics = computed(() => [
  {
    key: 'sync',
    label: '需要同步',
    value: healthSummary.value?.syncIssueCount || 0,
    tone: (healthSummary.value?.syncIssueCount || 0) > 0 ? 'warning' : 'muted'
  },
  {
    key: 'retrieval',
    label: '检索问题',
    value: healthSummary.value?.retrievalIssueCount || 0,
    tone: (healthSummary.value?.retrievalIssueCount || 0) > 0 ? 'error' : 'muted'
  },
  {
    key: 'conflict',
    label: '资料风险',
    value: healthSummary.value?.conflictIssueCount || 0,
    tone: (healthSummary.value?.conflictIssueCount || 0) > 0 ? 'warning' : 'muted'
  },
  {
    key: 'graph',
    label: '图谱过期',
    value: healthSummary.value?.graphStaleCount || 0,
    tone: (healthSummary.value?.graphStaleCount || 0) > 0 ? 'warning' : 'muted'
  }
])
const systemSignals = computed(() => [
  {
    key: 'answer',
    icon: SearchCheck,
    label: '问答可用性',
    value: healthSummary.value?.answerReady ? '可直接提问' : '需要处理',
    state: healthSummary.value?.answerReady ? 'ok' : 'warning',
    detail: `${healthSummary.value?.searchableDocumentCount || 0} 个文档可被检索命中`
  },
  {
    key: 'lucene',
    icon: Database,
    label: 'Lucene 一致性',
    value: healthSummary.value?.indexConsistent ? '一致' : '不一致',
    state: healthSummary.value?.indexConsistent ? 'ok' : 'error',
    detail: `${healthSummary.value?.luceneDocumentCount || 0} 文档 / ${healthSummary.value?.luceneChunkCount || 0} chunks`
  },
  {
    key: 'embedding',
    icon: BrainCircuit,
    label: 'Embedding 状态',
    value: healthSummary.value?.embeddingConfigured ? '可用' : '未配置',
    state: healthSummary.value?.embeddingConfigured ? 'ok' : 'warning',
    detail: healthSummary.value?.embeddingConfigured ? '向量和混合检索可用' : '向量/混合检索会降级提示'
  },
  {
    key: 'risk',
    icon: GitBranch,
    label: '资料变化与冲突',
    value: (healthSummary.value?.syncIssueCount || 0) + (healthSummary.value?.conflictIssueCount || 0)
      ? '需关注'
      : '无明显风险',
    state: (healthSummary.value?.syncIssueCount || 0) + (healthSummary.value?.conflictIssueCount || 0)
      ? 'warning'
      : 'ok',
    detail: `同步 ${healthSummary.value?.syncIssueCount || 0} · 资料风险 ${healthSummary.value?.conflictIssueCount || 0} · 图谱 ${healthSummary.value?.graphStaleCount || 0}`
  },
  {
    key: 'running',
    icon: Activity,
    label: '当前维护任务',
    value: currentRuns.value.length ? `${currentRuns.value.length} 个运行中` : '空闲',
    state: currentRuns.value.length ? 'warning' : 'ok',
    detail: currentRuns.value.length
      ? currentRuns.value.map((run) => `${runScopeLabel(run)} ${runOperationLabel(run)}`).join('、')
      : '没有正在执行的维护任务'
  }
])
const latestRun = computed(() => maintenanceStore.latestRun || healthStore.health?.latestRun || null)

function healthStatusLabel(status) {
  return HEALTH_STATUS_LABELS[status] || status || '未知'
}

function healthStatusClass(status) {
  return `status-chip--health-${String(status || 'unknown').toLowerCase()}`
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

function runStatusLabel(run) {
  return RUN_STATUS_LABELS[run?.status] || run?.status || '无记录'
}

function runStatusClass(run) {
  return `knowledge-run-status--${String(run?.status || 'unknown').toLowerCase()}`
}

async function refreshHealthPanel() {
  await maintenanceStore.refreshKnowledgeSnapshots()
}

async function rebuildAllIndexes() {
  if (!await confirmRebuildAllIndex()) {
    return
  }
  await searchStore.rebuildIndex()
}

async function handlePrimaryAction() {
  if (hasIndexIssue.value) {
    await rebuildAllIndexes()
    return
  }
  if (totalIssueEntries.value > 0) {
    openGlobalIssues()
    return
  }
  await refreshHealthPanel()
}

function openGlobalIssues() {
  healthStore.selectedFolderId = ''
  healthStore.folderHealth = null
  healthStore.isDrawerOpen = true
}

function openIssueSection(section) {
  selectedIssueSection.value = section
  isIssueDetailDialogOpen.value = true
}

async function openRunsDialog() {
  isRunsDialogOpen.value = true
  runPage.value = 1
  selectedRunIds.value = []
  await fetchRunsPage()
}

async function fetchRunsPage() {
  const [timeFrom, timeTo] = runFilters.value.timeRange || []
  await healthStore.fetchRunsPage({
    keyword: runFilters.value.keyword?.trim(),
    scopeType: runFilters.value.scopeType,
    operations: runFilters.value.operations,
    statuses: runFilters.value.statuses,
    timeFrom: timeFrom ? Number(timeFrom) : undefined,
    timeTo: timeTo ? Number(timeTo) : undefined,
    page: runPage.value,
    pageSize: runPageSize.value
  })
}

async function applyRunFilters() {
  runPage.value = 1
  selectedRunIds.value = []
  await fetchRunsPage()
}

async function resetRunFilters() {
  runFilters.value = {
    keyword: '',
    scopeType: '',
    operations: [],
    statuses: [],
    timeRange: []
  }
  await applyRunFilters()
}

async function handleRunPageChange(page) {
  runPage.value = page
  await fetchRunsPage()
}

async function handleRunPageSizeChange(pageSize) {
  runPageSize.value = pageSize
  runPage.value = 1
  selectedRunIds.value = []
  await fetchRunsPage()
}

function handleRunSelectionChange(rows) {
  selectedRunIds.value = rows.map((run) => run.id)
}

async function openRunDetail(run) {
  isRunDetailDialogOpen.value = true
  await healthStore.fetchRunDetail(run.id)
}

async function deleteRun(run) {
  if (!isTerminalRun(run)) {
    ElMessage.warning('只能删除已完成、失败或已取消的历史记录。')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定删除“${runScopeLabel(run)} · ${runOperationLabel(run)}”这条维护记录吗？该操作不会删除目录、文档或索引。`,
      '删除维护记录',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch (err) {
    if (err === 'cancel' || err === 'close') {
      return
    }
    throw err
  }
  const result = await healthStore.deleteRun(run.id)
  ElMessage.success(result.deletedCount ? '维护记录已删除。' : '该记录暂未删除。')
  await refreshAfterRunHistoryChanged()
}

async function batchDeleteRuns() {
  if (!selectedRunIds.value.length) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${selectedRunIds.value.length} 条维护记录吗？排队或运行中的任务会自动跳过。`,
      '批量删除维护记录',
      {
        type: 'warning',
        confirmButtonText: '批量删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch (err) {
    if (err === 'cancel' || err === 'close') {
      return
    }
    throw err
  }
  const result = await healthStore.batchDeleteRuns(selectedRunIds.value)
  ElMessage.success(`已删除 ${result.deletedCount} 条维护记录${result.skippedCount ? `，跳过 ${result.skippedCount} 条` : ''}。`)
  selectedRunIds.value = []
  await refreshAfterRunHistoryChanged()
}

async function refreshAfterRunHistoryChanged() {
  await fetchRunsPage()
  await maintenanceStore.refreshKnowledgeSnapshots()
}

function runScopeLabel(run) {
  if (!run?.scopeType) {
    return '未知范围'
  }
  if (run.scopeType === 'ALL') {
    return '全库'
  }
  const folder = folderDisplayById.value.get(run.scopeId)
  if (folder?.name) {
    return `目录 · ${folder.name}`
  }
  return run.scopeId ? '目录 · 未知目录' : '目录'
}

function runCurrentItemLabel(run) {
  if (!run) {
    return '处理中'
  }
  if (run.scopeType === 'KNOWLEDGE_FOLDER') {
    const folder = folderDisplayById.value.get(run.scopeId)
    if (folder?.path) {
      return folder.path
    }
    if (folder?.name) {
      return folder.name
    }
  }
  return run.currentItem || (run.status === 'QUEUED' ? '等待执行' : '处理中')
}

function runCountSummary(run) {
  const pieces = []
  if (run?.scannedCount != null) {
    pieces.push(`扫描 ${run.scannedCount}`)
  }
  if (run?.parsedCount != null) {
    pieces.push(`解析 ${run.parsedCount}`)
  }
  if (run?.indexedDocumentCount != null) {
    pieces.push(`索引 ${run.indexedDocumentCount}`)
  }
  const failedCount = (run?.failedCount || 0) + (run?.failedDocumentCount || 0)
  if (failedCount) {
    pieces.push(`失败 ${failedCount}`)
  }
  return pieces.length ? pieces.join(' · ') : '无计数'
}

function isTerminalRun(run) {
  return TERMINAL_STATUSES.has(run?.status)
}

function runTimeLabel(run) {
  return formatTime(run?.completedAt || run?.startedAt || run?.queuedAt || run?.createdAt)
}

function runDurationLabel(run) {
  return run?.durationMs != null ? `${run.durationMs} ms` : '运行中'
}

function runFailureCount(run) {
  return (run?.failedCount || 0) + (run?.failedDocumentCount || 0)
}

function uniqueRuns(runs) {
  const byId = new Map()
  runs.forEach((run) => {
    if (run?.id) {
      byId.set(run.id, run)
    }
  })
  return [...byId.values()]
}

defineExpose({
  openRunsDialog
})
</script>

<template>
  <section class="knowledge-pane knowledge-pane--health" aria-label="知识库问答可用性">
    <header class="knowledge-health-page-header">
      <div>
        <p class="eyebrow">问答可用性</p>
        <h3>问答诊断与维护</h3>
        <p class="muted-text">检查资料是否已同步、能否被搜索命中，以及是否存在过期或冲突风险。</p>
      </div>
      <div class="header-actions">
        <el-button
          type="primary"
          :disabled="healthStore.isLoading || searchStore.isRebuildingIndex || maintenanceStore.isEnqueueing"
          :loading="searchStore.isRebuildingIndex"
          @click="handlePrimaryAction"
        >
          <RotateCcw v-if="hasIndexIssue" aria-hidden="true" />
          <AlertTriangle v-else-if="totalIssueEntries > 0" aria-hidden="true" />
          <RefreshCw v-else aria-hidden="true" />
          <span>{{ primaryActionLabel }}</span>
        </el-button>
        <el-button
          v-if="showSecondaryRefresh"
          :loading="healthStore.isLoading || healthStore.isLoadingRuns"
          @click="refreshHealthPanel"
        >
          <RefreshCw aria-hidden="true" />
          <span>刷新</span>
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="healthStore.error"
      class="settings-inline-alert"
      type="error"
      :title="healthStore.error"
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

    <section class="knowledge-health-command-center" aria-label="问答可用性摘要">
      <div class="knowledge-health-command-card">
        <span :class="['knowledge-health-command-card__icon', healthStatusClass(healthStore.health?.status)]">
          <component :is="statusIcon" aria-hidden="true" />
        </span>
        <div class="knowledge-health-command-card__content">
          <span :class="['status-chip', healthStatusClass(healthStore.health?.status)]">
            {{ healthStatusLabel(healthStore.health?.status) }}
          </span>
          <strong>全库问答状态</strong>
          <p>{{ summaryText }}</p>
          <div class="knowledge-health-command-card__signals">
            <span>Lucene {{ healthSummary?.indexConsistent ? '一致' : '不一致' }}</span>
            <span>Embedding {{ healthSummary?.embeddingConfigured ? '可用' : '未配置' }}</span>
            <span>{{ healthSummary?.answerReady ? '可直接提问' : '需要处理后再提问' }}</span>
          </div>
        </div>
      </div>

      <dl class="knowledge-health-command-metrics" aria-label="问答可用性问题计数">
        <div
          v-for="metric in issueMetrics"
          :key="metric.key"
          :class="['knowledge-health-command-metric', `knowledge-health-command-metric--${metric.tone}`]"
        >
          <dt>{{ metric.label }}</dt>
          <dd>{{ metric.value }}</dd>
        </div>
      </dl>
    </section>

    <section class="knowledge-health-signal-grid" aria-label="系统诊断信号">
      <article
        v-for="signal in systemSignals"
        :key="signal.key"
        :class="['knowledge-health-signal-card', `knowledge-health-signal-card--${signal.state}`]"
      >
        <component :is="signal.icon" aria-hidden="true" />
        <div>
          <span>{{ signal.label }}</span>
          <strong>{{ signal.value }}</strong>
          <p>{{ signal.detail }}</p>
        </div>
      </article>
    </section>

    <section class="knowledge-health-main-grid">
      <section class="knowledge-health-section" aria-label="全局诊断问题">
        <header>
          <div>
            <p class="eyebrow">全局诊断</p>
            <h4>问答能力</h4>
          </div>
          <div class="knowledge-health-section__header-actions">
            <span>{{ activeGlobalIssueCount }} 项</span>
            <el-button
              v-if="ignoredGlobalIssueCount"
              text
              @click="restoreAllIgnoredIssues"
            >
              恢复忽略
            </el-button>
          </div>
        </header>

        <p v-if="!globalIssueSections.length" class="knowledge-health-empty">
          <CheckCircle2 aria-hidden="true" />
          <span>没有影响问答能力的全局问题。</span>
        </p>

        <button
          v-for="section in globalIssueSections"
          :key="section.key"
          :class="['knowledge-health-category-row', `knowledge-health-category-row--${section.tone}`, { 'is-ignored': !section.activeCount }]"
          type="button"
          @click="openIssueSection(section)"
        >
          <div>
            <component :is="section.icon" aria-hidden="true" />
            <div>
              <strong>{{ section.title }}</strong>
              <p>{{ section.subtitle }}</p>
              <small v-if="section.ruleSummary">{{ section.ruleSummary }}</small>
            </div>
          </div>
          <span>
            {{ section.activeCount ? `${section.activeCount} 项` : '已忽略' }}
            <em v-if="section.ignoredCount">{{ section.ignoredCount }} 已忽略</em>
            <ChevronRight aria-hidden="true" />
          </span>
        </button>
      </section>

      <section class="knowledge-health-section" aria-label="目录诊断问题">
        <header>
          <div>
            <p class="eyebrow">目录诊断</p>
            <h4>需要处理的目录</h4>
          </div>
          <span>{{ folderIssues.length }} 个目录</span>
        </header>

        <p v-if="!folderIssues.length" class="knowledge-health-empty">
          <CheckCircle2 aria-hidden="true" />
          <span>所有启用目录当前可用于问答。</span>
        </p>

        <article
          v-for="folder in folderIssues"
          :key="folder.id"
          class="knowledge-health-folder-row"
        >
          <div>
            <strong>
              <FolderOpen aria-hidden="true" />
              <span>{{ folder.displayName }}</span>
            </strong>
            <p class="path-text">{{ folder.folderPath }}</p>
            <div class="folder-meta">
              <span :class="['status-chip', healthStatusClass(folder.status)]">
                {{ healthStatusLabel(folder.status) }}
              </span>
              <span>{{ folderHealthIssueCount(folder) }} 个问题</span>
              <span v-if="folder.failedCount">失败 {{ folder.failedCount }}</span>
              <span v-if="folder.unindexedCount">未索引 {{ folder.unindexedCount }}</span>
              <span v-if="folder.missingLocalFileCount">缺失 {{ folder.missingLocalFileCount }}</span>
              <span v-if="folder.staleLocalFileCount">变化 {{ folder.staleLocalFileCount }}</span>
              <span v-if="folder.newLocalFileCount">新增 {{ folder.newLocalFileCount }}</span>
            </div>
          </div>
          <el-button @click="healthStore.openFolderIssues(folder.id)">
            <AlertTriangle aria-hidden="true" />
            <span>诊断详情</span>
          </el-button>
        </article>
      </section>
    </section>

    <section class="knowledge-health-section knowledge-health-section--queue" aria-label="维护队列">
      <header>
        <div>
          <p class="eyebrow">维护队列</p>
          <h4>当前任务与等待队列</h4>
        </div>
        <el-button :loading="maintenanceStore.isLoadingQueue" @click="maintenanceStore.fetchQueue">
          <RefreshCw aria-hidden="true" />
          <span>刷新队列</span>
        </el-button>
      </header>

      <article v-if="currentRuns.length" class="knowledge-maintenance-task knowledge-maintenance-task--running">
        <div>
          <strong>{{ runScopeLabel(currentRuns[0]) }} · {{ runOperationLabel(currentRuns[0]) }}</strong>
          <span :class="['knowledge-run-status', runStatusClass(currentRuns[0])]">
            <Activity aria-hidden="true" />
            <span>{{ runStatusLabel(currentRuns[0]) }}</span>
          </span>
        </div>
        <p>{{ currentRuns[0].phase || 'RUNNING' }} · {{ runCurrentItemLabel(currentRuns[0]) }}</p>
        <span class="knowledge-maintenance-task__hint">
          文件较多时导入、同步或重建索引可能需要较长时间；运行中任务不可取消，等待队列中的任务可取消。
        </span>
      </article>

      <p v-else class="knowledge-health-empty">
        <CheckCircle2 aria-hidden="true" />
        <span>当前没有运行中的维护任务。</span>
      </p>

      <section v-if="queuedRuns.length" class="knowledge-maintenance-queue-list" aria-label="等待队列">
        <article v-for="run in queuedRuns" :key="run.id" class="knowledge-maintenance-task">
          <div>
            <strong>#{{ run.queuePosition || '-' }} {{ runScopeLabel(run) }} · {{ runOperationLabel(run) }}</strong>
            <span :class="['knowledge-run-status', runStatusClass(run)]">
              <Activity aria-hidden="true" />
              <span>{{ runStatusLabel(run) }}</span>
            </span>
          </div>
          <p>{{ runCurrentItemLabel(run) }} · 排队 {{ formatTime(run.queuedAt || run.createdAt) }}</p>
          <el-button
            :loading="maintenanceStore.isRunCancelling(run.id)"
            @click="maintenanceStore.cancelRun(run.id)"
          >
            取消排队
          </el-button>
        </article>
      </section>
    </section>

    <section class="knowledge-health-section knowledge-health-section--runs-entry" aria-label="维护记录入口">
      <header>
        <div>
          <p class="eyebrow">维护记录</p>
          <h4>运行历史</h4>
        </div>
        <el-button @click="openRunsDialog">
          <Activity aria-hidden="true" />
          <span>查看全部</span>
        </el-button>
      </header>

      <article v-if="latestRun" class="knowledge-health-run-preview">
        <div>
          <strong>{{ runOperationLabel(latestRun) }}</strong>
          <span :class="['knowledge-run-status', runStatusClass(latestRun)]">
            <Activity aria-hidden="true" />
            <span>{{ runStatusLabel(latestRun) }}</span>
          </span>
        </div>
        <p>{{ runScopeLabel(latestRun) }} · {{ runCountSummary(latestRun) }}</p>
        <em>{{ formatTime(latestRun.completedAt || latestRun.startedAt) }} · {{ latestRun.durationMs != null ? `${latestRun.durationMs} ms` : '运行中' }}</em>
      </article>
      <p v-else class="knowledge-health-empty">
        <Activity aria-hidden="true" />
        <span>暂无最近维护记录；点击查看全部可按页读取历史数据。</span>
      </p>
    </section>

    <el-dialog
      v-model="isRunsDialogOpen"
      class="knowledge-health-runs-dialog"
      title="维护记录"
      width="min(1120px, calc(100vw - 32px))"
      align-center
    >
      <section class="knowledge-run-history-toolbar" aria-label="维护记录筛选">
        <div class="knowledge-run-history-toolbar__filters">
          <el-input
            v-model="runFilters.keyword"
            class="knowledge-run-history-toolbar__search"
            clearable
            placeholder="搜索范围、目录、错误、任务 ID"
            @keyup.enter="applyRunFilters"
          >
            <template #prefix>
              <Search aria-hidden="true" />
            </template>
          </el-input>
          <el-select v-model="runFilters.scopeType" clearable placeholder="范围">
            <el-option label="全库" value="ALL" />
            <el-option label="目录" value="KNOWLEDGE_FOLDER" />
          </el-select>
          <el-select v-model="runFilters.operations" multiple collapse-tags collapse-tags-tooltip placeholder="操作">
            <el-option
              v-for="option in RUN_OPERATION_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-select v-model="runFilters.statuses" multiple collapse-tags collapse-tags-tooltip placeholder="状态">
            <el-option
              v-for="option in RUN_STATUS_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-date-picker
            v-model="runFilters.timeRange"
            class="knowledge-run-history-toolbar__time"
            type="datetimerange"
            value-format="x"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            range-separator="至"
          />
        </div>
        <div class="knowledge-run-history-toolbar__actions">
          <el-button type="primary" :loading="healthStore.isLoadingRuns" @click="applyRunFilters">
            <Search aria-hidden="true" />
            查询
          </el-button>
          <el-button @click="resetRunFilters">
            <RefreshCw aria-hidden="true" />
            重置
          </el-button>
        </div>
      </section>

      <section class="knowledge-run-history-bulkbar" aria-label="维护记录批量操作">
        <span>已选 {{ selectedRunIds.length }} 条</span>
        <el-button
          v-if="selectedRunIds.length"
          type="danger"
          plain
          @click="batchDeleteRuns"
        >
          <Trash2 aria-hidden="true" />
          <span>批量删除</span>
        </el-button>
      </section>

      <el-table
        v-loading="healthStore.isLoadingRuns"
        class="knowledge-run-history-table"
        :data="healthStore.runsPage.items"
        :max-height="440"
        empty-text="暂无维护记录"
        row-key="id"
        @selection-change="handleRunSelectionChange"
      >
        <el-table-column type="selection" width="48" :selectable="isTerminalRun" />
        <el-table-column label="操作" min-width="88">
          <template #default="{ row }">
            <strong class="knowledge-run-history-table__operation">{{ runOperationLabel(row) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="116">
          <template #default="{ row }">
            <span :class="['knowledge-run-status', runStatusClass(row)]">
              <Activity aria-hidden="true" />
              <span>{{ runStatusLabel(row) }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="范围" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">
            {{ runScopeLabel(row) }}
          </template>
        </el-table-column>
        <el-table-column label="计数" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            {{ runCountSummary(row) }}
          </template>
        </el-table-column>
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">
            {{ runTimeLabel(row) }}
          </template>
        </el-table-column>
        <el-table-column label="耗时" min-width="96">
          <template #default="{ row }">
            {{ runDurationLabel(row) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="128">
          <template #default="{ row }">
            <div class="knowledge-run-history-table__actions">
              <el-button
                text
                title="查看详情"
                aria-label="查看详情"
                @click="openRunDetail(row)"
              >
                <Eye aria-hidden="true" />
              </el-button>
              <el-button
                text
                type="danger"
                title="删除记录"
                aria-label="删除记录"
                :disabled="!isTerminalRun(row)"
                @click="deleteRun(row)"
              >
                <Trash2 aria-hidden="true" />
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-pagination
          v-model:current-page="runPage"
          v-model:page-size="runPageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="healthStore.runsPage.total"
          background
          layout="total, sizes, prev, pager, next"
          @current-change="handleRunPageChange"
          @size-change="handleRunPageSizeChange"
        />
      </template>
    </el-dialog>

    <el-dialog
      v-model="isRunDetailDialogOpen"
      class="knowledge-run-detail-dialog"
      title="维护记录详情"
      width="min(720px, calc(100vw - 32px))"
      align-center
    >
      <p v-if="healthStore.isLoadingRuns && !healthStore.selectedRunDetail" class="panel-message">
        正在读取维护记录详情...
      </p>
      <section v-else-if="healthStore.selectedRunDetail" class="knowledge-run-detail">
        <div class="knowledge-run-detail__header">
          <strong>{{ runOperationLabel(healthStore.selectedRunDetail) }}</strong>
          <span :class="['knowledge-run-status', runStatusClass(healthStore.selectedRunDetail)]">
            <Activity aria-hidden="true" />
            <span>{{ runStatusLabel(healthStore.selectedRunDetail) }}</span>
          </span>
        </div>
        <dl class="knowledge-run-detail__grid">
          <div>
            <dt>范围</dt>
            <dd>{{ runScopeLabel(healthStore.selectedRunDetail) }}</dd>
          </div>
          <div>
            <dt>任务 ID</dt>
            <dd>{{ healthStore.selectedRunDetail.id }}</dd>
          </div>
          <div v-if="healthStore.selectedRunDetail.folderPath">
            <dt>目录路径</dt>
            <dd>{{ healthStore.selectedRunDetail.folderPath }}</dd>
          </div>
          <div>
            <dt>阶段</dt>
            <dd>{{ healthStore.selectedRunDetail.phase || '无' }}</dd>
          </div>
          <div>
            <dt>排队时间</dt>
            <dd>{{ formatTime(healthStore.selectedRunDetail.queuedAt) }}</dd>
          </div>
          <div>
            <dt>开始时间</dt>
            <dd>{{ formatTime(healthStore.selectedRunDetail.startedAt) }}</dd>
          </div>
          <div>
            <dt>完成时间</dt>
            <dd>{{ formatTime(healthStore.selectedRunDetail.completedAt) }}</dd>
          </div>
          <div>
            <dt>耗时</dt>
            <dd>{{ runDurationLabel(healthStore.selectedRunDetail) }}</dd>
          </div>
        </dl>
        <dl class="knowledge-run-detail__metrics">
          <div>
            <dt>扫描</dt>
            <dd>{{ healthStore.selectedRunDetail.scannedCount }}</dd>
          </div>
          <div>
            <dt>解析</dt>
            <dd>{{ healthStore.selectedRunDetail.parsedCount }}</dd>
          </div>
          <div>
            <dt>索引文档</dt>
            <dd>{{ healthStore.selectedRunDetail.indexedDocumentCount }}</dd>
          </div>
          <div :class="{ 'knowledge-run-detail__metric--error': runFailureCount(healthStore.selectedRunDetail) }">
            <dt>失败</dt>
            <dd>{{ runFailureCount(healthStore.selectedRunDetail) }}</dd>
          </div>
        </dl>
        <p v-if="healthStore.selectedRunDetail.errorMessage" class="knowledge-run-detail__message">
          {{ healthStore.selectedRunDetail.errorMessage }}
        </p>
        <section v-if="healthStore.selectedRunDetail.failures?.length" class="knowledge-run-detail__failures">
          <h5>失败明细</h5>
          <article v-for="failure in healthStore.selectedRunDetail.failures" :key="`${failure.sourcePath}-${failure.message}`">
            <strong>{{ failure.sourcePath }}</strong>
            <span>{{ failure.message }}</span>
          </article>
        </section>
      </section>
    </el-dialog>

    <KnowledgeHealthIssueDetailDialog
      v-model="isIssueDetailDialogOpen"
      :section="selectedIssueSection"
    />

    <KnowledgeHealthDrawer />
  </section>
</template>
