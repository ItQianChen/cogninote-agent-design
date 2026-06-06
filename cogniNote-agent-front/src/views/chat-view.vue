<script setup>
import { computed, defineAsyncComponent, nextTick, ref, watch } from 'vue'
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
const composerActionTitle = computed(() => (chatStore.isStreaming ? '停止对话' : '发送信息'))
const activeModelSummary = computed(() => {
  const chat = modelConfigStore.activeChatConfig?.modelName || '未配置对话模型'
  const embedding = modelConfigStore.activeEmbeddingConfig?.modelName || '未配置向量模型'
  return `${chat} / ${embedding}`
})

function handleComposerAction() {
  if (chatStore.isStreaming) {
    chatStore.stopChat()
  }
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
  applyMessageScrollBottom()
  window.requestAnimationFrame(() => {
    applyMessageScrollBottom()
    window.setTimeout(applyMessageScrollBottom, 80)
  })
}

function applyMessageScrollBottom() {
  const stream = messageStreamRef.value
  if (stream) {
    stream.scrollTop = stream.scrollHeight
  }
}

watch(
  () => [
    chatStore.activeSessionId,
    chatStore.isLoadingActiveSession,
    chatStore.activeMessages.length,
    chatStore.activeMessages.at(-1)?.content.length || 0
  ],
  () => {
    if (!chatStore.isLoadingActiveSession) {
      scrollMessagesToBottom()
    }
  },
  { flush: 'post' }
)
</script>

<template>
  <section class="conversation-page">
    <header class="conversation-header">
      <div>
        <p class="eyebrow">对话</p>
        <h2>{{ chatStore.activeSession?.title || '新对话' }}</h2>
      </div>
      <div class="conversation-meta">
        <span>{{ chatStore.useKnowledgeBase ? '知识库已启用' : '纯模型对话' }}</span>
        <span>{{ chatStore.mode }}</span>
        <span>{{ activeModelSummary }}</span>
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

    <section ref="messageStreamRef" class="message-stream" aria-live="polite">
      <div v-if="!chatStore.hasMessages" class="empty-chat">
        <p class="eyebrow">开始一次对话</p>
        <h3>可以直接问，也可以带知识库问。</h3>
        <p>会话和消息会保存到本地 SQLite。开启知识库时，回答会附带检索来源；关闭后就是纯模型对话。</p>
      </div>

      <article
        v-for="message in chatStore.activeMessages"
        :key="message.id"
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
          rows="3"
          :placeholder="chatStore.useKnowledgeBase ? '向知识库提问...' : '直接和模型对话...'"
          :disabled="chatStore.isStreaming"
          @keydown="handleDraftKeydown"
        ></textarea>

        <div class="composer-side-actions">
          <button
            class="composer-settings-button"
            type="button"
            title="对话设置"
            :aria-expanded="isComposerSettingsOpen"
            aria-label="打开对话设置"
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
