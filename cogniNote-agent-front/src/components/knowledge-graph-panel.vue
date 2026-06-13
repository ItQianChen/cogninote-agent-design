<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Maximize2, Network, Play, RefreshCw, Square } from 'lucide-vue-next'
import GraphAdjacencyList from './graph-adjacency-list.vue'
import GraphEvidenceDrawer from './graph-evidence-drawer.vue'
import GraphViewer from './graph-viewer.vue'
import MindmapViewer from './mindmap-viewer.vue'
import SegmentedControl from './segmented-control.vue'
import { useKnowledgeFoldersStore } from '../stores/knowledge-folders'
import { GRAPH_VIEW_OPTIONS, useKnowledgeGraphStore } from '../stores/knowledge-graph'
import { formatTime } from '../utils/formatters'

const knowledgeStore = useKnowledgeFoldersStore()
const graphStore = useKnowledgeGraphStore()
const isExplorerDialogOpen = ref(false)

/**
 * 知识图谱管理面板。
 *
 * <p>面板负责让 scope 选择始终指向一个可生成的目录或文档；实际 run 状态、SSE 订阅和视图缓存都归
 * knowledgeGraph store 管理。</p>
 */
const scopeTypeOptions = [
  { value: 'ALL', label: '全库' },
  { value: 'KNOWLEDGE_FOLDER', label: '目录' },
  { value: 'DOCUMENT', label: '文档' }
]
const folderOptions = computed(() => knowledgeStore.folders.filter((folder) => folder.enabled))
const documentOptions = computed(() => [
  ...knowledgeStore.folders.flatMap((folder) => folder.documents || []),
  ...knowledgeStore.unassignedDocuments
].filter((document) => document.status === 'PARSED'))
const scopedOptions = computed(() =>
  graphStore.scopeType === 'KNOWLEDGE_FOLDER' ? folderOptions.value : documentOptions.value
)
const canGenerate = computed(() => graphStore.scopeType === 'ALL' || Boolean(graphStore.scopeId))
const progressPercent = computed(() => {
  const total = graphStore.progress?.totalChunkCount || graphStore.currentRun?.totalChunkCount || 0
  const processed = graphStore.progress?.processedChunkCount || graphStore.currentRun?.processedChunkCount || 0
  if (!total) {
    return graphStore.isRunActive ? 5 : 0
  }
  return Math.min(100, Math.round((processed / total) * 100))
})
const runStatusText = computed(() => {
  const phase = graphStore.progress?.phase || graphStore.currentRun?.status || 'IDLE'
  const labels = {
    QUEUED: '等待中',
    EXTRACTING: '抽取中',
    MERGING: '合并中',
    CANCELLING: '取消中',
    COMPLETED: '已完成',
    CANCELLED: '已取消',
    FAILED: '失败',
    RUNNING: '运行中'
  }
  return labels[phase] || phase
})
const failedMessage = computed(() => graphStore.currentRun?.status === 'FAILED'
  ? graphStore.currentRun.errorMessage || '知识图谱生成失败'
  : '')
const currentViewLabel = computed(() =>
  GRAPH_VIEW_OPTIONS.find((option) => option.value === graphStore.viewType)?.label || '图谱视图'
)

onMounted(async () => {
  await knowledgeStore.ensureFoldersLoaded()
  await ensureScopeSelection()
  await graphStore.loadStatus()
})

watch(
  () => graphStore.viewType,
  () => {
    // 切换展示类型不改变图谱 scope，只按需读取对应后端视图或复用 GRAPH payload。
    void graphStore.loadCurrentView()
  }
)

async function ensureScopeSelection() {
  // 默认选中当前可用的第一项，避免用户切到目录/文档范围后生成按钮处于无效空 scope。
  if (graphStore.scopeType === 'KNOWLEDGE_FOLDER' && !graphStore.scopeId && folderOptions.value.length) {
    await graphStore.selectScope('KNOWLEDGE_FOLDER', folderOptions.value[0].id)
  }
  if (graphStore.scopeType === 'DOCUMENT' && !graphStore.scopeId && documentOptions.value.length) {
    await graphStore.selectScope('DOCUMENT', documentOptions.value[0].id)
  }
}

async function handleScopeTypeChange(value) {
  if (value === 'KNOWLEDGE_FOLDER') {
    await graphStore.selectScope(value, folderOptions.value[0]?.id || '')
    return
  }
  if (value === 'DOCUMENT') {
    await graphStore.selectScope(value, documentOptions.value[0]?.id || '')
    return
  }
  await graphStore.selectScope('ALL', '')
}

async function handleScopeIdChange(value) {
  await graphStore.selectScope(graphStore.scopeType, value)
}
</script>

<template>
  <section class="knowledge-pane knowledge-pane--graph" aria-label="知识图谱">
    <header class="knowledge-pane__header knowledge-pane__header--compact">
      <div>
        <p class="eyebrow">知识图谱</p>
        <h3>图谱</h3>
        <p class="muted-text">
          {{ graphStore.statusSnapshot?.nodeCount || 0 }} 节点 ·
          {{ graphStore.statusSnapshot?.edgeCount || 0 }} 关系 ·
          更新 {{ formatTime(graphStore.statusSnapshot?.generatedAt) }}
        </p>
      </div>
      <div class="header-actions">
        <el-button
          :loading="graphStore.isLoadingStatus"
          aria-label="刷新图谱状态"
          title="刷新图谱状态"
          @click="graphStore.loadStatus"
        >
          <RefreshCw aria-hidden="true" />
        </el-button>
        <el-button
          type="primary"
          :disabled="!canGenerate || graphStore.isRunActive"
          :loading="graphStore.isRebuilding"
          @click="graphStore.rebuild"
        >
          <Play aria-hidden="true" />
          <span>生成</span>
        </el-button>
        <el-button
          v-if="graphStore.isRunActive"
          :loading="graphStore.isCancelling"
          @click="graphStore.cancelRun"
        >
          <Square aria-hidden="true" />
          <span>取消</span>
        </el-button>
      </div>
    </header>

    <section class="graph-toolbar" aria-label="图谱工具栏">
      <label class="field graph-toolbar__field">
        <span>范围</span>
        <el-select v-model="graphStore.scopeType" @change="handleScopeTypeChange">
          <el-option
            v-for="option in scopeTypeOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </label>

      <label v-if="graphStore.scopeType !== 'ALL'" class="field graph-toolbar__field graph-toolbar__field--wide">
        <span>{{ graphStore.scopeType === 'KNOWLEDGE_FOLDER' ? '目录' : '文档' }}</span>
        <el-select v-model="graphStore.scopeId" filterable @change="handleScopeIdChange">
          <el-option
            v-for="option in scopedOptions"
            :key="option.id"
            :label="option.displayName || option.fileName"
            :value="option.id"
          />
        </el-select>
      </label>

      <SegmentedControl v-model="graphStore.viewType" :options="GRAPH_VIEW_OPTIONS" label="图谱视图" />

      <el-button
        class="graph-toolbar__fullscreen"
        :disabled="!graphStore.hasCurrentViewReady() || graphStore.isRunActive"
        aria-label="全屏探索图谱"
        title="全屏探索图谱"
        @click="isExplorerDialogOpen = true"
      >
        <Maximize2 aria-hidden="true" />
      </el-button>
    </section>

    <el-alert
      v-if="graphStore.error"
      class="settings-inline-alert"
      type="error"
      :title="graphStore.error"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="graphStore.viewError"
      class="settings-inline-alert"
      type="error"
      :title="graphStore.viewError"
      :closable="false"
      show-icon
    />

    <section v-if="graphStore.isRunActive" class="graph-run-panel" aria-label="图谱生成进度">
      <div>
        <Network aria-hidden="true" />
        <strong>{{ runStatusText }}</strong>
      </div>
      <el-progress :percentage="progressPercent" :stroke-width="10" />
      <p>
        已处理 {{ graphStore.progress?.processedChunkCount || graphStore.currentRun?.processedChunkCount || 0 }} /
        {{ graphStore.progress?.totalChunkCount || graphStore.currentRun?.totalChunkCount || 0 }} chunks ·
        跳过 {{ graphStore.progress?.skippedChunkCount || graphStore.currentRun?.skippedChunkCount || 0 }} ·
        失败 {{ graphStore.progress?.failedChunkCount || graphStore.currentRun?.failedChunkCount || 0 }}
      </p>
    </section>

    <el-alert
      v-else-if="failedMessage"
      class="settings-inline-alert"
      type="error"
      :title="failedMessage"
      :closable="false"
      show-icon
    />

    <section v-if="!graphStore.isRunActive && !graphStore.hasCurrentViewReady()" class="graph-empty-state">
      <Network aria-hidden="true" />
      <p>当前范围还没有知识图谱。</p>
      <el-button type="primary" :disabled="!canGenerate" @click="graphStore.rebuild">生成知识图谱</el-button>
    </section>

    <section v-else-if="!graphStore.isRunActive" class="graph-view-shell">
      <p v-if="graphStore.isLoadingView" class="panel-message">正在读取图谱视图...</p>
      <MindmapViewer
        v-else-if="graphStore.viewType === 'MINDMAP'"
        :payload="graphStore.activeViewPayload"
        @open-evidence="graphStore.openEvidence"
      />
      <GraphViewer
        v-else-if="graphStore.viewType === 'GRAPH'"
        :payload="graphStore.activeViewPayload"
        @open-evidence="graphStore.openEvidence"
      />
      <GraphAdjacencyList
        v-else
        :payload="graphStore.activeViewPayload"
        @open-evidence="graphStore.openEvidence"
      />
    </section>

    <el-dialog
      v-model="isExplorerDialogOpen"
      class="graph-fullscreen-dialog"
      fullscreen
      destroy-on-close
      :title="`知识图谱 · ${currentViewLabel}`"
    >
      <section class="graph-fullscreen-dialog__body" aria-label="全屏图谱探索">
        <MindmapViewer
          v-if="graphStore.viewType === 'MINDMAP'"
          :payload="graphStore.activeViewPayload"
          fullscreen
          @open-evidence="graphStore.openEvidence"
        />
        <GraphViewer
          v-else-if="graphStore.viewType === 'GRAPH'"
          :payload="graphStore.activeViewPayload"
          fullscreen
          @open-evidence="graphStore.openEvidence"
        />
        <GraphAdjacencyList
          v-else
          :payload="graphStore.activeViewPayload"
          fullscreen
          @open-evidence="graphStore.openEvidence"
        />
      </section>
    </el-dialog>

    <GraphEvidenceDrawer
      v-model="graphStore.isEvidenceOpen"
      :target="graphStore.evidenceTarget"
      :evidence="graphStore.evidenceItems"
      :loading="graphStore.isLoadingEvidence"
      :error="graphStore.evidenceError"
    />
  </section>
</template>
