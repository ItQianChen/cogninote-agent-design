<script setup>
import StatGrid from '../components/stat-grid.vue'
import { useModelConfigStore } from '../stores/model-config'
import { formatTime } from '../utils/formatters'

const modelConfigStore = useModelConfigStore()

function roleConfigs(role) {
  return role === modelConfigStore.ROLES.CHAT
    ? modelConfigStore.chatConfigs
    : modelConfigStore.embeddingConfigs
}

function roleActiveConfig(role) {
  return role === modelConfigStore.ROLES.CHAT
    ? modelConfigStore.activeChatConfig
    : modelConfigStore.activeEmbeddingConfig
}
</script>

<template>
  <section class="model-config-center">
    <header class="model-config-toolbar">
      <div class="model-role-tabs" role="tablist" aria-label="模型配置类型">
        <button
          type="button"
          :class="{ active: modelConfigStore.activeRole === modelConfigStore.ROLES.CHAT }"
          @click="modelConfigStore.switchRole(modelConfigStore.ROLES.CHAT)"
        >
          对话模型
        </button>
        <button
          type="button"
          :class="{ active: modelConfigStore.activeRole === modelConfigStore.ROLES.EMBEDDING }"
          @click="modelConfigStore.switchRole(modelConfigStore.ROLES.EMBEDDING)"
        >
          Embedding 模型
        </button>
      </div>
      <button class="primary-button" type="button" @click="modelConfigStore.startCreate()">
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
          @click="modelConfigStore.editConfig(config)"
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
          @submit.prevent="modelConfigStore.saveModelConfig"
        >
          <label class="field">
            <span>配置名称</span>
            <input v-model="modelConfigStore.form.displayName" type="text" autocomplete="off" />
          </label>

          <label class="field">
            <span>Provider</span>
            <select
              v-model="modelConfigStore.form.provider"
              @change="modelConfigStore.changeProvider(modelConfigStore.form.provider)"
            >
              <option
                v-for="provider in modelConfigStore.providerOptions"
                :key="provider.value"
                :value="provider.value"
              >
                {{ provider.label }}
              </option>
            </select>
          </label>

          <label class="field field--full">
            <span>Base URL</span>
            <input
              v-model="modelConfigStore.form.baseUrl"
              type="url"
              autocomplete="off"
              :readonly="!modelConfigStore.isOpenAiCompatible"
              :placeholder="modelConfigStore.isOpenAiCompatible
                ? 'https://api.example.com/v1'
                : 'https://dashscope.aliyuncs.com/api/v1'"
            />
          </label>

          <label class="field field--full">
            <span>API Key</span>
            <div class="secret-input">
              <input
                v-model="modelConfigStore.form.apiKey"
                :type="modelConfigStore.visibleApiKeyByRole[modelConfigStore.activeRole] ? 'text' : 'password'"
                :placeholder="modelConfigStore.apiKeyPlaceholder"
                autocomplete="off"
              />
              <button class="secondary-button" type="button" @click="modelConfigStore.toggleApiKeyVisible()">
                {{ modelConfigStore.visibleApiKeyByRole[modelConfigStore.activeRole] ? '隐藏' : '显示' }}
              </button>
              <button
                class="secondary-button"
                type="button"
                :disabled="!modelConfigStore.form.apiKey"
                @click="modelConfigStore.copyApiKey()"
              >
                复制
              </button>
            </div>
          </label>

          <label class="field field--full">
            <span>模型 ID</span>
            <input
              v-model="modelConfigStore.form.modelName"
              type="text"
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
            <span>Embedding 维度</span>
            <input v-model="modelConfigStore.form.embeddingDimensions" type="number" min="1" max="8192" />
          </label>

          <label v-if="modelConfigStore.activeRole === modelConfigStore.ROLES.CHAT" class="field">
            <span>Temperature</span>
            <input v-model="modelConfigStore.form.temperature" type="number" min="0" max="2" step="0.1" />
          </label>

          <label v-if="modelConfigStore.activeRole === modelConfigStore.ROLES.CHAT" class="field">
            <span>默认 Top K</span>
            <input v-model="modelConfigStore.form.defaultTopK" type="number" min="1" max="50" />
          </label>

          <div class="model-form__actions">
            <button
              class="secondary-button"
              type="button"
              :disabled="modelConfigStore.isFetchingModels"
              @click="modelConfigStore.fetchModels"
            >
              {{ modelConfigStore.isFetchingModels ? '获取中...' : '获取模型' }}
            </button>
            <button class="primary-button" type="submit" :disabled="modelConfigStore.isSavingModelConfig">
              {{ modelConfigStore.isSavingModelConfig ? '保存中...' : (modelConfigStore.isEditingExisting ? '保存配置' : '创建配置') }}
            </button>
            <button
              class="secondary-button"
              type="button"
              :disabled="modelConfigStore.isTestingModelConfig"
              @click="modelConfigStore.testModelConfig"
            >
              {{ modelConfigStore.isTestingModelConfig ? '测试中...' : '测试连接' }}
            </button>
            <button
              v-if="modelConfigStore.isEditingExisting && !modelConfigStore.selectedConfig?.active"
              class="secondary-button"
              type="button"
              :disabled="modelConfigStore.isActivating"
              @click="modelConfigStore.activateConfig(modelConfigStore.selectedConfig)"
            >
              激活
            </button>
            <button
              v-if="modelConfigStore.isEditingExisting"
              class="secondary-button danger-button"
              type="button"
              :disabled="modelConfigStore.isDeleting"
              @click="modelConfigStore.removeConfig(modelConfigStore.selectedConfig)"
            >
              删除
            </button>
            <button
              class="secondary-button"
              type="button"
              :disabled="modelConfigStore.isLoadingModelConfig"
              @click="modelConfigStore.fetchModelConfig"
            >
              重新读取
            </button>
          </div>
        </form>

        <p v-if="modelConfigStore.error" class="error-message">{{ modelConfigStore.error }}</p>
        <p v-if="modelConfigStore.message" class="success-message">{{ modelConfigStore.message }}</p>

        <StatGrid
          class="config-summary"
          :columns="6"
          :items="[
            { label: '当前类型', value: modelConfigStore.roleLabel },
            { label: 'Provider', value: modelConfigStore.providerLabel },
            { label: 'Base URL', value: modelConfigStore.form.baseUrl, mono: true },
            { label: 'API Key', value: modelConfigStore.form.apiKey ? '已填写' : '未配置' },
            { label: '模型', value: modelConfigStore.form.modelName },
            { label: '更新于', value: formatTime(modelConfigStore.activeConfigForRole?.updatedAt) }
          ]"
        />

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

    <section class="model-active-summary">
      <article
        v-for="role in [modelConfigStore.ROLES.CHAT, modelConfigStore.ROLES.EMBEDDING]"
        :key="role"
      >
        <p class="eyebrow">{{ role === modelConfigStore.ROLES.CHAT ? 'Active Chat' : 'Active Embedding' }}</p>
        <h3>{{ roleActiveConfig(role)?.displayName || '-' }}</h3>
        <p>{{ roleActiveConfig(role)?.modelName || '-' }}</p>
        <small>{{ roleActiveConfig(role)?.baseUrl || '-' }}</small>
      </article>
    </section>

    <p class="warning-message">
      阿里百炼会使用默认 DashScope 地址；OpenAI-compatible 使用用户填写的 Base URL，并调用 Base URL + /chat/completions、/embeddings 和 /models。
      当前阶段 API Key 会以明文保存到本机 SQLite。Embedding 模型或维度变化后，旧向量索引不会自动重建，请按需在知识库中手动重建索引。
    </p>
  </section>
</template>
