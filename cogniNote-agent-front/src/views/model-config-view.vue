<script setup>
// 模型配置页只编排表单交互；配置保存、激活和默认值归 model-config store 管理。
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CircleHelp } from 'lucide-vue-next'
import { useModelConfigStore } from '../stores/model-config'
import { useSearchStore } from '../stores/search'

const props = defineProps({
  initialRole: {
    type: String,
    default: ''
  }
})

const modelConfigStore = useModelConfigStore()
const searchStore = useSearchStore()
const modelSelectRef = ref(null)
const modelSelectKeyword = ref('')
const selectableModelOptions = computed(() => {
  if (modelConfigStore.activeRole === modelConfigStore.ROLES.CHAT) {
    return modelConfigStore.chatModelOptions
  }
  if (modelConfigStore.activeRole === modelConfigStore.ROLES.EMBEDDING) {
    return modelConfigStore.embeddingModelOptions
  }
  return modelConfigStore.visionModelOptions
})
const filteredModelOptions = computed(() => {
  const keyword = modelSelectKeyword.value.trim().toLowerCase()
  if (!keyword) {
    return selectableModelOptions.value
  }
  return selectableModelOptions.value.filter(model => {
    return [model.id, model.name, model.capability]
      .filter(Boolean)
      .some(value => String(value).toLowerCase().includes(keyword))
  })
})

watch(
  () => props.initialRole,
  () => loadInitialSettings(),
  { immediate: true }
)

watch(
  () => modelConfigStore.activeRole,
  () => {
    modelSelectKeyword.value = ''
  }
)

async function loadInitialSettings() {
  const role = normalizeInitialRole(props.initialRole)
  if (role) {
    return await modelConfigStore.switchRole(role)
  }
  await modelConfigStore.initializeEditor()
}

function handleStartCreate() {
  modelConfigStore.startCreate()
}

function handleCancelDraft() {
  modelConfigStore.cancelDraft()
}

function handleEditConfig(config) {
  modelConfigStore.editConfig(config)
}

function filterModelOptions(query) {
  modelSelectKeyword.value = String(query || '')
}

function handleModelSelectChange(value) {
  modelConfigStore.form.modelName = value || ''
}

function modelCapabilityLabel(capability) {
  return {
    CHAT: '对话',
    EMBEDDING: '向量',
    VISION: '视觉',
    UNKNOWN: '未识别'
  }[capability] || '未识别'
}

async function handleModelSelectVisibleChange(visible) {
  if (!visible) {
    modelSelectKeyword.value = ''
    return
  }
  moveModelSelectCursorToEnd()
  if (shouldFetchModelOptionsOnOpen()) {
    await modelConfigStore.fetchModels()
    moveModelSelectCursorToEnd()
  }
}

function shouldFetchModelOptionsOnOpen() {
  return !modelConfigStore.isFetchingModels && !modelConfigStore.hasFreshModelOptions()
}

function moveModelSelectCursorToEnd() {
  nextTick(() => {
    const input = modelSelectRef.value?.$el?.querySelector('.el-select__input')
    if (!input || typeof input.setSelectionRange !== 'function') {
      return
    }
    const end = input.value.length
    input.setSelectionRange(end, end)
  })
}

async function handleReload() {
  await modelConfigStore.reloadEditor()
}

/**
 * 保存配置后检查是否需要重建向量索引。
 *
 * <p>Embedding 配置变化不会自动改写已有 Lucene 向量，必须提示用户手动重建。</p>
 */
async function handleSave() {
  const previousEmbeddingIndexSignature = isEditingActiveEmbeddingConfig()
    ? embeddingIndexSignature(modelConfigStore.activeEmbeddingConfig)
    : ''
  const snapshot = await modelConfigStore.saveModelConfig()
  await promptRebuildIndexIfVectorConfigChanged(snapshot, { previousEmbeddingIndexSignature })
}

/**
 * 激活配置后同步处理向量索引提醒。
 *
 * <p>只有 Embedding role 会影响已写入索引的向量维度或模型语义。</p>
 */
async function handleActivate() {
  const previousEmbeddingIndexSignature = embeddingIndexSignature(modelConfigStore.activeEmbeddingConfig)
  const snapshot = await modelConfigStore.activateConfig(modelConfigStore.selectedConfig)
  await promptRebuildIndexIfVectorConfigChanged(snapshot, { previousEmbeddingIndexSignature })
}

async function handleRemove() {
  await modelConfigStore.removeConfig(modelConfigStore.selectedConfig)
}

function isEditingActiveEmbeddingConfig() {
  return modelConfigStore.activeRole === modelConfigStore.ROLES.EMBEDDING
    && Boolean(modelConfigStore.selectedConfig?.active)
}

async function promptRebuildIndexIfVectorConfigChanged(snapshot, { previousEmbeddingIndexSignature = '' } = {}) {
  const nextEmbeddingIndexSignature = embeddingIndexSignature(snapshot?.active?.embedding)
  if (
    !snapshot
    || snapshot.role !== modelConfigStore.ROLES.EMBEDDING
    || !previousEmbeddingIndexSignature
    || previousEmbeddingIndexSignature === nextEmbeddingIndexSignature
  ) {
    return
  }

  // 只在影响向量内容的字段变化时提示；配置名称和 API Key 不会改变已有向量索引。
  try {
    await ElMessageBox.confirm(
      '向量模型或向量维度变化后，已有知识库向量索引不会自动更新。是否现在重建全部索引？',
      '建议重建知识库索引',
      {
        confirmButtonText: '立即重建',
        cancelButtonText: '稍后处理',
        type: 'warning'
      }
    )
    await searchStore.rebuildIndex()
    if (searchStore.indexError) {
      ElMessage.error(searchStore.indexError)
      return
    }
    ElMessage.success('全部索引已重建')
  } catch (err) {
    if (err !== 'cancel' && err !== 'close') {
      ElMessage.error(`重建索引失败：${err.message || err}`)
    }
  }
}

function embeddingIndexSignature(config) {
  if (!config) {
    return ''
  }
  return [
    normalizeComparableText(config.provider),
    normalizeComparableBaseUrl(config.baseUrl),
    normalizeComparableText(config.modelName),
    normalizeComparableNumber(config.embeddingDimensions)
  ].join('|')
}

function normalizeComparableText(value) {
  return String(value || '').trim()
}

function normalizeComparableBaseUrl(value) {
  return normalizeComparableText(value).replace(/\/+$/, '')
}

function normalizeComparableNumber(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? String(parsed) : ''
}

function normalizeInitialRole(role) {
  const normalized = String(role || '').trim().toUpperCase()
  return [
    modelConfigStore.ROLES.CHAT,
    modelConfigStore.ROLES.EMBEDDING,
    modelConfigStore.ROLES.VISION
  ].includes(normalized)
    ? normalized
    : ''
}

function modelIdPlaceholder() {
  return {
    [modelConfigStore.ROLES.CHAT]: '例如 qwen-plus 或 gpt-4.1-mini',
    [modelConfigStore.ROLES.EMBEDDING]: '例如 text-embedding-v4',
    [modelConfigStore.ROLES.VISION]: '例如 qwen3-vl-plus 或 gpt-4o-mini'
  }[modelConfigStore.activeRole] || '请输入模型 ID'
}

function showTemperatureField() {
  return [
    modelConfigStore.ROLES.CHAT,
    modelConfigStore.ROLES.VISION
  ].includes(modelConfigStore.activeRole)
}
</script>

<template>
  <section class="model-config-center">
    <header class="model-config-toolbar">
      <div class="model-config-toolbar__title">
        <p class="eyebrow">模型配置</p>
        <h3>{{ modelConfigStore.roleLabel }}</h3>
      </div>
      <button class="primary-button" type="button" @click="handleStartCreate">
        新建{{ modelConfigStore.roleLabel }}
      </button>
    </header>

    <div class="model-config-layout">
      <aside class="model-config-list" aria-label="模型配置列表">
        <button
          v-for="config in modelConfigStore.activeList"
          :key="config.id"
          class="model-config-item"
          :class="{ active: modelConfigStore.editingIdByRole[config.role] === config.id }"
          type="button"
          @click="handleEditConfig(config)"
        >
          <span class="model-config-item__title">
            <strong>{{ config.displayName }}</strong>
            <em v-if="config.active">ACTIVE</em>
            <em v-else-if="config.isDraft" class="model-config-item__draft">未保存</em>
          </span>
          <span>{{ config.provider }} · {{ config.modelName }}</span>
          <small>{{ config.baseUrl }}</small>
        </button>
        <p v-if="!modelConfigStore.activeList.length" class="hint-message">
          暂无配置，点击右上角新建。
        </p>
      </aside>

      <section class="model-config-editor">
        <form
          class="model-form"
          @input="modelConfigStore.markFormTouched"
          @change="modelConfigStore.markFormTouched"
          @submit.prevent="handleSave"
        >
          <label class="field">
            <span>配置名称</span>
            <el-input v-model="modelConfigStore.form.displayName" autocomplete="off" />
          </label>

          <label class="field field--provider">
            <span class="field-heading">
              <span>模型服务商</span>
            </span>
            <div class="provider-field-control">
              <el-select
                :model-value="modelConfigStore.form.provider"
                @change="modelConfigStore.changeProvider"
              >
                <el-option
                  v-for="provider in modelConfigStore.providerOptions"
                  :key="provider.value"
                  :value="provider.value"
                  :label="provider.label"
                >
                  {{ provider.label }}
                </el-option>
              </el-select>
              <span
                v-if="modelConfigStore.isEditingExisting && modelConfigStore.selectedConfig?.active"
                class="active-config-badge"
              >
                已启用
              </span>
              <button
                v-else-if="modelConfigStore.isEditingExisting"
                class="enable-config-button"
                type="button"
                :disabled="modelConfigStore.isActivating"
                @click="handleActivate"
              >
                {{ modelConfigStore.isActivating ? '启用中...' : '设为启用' }}
              </button>
            </div>
          </label>

          <label class="field field--full">
            <span>Base URL</span>
            <el-input
              v-model="modelConfigStore.form.baseUrl"
              type="url"
              autocomplete="off"
              :readonly="modelConfigStore.form.provider !== 'OPENAI_COMPATIBLE'"
              :placeholder="modelConfigStore.form.provider === 'OPENAI_COMPATIBLE'
                ? 'https://api.example.com/v1'
                : 'https://dashscope.aliyuncs.com/api/v1'"
            />
          </label>

          <label class="field field--full">
            <span>API Key</span>
            <div class="secret-input">
              <el-input
                :model-value="modelConfigStore.form.apiKey"
                :type="modelConfigStore.visibleApiKeyByRole[modelConfigStore.activeRole] ? 'text' : 'password'"
                :placeholder="modelConfigStore.apiKeyPlaceholder"
                autocomplete="off"
                @update:model-value="modelConfigStore.updateApiKey"
              />
              <el-button @click="modelConfigStore.toggleApiKeyVisible()">
                {{ modelConfigStore.visibleApiKeyByRole[modelConfigStore.activeRole] ? '隐藏' : '显示' }}
              </el-button>
              <el-button
                :disabled="!modelConfigStore.form.apiKey"
                @click="modelConfigStore.copyApiKey()"
              >
                复制
              </el-button>
              <el-button
                :disabled="!modelConfigStore.form.apiKey && !modelConfigStore.selectedConfig?.apiKeyConfigured"
                @click="modelConfigStore.clearApiKey()"
              >
                清空
              </el-button>
            </div>
          </label>

          <label class="field field--full">
            <span>模型 ID</span>
            <el-select
              ref="modelSelectRef"
              v-model="modelConfigStore.form.modelName"
              class="model-id-select"
              clearable
              filterable
              allow-create
              default-first-option
              :loading="modelConfigStore.isFetchingModels"
              loading-text="正在获取模型..."
              no-data-text="暂无模型，可手动输入模型 ID"
              :filter-method="filterModelOptions"
              popper-class="model-id-select-popper"
              :placeholder="modelIdPlaceholder()"
              @change="handleModelSelectChange"
              @visible-change="handleModelSelectVisibleChange"
            >
              <el-option
                v-for="model in filteredModelOptions"
                :key="model.id"
                :label="model.id"
                :value="model.id"
              >
                <div class="model-id-option">
                  <strong>{{ model.id }}</strong>
                  <span v-if="model.name && model.name !== model.id">{{ model.name }}</span>
                  <em>{{ modelCapabilityLabel(model.capability) }}</em>
                </div>
              </el-option>
            </el-select>
          </label>

          <label v-if="modelConfigStore.activeRole === modelConfigStore.ROLES.EMBEDDING" class="field">
            <span>向量维度</span>
            <el-input-number :model-value="1024" :controls="false" readonly />
            <small class="field-hint">当前 Lucene 向量索引固定使用 1024 维。</small>
          </label>

          <section
            v-if="modelConfigStore.activeRole === modelConfigStore.ROLES.EMBEDDING"
            class="embedding-rate-limit-panel field--full"
          >
            <div class="field-heading">
              <div class="embedding-rate-limit-title">
                <span>请求限速</span>
                <el-popover
                  placement="top-start"
                  trigger="hover"
                  popper-class="embedding-rate-limit-popover"
                  :width="380"
                  :show-after="120"
                >
                  <template #reference>
                    <button
                      class="embedding-rate-limit-help"
                      type="button"
                      aria-label="查看请求限速注意事项"
                    >
                      <CircleHelp aria-hidden="true" />
                      <span>注意事项</span>
                    </button>
                  </template>
                  <div class="embedding-rate-limit-notes">
                    <strong>请求限速说明</strong>
                    <ul>
                      <li>RPM 控制每分钟最多发送多少次 Embedding 请求。</li>
                      <li>TPM 控制 60 秒滚动窗口内的输入 Token 估算量；超过时会等待窗口释放。</li>
                      <li>Batch 是单次请求最多包含的 chunk 数，当前不会按 TPM 自动拆小批次。</li>
                      <li>如果供应商提示单请求过大或继续限流，请调小 Batch、RPM 或 TPM 后再补写索引。</li>
                    </ul>
                  </div>
                </el-popover>
              </div>
              <small>请按供应商控制台配额填写；限流不是程序 bug，系统会自动退避重试。</small>
            </div>
            <div class="embedding-rate-limit-presets" aria-label="向量模型请求限速档位">
              <button
                v-for="preset in modelConfigStore.embeddingRateLimitPresets"
                :key="preset.value"
                class="embedding-rate-limit-preset"
                :class="{ active: modelConfigStore.form.embeddingRateLimitPreset === preset.value }"
                type="button"
                @click="modelConfigStore.setEmbeddingRateLimitPreset(preset.value)"
              >
                <strong>{{ preset.label }}</strong>
                <span v-if="preset.value !== 'custom'">
                  {{ preset.requestsPerMinute }} RPM · {{ preset.tokensPerMinute }} TPM · batch {{ preset.batchSize }}
                </span>
              </button>
            </div>
            <div
              v-if="modelConfigStore.form.embeddingRateLimitPreset === 'custom'"
              class="embedding-rate-limit-custom"
            >
              <label class="field">
                <span>每分钟请求数</span>
                <el-input-number
                  v-model="modelConfigStore.form.embeddingRequestsPerMinute"
                  :min="1"
                  :max="10000"
                  controls-position="right"
                />
              </label>
              <label class="field">
                <span>每分钟 Token 数</span>
                <el-input-number
                  v-model="modelConfigStore.form.embeddingTokensPerMinute"
                  :min="1000"
                  :max="10000000"
                  :step="1000"
                  controls-position="right"
                />
              </label>
              <label class="field">
                <span>每批 chunk 数</span>
                <el-input-number
                  v-model="modelConfigStore.form.embeddingBatchSize"
                  :min="1"
                  :max="128"
                  controls-position="right"
                />
              </label>
            </div>
          </section>

          <label v-if="showTemperatureField()" class="field">
            <span>Temperature</span>
            <el-input-number v-model="modelConfigStore.form.temperature" :min="0" :max="2" :step="0.1" controls-position="right" />
          </label>

          <label v-if="modelConfigStore.activeRole === modelConfigStore.ROLES.CHAT" class="field">
            <span>默认 Top K</span>
            <el-input-number v-model="modelConfigStore.form.defaultTopK" :min="1" :max="50" controls-position="right" />
          </label>

          <label v-if="modelConfigStore.activeRole === modelConfigStore.ROLES.CHAT" class="field">
            <span>上下文长度</span>
            <div class="context-window-field">
              <el-input-number
                v-model="modelConfigStore.form.contextWindowTokens"
                :min="1024"
                :max="2000000"
                :step="1"
                controls-position="right"
              />
              <div class="context-window-presets" aria-label="上下文长度预设">
                <button
                  v-for="preset in modelConfigStore.contextWindowPresets"
                  :key="preset.value"
                  class="context-window-preset"
                  :class="{ active: modelConfigStore.form.contextWindowTokens === preset.value }"
                  type="button"
                  @click="modelConfigStore.setContextWindowTokens(preset.value)"
                >
                  {{ preset.label }}
                </button>
              </div>
            </div>
          </label>

          <div class="model-form__actions">
            <el-button type="primary" native-type="submit" :loading="modelConfigStore.isSavingModelConfig">
              {{ modelConfigStore.isEditingExisting ? '保存配置' : '确认创建' }}
            </el-button>
            <el-button v-if="modelConfigStore.isCreatingDraft" @click="handleCancelDraft">
              取消
            </el-button>
            <el-button
              :disabled="modelConfigStore.isTestingModelConfig"
              :loading="modelConfigStore.isTestingModelConfig"
              @click="modelConfigStore.testModelConfig()"
            >
              测试连接
            </el-button>
            <el-popconfirm
              v-if="modelConfigStore.isEditingExisting"
              title="删除当前模型配置？"
              confirm-button-text="删除"
              cancel-button-text="取消"
              @confirm="handleRemove"
            >
              <template #reference>
                <el-button type="danger" plain :loading="modelConfigStore.isDeleting">
                  删除
                </el-button>
              </template>
            </el-popconfirm>
            <el-button
              :disabled="modelConfigStore.isLoadingModelConfig"
              :loading="modelConfigStore.isLoadingModelConfig"
              @click="handleReload"
            >
              重新读取
            </el-button>
          </div>
        </form>

        <p v-if="modelConfigStore.error" class="error-message">{{ modelConfigStore.error }}</p>
        <p v-if="modelConfigStore.message" class="success-message">{{ modelConfigStore.message }}</p>
      </section>
    </div>

    <p class="warning-message">
      阿里百炼会使用默认 DashScope 地址；OpenAI-compatible 使用用户填写的 Base URL，并调用 Base URL + /chat/completions、/embeddings 和 /models。
      当前阶段 API Key 会以明文保存到本机 SQLite。向量模型或维度变化后，旧向量索引不会自动重建，请按需在知识库中手动重建索引。
    </p>
  </section>
</template>
