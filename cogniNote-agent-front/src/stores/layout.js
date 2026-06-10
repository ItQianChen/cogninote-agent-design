import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export const useLayoutStore = defineStore('layout', () => {
  const isContextSidebarCollapsed = ref(false)
  const isSourceInspectorOpen = ref(false)
  const sourceInspectorMessageId = ref('')
  const sourceInspectorChunkId = ref('')
  const sourceDetailRequestId = ref(0)
  const sourceDetailChunkId = ref('')

  const contextSidebarToggleTitle = computed(() =>
    isContextSidebarCollapsed.value ? '展开上下文侧栏' : '隐藏上下文侧栏'
  )
  // 旧聊天页仍消费 sidebar 命名；保留别名，让工作台外壳替换不破坏现有调用方。
  const sidebarToggleTitle = contextSidebarToggleTitle
  const isSidebarCollapsed = isContextSidebarCollapsed

  function toggleContextSidebar() {
    isContextSidebarCollapsed.value = !isContextSidebarCollapsed.value
  }

  function setContextSidebarCollapsed(collapsed) {
    isContextSidebarCollapsed.value = Boolean(collapsed)
  }

  function toggleSidebar() {
    toggleContextSidebar()
  }

  function normalizeChunkId(chunkId) {
    return chunkId === null || chunkId === undefined ? '' : String(chunkId)
  }

  function openSourceInspector(messageId, chunkId = '', options = {}) {
    // Inspector 只保存消息和 chunk 的定位信息，来源详情继续由聊天消息快照派生，避免复制证据数据。
    const nextChunkId = normalizeChunkId(chunkId)
    sourceInspectorMessageId.value = messageId || ''
    sourceInspectorChunkId.value = nextChunkId
    isSourceInspectorOpen.value = Boolean(messageId)
    if (messageId && nextChunkId && options.openDetail) {
      sourceDetailChunkId.value = nextChunkId
      sourceDetailRequestId.value += 1
    } else {
      sourceDetailChunkId.value = ''
    }
  }

  function selectInspectorSource(chunkId) {
    sourceInspectorChunkId.value = normalizeChunkId(chunkId)
  }

  function clearSourceDetailRequest(requestId) {
    if (!requestId || requestId === sourceDetailRequestId.value) {
      sourceDetailChunkId.value = ''
    }
  }

  function closeSourceInspector() {
    isSourceInspectorOpen.value = false
    sourceInspectorMessageId.value = ''
    sourceInspectorChunkId.value = ''
    sourceDetailChunkId.value = ''
  }

  return {
    isContextSidebarCollapsed,
    isSidebarCollapsed,
    isSourceInspectorOpen,
    sourceInspectorMessageId,
    sourceInspectorChunkId,
    sourceDetailRequestId,
    sourceDetailChunkId,
    contextSidebarToggleTitle,
    sidebarToggleTitle,
    toggleContextSidebar,
    toggleSidebar,
    setContextSidebarCollapsed,
    openSourceInspector,
    selectInspectorSource,
    clearSourceDetailRequest,
    closeSourceInspector
  }
})
