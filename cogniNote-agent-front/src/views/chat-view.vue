<script setup>
// 聊天页保留滚动、输入框尺寸和来源抽屉这类 UI 状态；流式消息和会话持久化由 chat store 管理。
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ArrowDown,
  ChevronLeft,
  ChevronRight,
  LoaderCircle,
  MessageSquareQuote,
  Send,
  SlidersHorizontal,
  Trash2,
  X
} from 'lucide-vue-next'
import ChatSettingsPopover from '../components/chat-settings-popover.vue'
import SourceInspector from '../components/source-inspector.vue'
import { APP_DISPLAY_NAME } from '../config/brand'
import { useChatStore } from '../stores/chat'
import { useChatSettingsStore } from '../stores/chat-settings'
import { useLayoutStore } from '../stores/layout'
import { useModelConfigStore } from '../stores/model-config'
import { SEARCH_MODES } from '../stores/search'
import { useWebSearchSettingsStore } from '../stores/web-search-settings'

const chatStore = useChatStore()
const chatSettingsStore = useChatSettingsStore()
const layoutStore = useLayoutStore()
const modelConfigStore = useModelConfigStore()
const webSearchSettingsStore = useWebSearchSettingsStore()
const AiMarkdownRenderer = defineAsyncComponent(() => import('../components/ai-markdown-renderer.vue'))
const isComposerSettingsOpen = ref(false)
const composerSettingsButtonRef = ref(null)
const composerSettingsPopoverRef = ref(null)
const messageStreamShellRef = ref(null)
const messageStreamRef = ref(null)
const isRestoringScroll = ref(false)
const shouldFollowBottom = ref(true)
const showScrollToBottomButton = ref(false)
const activeTimelineMessageId = ref('')
const hoveredTimelineIndex = ref(-1)
const isTimelineVisible = ref(false)
const BOTTOM_THRESHOLD_PX = 80
const ANCHOR_VIEWPORT_RATIO = 0.35
const DEFAULT_CONTEXT_WINDOW_TOKENS = 128000
const COMPOSER_MIN_HEIGHT = 76
const COMPOSER_MAX_HEIGHT = 220
const TIMELINE_USER_PREVIEW_LENGTH = 52
const TIMELINE_ASSISTANT_PREVIEW_LENGTH = 86
const TIMELINE_MIN_MESSAGE_AREA_WIDTH_PX = 1032
let restoreRunId = 0
let timelineVisibilityObserver = null
let composerResizeState = null
const composerTextareaHeight = ref(COMPOSER_MIN_HEIGHT)
const composerActionTitle = computed(() => (chatStore.isStreaming ? '停止对话' : '发送信息'))
const selectionReferenceAction = ref(null)
const totalSourceCount = computed(() =>
  chatStore.activeMessages.reduce((total, message) => total + (message.sources?.length || 0), 0)
)
const firstSourceMessageId = computed(() =>
  chatStore.activeMessages.find((message) => message.sources?.length)?.id || ''
)
const activeModelSummary = computed(() => {
  const chat = modelConfigStore.activeChatConfig?.apiKeyConfigured
    ? modelConfigStore.activeChatConfig?.modelName
    : ''
  const embedding = modelConfigStore.activeEmbeddingConfig?.apiKeyConfigured
    ? modelConfigStore.activeEmbeddingConfig?.modelName
    : ''
  return [chat, embedding].filter(Boolean).join(' / ')
})
const activeRetrievalModeLabel = computed(() => formatRetrievalModeLabel(chatStore.mode))
const conversationStyle = computed(() => ({
  '--assistant-message-width': `${chatSettingsStore.assistantMessageWidth}%`,
  '--user-message-width': `${chatSettingsStore.userMessageWidth}%`,
  '--composer-width': `${chatSettingsStore.composerWidth}%`
}))

// Header 只展示已经真实可用的运行状态；未配置模型的引导留给输入区提示。
const conversationMetaItems = computed(() => {
  const items = [
    {
      id: 'knowledge',
      label: chatStore.useKnowledgeBase ? '知识库已启用' : '纯模型对话',
      state: chatStore.useKnowledgeBase ? 'success' : 'neutral'
    },
    {
      id: 'mode',
      label: activeRetrievalModeLabel.value,
      state: 'info'
    }
  ]

  if (chatStore.useWebSearch) {
    items.push({
      id: 'web-search',
      label: '联网搜索',
      state: webSearchSettingsStore.available ? 'success' : 'neutral'
    })
  }

  if (activeModelSummary.value) {
    items.push({
      id: 'model',
      label: activeModelSummary.value,
      state: 'neutral',
      className: 'conversation-meta__model'
    })
  }

  return items
})
const activeContextUsage = computed(() => {
  const usage = chatStore.activeContextUsage
  const contextWindowTokens = normalizeTokenCount(
    usage?.contextWindowTokens || modelConfigStore.activeChatConfig?.contextWindowTokens || DEFAULT_CONTEXT_WINDOW_TOKENS
  )
  const usedTokens = normalizeTokenCount(usage?.usedTokens)
  const usageRatio = normalizeRatio(
    usage?.usageRatio,
    contextWindowTokens > 0 ? usedTokens / contextWindowTokens : 0
  )
  return {
    ...usage,
    contextWindowTokens,
    usedTokens,
    usageRatio,
    compressed: Boolean(usage?.compressed)
  }
})
const contextUsageLabel = computed(() => {
  return `上下文 ${formatTokenCount(activeContextUsage.value.usedTokens)} / ${formatTokenCount(activeContextUsage.value.contextWindowTokens)}`
})
const contextUsagePercentLabel = computed(() => `${Math.round(activeContextUsage.value.usageRatio * 100)}%`)
const contextUsageTitle = computed(() => {
  const usage = activeContextUsage.value
  const method = usage.estimationMethod || 'local'
  const recentCount = normalizeTokenCount(usage.recentMessageCount)
  const totalCount = normalizeTokenCount(usage.totalMessageCount)
  return `估算：${method}；最近原文 ${recentCount}/${totalCount}`
})
const isContextUsageWarning = computed(() => activeContextUsage.value.usageRatio >= 0.8)
const isContextCompressed = computed(() => activeContextUsage.value.compressed)
const contextUsageDetails = computed(() => {
  const compressed = isContextCompressed.value ? '已压缩' : '未压缩'
  return `${contextUsageLabel.value} · ${contextUsagePercentLabel.value}；${compressed}；${contextUsageTitle.value}`
})
const contextUsageStyle = computed(() => ({
  '--context-progress': `${Math.round(activeContextUsage.value.usageRatio * 100)}%`
}))
const composerTextareaStyle = computed(() => ({
  height: `${composerTextareaHeight.value}px`
}))
const timelineTurns = computed(() => buildTimelineTurns(chatStore.activeMessages))

function referenceCountLabel(references) {
  const count = Array.isArray(references) ? references.length : 0
  return `${count} 个已选文本片段`
}

function compactReferenceSnippet(snippet) {
  const text = String(snippet || '').replace(/\s+/g, ' ').trim()
  return text.length <= 160 ? text : `${text.slice(0, 160)}...`
}

function buildTimelineTurns(messages) {
  const turns = []
  for (let index = 0; index < messages.length; index += 1) {
    const user = messages[index]
    if (user.role !== 'user') {
      continue
    }

    let assistant = null
    for (let nextIndex = index + 1; nextIndex < messages.length; nextIndex += 1) {
      const nextMessage = messages[nextIndex]
      if (nextMessage.role === 'user') {
        break
      }
      if (nextMessage.role === 'assistant') {
        assistant = nextMessage
        break
      }
    }

    const userPreview = compactTimelinePreview(user.content, TIMELINE_USER_PREVIEW_LENGTH)
    const assistantPreview = timelineAssistantPreview(assistant)
    turns.push({
      user,
      assistant,
      userPreview,
      assistantPreview,
      ariaLabel: `跳转到提问：${userPreview}；模型回答：${assistantPreview}`
    })
  }
  return turns
}

function timelineAssistantPreview(assistant) {
  if (!assistant) {
    return '等待模型回复...'
  }
  if (assistant.status === 'streaming') {
    return assistant.content
      ? `${compactTimelinePreview(assistant.content, TIMELINE_ASSISTANT_PREVIEW_LENGTH)}（生成中）`
      : '生成中...'
  }
  if (assistant.status === 'error') {
    return '模型回答失败'
  }
  if (assistant.status === 'stopped') {
    return '回答已停止'
  }
  return compactTimelinePreview(assistant.content, TIMELINE_ASSISTANT_PREVIEW_LENGTH)
}

function compactTimelinePreview(content, maxLength) {
  const text = String(content || '')
    .replace(/```[\s\S]*?```/g, '代码片段')
    .replace(/!\[.*?\]\(.*?\)/g, '')
    .replace(/\[(.*?)\]\(.*?\)/g, '$1')
    .replace(/[#>*_`~]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  if (!text) {
    return '暂无内容'
  }
  return text.length <= maxLength ? text : `${text.slice(0, maxLength)}...`
}

function timelineMarkerStyle(turn, index) {
  const groupOffset = (index - (timelineTurns.value.length - 1) / 2) * 12
  return { top: `calc(50% + ${groupOffset}px)` }
}

function timelineLineStyle(index) {
  const distance = hoveredTimelineIndex.value < 0
    ? Number.POSITIVE_INFINITY
    : Math.abs(index - hoveredTimelineIndex.value)
  const width = distance === 0
    ? 30
    : distance === 1
      ? 24
      : distance === 2
        ? 18
        : distance === 3
          ? 13
          : 8
  return { width: `${width}px` }
}

function handleTimelineMarkerEnter(index) {
  hoveredTimelineIndex.value = index
}

function handleTimelineMarkerLeave() {
  hoveredTimelineIndex.value = -1
}

function updateTimelineVisibility() {
  const streamShell = messageStreamShellRef.value
  if (!streamShell) {
    isTimelineVisible.value = false
    return
  }
  isTimelineVisible.value = streamShell.clientWidth >= TIMELINE_MIN_MESSAGE_AREA_WIDTH_PX
}

/**
 * 发送按钮在流式输出期间转为停止按钮。
 *
 * <p>真正的取消需要 chat store 同时通知后端和关闭浏览器 SSE 连接。</p>
 */
function handleComposerAction() {
  if (chatStore.isStreaming) {
    chatStore.stopChat()
  }
}

function startComposerResize(event) {
  if (event.button != null && event.button !== 0) {
    return
  }
  event.preventDefault()
  composerResizeState = {
    startY: event.clientY,
    startHeight: composerTextareaHeight.value
  }
  window.addEventListener('pointermove', handleComposerResize)
  window.addEventListener('pointerup', stopComposerResize)
  window.addEventListener('pointercancel', stopComposerResize)
}

/**
 * 根据 pointer 位移调整输入框高度。
 *
 * <p>高度限制在固定区间内，避免拖动时挤压消息流或让按钮区域跳动。</p>
 */
function handleComposerResize(event) {
  if (!composerResizeState) {
    return
  }
  const nextHeight = composerResizeState.startHeight + composerResizeState.startY - event.clientY
  composerTextareaHeight.value = Math.min(
    COMPOSER_MAX_HEIGHT,
    Math.max(COMPOSER_MIN_HEIGHT, nextHeight)
  )
}

function stopComposerResize() {
  if (!composerResizeState) {
    return
  }
  composerResizeState = null
  window.removeEventListener('pointermove', handleComposerResize)
  window.removeEventListener('pointerup', stopComposerResize)
  window.removeEventListener('pointercancel', stopComposerResize)
}

function resetComposerHeight() {
  composerTextareaHeight.value = COMPOSER_MIN_HEIGHT
}

/**
 * 提供键盘可访问的输入框高度调整。
 *
 * <p>Shift 使用更大步长，Home/End 对应最小和最大高度，和拖拽逻辑共用同一边界。</p>
 */
function handleComposerResizeKeydown(event) {
  const step = event.shiftKey ? 24 : 8
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    composerTextareaHeight.value = Math.min(COMPOSER_MAX_HEIGHT, composerTextareaHeight.value + step)
    return
  }
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    composerTextareaHeight.value = Math.max(COMPOSER_MIN_HEIGHT, composerTextareaHeight.value - step)
    return
  }
  if (event.key === 'Home') {
    event.preventDefault()
    composerTextareaHeight.value = COMPOSER_MIN_HEIGHT
    return
  }
  if (event.key === 'End') {
    event.preventDefault()
    composerTextareaHeight.value = COMPOSER_MAX_HEIGHT
  }
}

function normalizeTokenCount(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.max(0, Math.trunc(parsed)) : 0
}

function normalizeRatio(value, fallback = 0) {
  const parsed = Number(value)
  const ratio = Number.isFinite(parsed) ? parsed : fallback
  return Math.min(1, Math.max(0, ratio))
}

function formatTokenCount(value) {
  const normalized = normalizeTokenCount(value)
  if (normalized >= 1000000) {
    return `${formatCompactNumber(normalized / 1000000)}M`
  }
  if (normalized >= 1000) {
    return `${formatCompactNumber(normalized / 1000)}K`
  }
  return String(normalized)
}

function formatCompactNumber(value) {
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, '')
}

function formatRetrievalModeLabel(mode) {
  return SEARCH_MODES.find((item) => item.value === mode)?.label || mode || ''
}

function sourceDisplayName(source) {
  return source?.sourceType === 'WEB'
    ? source.title || source.fileName || source.url || '网页来源'
    : source?.fileName || '文档来源'
}

/**
 * Enter 直接发送，Shift+Enter 保留换行。
 *
 * <p>中文输入法合成期间不拦截 Enter，避免用户选词时误发送。</p>
 */
function handleDraftKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) {
    return
  }
  event.preventDefault()
  if (chatStore.canSend) {
    chatStore.streamChat()
  }
}

function clearMessages() {
  if (!chatStore.hasMessages || chatStore.isStreaming) {
    return
  }
  const confirmed = window.confirm('清空当前会话的全部消息？')
  if (confirmed) {
    chatStore.clearActiveMessages()
  }
}

function openMessageSources(message, source) {
  const chunkId = source?.chunkId || message.sources?.[0]?.chunkId || ''
  layoutStore.openSourceInspector(message.id, chunkId, { openDetail: Boolean(source?.chunkId) })
}

function handlePageKeydown(event) {
  if (event.key !== 'Escape') {
    return
  }
  if (isComposerSettingsOpen.value) {
    isComposerSettingsOpen.value = false
    return
  }
  if (selectionReferenceAction.value) {
    window.getSelection()?.removeAllRanges()
    hideSelectionReferenceAction()
    return
  }
  if (layoutStore.isSourceInspectorOpen) {
    layoutStore.closeSourceInspector()
  }
}

function handleUseWebSearchChange(value) {
  if (value && !webSearchSettingsStore.available) {
    webSearchSettingsStore.fetchSettings({ force: true })
    return
  }
  chatStore.setUseWebSearch(value)
}

function handleComposerSettingsPointerDown(event) {
  if (!isComposerSettingsOpen.value) {
    return
  }
  const target = event.target
  if (
    isEventTargetInside(composerSettingsButtonRef.value, target)
    || isEventTargetInside(composerSettingsPopoverRef.value, target)
  ) {
    return
  }
  isComposerSettingsOpen.value = false
}

function isEventTargetInside(element, target) {
  const node = element?.$el || element
  return Boolean(node && target && typeof node.contains === 'function' && node.contains(target))
}

function handleDocumentSelectionChange() {
  window.setTimeout(updateSelectionReferenceAction, 0)
}

function updateSelectionReferenceAction() {
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0 || selection.isCollapsed) {
    hideSelectionReferenceAction()
    return
  }
  const range = selection.getRangeAt(0)
  const snippet = selection.toString().replace(/\s+/g, ' ').trim()
  if (!snippet) {
    hideSelectionReferenceAction()
    return
  }
  const startBubble = closestMessageBubble(range.startContainer)
  const endBubble = closestMessageBubble(range.endContainer)
  if (!startBubble || !endBubble || startBubble !== endBubble) {
    hideSelectionReferenceAction()
    return
  }
  const message = chatStore.activeMessages.find((item) => item.id === startBubble.dataset.messageId)
  if (!message || message.role !== 'assistant' || message.status === 'streaming') {
    hideSelectionReferenceAction()
    return
  }
  const messageContent = startBubble.querySelector('.message-content')
  if (!messageContent?.contains(range.startContainer) || !messageContent.contains(range.endContainer)) {
    hideSelectionReferenceAction()
    return
  }
  const rect = firstVisibleRangeRect(range)
  if (!rect) {
    hideSelectionReferenceAction()
    return
  }
  selectionReferenceAction.value = {
    messageId: message.id,
    snippet,
    style: {
      top: `${Math.max(10, rect.top - 46)}px`,
      left: `${Math.max(10, Math.min(Math.max(10, window.innerWidth - 172), rect.left))}px`
    }
  }
}

function closestMessageBubble(node) {
  const element = node?.nodeType === Node.ELEMENT_NODE ? node : node?.parentElement
  return element?.closest?.('[data-message-id]') || null
}

function firstVisibleRangeRect(range) {
  const rects = Array.from(range.getClientRects()).filter((rect) => rect.width > 0 && rect.height > 0)
  return rects[0] || null
}

function hideSelectionReferenceAction() {
  selectionReferenceAction.value = null
}

function addSelectionReference() {
  const action = selectionReferenceAction.value
  if (!action) {
    return
  }
  chatStore.addPendingReference({
    messageId: action.messageId,
    snippet: action.snippet
  })
  window.getSelection()?.removeAllRanges()
  hideSelectionReferenceAction()
}

/**
 * 维护聊天滚动锚点。
 * <p>长对话切换、流式输出和 DOM 高度变化时都依赖该逻辑恢复阅读位置。</p>
 */
async function scrollMessagesToBottom() {
  await nextTick()
  applyMessageScrollBottom(true)
  window.requestAnimationFrame(() => {
    applyMessageScrollBottom(true)
    window.setTimeout(() => applyMessageScrollBottom(true), 80)
  })
}

function applyMessageScrollBottom(saveAfterScroll = false) {
  const stream = messageStreamRef.value
  if (stream) {
    stream.scrollTop = Math.max(0, stream.scrollHeight - stream.clientHeight)
    shouldFollowBottom.value = true
    showScrollToBottomButton.value = false
    updateActiveTimelineMessage(stream)
    if (saveAfterScroll) {
      saveCurrentSessionScrollPosition()
    }
  }
}

function distanceFromBottom(stream) {
  return Math.max(0, stream.scrollHeight - stream.scrollTop - stream.clientHeight)
}

function isNearBottom(stream) {
  return distanceFromBottom(stream) <= BOTTOM_THRESHOLD_PX
}

function updateScrollToBottomButton(stream = messageStreamRef.value) {
  showScrollToBottomButton.value = Boolean(
    chatStore.hasMessages
      && stream
      && distanceFromBottom(stream) > BOTTOM_THRESHOLD_PX
  )
}

function handleScrollToBottomClick() {
  scrollMessagesToBottom()
}

function jumpToMessage(messageId) {
  const stream = messageStreamRef.value
  if (!stream || !messageId) {
    return
  }
  const target = stream.querySelector(`[data-message-id="${CSS.escape(messageId)}"]`)
  if (!target) {
    return
  }

  const targetRect = target.getBoundingClientRect()
  const targetTop = getMessageScrollTargetTop(stream, targetRect)
  const behavior = window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'
  activeTimelineMessageId.value = messageId
  stream.scrollTo({ top: targetTop, behavior })
}

function getMessageScrollTargetTop(stream, targetRect) {
  const streamRect = stream.getBoundingClientRect()
  return Math.min(
    Math.max(0, stream.scrollHeight - stream.clientHeight),
    Math.max(0, stream.scrollTop + targetRect.top - streamRect.top
      - (stream.clientHeight - targetRect.height) / 2)
  )
}

function saveCurrentSessionScrollPosition(sessionId = chatStore.activeSessionId) {
  const stream = messageStreamRef.value
  if (!stream || !sessionId) {
    return
  }
  const anchor = findScrollAnchor(stream)
  chatStore.saveSessionScrollPosition(sessionId, {
    scrollTop: stream.scrollTop,
    scrollHeight: stream.scrollHeight,
    clientHeight: stream.clientHeight,
    distanceFromBottom: distanceFromBottom(stream),
    anchorMessageId: anchor?.messageId || '',
    anchorMessageIndex: anchor?.messageIndex || 0,
    anchorOffsetTop: anchor?.offsetTop || 0,
    anchorProgress: anchor?.progress || 0,
    anchorViewportRatio: anchor?.viewportRatio || ANCHOR_VIEWPORT_RATIO
  })
}

/**
 * 维护聊天滚动锚点。
 * <p>长对话切换、流式输出和 DOM 高度变化时都依赖该逻辑恢复阅读位置。</p>
 */
function findScrollAnchor(stream) {
  const streamRect = stream.getBoundingClientRect()
  const viewportRatio = ANCHOR_VIEWPORT_RATIO
  const readingLineTop = streamRect.top + stream.clientHeight * viewportRatio
  // DOM 查询使用转义后的 id，避免特殊字符破坏选择器。
  const messages = Array.from(stream.querySelectorAll('[data-message-id]'))
  let lastVisible = null
  for (let index = 0; index < messages.length; index += 1) {
    const message = messages[index]
    const rect = message.getBoundingClientRect()
    if (rect.bottom < streamRect.top) {
      continue
    }
    if (rect.top > streamRect.bottom) {
      break
    }

    const progress = rect.height > 0
      ? Math.min(1, Math.max(0, (readingLineTop - rect.top) / rect.height))
      : 0
    const candidate = {
      messageId: message.dataset.messageId,
      messageIndex: index,
      offsetTop: rect.top - streamRect.top,
      progress,
      viewportRatio
    }

    if (rect.top <= readingLineTop && rect.bottom >= readingLineTop) {
      return candidate
    }
    if (rect.top <= readingLineTop) {
      lastVisible = candidate
    }
    if (!lastVisible && rect.top > readingLineTop) {
      return {
        ...candidate,
        progress: 0
      }
    }
  }
  return lastVisible
}

/**
 * 记录用户是否仍贴近底部。
 *
 * <p>用户主动向上阅读时暂停自动跟随，避免流式输出把视口不断拉回底部。</p>
 */
function handleMessageStreamScroll() {
  if (isRestoringScroll.value) {
    return
  }
  const stream = messageStreamRef.value
  shouldFollowBottom.value = stream ? isNearBottom(stream) : true
  updateScrollToBottomButton(stream)
  updateActiveTimelineMessage(stream)
  saveCurrentSessionScrollPosition()
}

function updateActiveTimelineMessage(stream = messageStreamRef.value) {
  if (!stream || !chatStore.activeMessages.length) {
    activeTimelineMessageId.value = ''
    return
  }
  let closestTurn = null
  let closestDistance = Number.POSITIVE_INFINITY
  for (const turn of timelineTurns.value) {
    const target = stream.querySelector(`[data-message-id="${CSS.escape(turn.user.id)}"]`)
    if (!target) {
      continue
    }
    const targetTop = getMessageScrollTargetTop(stream, target.getBoundingClientRect())
    const distance = Math.abs(stream.scrollTop - targetTop)
    if (distance < closestDistance) {
      closestDistance = distance
      closestTurn = turn
    }
  }
  activeTimelineMessageId.value = closestTurn?.user.id || ''
}

/**
 * 维护聊天滚动锚点。
 * <p>长对话切换、流式输出和 DOM 高度变化时都依赖该逻辑恢复阅读位置。</p>
 */
function resolveSessionScrollTop(stream, savedPosition) {
  const bottomTop = Math.max(0, stream.scrollHeight - stream.clientHeight)
  if (!savedPosition) {
    return bottomTop
  }
  if (savedPosition.distanceFromBottom <= BOTTOM_THRESHOLD_PX) {
    return bottomTop
  }
  const anchorTop = resolveAnchorScrollTop(stream, savedPosition)
  if (anchorTop != null) {
    return Math.min(Math.max(0, anchorTop), bottomTop)
  }
  return Math.min(Math.max(0, savedPosition.scrollTop), bottomTop)
}

/**
 * 维护聊天滚动锚点。
 * <p>长对话切换、流式输出和 DOM 高度变化时都依赖该逻辑恢复阅读位置。</p>
 */
function resolveAnchorScrollTop(stream, savedPosition) {
  const anchor = findSavedAnchorElement(stream, savedPosition)
  if (!anchor) {
    return null
  }
  const anchorRect = anchor.getBoundingClientRect()
  if (anchorRect.height <= 0) {
    return null
  }
  const streamTop = stream.getBoundingClientRect().top
  const anchorTop = anchorRect.top - streamTop
  const progressTop = anchorTop + anchorRect.height * savedPosition.anchorProgress
  const viewportTop = stream.clientHeight * (savedPosition.anchorViewportRatio || ANCHOR_VIEWPORT_RATIO)
  return stream.scrollTop + progressTop - viewportTop
}

/**
 * 维护聊天滚动锚点。
 * <p>长对话切换、流式输出和 DOM 高度变化时都依赖该逻辑恢复阅读位置。</p>
 */
function findSavedAnchorElement(stream, savedPosition) {
  if (savedPosition.anchorMessageId) {
    // DOM 查询使用转义后的 id，避免特殊字符破坏选择器。
    const anchor = stream.querySelector(`[data-message-id="${CSS.escape(savedPosition.anchorMessageId)}"]`)
    if (anchor) {
      return anchor
    }
  }
  // DOM 查询使用转义后的 id，避免特殊字符破坏选择器。
  const messages = Array.from(stream.querySelectorAll('[data-message-id]'))
  return messages[savedPosition.anchorMessageIndex] || null
}

/**
 * 维护聊天滚动锚点。
 * <p>长对话切换、流式输出和 DOM 高度变化时都依赖该逻辑恢复阅读位置。</p>
 */
async function restoreSessionScroll(sessionId) {
  if (!sessionId) {
    return
  }
  const runId = ++restoreRunId
  await nextTick()
  const savedPosition = chatStore.getSessionScrollPosition(sessionId)
  const applyRestore = () => {
    const stream = messageStreamRef.value
    if (!stream || runId !== restoreRunId || chatStore.activeSessionId !== sessionId) {
      return false
    }
    stream.scrollTop = resolveSessionScrollTop(stream, savedPosition)
    return true
  }

  isRestoringScroll.value = true
  applyRestore()
  window.requestAnimationFrame(() => {
    applyRestore()
    window.setTimeout(() => {
      const restored = applyRestore()
      if (restored && messageStreamRef.value) {
        shouldFollowBottom.value = isNearBottom(messageStreamRef.value)
        updateScrollToBottomButton(messageStreamRef.value)
        updateActiveTimelineMessage(messageStreamRef.value)
        saveCurrentSessionScrollPosition(sessionId)
      }
      if (runId === restoreRunId) {
        isRestoringScroll.value = false
      }
    }, 80)
  })
}

function getActiveMessageScrollSignature() {
  const lastMessage = chatStore.activeMessages.at(-1)
  return {
    sessionId: chatStore.activeSessionId,
    loading: chatStore.isLoadingActiveSession,
    messageCount: chatStore.activeMessages.length,
    lastMessageId: lastMessage?.id || '',
    lastContentLength: lastMessage?.content?.length || 0,
    lastStatus: lastMessage?.status || '',
    lastSourceCount: lastMessage?.sources?.length || 0
  }
}

watch(
  () => chatStore.activeSessionId,
  (sessionId, previousSessionId) => {
    if (previousSessionId && previousSessionId !== sessionId) {
      saveCurrentSessionScrollPosition(previousSessionId)
    }
    shouldFollowBottom.value = true
    showScrollToBottomButton.value = false
    activeTimelineMessageId.value = ''
  }
)

watch(
  () => [chatStore.activeSessionId, chatStore.isLoadingActiveSession],
  ([sessionId, isLoading]) => {
    if (sessionId && !isLoading) {
      restoreSessionScroll(sessionId)
    }
  },
  { flush: 'post', immediate: true }
)

watch(
  getActiveMessageScrollSignature,
  (current, previous) => {
    if (!current.sessionId || current.loading || previous?.sessionId !== current.sessionId) {
      return
    }

    const hasNewMessage = current.messageCount > (previous?.messageCount || 0)
    const hasLastMessageChanged = current.lastMessageId !== previous?.lastMessageId
      || current.lastContentLength !== previous?.lastContentLength
      || current.lastStatus !== previous?.lastStatus
      || current.lastSourceCount !== previous?.lastSourceCount

    if (hasNewMessage) {
      scrollMessagesToBottom()
      return
    }

    if (hasLastMessageChanged && shouldFollowBottom.value) {
      scrollMessagesToBottom()
      return
    }

    if (hasLastMessageChanged) {
      updateScrollToBottomButton()
      saveCurrentSessionScrollPosition(current.sessionId)
    }
  },
  { flush: 'post' }
)

onMounted(() => {
  webSearchSettingsStore.fetchSettings()
  timelineVisibilityObserver = typeof ResizeObserver === 'function'
    ? new ResizeObserver(updateTimelineVisibility)
    : null
  if (messageStreamShellRef.value) {
    timelineVisibilityObserver?.observe(messageStreamShellRef.value)
  }
  window.addEventListener('resize', updateTimelineVisibility)
  window.addEventListener('keydown', handlePageKeydown)
  window.addEventListener('pointerdown', handleComposerSettingsPointerDown)
  document.addEventListener('selectionchange', handleDocumentSelectionChange)
  updateTimelineVisibility()
})

onBeforeUnmount(() => {
  timelineVisibilityObserver?.disconnect()
  timelineVisibilityObserver = null
  window.removeEventListener('resize', updateTimelineVisibility)
  window.removeEventListener('keydown', handlePageKeydown)
  window.removeEventListener('pointerdown', handleComposerSettingsPointerDown)
  document.removeEventListener('selectionchange', handleDocumentSelectionChange)
  stopComposerResize()
  saveCurrentSessionScrollPosition()
})
</script>

<template>
  <section
    class="conversation-page"
    :class="{ 'conversation-page--inspector-open': layoutStore.isSourceInspectorOpen }"
    :style="conversationStyle"
  >
    <header class="conversation-header">
      <div class="conversation-title-group">
        <button
          class="sidebar-toggle-button"
          type="button"
          :title="layoutStore.sidebarToggleTitle"
          :aria-label="layoutStore.sidebarToggleTitle"
          :aria-expanded="!layoutStore.isSidebarCollapsed"
          aria-controls="workspace-context-sidebar"
          @click="layoutStore.toggleSidebar"
        >
          <ChevronRight v-if="layoutStore.isSidebarCollapsed" aria-hidden="true" />
          <ChevronLeft v-else aria-hidden="true" />
        </button>
        <h2>{{ chatStore.activeSession?.title || '新对话' }}</h2>
      </div>
      <div class="conversation-meta">
        <span
          v-for="item in conversationMetaItems"
          :key="item.id"
          :class="['conversation-meta__pill', `conversation-meta__pill--${item.state}`, item.className]"
        >
          {{ item.label }}
        </span>
        <button
          v-if="totalSourceCount"
          class="conversation-source-button"
          type="button"
          @click="layoutStore.openSourceInspector(firstSourceMessageId)"
        >
          {{ totalSourceCount }} 个来源
        </button>
        <button
          class="conversation-action-button"
          type="button"
          title="清空当前会话"
          aria-label="清空当前会话"
          :disabled="!chatStore.hasMessages || chatStore.isStreaming"
          @click="clearMessages"
        >
          <Trash2 aria-hidden="true" />
        </button>
      </div>
    </header>

    <button
      v-if="selectionReferenceAction"
      class="selection-reference-action"
      type="button"
      :style="selectionReferenceAction.style"
      @pointerdown.prevent
      @click="addSelectionReference"
    >
      <MessageSquareQuote aria-hidden="true" />
      添加到对话
    </button>

    <div class="conversation-body">
      <div ref="messageStreamShellRef" class="message-stream-shell">
        <nav
          v-if="timelineTurns.length && isTimelineVisible"
          class="conversation-timeline"
          aria-label="对话回合时间轴"
        >
          <button
            v-for="(turn, index) in timelineTurns"
            :key="turn.user.id"
            class="conversation-timeline-marker"
            :style="timelineMarkerStyle(turn, index)"
            type="button"
            :aria-label="turn.ariaLabel"
            :aria-current="activeTimelineMessageId === turn.user.id ? 'true' : null"
            @pointerenter="handleTimelineMarkerEnter(index)"
            @pointerleave="handleTimelineMarkerLeave"
            @focus="handleTimelineMarkerEnter(index)"
            @blur="handleTimelineMarkerLeave"
            @click="jumpToMessage(turn.user.id)"
          >
            <span
              class="conversation-timeline-marker__line"
              :style="timelineLineStyle(index)"
              aria-hidden="true"
            ></span>
            <span class="conversation-timeline-marker__preview" aria-hidden="true">
              <strong>{{ turn.userPreview }}</strong>
              <span>{{ turn.assistantPreview }}</span>
            </span>
          </button>
        </nav>

        <section
          ref="messageStreamRef"
          class="message-stream"
          aria-live="polite"
          @scroll.passive="handleMessageStreamScroll"
          @pointerup="handleDocumentSelectionChange"
        >
          <div v-if="!chatStore.hasMessages" class="empty-chat">
            <p class="eyebrow">开始一次对话</p>
            <h3>可以直接问，也可以带知识库问。</h3>
            <p>会话和消息会保存到本地 SQLite。开启知识库时，回答会附带检索来源；关闭后就是纯模型对话。</p>
          </div>

          <div
            v-for="message in chatStore.activeMessages"
            :key="message.id"
            class="message-row"
            :class="`message-row--${message.role}`"
          >
            <article
              :data-message-id="message.id"
              class="message-bubble"
              :class="[`message-bubble--${message.role}`, `message-bubble--${message.status}`]"
            >
              <div class="message-label">
                <span>{{ message.role === 'user' ? '你' : APP_DISPLAY_NAME }}</span>
                <em v-if="message.retrievalMode">{{ formatRetrievalModeLabel(message.retrievalMode) }}</em>
                <em v-else-if="message.status === 'streaming'">生成中</em>
                <em v-else-if="message.status === 'error'">未完成</em>
                <em v-else-if="message.status === 'stopped'">已停止</em>
              </div>
              <template v-if="message.role === 'assistant'">
                <details
                  v-if="message.reasoningContent || message.reasoningStatus === 'streaming'"
                  class="message-reasoning"
                  :open="message.reasoningStatus === 'streaming'"
                >
                  <summary>
                    {{ message.reasoningStatus === 'streaming' ? '思考中…' : '思考过程' }}
                  </summary>
                  <pre v-if="message.reasoningContent">{{ message.reasoningContent }}</pre>
                </details>
                <AiMarkdownRenderer
                  class="message-content"
                  :content="message.content"
                  :sources="message.sources"
                  empty-text="正在等待模型返回..."
                  :final="message.status !== 'streaming'"
                  @citation-click="openMessageSources(message, $event)"
                />
              </template>
              <template v-else>
                <div
                  v-if="message.references?.length"
                  class="reference-chip reference-chip--message"
                  tabindex="0"
                  :aria-label="referenceCountLabel(message.references)"
                >
                  <MessageSquareQuote aria-hidden="true" />
                  <span>{{ referenceCountLabel(message.references) }}</span>
                  <div class="reference-preview" role="tooltip">
                    <strong>{{ referenceCountLabel(message.references) }}</strong>
                    <ol>
                      <li v-for="reference in message.references" :key="reference.id">
                        {{ compactReferenceSnippet(reference.snippet) }}
                      </li>
                    </ol>
                  </div>
                </div>
                <p class="message-content">{{ message.content || '正在等待模型返回...' }}</p>
              </template>

              <div v-if="message.sources?.length" class="message-source-strip" aria-label="回答来源">
                <button class="message-source-summary" type="button" @click="openMessageSources(message)">
                  {{ message.sources.length }} 个来源
                </button>
                <button
                  v-for="source in message.sources.slice(0, 3)"
                  :key="source.chunkId"
                  class="message-source-chip"
                  type="button"
                  :title="sourceDisplayName(source)"
                  @click="openMessageSources(message, source)"
                >
                  [{{ source.index }}] {{ sourceDisplayName(source) }}
                </button>
              </div>
            </article>
          </div>
        </section>

        <button
          v-if="showScrollToBottomButton"
          class="scroll-to-bottom-button"
          type="button"
          title="回到最新消息"
          aria-label="回到最新消息"
          @click="handleScrollToBottomClick"
        >
          <ArrowDown aria-hidden="true" />
        </button>
      </div>

      <SourceInspector
        v-if="layoutStore.isSourceInspectorOpen"
        :messages="chatStore.activeMessages"
        @ask-source="chatStore.askAboutSource"
      />
    </div>

    <form class="composer-bar" @submit.prevent="chatStore.streamChat">
      <div v-if="chatStore.hasPendingReferences" class="composer-reference-row">
        <div
          class="reference-chip reference-chip--composer"
          tabindex="0"
          :aria-label="referenceCountLabel(chatStore.pendingReferences)"
        >
          <MessageSquareQuote aria-hidden="true" />
          <span>{{ referenceCountLabel(chatStore.pendingReferences) }}</span>
          <button
            class="reference-chip__clear"
            type="button"
            title="清空引用"
            aria-label="清空引用"
            @click="chatStore.clearPendingReferences"
          >
            <X aria-hidden="true" />
          </button>
          <div class="reference-preview" role="tooltip">
            <strong>{{ referenceCountLabel(chatStore.pendingReferences) }}</strong>
            <ol>
              <li v-for="reference in chatStore.pendingReferences" :key="reference.id">
                {{ compactReferenceSnippet(reference.snippet) }}
              </li>
            </ol>
          </div>
        </div>
      </div>

      <div class="composer-input-row">
        <div
          class="composer-resize-handle"
          role="separator"
          aria-orientation="horizontal"
          aria-label="调整输入框高度"
          tabindex="0"
          title="拖动调整输入框高度"
          @dblclick="resetComposerHeight"
          @keydown="handleComposerResizeKeydown"
          @pointerdown="startComposerResize"
        >
          <span aria-hidden="true"></span>
        </div>
        <textarea
          v-model="chatStore.draft"
          rows="1"
          :style="composerTextareaStyle"
          :placeholder="chatStore.useKnowledgeBase ? '向知识库提问...' : '直接和模型对话...'"
          :disabled="chatStore.isStreaming"
          @keydown="handleDraftKeydown"
        ></textarea>

        <div class="composer-side-actions">
          <span
            class="composer-context-indicator"
            :class="{ 'is-warning': isContextUsageWarning, 'is-compressed': isContextCompressed }"
            :style="contextUsageStyle"
            :aria-label="contextUsageDetails"
            role="img"
            tabindex="0"
          >
            <span class="composer-context-tooltip" role="tooltip">
              <strong>{{ contextUsageLabel }} · {{ contextUsagePercentLabel }}</strong>
              <em>{{ isContextCompressed ? '已压缩' : '未压缩' }} · {{ contextUsageTitle }}</em>
            </span>
          </span>
          <button
            ref="composerSettingsButtonRef"
            class="composer-settings-button"
            type="button"
            title="知识库设置"
            :aria-expanded="isComposerSettingsOpen"
            aria-label="打开知识库设置"
            @click="isComposerSettingsOpen = !isComposerSettingsOpen"
          >
            <SlidersHorizontal aria-hidden="true" />
          </button>
          <button
            class="composer-icon-button"
            :class="{ 'composer-icon-button--streaming': chatStore.isStreaming }"
            :type="chatStore.isStreaming ? 'button' : 'submit'"
            :disabled="!chatStore.isStreaming && !chatStore.canSend"
            :title="composerActionTitle"
            :aria-label="composerActionTitle"
            @click="handleComposerAction"
          >
            <LoaderCircle v-if="chatStore.isStreaming" aria-hidden="true" />
            <Send v-else aria-hidden="true" />
          </button>
        </div>

        <ChatSettingsPopover
          ref="composerSettingsPopoverRef"
          v-if="isComposerSettingsOpen"
          :use-knowledge-base="chatStore.useKnowledgeBase"
          :mode="chatStore.mode"
          :top-k="chatStore.topK"
          :use-web-search="chatStore.useWebSearch"
          :web-search-available="webSearchSettingsStore.available"
          :web-search-status-label="webSearchSettingsStore.statusLabel"
          :modes="SEARCH_MODES"
          @update:use-knowledge-base="chatStore.setUseKnowledgeBase"
          @update:use-web-search="handleUseWebSearchChange"
          @update:mode="chatStore.setMode"
          @update:top-k="chatStore.setTopK"
        />
      </div>

      <div class="composer-feedback">
        <p v-if="chatStore.error" class="error-message">{{ chatStore.error }}</p>
        <p v-else-if="chatStore.knowledgeDisabledHint" class="hint-message">{{ chatStore.knowledgeDisabledHint }}</p>
        <p v-else-if="!modelConfigStore.activeChatConfig?.apiKeyConfigured" class="hint-message">
          尚未保存对话模型 API Key。请先到设置中的模型配置保存后再对话。
        </p>
        <p v-else-if="chatStore.useKnowledgeBase && !modelConfigStore.activeEmbeddingConfig?.apiKeyConfigured" class="hint-message">
          尚未保存向量模型 API Key。向量检索不可用时会降级到关键词检索。
        </p>
      </div>
    </form>
  </section>
</template>
