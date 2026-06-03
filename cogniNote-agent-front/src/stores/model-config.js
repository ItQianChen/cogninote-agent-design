import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  activateModelConfig as requestActivateModelConfig,
  createModelConfig as requestCreateModelConfig,
  deleteModelConfig as requestDeleteModelConfig,
  fetchModelOptions as requestFetchModelOptions,
  getActiveModelConfigs,
  listModelConfigs,
  testModelConfig as requestTestModelConfig,
  updateModelConfig as requestUpdateModelConfig
} from '../api/model-config-api'
import { useSearchStore } from './search'

const ROLES = {
  CHAT: 'CHAT',
  EMBEDDING: 'EMBEDDING'
}

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

  const activeRole = ref(ROLES.CHAT)
  const chatConfigs = ref([])
  const embeddingConfigs = ref([])
  const activeChatConfig = ref(null)
  const activeEmbeddingConfig = ref(null)
  const isLoadingModelConfig = ref(false)
  const isSavingModelConfig = ref(false)
  const isTestingModelConfig = ref(false)
  const isFetchingModels = ref(false)
  const isActivating = ref(false)
  const isDeleting = ref(false)
  const modelOptionsByRole = ref({ CHAT: [], EMBEDDING: [] })
  const modelsFetchedAtByRole = ref({ CHAT: null, EMBEDDING: null })
  const error = ref('')
  const message = ref('')
  const draftByRole = ref({
    CHAT: defaultForm(ROLES.CHAT),
    EMBEDDING: defaultForm(ROLES.EMBEDDING)
  })
  const editingIdByRole = ref({ CHAT: null, EMBEDDING: null })
  const touchedByRole = ref({ CHAT: false, EMBEDDING: false })
  const visibleApiKeyByRole = ref({ CHAT: false, EMBEDDING: false })

  const form = computed(() => draftByRole.value[activeRole.value])
  const activeConfigs = computed(() => ({
    chat: activeChatConfig.value,
    embedding: activeEmbeddingConfig.value
  }))
  const modelConfig = computed(() => activeChatConfig.value)
  const activeList = computed(() => configsByRole(activeRole.value).value)
  const selectedConfig = computed(() => {
    const selectedId = editingIdByRole.value[activeRole.value]
    return activeList.value.find(config => config.id === selectedId) || null
  })
  const activeConfigForRole = computed(() => activeRole.value === ROLES.CHAT
    ? activeChatConfig.value
    : activeEmbeddingConfig.value)
  const isEditingExisting = computed(() => Boolean(editingIdByRole.value[activeRole.value]))
  const roleLabel = computed(() => activeRole.value === ROLES.CHAT ? '对话模型' : 'Embedding 模型')
  const isOpenAiCompatible = computed(() => form.value.provider === 'OPENAI_COMPATIBLE')
  const providerLabel = computed(() => {
    return providerOptions.find(option => option.value === form.value.provider)?.label || form.value.provider
  })
  const apiKeyPlaceholder = computed(() => {
    const current = activeList.value.find(config => config.id === editingIdByRole.value[activeRole.value])
    if (current?.apiKeyConfigured) {
      return '已保存，留空表示继续使用当前 Key'
    }
    return '请输入 API Key'
  })
  const modelOptions = computed(() => modelOptionsByRole.value[activeRole.value] || [])
  const modelsFetchedAt = computed(() => modelsFetchedAtByRole.value[activeRole.value])
  const chatModelOptions = computed(() => {
    return (modelOptionsByRole.value.CHAT || [])
      .filter(model => model.capability === 'CHAT' || model.capability === 'UNKNOWN')
  })
  const embeddingModelOptions = computed(() => {
    return (modelOptionsByRole.value.EMBEDDING || [])
      .filter(model => model.capability === 'EMBEDDING' || model.capability === 'UNKNOWN')
  })

  async function fetchModelConfig({ force = true } = {}) {
    if (!force && (activeChatConfig.value || isLoadingModelConfig.value)) {
      return
    }

    isLoadingModelConfig.value = true
    error.value = ''
    message.value = ''

    try {
      const [active, chats, embeddings] = await Promise.all([
        getActiveModelConfigs(),
        listModelConfigs(ROLES.CHAT),
        listModelConfigs(ROLES.EMBEDDING)
      ])
      activeChatConfig.value = active.chat
      activeEmbeddingConfig.value = active.embedding
      chatConfigs.value = chats || []
      embeddingConfigs.value = embeddings || []
      syncPristineDrafts()
    } catch (err) {
      error.value = `模型配置读取失败：${err.message}`
    } finally {
      isLoadingModelConfig.value = false
    }
  }

  function ensureModelConfigLoaded() {
    if (activeChatConfig.value && activeEmbeddingConfig.value) {
      syncPristineDrafts()
      return Promise.resolve()
    }
    return fetchModelConfig({ force: false })
  }

  function switchRole(role) {
    activeRole.value = role
    error.value = ''
    message.value = ''
    if (!touchedByRole.value[role]) {
      syncDraftFromConfig(activeConfigForRole.value || configsByRole(role).value[0] || defaultForm(role))
    }
  }

  function startCreate(role = activeRole.value) {
    activeRole.value = role
    editingIdByRole.value[role] = null
    draftByRole.value[role] = defaultForm(role)
    touchedByRole.value[role] = true
    visibleApiKeyByRole.value[role] = false
    error.value = ''
    message.value = ''
  }

  function editConfig(config) {
    activeRole.value = config.role
    editingIdByRole.value[config.role] = config.id
    syncDraftFromConfig(config)
    touchedByRole.value[config.role] = false
    visibleApiKeyByRole.value[config.role] = false
    error.value = ''
    message.value = ''
  }

  async function saveModelConfig() {
    const searchStore = useSearchStore()
    const role = activeRole.value
    isSavingModelConfig.value = true
    error.value = ''
    message.value = ''

    try {
      const id = editingIdByRole.value[role]
      const saved = id
        ? await requestUpdateModelConfig(id, payload(role))
        : await requestCreateModelConfig(payload(role))
      await refreshRole(role)
      editConfig(saved)
      message.value = `${roleLabel.value}配置已保存`
      if (role === ROLES.EMBEDDING) {
        message.value += '。Embedding 维度或模型变化后，请按需重建索引。'
        await searchStore.fetchIndexStatus()
      }
    } catch (err) {
      error.value = `保存失败：${err.message}`
    } finally {
      isSavingModelConfig.value = false
    }
  }

  async function activateConfig(config) {
    const searchStore = useSearchStore()
    isActivating.value = true
    error.value = ''
    message.value = ''

    try {
      const activated = await requestActivateModelConfig(config.id)
      await refreshRole(activated.role)
      if (activated.role === ROLES.EMBEDDING) {
        await searchStore.fetchIndexStatus()
        message.value = 'Embedding 配置已激活。模型或维度变化后，请按需重建索引。'
      } else {
        message.value = '对话模型配置已激活'
      }
    } catch (err) {
      error.value = `激活失败：${err.message}`
    } finally {
      isActivating.value = false
    }
  }

  async function removeConfig(config) {
    isDeleting.value = true
    error.value = ''
    message.value = ''

    try {
      await requestDeleteModelConfig(config.id)
      await refreshRole(config.role)
      if (editingIdByRole.value[config.role] === config.id) {
        startCreate(config.role)
      }
      message.value = '模型配置已删除'
    } catch (err) {
      error.value = `删除失败：${err.message}`
    } finally {
      isDeleting.value = false
    }
  }

  async function fetchModels() {
    const role = activeRole.value
    isFetchingModels.value = true
    error.value = ''
    message.value = ''

    try {
      const result = await requestFetchModelOptions(payload(role))
      modelOptionsByRole.value[role] = result.models || []
      modelsFetchedAtByRole.value[role] = result.fetchedAt || Date.now()
      autoSelectModel(role)
      message.value = modelOptionsByRole.value[role].length
        ? `已获取 ${modelOptionsByRole.value[role].length} 个模型`
        : '模型列表为空，可继续手动输入模型 ID'
    } catch (err) {
      error.value = `获取模型失败：${err.message}`
    } finally {
      isFetchingModels.value = false
    }
  }

  async function testModelConfig() {
    isTestingModelConfig.value = true
    error.value = ''
    message.value = ''

    try {
      const result = await requestTestModelConfig(payload(activeRole.value))
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
    modelOptionsByRole.value[activeRole.value] = []
    markFormTouched()

    form.value.modelName = activeRole.value === ROLES.CHAT ? 'qwen-plus' : 'text-embedding-v4'
    message.value = ''
    error.value = ''
  }

  function payload(role = activeRole.value) {
    const current = draftByRole.value[role]
    return {
      role,
      provider: current.provider,
      displayName: current.displayName.trim(),
      baseUrl: current.baseUrl.trim(),
      apiKey: current.apiKey,
      modelName: current.modelName.trim(),
      chatModel: role === ROLES.CHAT ? current.modelName.trim() : undefined,
      embeddingModel: role === ROLES.EMBEDDING ? current.modelName.trim() : undefined,
      embeddingDimensions: role === ROLES.EMBEDDING ? Number(current.embeddingDimensions) : undefined,
      temperature: role === ROLES.CHAT ? Number(current.temperature) : undefined,
      defaultTopK: role === ROLES.CHAT ? Number(current.defaultTopK) : undefined,
      topK: role === ROLES.CHAT ? Number(current.defaultTopK) : undefined
    }
  }

  function autoSelectModel(role) {
    if (role !== ROLES.CHAT) {
      return
    }
    const options = chatModelOptions.value
    if (options.length && !options.some(model => model.id === draftByRole.value[role].modelName)) {
      draftByRole.value[role].modelName = options[0].id
      touchedByRole.value[role] = true
    }
  }

  function markFormTouched() {
    touchedByRole.value[activeRole.value] = true
  }

  function toggleApiKeyVisible(role = activeRole.value) {
    visibleApiKeyByRole.value[role] = !visibleApiKeyByRole.value[role]
  }

  async function copyApiKey(role = activeRole.value) {
    const apiKey = draftByRole.value[role].apiKey
    if (!apiKey) {
      error.value = '当前没有可复制的 API Key'
      return
    }
    try {
      await navigator.clipboard.writeText(apiKey)
      message.value = 'API Key 已复制'
      error.value = ''
    } catch (err) {
      error.value = `复制失败：${err.message}`
    }
  }

  async function refreshRole(role) {
    const [active, configs] = await Promise.all([
      getActiveModelConfigs(),
      listModelConfigs(role)
    ])
    activeChatConfig.value = active.chat
    activeEmbeddingConfig.value = active.embedding
    if (role === ROLES.CHAT) {
      chatConfigs.value = configs || []
    } else {
      embeddingConfigs.value = configs || []
    }
    syncPristineDrafts()
  }

  function syncPristineDrafts() {
    for (const role of Object.values(ROLES)) {
      if (!touchedByRole.value[role]) {
        syncDraftFromConfig(activeConfig(role) || configsByRole(role).value[0] || defaultForm(role))
      }
    }
  }

  function syncDraftFromConfig(config) {
    const role = config.role || activeRole.value
    draftByRole.value[role] = {
      provider: config.provider || 'DASHSCOPE',
      displayName: config.displayName || (role === ROLES.CHAT ? 'DashScope Chat' : 'DashScope Embedding'),
      baseUrl: config.baseUrl || 'https://dashscope.aliyuncs.com/api/v1',
      apiKey: config.apiKey || '',
      modelName: config.modelName || (role === ROLES.CHAT ? 'qwen-plus' : 'text-embedding-v4'),
      embeddingDimensions: config.embeddingDimensions || 1024,
      temperature: config.temperature ?? 0.7,
      defaultTopK: config.defaultTopK || 8
    }
    editingIdByRole.value[role] = config.id || null
  }

  function configsByRole(role) {
    return role === ROLES.CHAT ? chatConfigs : embeddingConfigs
  }

  function activeConfig(role) {
    return role === ROLES.CHAT ? activeChatConfig.value : activeEmbeddingConfig.value
  }

  return {
    ROLES,
    activeRole,
    chatConfigs,
    embeddingConfigs,
    activeChatConfig,
    activeEmbeddingConfig,
    activeConfigs,
    modelConfig,
    isLoadingModelConfig,
    isSavingModelConfig,
    isTestingModelConfig,
    isFetchingModels,
    isActivating,
    isDeleting,
    modelOptionsByRole,
    modelOptions,
    modelsFetchedAt,
    modelsFetchedAtByRole,
    error,
    message,
    form,
    draftByRole,
    editingIdByRole,
    visibleApiKeyByRole,
    providerOptions,
    providerLabel,
    isOpenAiCompatible,
    apiKeyPlaceholder,
    activeList,
    selectedConfig,
    activeConfigForRole,
    isEditingExisting,
    roleLabel,
    chatModelOptions,
    embeddingModelOptions,
    fetchModelConfig,
    ensureModelConfigLoaded,
    switchRole,
    startCreate,
    editConfig,
    markFormTouched,
    toggleApiKeyVisible,
    copyApiKey,
    fetchModels,
    saveModelConfig,
    testModelConfig,
    activateConfig,
    removeConfig,
    changeProvider
  }
})

function defaultForm(role) {
  return {
    role,
    provider: 'DASHSCOPE',
    displayName: role === ROLES.CHAT ? 'DashScope Chat' : 'DashScope Embedding',
    baseUrl: 'https://dashscope.aliyuncs.com/api/v1',
    apiKey: '',
    modelName: role === ROLES.CHAT ? 'qwen-plus' : 'text-embedding-v4',
    embeddingDimensions: 1024,
    temperature: 0.7,
    defaultTopK: 8
  }
}
