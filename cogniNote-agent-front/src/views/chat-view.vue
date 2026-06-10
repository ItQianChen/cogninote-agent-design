<script setup>
// chat-view 负责 聊天会话 页面或组件的状态组织、用户交互和后端同步。
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight, LoaderCircle, Send, SlidersHorizontal, Trash2 } from 'lucide-vue-next'
import ChatSettingsPopover from '../components/chat-settings-popover.vue'
import SourceInspector from '../components/source-inspector.vue'
import { useChatStore } from '../stores/chat'
import { useLayoutStore } from '../stores/layout'
import { useModelConfigStore } from '../stores/model-config'
import { SEARCH_MODES } from '../stores/search'

const chatStore = useChatStore()
const layoutStore = useLayoutStore()
const modelConfigStore = useModelConfigStore()
const AiMarkdownRenderer = defineAsyncComponent(() => import('../components/ai-markdown-renderer.vue'))
const isComposerSettingsOpen = ref(false)
const messageStreamRef = ref(null)
const isRestoringScroll = ref(false)
const shouldFollowBottom = ref(true)
const BOTTOM_THRESHOLD_PX = 80
const ANCHOR_VIEWPORT_RATIO = 0.35
const DEFAULT_CONTEXT_WINDOW_TOKENS = 128000
const COMPOSER_MIN_HEIGHT = 76
const COMPOSER_MAX_HEIGHT = 220
let restoreRunId = 0
let composerResizeState = null
const composerTextareaHeight = ref(COMPOSER_MIN_HEIGHT)
const composerActionTitle = computed(() => (chatStore.isStreaming ? '停止对话' : '发送信息'))
const totalSourceCount = computed(() =>
  chatStore.activeMessages.reduce((total, message) => total + (message.sources?.length || 0), 0)
)
const firstSourceMessageId = computed(() =>
  chatStore.activeMessages.find((message) => message.sources?.length)?.id || ''
)
const activeModelSummary = computed(() => {
  const chat = modelConfigStore.activeChatConfig?.modelName || '未配置对话模型'
  const embedding = modelConfigStore.activeEmbeddingConfig?.modelName || '未配置向量模型'
  return `${chat} / ${embedding}`
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

/**
 * 处理 handle Composer Action 交互。
 * <p>事件处理函数只保留必要副作用，复杂状态交给 Store 维护。</p>
 */
function handleComposerAction() {
  if (chatStore.isStreaming) {
    chatStore.stopChat()
  }
}

/**
 * 创建或启动 start Composer Resize 对应的前端流程。
 * <p>该方法通常会同步本地响应式状态和后端快照。</p>
 */
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
 * 处理 handle Composer Resize 交互。
 * <p>事件处理函数只保留必要副作用，复杂状态交给 Store 维护。</p>
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

/**
 * 执行 聊天会话 中的 stop Composer Resize 步骤。
 * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
 */
function stopComposerResize() {
  if (!composerResizeState) {
    return
  }
  composerResizeState = null
  window.removeEventListener('pointermove', handleComposerResize)
  window.removeEventListener('pointerup', stopComposerResize)
  window.removeEventListener('pointercancel', stopComposerResize)
}

/**
 * 执行 聊天会话 中的 reset Composer Height 步骤。
 * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
 */
function resetComposerHeight() {
  composerTextareaHeight.value = COMPOSER_MIN_HEIGHT
}

/**
 * 处理 handle Composer Resize Keydown 交互。
 * <p>事件处理函数只保留必要副作用，复杂状态交给 Store 维护。</p>
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

/**
 * 规范化 normalize Token Count 输入。
 * <p>把后端、表单或浏览器传入的异常值收敛为安全范围。</p>
 */
function normalizeTokenCount(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.max(0, Math.trunc(parsed)) : 0
}

/**
 * 规范化比例输入。
 * <p>把后端、表单或浏览器传入的异常值收敛为安全范围。</p>
 */
function normalizeRatio(value, fallback = 0) {
  const parsed = Number(value)
  const ratio = Number.isFinite(parsed) ? parsed : fallback
  return Math.min(1, Math.max(0, ratio))
}

/**
 * 格式化 format Token Count 展示文本。
 * <p>统一页面上的数字、时间或语言标签展示口径。</p>
 */
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

/**
 * 格式化 format Compact Number 展示文本。
 * <p>统一页面上的数字、时间或语言标签展示口径。</p>
 */
function formatCompactNumber(value) {
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, '')
}

/**
 * 处理 handle Draft Keydown 交互。
 * <p>事件处理函数只保留必要副作用，复杂状态交给 Store 维护。</p>
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

/**
 * 删除或清理 clear Messages 对应的数据。
 * <p>清理时同步处理本地缓存，避免界面保留过期状态。</p>
 */
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
  if (layoutStore.isSourceInspectorOpen) {
    layoutStore.closeSourceInspector()
  }
}

/**
 * 维护聊天滚动锚点。
 * <p>长对话切换、流式输出和 DOM 高度变化时都依赖该逻辑恢复阅读位置。</p>
 */
async function scrollMessagesToBottom() {
  await nextTick()
  applyMessageScrollBottom(true)
  // 等待下一轮渲染后再读写 DOM，避免滚动位置计算使用旧布局。
  window.requestAnimationFrame(() => {
    applyMessageScrollBottom(true)
    // 等待下一轮渲染后再读写 DOM，避免滚动位置计算使用旧布局。
    window.setTimeout(() => applyMessageScrollBottom(true), 80)
  })
}

/**
 * 更新 apply Message Scroll Bottom 对应的状态。
 * <p>状态写入后需要保持控件、Store 和后端快照一致。</p>
 */
function applyMessageScrollBottom(saveAfterScroll = false) {
  const stream = messageStreamRef.value
  if (stream) {
    stream.scrollTop = Math.max(0, stream.scrollHeight - stream.clientHeight)
    shouldFollowBottom.value = true
    if (saveAfterScroll) {
      saveCurrentSessionScrollPosition()
    }
  }
}

/**
 * 执行 聊天会话 中的 distance From Bottom 步骤。
 * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
 */
function distanceFromBottom(stream) {
  return Math.max(0, stream.scrollHeight - stream.scrollTop - stream.clientHeight)
}

/**
 * 判断 is Near Bottom 条件。
 * <p>集中维护 UI 分支使用的同一套判定规则。</p>
 */
function isNearBottom(stream) {
  return distanceFromBottom(stream) <= BOTTOM_THRESHOLD_PX
}

/**
 * 更新 save Current Session Scroll Position 对应的状态。
 * <p>状态写入后需要保持控件、Store 和后端快照一致。</p>
 */
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
 * 处理 handle Message Stream Scroll 交互。
 * <p>事件处理函数只保留必要副作用，复杂状态交给 Store 维护。</p>
 */
function handleMessageStreamScroll() {
  if (isRestoringScroll.value) {
    return
  }
  const stream = messageStreamRef.value
  shouldFollowBottom.value = stream ? isNearBottom(stream) : true
  saveCurrentSessionScrollPosition()
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
  /**
   * 更新 apply Restore 对应的状态。
   * <p>状态写入后需要保持控件、Store 和后端快照一致。</p>
   */
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
  // 等待下一轮渲染后再读写 DOM，避免滚动位置计算使用旧布局。
  window.requestAnimationFrame(() => {
    applyRestore()
    // 等待下一轮渲染后再读写 DOM，避免滚动位置计算使用旧布局。
    window.setTimeout(() => {
      const restored = applyRestore()
      if (restored && messageStreamRef.value) {
        shouldFollowBottom.value = isNearBottom(messageStreamRef.value)
        saveCurrentSessionScrollPosition(sessionId)
      }
      if (runId === restoreRunId) {
        isRestoringScroll.value = false
      }
    }, 80)
  })
}

/**
 * 加载 get Active Message Scroll Signature 对应的数据。
 * <p>接口结果会被转换为页面或 Store 可直接消费的结构。</p>
 */
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
      saveCurrentSessionScrollPosition(current.sessionId)
    }
  },
  { flush: 'post' }
)

onMounted(() => {
  window.addEventListener('keydown', handlePageKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handlePageKeydown)
  stopComposerResize()
  saveCurrentSessionScrollPosition()
})
</script>

<template>
  <section class="conversation-page" :class="{ 'conversation-page--inspector-open': layoutStore.isSourceInspectorOpen }">
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
        <span>{{ chatStore.useKnowledgeBase ? '知识库已启用' : '纯模型对话' }}</span>
        <span>{{ chatStore.mode }}</span>
        <span class="conversation-meta__model">{{ activeModelSummary }}</span>
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

    <div class="conversation-body">
      <section ref="messageStreamRef" class="message-stream" aria-live="polite" @scroll.passive="handleMessageStreamScroll">
        <div v-if="!chatStore.hasMessages" class="empty-chat">
          <p class="eyebrow">开始一次对话</p>
          <h3>可以直接问，也可以带知识库问。</h3>
          <p>会话和消息会保存到本地 SQLite。开启知识库时，回答会附带检索来源；关闭后就是纯模型对话。</p>
        </div>

        <article
          v-for="message in chatStore.activeMessages"
          :key="message.id"
          :data-message-id="message.id"
          class="message-bubble"
          :class="[`message-bubble--${message.role}`, `message-bubble--${message.status}`]"
        >
          <div class="message-label">
            <span>{{ message.role === 'user' ? '你' : 'CogniNote' }}</span>
            <em v-if="message.retrievalMode">{{ message.retrievalMode }}</em>
            <em v-else-if="message.status === 'streaming'">生成中</em>
            <em v-else-if="message.status === 'error'">未完成</em>
            <em v-else-if="message.status === 'stopped'">已停止</em>
          </div>
          <AiMarkdownRenderer
            v-if="message.role === 'assistant'"
            class="message-content"
            :content="message.content"
            empty-text="正在等待模型返回..."
            :final="message.status !== 'streaming'"
          />
          <p v-else class="message-content">{{ message.content || '正在等待模型返回...' }}</p>

          <div v-if="message.sources?.length" class="message-source-strip" aria-label="回答来源">
            <button class="message-source-summary" type="button" @click="openMessageSources(message)">
              {{ message.sources.length }} 个来源
            </button>
            <button
              v-for="source in message.sources.slice(0, 3)"
              :key="source.chunkId"
              class="message-source-chip"
              type="button"
              :title="source.fileName"
              @click="openMessageSources(message, source)"
            >
              [{{ source.index }}] {{ source.fileName }}
            </button>
          </div>
        </article>
      </section>

      <SourceInspector
        v-if="layoutStore.isSourceInspectorOpen"
        :messages="chatStore.activeMessages"
        @ask-source="chatStore.askAboutSource"
      />
    </div>

    <form class="composer-bar" @submit.prevent="chatStore.streamChat">
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
          v-if="isComposerSettingsOpen"
          :use-knowledge-base="chatStore.useKnowledgeBase"
          :mode="chatStore.mode"
          :top-k="chatStore.topK"
          :modes="SEARCH_MODES"
          @update:use-knowledge-base="chatStore.setUseKnowledgeBase"
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
