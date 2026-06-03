<script setup>
import { computed, ref } from 'vue'
import { LoaderCircle, Send, SlidersHorizontal } from 'lucide-vue-next'
import MarkdownRenderer from '../components/markdown-renderer.vue'
import SegmentedControl from '../components/segmented-control.vue'
import SourceList from '../components/source-list.vue'
import { useChatStore } from '../stores/chat'
import { useModelConfigStore } from '../stores/model-config'
import { SEARCH_MODES } from '../stores/search'

const chatStore = useChatStore()
const modelConfigStore = useModelConfigStore()
const isComposerSettingsOpen = ref(false)
const activeModeLabel = computed(() => SEARCH_MODES.find((item) => item.value === chatStore.mode)?.label || chatStore.mode)
const composerActionTitle = computed(() => (chatStore.isStreaming ? '停止对话' : '发送信息'))
const activeModelSummary = computed(() => {
  const chat = modelConfigStore.activeChatConfig?.modelName || '未配置对话模型'
  const embedding = modelConfigStore.activeEmbeddingConfig?.modelName || '未配置 Embedding'
  return `${chat} / ${embedding}`
})

function handleComposerAction() {
  if (chatStore.isStreaming) {
    chatStore.stopChat()
  }
}
</script>

<template>
  <section class="conversation-page">
    <header class="conversation-header">
      <div>
        <p class="eyebrow">对话</p>
        <h2>{{ chatStore.activeSession?.title || '新对话' }}</h2>
      </div>
      <div class="conversation-meta">
        <span>{{ chatStore.useKnowledgeBase ? '知识库已启用' : '纯对话待接入' }}</span>
        <span>{{ chatStore.mode }}</span>
        <span>{{ activeModelSummary }}</span>
      </div>
    </header>

    <section class="message-stream" aria-live="polite">
      <div v-if="!chatStore.hasMessages" class="empty-chat">
        <p class="eyebrow">开始一次检索增强对话</p>
        <h3>导入资料后，直接问它。</h3>
        <p>第七阶段先完成对话式前端。左侧会话是临时状态；跨重启聊天记忆会在第八阶段用 SQLite 接上。</p>
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
          <em v-else-if="message.status === 'stopped'">已停止</em>
        </div>
        <MarkdownRenderer
          v-if="message.role === 'assistant' && message.status !== 'error'"
          class="message-content"
          :content="message.content"
          empty-text="正在等待模型返回..."
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
          :placeholder="chatStore.useKnowledgeBase ? '向知识库提问...' : '纯对话将在第八阶段启用'"
          :disabled="chatStore.isStreaming"
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

        <div v-if="isComposerSettingsOpen" class="composer-settings-popover">
          <div class="composer-settings__summary">
            <span>{{ chatStore.useKnowledgeBase ? '使用知识库' : '纯对话待接入' }}</span>
            <span>{{ activeModeLabel }}</span>
            <span>Top K {{ chatStore.topK }}</span>
          </div>

          <div class="composer-settings__body">
            <label class="knowledge-toggle">
              <input v-model="chatStore.useKnowledgeBase" type="checkbox" />
              <span>使用知识库</span>
            </label>

            <SegmentedControl v-model="chatStore.mode" :options="SEARCH_MODES" label="RAG 检索模式" />
            <label class="field field--small">
              <span>Top K</span>
              <input v-model="chatStore.topK" type="number" min="1" max="50" />
            </label>
          </div>
        </div>
      </div>

      <div class="composer-feedback">
        <p v-if="chatStore.error" class="error-message">{{ chatStore.error }}</p>
        <p v-else-if="chatStore.knowledgeDisabledHint" class="hint-message">{{ chatStore.knowledgeDisabledHint }}</p>
        <p v-else-if="!modelConfigStore.activeChatConfig?.apiKeyConfigured" class="hint-message">
          尚未保存对话模型 API Key。请先到设置中的模型配置保存后再对话。
        </p>
        <p v-else-if="chatStore.useKnowledgeBase && !modelConfigStore.activeEmbeddingConfig?.apiKeyConfigured" class="hint-message">
          尚未保存 Embedding 模型 API Key。向量检索不可用时会降级到关键词检索。
        </p>
      </div>
    </form>
  </section>
</template>
