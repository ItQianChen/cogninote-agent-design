import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  fetchModelOptions as requestFetchModelOptions,
  getModelConfig,
  saveModelConfig as requestSaveModelConfig,
  testModelConfig as requestTestModelConfig
} from '../api/model-config-api'
import { useSearchStore } from './search'

export const useModelConfigStore = defineStore('modelConfig', () => {
  const providerOptions = [
    {
      value: 'DASHSCOPE',
      label: '阿里百炼 DashScope',
      baseUrl: 'https://dashscope.aliyuncs.com/api/v1',
      displayName: 'DashScope'
    },
    {
      value: 'OPENAI_COMPATIBLE',
      label: 'OpenAI-compatible Completions',
      baseUrl: '',
      displayName: 'OpenAI-compatible Completions'
    }
  ]
  const modelConfig = ref(null)
  const isLoadingModelConfig = ref(false)
  const isSavingModelConfig = ref(false)
  const isTestingModelConfig = ref(false)
  const isFetchingModels = ref(false)
  const modelOptions = ref([])
  const modelsFetchedAt = ref(null)
  const error = ref('')
  const message = ref('')
  const form = ref(defaultForm())
  const isFormTouched = ref(false)
  const isApiKeyVisible = ref(false)

  const apiKeyPlaceholder = computed(() => {
    if (modelConfig.value?.apiKeyConfigured) {
      return '已保存，留空表示继续使用当前 Key'
    }
    return '请输入 API Key'
  })

  const providerLabel = computed(() => {
    return providerOptions.find(option => option.value === form.value.provider)?.label || form.value.provider
  })

  const isOpenAiCompatible = computed(() => form.value.provider === 'OPENAI_COMPATIBLE')

  async function fetchModelConfig({ force = true } = {}) {
    if (!force && (modelConfig.value || isLoadingModelConfig.value)) {
      return
    }

    isLoadingModelConfig.value = true
    error.value = ''
    message.value = ''

    try {
      const config = await getModelConfig()
      modelConfig.value = config
      syncFormFromConfig(config)
    } catch (err) {
      error.value = `模型配置读取失败：${err.message}`
    } finally {
      isLoadingModelConfig.value = false
    }
  }

  function ensureModelConfigLoaded() {
    // 设置页首次切到“模型”时只补齐缺失数据。
    // 这里不能强制刷新，否则异步失败或重复挂载会把用户正在编辑的表单状态打掉。
    if (modelConfig.value) {
      syncFormFromConfigIfPristine()
      return Promise.resolve()
    }
    return fetchModelConfig({ force: false })
  }

  async function fetchModels() {
    isFetchingModels.value = true
    error.value = ''
    message.value = ''

    try {
      const result = await requestFetchModelOptions(payload())
      modelOptions.value = result.models || []
      modelsFetchedAt.value = result.fetchedAt || Date.now()
      autoSelectModels()
      message.value = modelOptions.value.length
        ? `已获取 ${modelOptions.value.length} 个模型`
        : '模型列表为空，可继续手动输入模型 ID'
    } catch (err) {
      error.value = `获取模型失败：${err.message}`
    } finally {
      isFetchingModels.value = false
    }
  }

  async function saveModelConfig() {
    const searchStore = useSearchStore()
    isSavingModelConfig.value = true
    error.value = ''
    message.value = ''

    try {
      const saved = await requestSaveModelConfig(payload())
      modelConfig.value = saved
      syncFormFromConfig(saved)
      message.value = '模型配置已保存'
      await searchStore.fetchIndexStatus()
    } catch (err) {
      error.value = `保存失败：${err.message}`
    } finally {
      isSavingModelConfig.value = false
    }
  }

  async function testModelConfig() {
    isTestingModelConfig.value = true
    error.value = ''
    message.value = ''

    try {
      const result = await requestTestModelConfig(payload())
      message.value = result.message || '模型连接测试成功'
    } catch (err) {
      error.value = `连接测试失败：${err.message}`
    } finally {
      isTestingModelConfig.value = false
    }
  }

  function changeProvider(provider) {
    const option = providerOptions.find(item => item.value === provider)
    if (!option) {
      return
    }

    form.value.provider = option.value
    form.value.displayName = option.displayName
    form.value.baseUrl = option.baseUrl
    isFormTouched.value = true
    modelOptions.value = []

    // 切换协议时模型 ID 的可用范围也跟着变。
    // 先回到保守默认值，用户再通过“获取模型”或手动输入覆盖。
    form.value.chatModel = 'qwen-plus'
    form.value.embeddingModel = 'text-embedding-v4'
    message.value = ''
    error.value = ''
  }

  function payload() {
    return {
      provider: form.value.provider,
      displayName: form.value.displayName.trim(),
      baseUrl: form.value.baseUrl.trim(),
      apiKey: form.value.apiKey,
      chatModel: form.value.chatModel.trim(),
      embeddingModel: form.value.embeddingModel.trim(),
      embeddingDimensions: Number(form.value.embeddingDimensions),
      temperature: Number(form.value.temperature),
      topK: Number(form.value.topK)
    }
  }

  function autoSelectModels() {
    const chatModels = chatModelOptions.value
    // Chat 模型可从 /models 返回的候选中兜底；Embedding 第一版允许用户手动填，
    // 因为不少服务不会在模型列表里准确标注 embedding capability。
    if (chatModels.length && !chatModels.some(model => model.id === form.value.chatModel)) {
      form.value.chatModel = chatModels[0].id
      isFormTouched.value = true
    }
  }

  function markFormTouched() {
    isFormTouched.value = true
  }

  function syncFormFromConfigIfPristine() {
    if (!isFormTouched.value && modelConfig.value) {
      syncFormFromConfig(modelConfig.value)
    }
  }

  function syncFormFromConfig(config) {
    form.value = {
      provider: config.provider || 'DASHSCOPE',
      displayName: config.displayName || 'DashScope',
      baseUrl: config.baseUrl || defaultForm().baseUrl,
      apiKey: config.apiKey || '',
      chatModel: config.chatModel || defaultForm().chatModel,
      embeddingModel: config.embeddingModel || defaultForm().embeddingModel,
      embeddingDimensions: config.embeddingDimensions || defaultForm().embeddingDimensions,
      temperature: config.temperature ?? defaultForm().temperature,
      topK: config.topK || defaultForm().topK
    }
    isFormTouched.value = false
    isApiKeyVisible.value = false
  }

  function toggleApiKeyVisible() {
    isApiKeyVisible.value = !isApiKeyVisible.value
  }

  async function copyApiKey() {
    if (!form.value.apiKey) {
      error.value = '当前没有可复制的 API Key'
      return
    }
    try {
      await navigator.clipboard.writeText(form.value.apiKey)
      message.value = 'API Key 已复制'
      error.value = ''
    } catch (err) {
      error.value = `复制失败：${err.message}`
    }
  }

  const chatModelOptions = computed(() => {
    return modelOptions.value.filter(model => model.capability === 'CHAT' || model.capability === 'UNKNOWN')
  })

  const embeddingModelOptions = computed(() => {
    return modelOptions.value.filter(model => model.capability === 'EMBEDDING')
  })

  return {
    modelConfig,
    isLoadingModelConfig,
    isSavingModelConfig,
    isTestingModelConfig,
    isFetchingModels,
    modelOptions,
    modelsFetchedAt,
    error,
    message,
    form,
    isFormTouched,
    isApiKeyVisible,
    providerOptions,
    providerLabel,
    isOpenAiCompatible,
    apiKeyPlaceholder,
    chatModelOptions,
    embeddingModelOptions,
    fetchModelConfig,
    ensureModelConfigLoaded,
    markFormTouched,
    toggleApiKeyVisible,
    copyApiKey,
    fetchModels,
    saveModelConfig,
    testModelConfig,
    changeProvider
  }
})

function defaultForm() {
  return {
    provider: 'DASHSCOPE',
    displayName: 'DashScope',
    baseUrl: 'https://dashscope.aliyuncs.com/api/v1',
    apiKey: '',
    chatModel: 'qwen-plus',
    embeddingModel: 'text-embedding-v4',
    embeddingDimensions: 1024,
    temperature: 0.7,
    topK: 8
  }
}
