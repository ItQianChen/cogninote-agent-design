<script setup>
import { computed, ref, watch } from 'vue'
import { FileText, MessageSquareQuote, X } from 'lucide-vue-next'
import { getDocumentChunk } from '../api/documents-api'
import { useLayoutStore } from '../stores/layout'
import { formatScore } from '../utils/formatters'

const props = defineProps({
  messages: {
    type: Array,
    required: true
  }
})

const emit = defineEmits(['ask-source'])
const layoutStore = useLayoutStore()
const isDetailDialogOpen = ref(false)
const dialogSource = ref(null)
const dialogChunk = ref(null)
const isLoadingDialogChunk = ref(false)
const dialogChunkError = ref('')
let handledDetailRequestId = 0
let chunkRequestId = 0

const sourceMessages = computed(() =>
  props.messages.filter((message) => message.role === 'assistant' && message.sources?.length)
)
const activeMessage = computed(() => {
  return sourceMessages.value.find((message) => message.id === layoutStore.sourceInspectorMessageId)
    || sourceMessages.value[0]
    || null
})
const activeSources = computed(() => activeMessage.value?.sources || [])
const selectedSource = computed(() => {
  return activeSources.value.find((source) => isSameChunkId(source.chunkId, layoutStore.sourceInspectorChunkId)) || null
})
const dialogDetail = computed(() => {
  const source = dialogSource.value
  const chunk = dialogChunk.value
  if (!source && !chunk) {
    return null
  }

  return {
    fileName: chunk?.fileName || source?.fileName || '来源详情',
    sourcePath: chunk?.sourcePath || source?.sourcePath || '',
    heading: chunk?.heading || source?.heading || '',
    pageNumber: chunk?.pageNumber ?? source?.pageNumber,
    chunkId: chunk?.chunkId || source?.chunkId || '',
    score: source?.score
  }
})
const dialogChunkContent = computed(() => {
  return dialogChunk.value?.content || dialogSource.value?.preview || '暂无片段内容'
})

watch(
  () => layoutStore.isSourceInspectorOpen,
  (isOpen) => {
    if (!isOpen) {
      closeSourceDialog()
    }
  }
)

watch(
  () => [activeMessage.value?.id, activeSources.value.map((source) => source.chunkId).join('|')],
  (sources) => {
    if (!dialogSource.value) {
      return
    }
    const sourceStillVisible = activeSources.value.some((source) => source.chunkId === dialogSource.value.chunkId)
    if (!sourceStillVisible) {
      closeSourceDialog()
    }
  }
)

watch(
  () => [
    layoutStore.sourceDetailRequestId,
    layoutStore.sourceDetailChunkId,
    activeMessage.value?.id,
    activeSources.value.map((source) => source.chunkId).join('|')
  ],
  ([requestId, requestedChunkId]) => {
    if (!requestId || requestId === handledDetailRequestId || !requestedChunkId) {
      return
    }
    const requestedSource = activeSources.value.find((source) => isSameChunkId(source.chunkId, requestedChunkId))
    if (!requestedSource) {
      return
    }
    handledDetailRequestId = requestId
    openSourceDialog(requestedSource)
    layoutStore.clearSourceDetailRequest(requestId)
  },
  { immediate: true, flush: 'post' }
)

function isSameChunkId(left, right) {
  return String(left ?? '') === String(right ?? '')
}

function openMessageSources(message) {
  layoutStore.openSourceInspector(message.id, message.sources?.[0]?.chunkId || '')
}

function openSourceDialog(source) {
  layoutStore.selectInspectorSource(source.chunkId)
  dialogSource.value = source
  dialogChunk.value = null
  dialogChunkError.value = ''
  isDetailDialogOpen.value = true
  loadDialogChunk(source)
}

function closeSourceDialog() {
  isDetailDialogOpen.value = false
}

function clearSourceDialog() {
  chunkRequestId += 1
  dialogSource.value = null
  dialogChunk.value = null
  dialogChunkError.value = ''
  isLoadingDialogChunk.value = false
}

async function loadDialogChunk(source) {
  const chunkId = source?.chunkId
  if (!chunkId) {
    dialogChunkError.value = '缺少片段 ID，无法加载完整内容'
    return
  }

  // 弹窗允许快速切换来源，用递增请求号丢弃过期响应，避免旧片段覆盖新片段。
  const requestId = ++chunkRequestId
  isLoadingDialogChunk.value = true
  dialogChunkError.value = ''
  dialogChunk.value = null

  try {
    const chunk = await getDocumentChunk(chunkId)
    if (requestId !== chunkRequestId || !isSameChunkId(dialogSource.value?.chunkId, chunkId)) {
      return
    }
    dialogChunk.value = chunk
  } catch (error) {
    if (requestId !== chunkRequestId) {
      return
    }
    dialogChunkError.value = error?.message
      ? `片段内容加载失败：${error.message}`
      : '片段内容加载失败'
  } finally {
    if (requestId === chunkRequestId) {
      isLoadingDialogChunk.value = false
    }
  }
}

function askSource(source) {
  emit('ask-source', source)
  closeSourceDialog()
}
</script>

<template>
  <aside
    class="source-inspector"
    :class="{ 'source-inspector--open': layoutStore.isSourceInspectorOpen }"
    aria-label="来源证据面板"
    :aria-hidden="!layoutStore.isSourceInspectorOpen"
    :inert="layoutStore.isSourceInspectorOpen ? null : ''"
  >
    <header class="source-inspector__header">
      <div>
        <p class="eyebrow">证据</p>
        <h2>来源 Inspector</h2>
      </div>
      <button
        class="icon-button"
        type="button"
        title="关闭来源面板"
        aria-label="关闭来源面板"
        @click="layoutStore.closeSourceInspector"
      >
        <X aria-hidden="true" />
      </button>
    </header>

    <p v-if="!sourceMessages.length" class="panel-message">
      带知识库回答后，这里会显示对应文档片段和证据路径。
    </p>

    <template v-else>
      <section class="source-inspector__messages" aria-label="含来源回答">
        <button
          v-for="message in sourceMessages"
          :key="message.id"
          type="button"
          :class="{ active: activeMessage?.id === message.id }"
          @click="openMessageSources(message)"
        >
          <MessageSquareQuote aria-hidden="true" />
          <span>{{ message.sources.length }} 个来源</span>
        </button>
      </section>

      <div class="source-inspector__source-meta" aria-hidden="true">
        <span>来源片段</span>
        <span>点击条目预览</span>
      </div>

      <section class="source-inspector__sources" aria-label="来源片段列表">
        <button
          v-for="source in activeSources"
          :key="source.chunkId"
          type="button"
          :class="{ active: selectedSource?.chunkId === source.chunkId }"
          :aria-label="`打开 ${source.fileName} 来源预览，匹配度 ${formatScore(source.score)}`"
          @click="openSourceDialog(source)"
        >
          <span class="source-inspector__index">[{{ source.index }}]</span>
          <span>
            <strong>{{ source.fileName }}</strong>
            <em>{{ formatScore(source.score) }}</em>
          </span>
        </button>
      </section>
    </template>
  </aside>

  <el-dialog
    v-model="isDetailDialogOpen"
    class="source-detail-dialog"
    :title="dialogDetail?.fileName || '来源详情'"
    width="760px"
    destroy-on-close
    @closed="clearSourceDialog"
  >
    <article v-if="dialogSource && dialogDetail" class="source-detail-dialog__content">
      <div class="source-inspector__detail-title">
        <FileText aria-hidden="true" />
        <div>
          <h3>{{ dialogDetail.fileName }}</h3>
          <p class="path-text">{{ dialogDetail.sourcePath }}</p>
        </div>
      </div>

      <section class="source-detail-dialog__chunk" aria-label="片段内容">
        <p v-if="isLoadingDialogChunk" class="source-detail-dialog__state" role="status">
          正在加载完整片段...
        </p>
        <template v-else>
          <p v-if="dialogChunkError" class="source-detail-dialog__state source-detail-dialog__state--error" role="alert">
            {{ dialogChunkError }}
          </p>
          <p
            class="source-detail-dialog__chunk-content"
            :class="{ 'source-detail-dialog__chunk-content--fallback': dialogChunkError }"
          >
            {{ dialogChunkContent }}
          </p>
        </template>
      </section>

      <div class="document-meta">
        <span v-if="dialogDetail.heading">标题：{{ dialogDetail.heading }}</span>
        <span v-if="dialogDetail.pageNumber">页码：{{ dialogDetail.pageNumber }}</span>
        <span>chunk：{{ dialogDetail.chunkId }}</span>
        <span v-if="dialogDetail.score !== undefined && dialogDetail.score !== null">
          score：{{ formatScore(dialogDetail.score) }}
        </span>
      </div>
    </article>

    <template #footer>
      <button
        v-if="dialogSource"
        class="context-secondary-action source-detail-dialog__ask"
        type="button"
        @click="askSource(dialogSource)"
      >
        追问这段来源
      </button>
    </template>
  </el-dialog>
</template>
