<script setup>
import { computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useModelConfigStore } from '../stores/model-config'
import { useSearchStore } from '../stores/search'
import { formatTime } from '../utils/formatters'

const props = defineProps({
  initialRole: {
    type: String,
    default: ''
  }
})

const modelConfigStore = useModelConfigStore()
const searchStore = useSearchStore()
const apiKeyConfigured = computed(() => {
  return Boolean(modelConfigStore.form.apiKey || modelConfigStore.selectedConfig?.apiKeyConfigured)
})
const apiKeySummary = computed(() => apiKeyConfigured.value ? '已配置' : '未配置')
const updatedAtSummary = computed(() => {
  return formatTime(modelConfigStore.activeConfigForRole?.updatedAt)
})

watch(
  () => props.initialRole,
  () => loadInitialSettings(),
  { immediate: true }
)

function roleActiveConfig(role) {
  return role === modelConfigStore.ROLES.CHAT
    ? modelConfigStore.activeChatConfig
    : modelConfigStore.activeEmbeddingConfig
}

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

function handleEditConfig(config) {
  modelConfigStore.editConfig(config)
}

async function handleReload() {
  await modelConfigStore.reloadEditor()
}

async function handleSave() {
  const snapshot = await modelConfigStore.saveModelConfig()
  await promptRebuildIndexIfVectorConfigChanged(snapshot)
}

async function handleActivate() {
  const snapshot = await modelConfigStore.activateConfig(modelConfigStore.selectedConfig)
  await promptRebuildIndexIfVectorConfigChanged(snapshot)
}

async function handleRemove() {
  await modelConfigStore.removeConfig(modelConfigStore.selectedConfig)
}

function providerLabel(provider) {
  return modelConfigStore.providerOptions.find(option => option.value === provider)?.label || provider
}

async function promptRebuildIndexIfVectorConfigChanged(snapshot) {
  if (!snapshot || snapshot.role !== modelConfigStore.ROLES.EMBEDDING) {
    return
  }

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

function normalizeInitialRole(role) {
  const normalized = String(role || '').trim().toUpperCase()
  return [modelConfigStore.ROLES.CHAT, modelConfigStore.ROLES.EMBEDDING].includes(normalized)
    ? normalized
    : ''
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

    <section class="model-active-summary">
      <article
        v-for="role in [modelConfigStore.ROLES.CHAT, modelConfigStore.ROLES.EMBEDDING]"
        :key="role"
      >
        <p class="eyebrow">{{ role === modelConfigStore.ROLES.CHAT ? '当前对话模型' : '当前向量模型' }}</p>
        <h3>{{ roleActiveConfig(role)?.displayName || '-' }}</h3>
        <p>{{ roleActiveConfig(role)?.modelName || '-' }}</p>
          <small>{{ roleActiveConfig(role)?.baseUrl || '-' }}</small>
          <small v-if="role === modelConfigStore.ROLES.CHAT">
            上下文 {{ modelConfigStore.formatContextWindowTokens(roleActiveConfig(role)?.contextWindowTokens) }}
          </small>
      </article>
    </section>

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

          <label class="field">
            <span class="field-heading">
              <span>Provider</span>
              <button
                v-if="modelConfigStore.isEditingExisting"
                class="enable-config-button"
                type="button"
                :disabled="modelConfigStore.selectedConfig?.active || modelConfigStore.isActivating"
                @click="handleActivate"
              >
                {{ modelConfigStore.selectedConfig?.active ? '已启用' : (modelConfigStore.isActivating ? '启用中...' : '启用') }}
              </button>
            </span>
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
                v-model="modelConfigStore.form.apiKey"
                :type="modelConfigStore.visibleApiKeyByRole[modelConfigStore.activeRole] ? 'text' : 'password'"
                :placeholder="modelConfigStore.apiKeyPlaceholder"
                autocomplete="off"
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
            </div>
          </label>

          <label class="field field--full">
            <span>模型 ID</span>
            <el-input
              v-model="modelConfigStore.form.modelName"
              :list="modelConfigStore.activeRole === modelConfigStore.ROLES.CHAT
                ? 'chat-model-options'
                : 'embedding-model-options'"
              autocomplete="off"
              :placeholder="modelConfigStore.activeRole === modelConfigStore.ROLES.CHAT
                ? '例如 qwen-plus 或 gpt-4.1-mini'
                : '例如 text-embedding-v4'"
            />
            <datalist id="chat-model-options">
              <option v-for="model in modelConfigStore.chatModelOptions" :key="model.id" :value="model.id">
                {{ model.name || model.id }}
              </option>
            </datalist>
            <datalist id="embedding-model-options">
              <option v-for="model in modelConfigStore.embeddingModelOptions" :key="model.id" :value="model.id">
                {{ model.name || model.id }}
              </option>
            </datalist>
          </label>

          <label v-if="modelConfigStore.activeRole === modelConfigStore.ROLES.EMBEDDING" class="field">
            <span>向量维度</span>
            <el-input-number :model-value="1024" :controls="false" readonly />
            <small class="field-hint">当前 Lucene 向量索引固定使用 1024 维。</small>
          </label>

          <label v-if="modelConfigStore.activeRole === modelConfigStore.ROLES.CHAT" class="field">
            <span>Temperature</span>
            <el-input-number v-model="modelConfigStore.form.temperature" :min="0" :max="2" :step="0.1" controls-position="right" />
          </label>

          <label v-if="modelConfigStore.activeRole === modelConfigStore.ROLES.CHAT" class="field">
            <span>默认 Top K</span>
            <el-input-number v-model="modelConfigStore.form.defaultTopK" :min="1" :max="50" controls-position="right" />
          </label>

          <label v-if="modelConfigStore.activeRole === modelConfigStore.ROLES.CHAT" class="field field--full">
            <span>上下文长度</span>
            <div class="context-window-field">
              <el-input-number
                v-model="modelConfigStore.form.contextWindowTokens"
                :min="1024"
                :max="2000000"
                :step="1000"
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
            <el-button
              :disabled="modelConfigStore.isFetchingModels"
              :loading="modelConfigStore.isFetchingModels"
              @click="modelConfigStore.fetchModels()"
            >
              获取模型
            </el-button>
            <el-button type="primary" native-type="submit" :loading="modelConfigStore.isSavingModelConfig">
              {{ modelConfigStore.isEditingExisting ? '保存配置' : '创建配置' }}
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

        <section class="model-config-summary" aria-label="当前模型配置摘要">
          <div class="model-config-summary__header">
            <div>
              <p class="eyebrow">当前配置</p>
              <h3>{{ modelConfigStore.form.displayName || modelConfigStore.roleLabel }}</h3>
            </div>
            <span class="model-config-summary__badge" :class="{ 'is-ready': apiKeyConfigured }">
              API Key {{ apiKeySummary }}
            </span>
          </div>

          <dl class="model-config-summary__meta">
            <div>
              <dt>类型</dt>
              <dd>{{ modelConfigStore.roleLabel }}</dd>
            </div>
            <div>
              <dt>Provider</dt>
              <dd :title="providerLabel(modelConfigStore.form.provider)">
                {{ providerLabel(modelConfigStore.form.provider) }}
              </dd>
            </div>
            <div>
              <dt>更新于</dt>
              <dd>{{ updatedAtSummary }}</dd>
            </div>
          </dl>

          <dl class="model-config-summary__details">
            <div>
              <dt>Base URL</dt>
              <dd class="path-text" :title="modelConfigStore.form.baseUrl || '-'">
                {{ modelConfigStore.form.baseUrl || '-' }}
              </dd>
            </div>
            <div>
              <dt>模型 ID</dt>
              <dd :title="modelConfigStore.form.modelName || '-'">
                {{ modelConfigStore.form.modelName || '-' }}
              </dd>
            </div>
            <div v-if="modelConfigStore.activeRole === modelConfigStore.ROLES.CHAT">
              <dt>上下文窗口</dt>
              <dd>
                {{ modelConfigStore.formatContextWindowTokens(modelConfigStore.form.contextWindowTokens) }}
              </dd>
            </div>
          </dl>
        </section>

        <section v-if="modelConfigStore.modelOptions.length" class="model-options-panel">
          <div class="section-title-line">
            <h3>模型列表</h3>
            <span>{{ modelConfigStore.modelOptions.length }} 个模型</span>
          </div>
          <div class="model-options-grid">
            <span
              v-for="model in modelConfigStore.modelOptions"
              :key="model.id"
              class="model-option-chip"
            >
              <strong>{{ model.name || model.id }}</strong>
              <em>{{ model.capability }}</em>
            </span>
          </div>
        </section>
      </section>
    </div>

    <p class="warning-message">
      阿里百炼会使用默认 DashScope 地址；OpenAI-compatible 使用用户填写的 Base URL，并调用 Base URL + /chat/completions、/embeddings 和 /models。
      当前阶段 API Key 会以明文保存到本机 SQLite。向量模型或维度变化后，旧向量索引不会自动重建，请按需在知识库中手动重建索引。
    </p>
  </section>
</template>
