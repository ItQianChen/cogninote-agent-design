<script setup>
import { computed, ref } from 'vue'
import { FileText } from 'lucide-vue-next'
import { getDocumentChunk } from '../api/documents-api'
import { formatScore } from '../utils/formatters'

const props = defineProps({
  modelValue: {
    type: Boolean,
    required: true
  },
  target: {
    type: Object,
    default: null
  },
  evidence: {
    type: Array,
    default: () => []
  },
  loading: {
    type: Boolean,
    default: false
  },
  error: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['update:modelValue'])
const isDetailDialogOpen = ref(false)
const dialogEvidence = ref(null)
const dialogChunk = ref(null)
const isLoadingChunk = ref(false)
const chunkError = ref('')
let chunkRequestId = 0

const drawerTitle = computed(() => {
  if (!props.target) {
    return '图谱证据'
  }
  return `${props.target.label || '图谱证据'}`
})
const dialogContent = computed(() => dialogChunk.value?.content || dialogEvidence.value?.quote || '')

async function openChunk(evidence) {
  dialogEvidence.value = evidence
  dialogChunk.value = null
  chunkError.value = ''
  isDetailDialogOpen.value = true
  const requestId = ++chunkRequestId
  isLoadingChunk.value = true
  try {
    const chunk = await getDocumentChunk(evidence.chunkId)
    if (requestId === chunkRequestId) {
      dialogChunk.value = chunk
    }
  } catch (err) {
    if (requestId === chunkRequestId) {
      chunkError.value = `片段内容加载失败：${err.message}`
    }
  } finally {
    if (requestId === chunkRequestId) {
      isLoadingChunk.value = false
    }
  }
}

function closeDrawer(value) {
  emit('update:modelValue', value)
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    class="graph-evidence-drawer"
    size="420px"
    :title="drawerTitle"
    @update:model-value="closeDrawer"
  >
    <div class="graph-evidence-drawer__meta">
      <span v-if="target?.meta">{{ target.meta }}</span>
      <span>{{ evidence.length }} 条证据</span>
    </div>

    <p v-if="loading" class="panel-message">正在读取证据...</p>
    <el-alert
      v-else-if="error"
      class="settings-inline-alert"
      type="error"
      :title="error"
      :closable="false"
      show-icon
    />
    <p v-else-if="!evidence.length" class="panel-message">暂无证据。</p>

    <section v-else class="graph-evidence-list" aria-label="图谱证据列表">
      <button
        v-for="item in evidence"
        :key="item.id"
        type="button"
        class="graph-evidence-item"
        @click="openChunk(item)"
      >
        <strong>{{ item.quote }}</strong>
        <span>{{ item.fileName }}</span>
        <em>
          <span v-if="item.heading">{{ item.heading }}</span>
          <span v-if="item.pageNumber">页码 {{ item.pageNumber }}</span>
          <span>score {{ formatScore(item.confidence) }}</span>
        </em>
      </button>
    </section>
  </el-drawer>

  <el-dialog
    v-model="isDetailDialogOpen"
    class="source-detail-dialog"
    :title="dialogChunk?.fileName || dialogEvidence?.fileName || '证据片段'"
    width="760px"
    destroy-on-close
  >
    <article v-if="dialogEvidence" class="source-detail-dialog__content">
      <div class="source-inspector__detail-title">
        <FileText aria-hidden="true" />
        <div>
          <h3>{{ dialogChunk?.fileName || dialogEvidence.fileName }}</h3>
          <p class="path-text">{{ dialogChunk?.sourcePath || dialogEvidence.sourcePath }}</p>
        </div>
      </div>
      <section class="source-detail-dialog__chunk" aria-label="片段内容">
        <p v-if="isLoadingChunk" class="source-detail-dialog__state">正在加载完整片段...</p>
        <p v-else-if="chunkError" class="source-detail-dialog__state source-detail-dialog__state--error">
          {{ chunkError }}
        </p>
        <p v-else class="source-detail-dialog__chunk-content">{{ dialogContent }}</p>
      </section>
      <div class="document-meta">
        <span v-if="dialogEvidence.heading">标题：{{ dialogEvidence.heading }}</span>
        <span v-if="dialogEvidence.pageNumber">页码：{{ dialogEvidence.pageNumber }}</span>
        <span>chunk：{{ dialogEvidence.chunkId }}</span>
      </div>
    </article>
  </el-dialog>
</template>
