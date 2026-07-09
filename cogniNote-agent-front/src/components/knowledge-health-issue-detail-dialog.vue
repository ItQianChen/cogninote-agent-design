<script setup>
import { computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { BrainCircuit, Database, GitBranch, RotateCcw, ScanText, XCircle } from 'lucide-vue-next'
import { confirmRebuildAllIndex } from '../composables/use-knowledge-maintenance-confirm'
import { useKnowledgeHealthIssueIgnore } from '../composables/use-knowledge-health-issue-ignore'
import { useKnowledgeGraphStore } from '../stores/knowledge-graph'
import { useKnowledgeMaintenanceStore } from '../stores/knowledge-maintenance'
import { useOcrSettingsStore } from '../stores/ocr-settings'
import { useSearchStore } from '../stores/search'
import {
  canRebuildGraphExample,
  graphScopeConfirmText,
  issueExamples,
  issueExplanation,
  issueMetaText
} from '../utils/knowledge-health-issues'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  section: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['update:modelValue'])

const graphStore = useKnowledgeGraphStore()
const maintenanceStore = useKnowledgeMaintenanceStore()
const ocrSettingsStore = useOcrSettingsStore()
const searchStore = useSearchStore()
const {
  isIssueIgnored,
  ignoreIssue,
  restoreIssue
} = useKnowledgeHealthIssueIgnore()

const isOpen = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})
const issues = computed(() => props.section?.issues || [])
const activeIssues = computed(() => issues.value.filter((issue) => !isIssueIgnored(issue)))
const ignoredIssues = computed(() => issues.value.filter((issue) => isIssueIgnored(issue)))
const affectedCount = computed(() => activeIssues.value.reduce((total, issue) => total + (issue.count || 1), 0))
const dialogTitle = computed(() => props.section?.title ? `${props.section.title} · 诊断详情` : '诊断详情')

onMounted(() => {
  ocrSettingsStore.fetchSettings()
})

async function rebuildGlobalIndex() {
  if (!await confirmRebuildAllIndex()) {
    return
  }
  await searchStore.rebuildIndex()
}

async function rebuildGraphExample(example) {
  if (!canRebuildGraphExample(example)) {
    return
  }
  try {
    await ElMessageBox.confirm(
      graphScopeConfirmText(example),
      '重建图谱',
      {
        confirmButtonText: '开始重建',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch (err) {
    if (err === 'cancel' || err === 'close') {
      return
    }
    throw err
  }
  graphStore.setScopeForGeneration(example.scopeType, example.scopeId || '')
  await graphStore.rebuild()
}

async function reparseIssueFolder(issue) {
  if (!issue?.scopeId || maintenanceStore.hasActiveRun) {
    return
  }
  await maintenanceStore.reparseFolder(issue.scopeId)
  ElMessage.success('重新解析任务已加入维护队列')
}

function handleIgnoreIssue(issue) {
  ignoreIssue(issue)
  ElMessage.success('已忽略该诊断提示')
}

function handleRestoreIssue(issue) {
  restoreIssue(issue)
  ElMessage.success('已恢复该诊断提示')
}

function issueActionIcon(issue) {
  if (issue?.code === 'INDEX_INCONSISTENT') {
    return Database
  }
  if (issue?.code === 'EMBEDDING_UNCONFIGURED') {
    return BrainCircuit
  }
  if (issue?.code === 'GRAPH_STALE') {
    return GitBranch
  }
  if (issue?.code === 'PDF_OCR_REQUIRED') {
    return ScanText
  }
  return XCircle
}

function exampleDocumentItems(example) {
  return (example?.items || []).map((item, index) => {
    const text = String(item || '').trim()
    const delimiterIndex = text.indexOf(' · ')
    // 后端为兼容旧响应仍返回字符串；这里只做展示拆分，避免把文件名和长路径挤成一段。
    if (delimiterIndex > 0) {
      return {
        key: `${index}-${text}`,
        name: text.slice(0, delimiterIndex),
        path: text.slice(delimiterIndex + 3)
      }
    }
    return {
      key: `${index}-${text}`,
      name: text,
      path: ''
    }
  })
}
</script>

<template>
  <el-dialog
    v-model="isOpen"
    class="knowledge-health-issue-detail-dialog"
    :title="dialogTitle"
    width="min(860px, calc(100vw - 32px))"
    align-center
  >
    <section v-if="section" class="knowledge-health-issue-detail">
      <header class="knowledge-health-issue-detail__summary">
        <div>
          <p class="eyebrow">{{ section.subtitle }}</p>
          <h4>{{ section.title }}</h4>
          <span>{{ affectedCount }} 个当前影响项<span v-if="ignoredIssues.length"> · {{ ignoredIssues.length }} 项已忽略</span></span>
        </div>
        <RouterLink
          v-if="section.key === 'graph'"
          class="knowledge-header-link"
          :to="{ name: 'knowledge', query: { panel: 'graph' } }"
          @click="isOpen = false"
        >
          <GitBranch aria-hidden="true" />
          <span>查看知识图谱</span>
        </RouterLink>
      </header>

      <p v-if="!issues.length" class="panel-message">当前没有这类诊断问题。</p>

      <section v-if="section.rules?.length" class="knowledge-health-issue-detail__rules" aria-label="判断规则">
        <strong>判断规则</strong>
        <ul>
          <li v-for="rule in section.rules" :key="rule">{{ rule }}</li>
        </ul>
      </section>

      <article
        v-for="issue in issues"
        :key="`${issue.code}-${issue.count}`"
        :class="['knowledge-health-issue-detail__issue', { 'is-ignored': isIssueIgnored(issue) }]"
      >
        <header>
          <component :is="issueActionIcon(issue)" aria-hidden="true" />
          <div>
            <strong>{{ issue.message }}</strong>
            <span>{{ issueMetaText(issue) }}</span>
          </div>
        </header>

        <p v-if="issueExplanation(issue)" class="knowledge-health-issue-detail__explain">
          {{ issueExplanation(issue) }}
        </p>

        <ul v-if="issueExamples(issue).length" class="knowledge-health-issue-detail__examples">
          <li v-for="example in issueExamples(issue)" :key="example.key">
            <div>
              <strong>{{ example.label }}</strong>
              <p v-if="example.description">{{ example.description }}</p>
              <ol v-if="exampleDocumentItems(example).length" class="knowledge-health-issue-detail__document-list">
                <li v-for="item in exampleDocumentItems(example)" :key="item.key">
                  <span>{{ item.name }}</span>
                  <em v-if="item.path">{{ item.path }}</em>
                </li>
              </ol>
            </div>
            <el-button
              v-if="canRebuildGraphExample(example)"
              :loading="graphStore.isRebuilding"
              :disabled="graphStore.isRunActive"
              @click="rebuildGraphExample(example)"
            >
              <GitBranch aria-hidden="true" />
              <span>重建该图谱</span>
            </el-button>
          </li>
        </ul>

        <footer>
          <el-button
            v-if="issue.code === 'INDEX_INCONSISTENT'"
            :loading="searchStore.isRebuildingIndex"
            :disabled="maintenanceStore.hasActiveRun"
            @click="rebuildGlobalIndex"
          >
            <RotateCcw aria-hidden="true" />
            <span>重建索引</span>
          </el-button>
          <RouterLink
            v-else-if="issue.code === 'EMBEDDING_UNCONFIGURED'"
            class="knowledge-header-link"
            :to="{ name: 'settings', query: { item: 'model-embedding' } }"
            @click="isOpen = false"
          >
            <BrainCircuit aria-hidden="true" />
            <span>配置向量模型</span>
          </RouterLink>
          <el-button
            v-else-if="issue.code === 'PDF_OCR_REQUIRED' && ocrSettingsStore.available && issue.scopeId"
            :loading="maintenanceStore.isEnqueueing"
            :disabled="maintenanceStore.hasActiveRun"
            @click="reparseIssueFolder(issue)"
          >
            <ScanText aria-hidden="true" />
            <span>重新解析目录</span>
          </el-button>
          <RouterLink
            v-else-if="issue.code === 'PDF_OCR_REQUIRED' && !ocrSettingsStore.available"
            class="knowledge-header-link"
            :to="{ name: 'settings', query: { item: 'ocr' } }"
            @click="isOpen = false"
          >
            <ScanText aria-hidden="true" />
            <span>配置视觉模型</span>
          </RouterLink>
          <RouterLink
            v-else-if="issue.code === 'PDF_OCR_REQUIRED'"
            class="knowledge-header-link"
            :to="{ name: 'knowledge', query: { panel: 'directories' } }"
            @click="isOpen = false"
          >
            <ScanText aria-hidden="true" />
            <span>查看目录管理</span>
          </RouterLink>
          <el-button v-if="isIssueIgnored(issue)" @click="handleRestoreIssue(issue)">
            恢复提示
          </el-button>
          <el-button v-else text @click="handleIgnoreIssue(issue)">
            忽略该提示
          </el-button>
        </footer>
      </article>
    </section>
  </el-dialog>
</template>
