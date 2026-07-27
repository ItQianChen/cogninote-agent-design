<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlertTriangle, CheckCircle2, RefreshCw, Settings2, XCircle } from 'lucide-vue-next'
import KnowledgeDirectoryManagerPanel from './knowledge-directory-manager-panel.vue'
import KnowledgeFolderPanel from './knowledge-folder-panel.vue'
import KnowledgeGraphPanel from './knowledge-graph-panel.vue'
import KnowledgeHealthPanel from './knowledge-health-panel.vue'
import KnowledgeSearchPanel from './knowledge-search-panel.vue'
import { normalizeKnowledgePanel } from '../config/knowledge-navigation'
import { useKnowledgeFoldersStore } from '../stores/knowledge-folders'
import { useKnowledgeHealthStore } from '../stores/knowledge-health'
import { useKnowledgeMaintenanceStore } from '../stores/knowledge-maintenance'
import { useModelConfigStore } from '../stores/model-config'
import { useSearchStore } from '../stores/search'

const route = useRoute()
const router = useRouter()
const knowledgeStore = useKnowledgeFoldersStore()
const knowledgeHealthStore = useKnowledgeHealthStore()
const maintenanceStore = useKnowledgeMaintenanceStore()
const searchStore = useSearchStore()
const modelConfigStore = useModelConfigStore()
const healthPanelRef = ref(null)

/**
 * 知识库工作区的面板编排组件。
 *
 * <p>这里聚合目录、索引和模型配置三个 store 的启动快照，避免每个子面板重复触发初始化请求。</p>
 */
const activePanel = computed(() => normalizeKnowledgePanel(route.query.panel))
const embeddingReady = computed(() => {
  const config = modelConfigStore.activeEmbeddingConfig
  return Boolean(config?.apiKeyConfigured && config?.modelName)
})
const isRefreshing = computed(() =>
  knowledgeStore.isLoading || knowledgeHealthStore.isLoading || searchStore.isLoadingIndexStatus
    || maintenanceStore.isLoadingQueue
)
const completionDialogOpen = computed({
  get: () => Boolean(maintenanceStore.completionNoticeRun),
  set: (value) => {
    if (!value) {
      maintenanceStore.acknowledgeCompletionNotice()
    }
  }
})
const completionRun = computed(() => maintenanceStore.completionNoticeRun)
const completionNoticeCount = computed(() => maintenanceStore.completionNoticeRuns.length)
const completionFailed = computed(() => completionRun.value?.status === 'FAILED' || completionRun.value?.status === 'INTERRUPTED')
const completionWarning = computed(() => completionRun.value?.status === 'COMPLETED_WITH_WARNINGS')
const completionIcon = computed(() => {
  if (completionFailed.value) {
    return XCircle
  }
  if (completionWarning.value) {
    return AlertTriangle
  }
  return CheckCircle2
})
const completionDialogTitle = computed(() => {
  if (completionFailed.value) {
    return completionRun.value?.status === 'INTERRUPTED' ? '维护任务已中断' : '维护任务失败'
  }
  if (completionWarning.value) {
    return '完成，但有失败项'
  }
  return '维护任务已完成'
})
const completionMetrics = computed(() => buildCompletionMetrics(completionRun.value))

const RUN_OPERATION_LABELS = {
  IMPORT: '导入目录',
  REBUILD_INDEX: '重建索引',
  REPAIR_INDEX: '补写索引',
  SYNC: '同步目录',
  REPARSE: '重新解析目录',
  ENABLE: '启用目录',
  DISABLE: '停用目录',
  DELETE: '删除目录'
}
onMounted(() => {
  // 三个请求互不依赖，并行加载能让侧栏摘要和当前面板尽快进入可用状态。
  void Promise.all([
    knowledgeStore.ensureFoldersLoaded(),
    knowledgeHealthStore.ensureHealthLoaded(),
    maintenanceStore.ensureQueueLoaded(),
    searchStore.ensureIndexStatusLoaded(),
    modelConfigStore.ensureModelConfigLoaded()
  ])
})

async function refreshWorkbench() {
  await Promise.all([
    maintenanceStore.refreshKnowledgeSnapshots(),
    modelConfigStore.ensureModelConfigLoaded()
  ])
}

function completionOperationLabel(run) {
  if (!run) {
    return '维护任务'
  }
  if (run.operation === 'REBUILD_INDEX' && run.scopeType === 'ALL') {
    return '重建全部索引'
  }
  if (run.operation === 'REPAIR_INDEX' && run.scopeType === 'ALL') {
    return '补写全部索引'
  }
  if (run.operation === 'REBUILD_INDEX') {
    return '重建目录索引'
  }
  if (run.operation === 'REPAIR_INDEX') {
    return '补写目录索引'
  }
  if (run.operation === 'REPARSE') {
    return '重新解析目录'
  }
  return RUN_OPERATION_LABELS[run.operation] || run.operation || '维护任务'
}

function completionScopeLabel(run) {
  if (!run) {
    return '未知范围'
  }
  if (run.scopeType === 'ALL') {
    return '全库'
  }
  const folder = findFolderDisplay(run.scopeId)
  if (folder?.name) {
    return folder.name
  }
  return run.currentItem || '未知目录'
}

function completionScopeDetail(run) {
  if (!run || run.scopeType === 'ALL') {
    return ''
  }
  const folder = findFolderDisplay(run.scopeId)
  return folder?.path || run.currentItem || ''
}

function findFolderDisplay(folderId) {
  const folder = knowledgeStore.folders.find((item) => item.id === folderId)
  if (folder) {
    return {
      name: folder.displayName || folder.folderPath || folder.id,
      path: folder.folderPath || ''
    }
  }
  const healthFolder = (knowledgeHealthStore.health?.folders || []).find((item) => item.id === folderId)
  if (!healthFolder) {
    return null
  }
  return {
    name: healthFolder.displayName || healthFolder.folderPath || healthFolder.id,
    path: healthFolder.folderPath || ''
  }
}

function buildCompletionMetrics(run) {
  if (!run) {
    return []
  }
  const metrics = []
  if (run.operation === 'IMPORT' || run.operation === 'REPARSE' || run.operation === 'SYNC') {
    metrics.push(
      { label: '扫描', value: run.scannedCount || 0 },
      { label: '解析', value: run.parsedCount || 0 },
      { label: '跳过', value: run.skippedCount || 0 },
      { label: '失败', value: (run.failedCount || 0) + (run.failedDocumentCount || 0), tone: hasRunFailure(run) ? 'error' : 'default' }
    )
  } else if (run.operation === 'REBUILD_INDEX' || run.operation === 'REPAIR_INDEX') {
    if (run.scannedCount != null && run.scopeType !== 'ALL') {
      metrics.push({ label: '扫描', value: run.scannedCount || 0 })
    }
    metrics.push(
      { label: '索引文档', value: run.indexedDocumentCount || 0 },
      { label: '索引 chunks', value: run.indexedChunkCount || 0 },
      { label: '失败文档', value: run.failedDocumentCount || 0, tone: hasRunFailure(run) ? 'error' : 'default' }
    )
  }
  metrics.push({ label: '耗时', value: formatDuration(run.durationMs) })
  return metrics
}

function hasRunFailure(run) {
  return run?.status === 'FAILED'
    || run?.status === 'INTERRUPTED'
    || run?.status === 'COMPLETED_WITH_WARNINGS'
    || Boolean((run?.failedCount || 0) + (run?.failedDocumentCount || 0))
}

function formatDuration(durationMs) {
  if (durationMs == null) {
    return '-'
  }
  if (durationMs < 1000) {
    return `${durationMs} ms`
  }
  return `${(durationMs / 1000).toFixed(1)} s`
}

async function openMaintenanceRuns() {
  maintenanceStore.acknowledgeCompletionNotice()
  if (activePanel.value !== 'health') {
    await router.push({ name: 'knowledge', query: { panel: 'health' } })
    await nextTick()
  }
  await healthPanelRef.value?.openRunsDialog()
}
</script>

<template>
  <section
    class="knowledge-workbench"
    :class="{ 'knowledge-workbench--directory-manager': activePanel === 'directories' }"
  >
    <header
      v-if="activePanel === 'search'"
      class="knowledge-workbench__header knowledge-workbench__header--actions-only"
    >
      <div class="knowledge-workbench__actions">
        <button
          class="secondary-button knowledge-workbench__refresh-button"
          type="button"
          :disabled="isRefreshing"
          aria-label="刷新知识库"
          title="刷新知识库"
          @click="refreshWorkbench"
        >
          <RefreshCw aria-hidden="true" />
        </button>
        <RouterLink class="secondary-button" :to="{ name: 'settings', query: { item: 'model-embedding' } }">
          <Settings2 aria-hidden="true" />
          <span>向量模型</span>
        </RouterLink>
      </div>
    </header>

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
      <KnowledgeHealthPanel v-else-if="activePanel === 'health'" ref="healthPanelRef" />
      <KnowledgeDirectoryManagerPanel v-else-if="activePanel === 'directories'" />
      <KnowledgeSearchPanel v-else-if="activePanel === 'search'" />
      <KnowledgeGraphPanel v-else />
    </div>

    <el-dialog
      v-model="completionDialogOpen"
      class="knowledge-maintenance-completion-dialog"
      :title="completionDialogTitle"
      width="min(620px, calc(100vw - 32px))"
      align-center
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
    >
      <section
        v-if="completionRun"
        :class="[
          'knowledge-maintenance-completion',
          completionFailed ? 'knowledge-maintenance-completion--error' : '',
          completionWarning ? 'knowledge-maintenance-completion--warning' : ''
        ]"
      >
        <span class="knowledge-maintenance-completion__icon">
          <component :is="completionIcon" aria-hidden="true" />
        </span>
        <div class="knowledge-maintenance-completion__body">
          <strong>{{ completionOperationLabel(completionRun) }} · {{ completionScopeLabel(completionRun) }}</strong>
          <p v-if="completionScopeDetail(completionRun)" class="path-text">{{ completionScopeDetail(completionRun) }}</p>
          <p v-if="completionRun.errorMessage" class="knowledge-maintenance-completion__message">
            {{ completionRun.errorMessage }}
          </p>
          <p v-if="completionNoticeCount > 1" class="knowledge-maintenance-completion__message">
            还有 {{ completionNoticeCount - 1 }} 条维护完成信息等待确认。
          </p>
          <dl class="knowledge-maintenance-completion__metrics">
            <div
              v-for="metric in completionMetrics"
              :key="metric.label"
              :class="{ 'knowledge-maintenance-completion__metric--error': metric.tone === 'error' }"
            >
              <dt>{{ metric.label }}</dt>
              <dd>{{ metric.value }}</dd>
            </div>
          </dl>
        </div>
      </section>

      <template #footer>
        <el-button @click="maintenanceStore.acknowledgeCompletionNotice">知道了</el-button>
        <el-button type="primary" @click="openMaintenanceRuns">查看维护记录</el-button>
      </template>
    </el-dialog>
  </section>
</template>
