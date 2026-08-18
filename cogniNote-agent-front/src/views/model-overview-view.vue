<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChevronRight, Database, Eye, MessageSquareText, RefreshCw } from 'lucide-vue-next'
import { useModelConfigStore } from '../stores/model-config'

const route = useRoute()
const router = useRouter()
const modelConfigStore = useModelConfigStore()

const modelCards = computed(() => [
  createModelCard({
    role: modelConfigStore.ROLES.CHAT,
    item: 'model-chat',
    label: '对话模型',
    icon: MessageSquareText,
    config: modelConfigStore.activeChatConfig,
    details: config => [
      { label: '上下文', value: config ? modelConfigStore.formatContextWindowTokens(config.contextWindowTokens) : '-' },
      { label: 'Temperature', value: formatNumber(config?.temperature) },
      { label: 'Top K', value: formatNumber(config?.defaultTopK) }
    ]
  }),
  createModelCard({
    role: modelConfigStore.ROLES.EMBEDDING,
    item: 'model-embedding',
    label: '向量模型',
    icon: Database,
    config: modelConfigStore.activeEmbeddingConfig,
    details: config => [
      { label: '向量维度', value: config?.embeddingDimensions ? `${config.embeddingDimensions} 维` : '-' },
      { label: '请求限速', value: formatRateLimit(config) }
    ]
  }),
  createModelCard({
    role: modelConfigStore.ROLES.VISION,
    item: 'model-vision',
    label: '视觉识别模型',
    icon: Eye,
    config: modelConfigStore.activeVisionConfig,
    details: config => [
      { label: 'Temperature', value: formatNumber(config?.temperature) }
    ]
  })
])

const hasActiveConfig = computed(() => modelCards.value.some(card => card.config))

function createModelCard(card) {
  return {
    ...card,
    provider: providerLabel(card.config?.provider),
    displayName: card.config?.displayName || '尚未配置',
    modelName: card.config?.modelName || '等待配置模型 ID',
    baseUrl: card.config?.baseUrl || '-',
    detailItems: card.details(card.config)
  }
}

function providerLabel(provider) {
  return modelConfigStore.providerOptions.find(option => option.value === provider)?.label || provider || '-'
}

function formatNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) ? String(number) : '-'
}

function formatRateLimit(config) {
  if (!config?.embeddingRequestsPerMinute || !config?.embeddingTokensPerMinute) {
    return '-'
  }
  return `${config.embeddingRequestsPerMinute} RPM / ${config.embeddingTokensPerMinute} TPM`
}

function openEditor(item) {
  router.replace({
    name: 'settings',
    query: {
      ...route.query,
      item
    }
  })
}

async function reloadSummary() {
  await modelConfigStore.fetchModelConfig()
}
</script>

<template>
  <section class="model-overview" :aria-busy="modelConfigStore.isLoadingActiveSummary">
    <header class="model-overview__header">
      <div>
        <p class="eyebrow">运行配置</p>
        <h3>当前启用模型</h3>
      </div>
      <el-tooltip content="重新读取模型摘要" placement="bottom">
        <el-button
          circle
          :loading="modelConfigStore.isLoadingActiveSummary"
          aria-label="重新读取模型摘要"
          @click="reloadSummary"
        >
          <RefreshCw v-if="!modelConfigStore.isLoadingActiveSummary" aria-hidden="true" />
        </el-button>
      </el-tooltip>
    </header>

    <section v-if="modelConfigStore.isLoadingActiveSummary && !hasActiveConfig" class="model-overview__grid" aria-label="正在读取模型摘要">
      <article v-for="index in 3" :key="index" class="model-overview-card model-overview-card--loading">
        <el-skeleton animated :rows="5" />
      </article>
    </section>

    <section v-else-if="modelConfigStore.activeSummaryError" class="model-overview__feedback" role="alert">
      <p>{{ modelConfigStore.activeSummaryError }}</p>
      <button class="secondary-button" type="button" @click="reloadSummary">重试</button>
    </section>

    <section v-else class="model-overview__grid" aria-label="当前启用模型">
      <button
        v-for="card in modelCards"
        :key="card.role"
        class="model-overview-card"
        type="button"
        :aria-label="`编辑${card.label}：${card.displayName}`"
        @click="openEditor(card.item)"
      >
        <span class="model-overview-card__topline">
          <span class="model-overview-card__role">
            <component :is="card.icon" aria-hidden="true" />
            {{ card.label }}
          </span>
          <span v-if="card.config" class="model-overview-card__status">已启用</span>
          <span v-else class="model-overview-card__status model-overview-card__status--empty">待配置</span>
        </span>
        <strong>{{ card.displayName }}</strong>
        <code>{{ card.modelName }}</code>
        <dl>
          <div>
            <dt>服务商</dt>
            <dd>{{ card.provider }}</dd>
          </div>
          <div>
            <dt>Base URL</dt>
            <dd class="model-overview-card__url">{{ card.baseUrl }}</dd>
          </div>
          <div v-for="detail in card.detailItems" :key="detail.label">
            <dt>{{ detail.label }}</dt>
            <dd>{{ detail.value }}</dd>
          </div>
        </dl>
        <span class="model-overview-card__action">
          进入编辑
          <ChevronRight aria-hidden="true" />
        </span>
      </button>
    </section>
  </section>
</template>
