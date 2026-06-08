<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { LoaderCircle, Send, SlidersHorizontal, Trash2 } from 'lucide-vue-next'
import ChatSettingsPopover from '../components/chat-settings-popover.vue'
import SourceList from '../components/source-list.vue'
import { useChatStore } from '../stores/chat'
import { useModelConfigStore } from '../stores/model-config'
import { SEARCH_MODES } from '../stores/search'

const chatStore = useChatStore()
const modelConfigStore = useModelConfigStore()
const AiMarkdownRenderer = defineAsyncComponent(() => import('../components/ai-markdown-renderer.vue'))
const isComposerSettingsOpen = ref(false)
const messageStreamRef = ref(null)
const isRestoringScroll = ref(false)
const shouldFollowBottom = ref(true)
const BOTTOM_THRESHOLD_PX = 80
const ANCHOR_VIEWPORT_RATIO = 0.35
const DEFAULT_CONTEXT_WINDOW_TOKENS = 128000
let restoreRunId = 0
const composerActionTitle = computed(() => (chatStore.isStreaming ? '停止对话' : '发送信息'))
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

function handleComposerAction() {
  if (chatStore.isStreaming) {
    chatStore.stopChat()
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

function findScrollAnchor(stream) {
  const streamRect = stream.getBoundingClientRect()
  const viewportRatio = ANCHOR_VIEWPORT_RATIO
  const readingLineTop = streamRect.top + stream.clientHeight * viewportRatio
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

function handleMessageStreamScroll() {
  if (isRestoringScroll.value) {
    return
  }
  const stream = messageStreamRef.value
  shouldFollowBottom.value = stream ? isNearBottom(stream) : true
  saveCurrentSessionScrollPosition()
}

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

function findSavedAnchorElement(stream, savedPosition) {
  if (savedPosition.anchorMessageId) {
    const anchor = stream.querySelector(`[data-message-id="${CSS.escape(savedPosition.anchorMessageId)}"]`)
    if (anchor) {
      return anchor
    }
  }
  const messages = Array.from(stream.querySelectorAll('[data-message-id]'))
  return messages[savedPosition.anchorMessageIndex] || null
}

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

onBeforeUnmount(() => {
  saveCurrentSessionScrollPosition()
})
</script>

<template>
  <section class="conversation-page">
    <header class="conversation-header">
      <div>
        <h2>{{ chatStore.activeSession?.title || '新对话' }}</h2>
      </div>
      <div class="conversation-meta">
        <span>{{ chatStore.useKnowledgeBase ? '知识库已启用' : '纯模型对话' }}</span>
        <span>{{ chatStore.mode }}</span>
        <span
          class="conversation-meta__context"
          :class="{ 'is-warning': isContextUsageWarning, 'is-compressed': isContextCompressed }"
          :title="contextUsageTitle"
        >
          {{ contextUsageLabel }} · {{ contextUsagePercentLabel }}
        </span>
        <span v-if="isContextCompressed" class="conversation-meta__compressed">已压缩</span>
        <span class="conversation-meta__model">{{ activeModelSummary }}</span>
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
        <SourceList
          v-if="message.sources?.length"
          :sources="message.sources"
          compact
          @ask-source="chatStore.askAboutSource"
        />
      </article>
    </section>

    <form class="composer-bar" @submit.prevent="chatStore.streamChat">
      <div class="composer-input-row">
        <textarea
          v-model="chatStore.draft"
          rows="1"
          :placeholder="chatStore.useKnowledgeBase ? '向知识库提问...' : '直接和模型对话...'"
          :disabled="chatStore.isStreaming"
          @keydown="handleDraftKeydown"
        ></textarea>

        <div class="composer-side-actions">
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
