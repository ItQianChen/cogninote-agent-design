<script setup>
import StatGrid from '../components/stat-grid.vue'
import { useModelConfigStore } from '../stores/model-config'
import { formatTime } from '../utils/formatters'

const modelConfigStore = useModelConfigStore()
</script>

<template>
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
          :type="modelConfigStore.isApiKeyVisible ? 'text' : 'password'"
          :placeholder="modelConfigStore.apiKeyPlaceholder"
          autocomplete="off"
        />
        <button class="secondary-button" type="button" @click="modelConfigStore.toggleApiKeyVisible">
          {{ modelConfigStore.isApiKeyVisible ? '隐藏' : '显示' }}
        </button>
        <button class="secondary-button" type="button" :disabled="!modelConfigStore.form.apiKey" @click="modelConfigStore.copyApiKey">
          复制
        </button>
      </div>
    </label>

    <label class="field">
      <span>Chat 模型</span>
      <input
        v-model="modelConfigStore.form.chatModel"
        type="text"
        list="chat-model-options"
        autocomplete="off"
        placeholder="例如 qwen-plus 或 gpt-4.1-mini"
      />
      <datalist id="chat-model-options">
        <option v-for="model in modelConfigStore.chatModelOptions" :key="model.id" :value="model.id">
          {{ model.name || model.id }}
        </option>
      </datalist>
    </label>

    <label class="field">
      <span>Embedding 模型</span>
      <input
        v-model="modelConfigStore.form.embeddingModel"
        type="text"
        list="embedding-model-options"
        autocomplete="off"
        placeholder="例如 text-embedding-v4"
      />
      <datalist id="embedding-model-options">
        <option v-for="model in modelConfigStore.embeddingModelOptions" :key="model.id" :value="model.id">
          {{ model.name || model.id }}
        </option>
      </datalist>
    </label>

    <label class="field">
      <span>Embedding 维度</span>
      <input v-model="modelConfigStore.form.embeddingDimensions" type="number" min="1" max="8192" />
    </label>

    <label class="field">
      <span>Temperature</span>
      <input v-model="modelConfigStore.form.temperature" type="number" min="0" max="2" step="0.1" />
    </label>

    <label class="field">
      <span>默认 Top K</span>
      <input v-model="modelConfigStore.form.topK" type="number" min="1" max="50" />
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
        {{ modelConfigStore.isSavingModelConfig ? '保存中...' : '保存配置' }}
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
      { label: 'Provider', value: modelConfigStore.modelConfig?.provider || 'DASHSCOPE' },
      { label: 'Base URL', value: modelConfigStore.modelConfig?.baseUrl || modelConfigStore.form.baseUrl },
      { label: 'API Key', value: modelConfigStore.modelConfig?.apiKeyConfigured ? '已保存' : '未配置' },
      { label: 'Chat', value: modelConfigStore.modelConfig?.chatModel || modelConfigStore.form.chatModel },
      { label: 'Embedding', value: modelConfigStore.modelConfig?.embeddingModel || modelConfigStore.form.embeddingModel },
      { label: '更新于', value: formatTime(modelConfigStore.modelConfig?.updatedAt) }
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

  <p class="warning-message">
    阿里百炼会使用默认 DashScope 地址；OpenAI-compatible 使用用户填写的 Base URL，并调用 Base URL + /chat/completions、/embeddings 和 /models。
    当前阶段 API Key 会以明文保存到本机 SQLite，仅用于开发态闭环；后续交付阶段再接入 Windows 本地加密或凭据管理。
  </p>
</template>
