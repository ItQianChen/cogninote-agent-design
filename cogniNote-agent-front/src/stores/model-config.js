import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  activateSettingsModelConfig,
  createSettingsModelConfig,
  deleteSettingsModelConfig,
  fetchModelOptions as requestFetchModelOptions,
  getActiveModelConfigs,
  getModelConfigSettings,
  testModelConfig as requestTestModelConfig,
  updateSettingsModelConfig
} from '../api/model-config-api'
import { useSearchStore } from './search'

const ROLES = {
  CHAT: 'CHAT',
  EMBEDDING: 'EMBEDDING',
  VISION: 'VISION'
}

const FIXED_EMBEDDING_DIMENSIONS = 1024
const DEFAULT_CONTEXT_WINDOW_TOKENS = 128000
const MIN_CONTEXT_WINDOW_TOKENS = 1024
const MAX_CONTEXT_WINDOW_TOKENS = 2000000
const MODEL_OPTIONS_CACHE_TTL_MS = 3 * 60 * 1000
const DEFAULT_EMBEDDING_RATE_LIMIT_PRESET = 'standard'
const EMBEDDING_RATE_LIMIT_PRESETS = [
  { value: 'conservative', label: '保守', requestsPerMinute: 60, tokensPerMinute: 100000, batchSize: 8 },
  { value: 'standard', label: '标准', requestsPerMinute: 300, tokensPerMinute: 300000, batchSize: 16 },
  { value: 'fast', label: '快速', requestsPerMinute: 1000, tokensPerMinute: 800000, batchSize: 32 },
  { value: 'custom', label: '自定义', requestsPerMinute: 300, tokensPerMinute: 300000, batchSize: 16 }
]

/**
 * 模型设置页的多角色编辑状态。
 *
 * Chat、Embedding 和 Vision 共用一套表单结构，但 active 配置、模型列表和 API Key 可见性必须按角色隔离。
 */
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
      label: 'OpenAI-compatible',
      baseUrl: '',
      displayName: 'OpenAI-compatible'
    }
  ]

  const activeRole = ref(ROLES.CHAT)
  const activeSummary = ref({ chat: null, embedding: null, vision: null })
  const roleState = ref({
    CHAT: emptyRoleState(ROLES.CHAT),
    EMBEDDING: emptyRoleState(ROLES.EMBEDDING),
    VISION: emptyRoleState(ROLES.VISION)
  })
  const modelOptionsByRole = ref({ CHAT: [], EMBEDDING: [], VISION: [] })
  const modelsFetchedAtByRole = ref({ CHAT: null, EMBEDDING: null, VISION: null })
  const modelOptionsSignatureByRole = ref({ CHAT: '', EMBEDDING: '', VISION: '' })
  const visibleApiKeyByRole = ref({ CHAT: false, EMBEDDING: false, VISION: false })
  const isFetchingModels = ref(false)
  const isTestingModelConfig = ref(false)
  const message = ref('')
  const contextWindowPresets = [
    { label: '32K', value: 32000 },
    { label: '64K', value: 64000 },
    { label: '128K', value: DEFAULT_CONTEXT_WINDOW_TOKENS },
    { label: '200K', value: 200000 },
    { label: '1M', value: 1000000 }
  ]

  const activeChatConfig = computed(() => activeSummary.value.chat)
  const activeEmbeddingConfig = computed(() => activeSummary.value.embedding)
  const activeVisionConfig = computed(() => activeSummary.value.vision)
  const chatConfigs = computed(() => roleState.value.CHAT.configs)
  const embeddingConfigs = computed(() => roleState.value.EMBEDDING.configs)
  const visionConfigs = computed(() => roleState.value.VISION.configs)
  const activeConfigs = computed(() => ({
    chat: activeChatConfig.value,
    embedding: activeEmbeddingConfig.value,
    vision: activeVisionConfig.value
  }))
  const modelConfig = computed(() => activeChatConfig.value)
  const currentState = computed(() => roleState.value[activeRole.value])
  const form = computed(() => currentState.value.form)
  const activeList = computed(() => currentState.value.configs)
  const selectedConfig = computed(() => currentState.value.selectedConfig)
  const activeConfigForRole = computed(() => activeRole.value === ROLES.CHAT
    ? activeChatConfig.value
    : activeRole.value === ROLES.EMBEDDING
      ? activeEmbeddingConfig.value
      : activeVisionConfig.value)
  const editingIdByRole = computed(() => ({
    CHAT: roleState.value.CHAT.selectedConfig?.id || null,
    EMBEDDING: roleState.value.EMBEDDING.selectedConfig?.id || null,
    VISION: roleState.value.VISION.selectedConfig?.id || null
  }))
  const isEditingExisting = computed(() => Boolean(selectedConfig.value?.id))
  const roleLabel = computed(() => roleDisplayName(activeRole.value))
  const isOpenAiCompatible = computed(() => form.value.provider === 'OPENAI_COMPATIBLE')
  const providerLabel = computed(() => {
    return providerOptions.find(option => option.value === form.value.provider)?.label || form.value.provider
  })
  const apiKeyPlaceholder = computed(() => {
    return selectedConfig.value?.apiKeyConfigured
      ? '已保存，留空表示继续使用当前 Key'
      : '请输入 API Key'
  })
  const modelOptions = computed(() => modelOptionsByRole.value[activeRole.value] || [])
  const modelsFetchedAt = computed(() => modelsFetchedAtByRole.value[activeRole.value])
  const chatModelOptions = computed(() => {
    return sortedModelOptionsForRole(modelOptionsByRole.value.CHAT || [], ROLES.CHAT)
  })
  const embeddingModelOptions = computed(() => {
    return sortedModelOptionsForRole(modelOptionsByRole.value.EMBEDDING || [], ROLES.EMBEDDING)
  })
  const visionModelOptions = computed(() => {
    return sortedModelOptionsForRole(modelOptionsByRole.value.VISION || [], ROLES.VISION)
  })
  const isLoadingModelConfig = computed(() => currentState.value.loading)
  const isSavingModelConfig = computed(() => currentState.value.saving)
  const isActivating = computed(() => currentState.value.activating)
  const isDeleting = computed(() => currentState.value.deleting)
  const error = computed(() => currentState.value.error)

  async function fetchModelConfig() {
    await refreshActiveSummary()
  }

  async function ensureModelConfigLoaded() {
    if (!activeSummary.value.chat || !activeSummary.value.embedding || !activeSummary.value.vision) {
      await refreshActiveSummary()
    }
  }

  async function enterModelSettings() {
    activeRole.value = ROLES.CHAT
    return await loadRoleSettings(ROLES.CHAT, { showMask: true })
  }

  async function initializeEditor() {
    if (!currentState.value.loaded) {
      return await loadRoleSettings(activeRole.value, { showMask: true })
    }
    return roleSnapshot(activeRole.value)
  }

  async function reloadEditor() {
    return await loadRoleSettings(activeRole.value, { showMask: true })
  }

  async function switchRole(role) {
    const normalizedRole = normalizeRoleValue(role)
    activeRole.value = normalizedRole
    message.value = ''
    return await loadRoleSettings(normalizedRole, { showMask: true })
  }

  function selectActiveConfig(role = activeRole.value) {
    activeRole.value = role
    applySelectedConfig(role, activeConfigFor(role))
  }

  function startCreate(role = activeRole.value) {
    const normalizedRole = normalizeRoleValue(role)
    activeRole.value = normalizedRole
    const state = roleState.value[normalizedRole]
    state.selectedConfig = null
    replaceEditorForm(normalizedRole, defaultForm(normalizedRole))
    invalidateModelOptionsIfStale(normalizedRole)
    state.error = ''
    state.dirty = false
    state.revision += 1
    visibleApiKeyByRole.value[normalizedRole] = false
    message.value = ''
  }

  function editConfig(config) {
    if (!config) {
      return
    }
    const role = normalizeRoleValue(config.role)
    activeRole.value = role
    applySelectedConfig(role, normalizeConfigForRole(config, role))
    roleState.value[role].error = ''
    visibleApiKeyByRole.value[role] = false
    message.value = ''
  }

  function markFormTouched() {
    currentState.value.dirty = true
    currentState.value.revision += 1
  }

  async function saveModelConfig(formOverride = null) {
    const role = activeRole.value
    const state = roleState.value[role]
    const searchStore = useSearchStore()
    state.saving = true
    state.error = ''
    message.value = ''
    const submittedRevision = state.revision
    const submittedApiKeyRevision = state.apiKeyRevision

    try {
      const payload = formPayload(role, formOverride)
      const snapshot = state.selectedConfig?.id
        ? await updateSettingsModelConfig(state.selectedConfig.id, payload)
        : await createSettingsModelConfig(payload)
      applySnapshot(snapshot, {
        preserveDraftRole: state.revision === submittedRevision ? null : role
      })
      clearSubmittedApiKeyDraft(state, submittedApiKeyRevision)
      message.value = `${roleLabel.value}配置已保存`
      if (role === ROLES.EMBEDDING) {
        // 向量模型变更会影响检索可用性，保存后立刻刷新索引状态提示。
        await searchStore.fetchIndexStatus()
      }
      return snapshot
    } catch (err) {
      state.error = `保存失败：${err.message}`
      return null
    } finally {
      state.saving = false
    }
  }

  async function activateConfig(config) {
    if (!config) {
      return
    }
    const role = config.role
    const state = roleState.value[role]
    const searchStore = useSearchStore()
    state.activating = true
    state.error = ''
    message.value = ''

    try {
      const snapshot = await activateSettingsModelConfig(config.id)
      applySnapshot(snapshot)
      if (role === ROLES.EMBEDDING) {
        await searchStore.fetchIndexStatus()
        message.value = '向量模型配置已启用'
      } else if (role === ROLES.VISION) {
        message.value = '视觉识别模型配置已启用'
      } else {
        message.value = '对话模型配置已启用'
      }
      return snapshot
    } catch (err) {
      state.error = `启用失败：${err.message}`
      return null
    } finally {
      state.activating = false
    }
  }

  async function removeConfig(config) {
    if (!config) {
      return
    }
    const role = config.role
    const state = roleState.value[role]
    state.deleting = true
    state.error = ''
    message.value = ''

    try {
      const snapshot = await deleteSettingsModelConfig(config.id)
      applySnapshot(snapshot)
      message.value = '模型配置已删除'
      return snapshot
    } catch (err) {
      state.error = `删除失败：${err.message}`
      return null
    } finally {
      state.deleting = false
    }
  }

  async function fetchModels(formOverride = null) {
    const role = activeRole.value
    const state = roleState.value[role]
    const signature = modelOptionsSignatureForRole(role, formOverride)
    isFetchingModels.value = true
    state.error = ''
    message.value = ''
    invalidateModelOptions(role)

    try {
      const result = await requestFetchModelOptions(formPayload(role, formOverride))
      modelOptionsByRole.value[role] = result.models || []
      modelsFetchedAtByRole.value[role] = result.fetchedAt || Date.now()
      modelOptionsSignatureByRole.value[role] = signature
      autoSelectModel(role)
      const availableModelCount = modelOptionsCountForRole(role)
      message.value = availableModelCount
        ? `已获取 ${availableModelCount} 个模型`
        : '模型列表为空，可继续手动输入模型 ID'
    } catch (err) {
      state.error = `获取模型失败：${err.message}`
    } finally {
      isFetchingModels.value = false
    }
  }

  function hasFreshModelOptions(role = activeRole.value, formOverride = null) {
    const fetchedAt = Number(modelsFetchedAtByRole.value[role])
    return Boolean(fetchedAt)
      && modelOptionsSignatureByRole.value[role] === modelOptionsSignatureForRole(role, formOverride)
      && Date.now() - fetchedAt <= MODEL_OPTIONS_CACHE_TTL_MS
  }

  async function testModelConfig(formOverride = null) {
    const role = activeRole.value
    const state = roleState.value[role]
    isTestingModelConfig.value = true
    state.error = ''
    message.value = ''

    try {
      const result = await requestTestModelConfig(formPayload(role, formOverride))
      message.value = result.message || '模型连接测试成功'
    } catch (err) {
      state.error = `连接测试失败：${err.message}`
    } finally {
      isTestingModelConfig.value = false
    }
  }

  function changeProvider(provider) {
    const currentForm = form.value
    const normalizedProvider = normalizeProviderValue(provider, currentForm.baseUrl)
    const option = providerOptions.find(item => item.value === normalizedProvider)
    if (!option) {
      return
    }
    const role = activeRole.value
    const state = roleState.value[role]
    const previousProvider = currentForm.provider
    const nextModelName = previousProvider === option.value
      ? currentForm.modelName
      : ''
    replaceEditorForm(role, {
      ...currentForm,
      role,
      provider: option.value,
      displayName: option.displayName,
      baseUrl: option.baseUrl,
      modelName: nextModelName,
      embeddingDimensions: role === ROLES.EMBEDDING ? FIXED_EMBEDDING_DIMENSIONS : null,
      contextWindowTokens: role === ROLES.CHAT
        ? normalizeContextWindowTokens(currentForm.contextWindowTokens)
        : null,
      temperature: isGenerationRole(role)
        ? normalizeTemperature(currentForm.temperature, role)
        : null
    })
    invalidateModelOptions(role)
    state.dirty = true
    state.revision += 1
    state.error = ''
    message.value = ''
  }

  function toggleApiKeyVisible(role = activeRole.value) {
    visibleApiKeyByRole.value[role] = !visibleApiKeyByRole.value[role]
  }

  function updateApiKey(value, role = activeRole.value) {
    const state = roleState.value[role]
    state.form.apiKey = String(value || '')
    if (state.form.apiKey.trim()) {
      state.form.clearApiKey = false
    }
    state.dirty = true
    state.revision += 1
    state.apiKeyRevision += 1
  }

  function clearApiKey(role = activeRole.value) {
    const state = roleState.value[role]
    state.form.apiKey = ''
    state.form.clearApiKey = true
    state.dirty = true
    state.revision += 1
    state.apiKeyRevision += 1
    state.error = ''
    message.value = '保存后将清空当前 API Key'
  }

  async function copyApiKey(formOverride = null, role = activeRole.value) {
    const apiKey = formOverride
      ? formOverride.apiKey
      : role === activeRole.value
        ? form.value.apiKey
        : roleState.value[role].form.apiKey
    if (!apiKey) {
      roleState.value[role].error = '当前没有可复制的 API Key'
      return
    }
    try {
      await navigator.clipboard.writeText(apiKey)
      message.value = 'API Key 已复制'
      roleState.value[role].error = ''
    } catch (err) {
      roleState.value[role].error = `复制失败：${err.message}`
    }
  }

  async function refreshActiveSummary() {
    const active = await getActiveModelConfigs()
    activeSummary.value = {
      chat: normalizeConfigForRole(active?.chat, ROLES.CHAT),
      embedding: normalizeConfigForRole(active?.embedding, ROLES.EMBEDDING),
      vision: normalizeConfigForRole(active?.vision, ROLES.VISION)
    }
  }

  async function loadRoleSettings(role, { showMask = false } = {}) {
    const state = roleState.value[role]
    if (showMask) {
      state.loading = true
    }
    state.error = ''
    message.value = ''

    try {
      const snapshot = await getModelConfigSettings(role)
      applySnapshot(snapshot, { preserveDraftRole: role })
      return snapshot
    } catch (err) {
      state.error = `模型配置读取失败：${err.message}`
      return null
    } finally {
      state.loading = false
    }
  }

  function applySnapshot(snapshot, { preserveDraftRole = null } = {}) {
    if (!snapshot) {
      return
    }
    // 后端一次返回 active 摘要和当前角色列表，前端必须同步更新，避免标签页切换时显示旧选中项。
    activeSummary.value = {
      chat: normalizeConfigForRole(snapshot.active?.chat, ROLES.CHAT),
      embedding: normalizeConfigForRole(snapshot.active?.embedding, ROLES.EMBEDDING),
      vision: normalizeConfigForRole(snapshot.active?.vision, ROLES.VISION)
    }
    const role = normalizeRoleValue(snapshot.role || activeRole.value)
    activeRole.value = role
    const state = roleState.value[role]
    const preserveDraft = preserveDraftRole === role && state.dirty
    state.configs = (snapshot.configs || []).map(config => normalizeConfigForRole(config, role))
    state.selectedConfig = snapshot.selectedConfig ? normalizeConfigForRole(snapshot.selectedConfig, role) : null
    if (!preserveDraft) {
      replaceEditorForm(role, snapshot.selectedConfig
        ? formFromConfig(state.selectedConfig)
        : defaultForm(role))
    }
    invalidateModelOptionsIfStale(role)
    state.loaded = true
    state.error = ''
    state.dirty = preserveDraft
    state.revision += 1
  }

  function applySelectedConfig(role, config) {
    const normalizedRole = normalizeRoleValue(role)
    const state = roleState.value[normalizedRole]
    const normalizedConfig = config ? normalizeConfigForRole(config, normalizedRole) : null
    state.selectedConfig = normalizedConfig
    replaceEditorForm(normalizedRole, normalizedConfig ? formFromConfig(normalizedConfig) : defaultForm(normalizedRole))
    invalidateModelOptionsIfStale(normalizedRole)
    state.dirty = false
    state.revision += 1
  }

  function formPayload(role = activeRole.value, formOverride = null) {
    const current = formOverride || roleState.value[role].form
    return {
      role,
      provider: normalizeProviderValue(current.provider, current.baseUrl),
      displayName: current.displayName.trim(),
      baseUrl: current.baseUrl.trim(),
      apiKey: current.apiKey,
      clearApiKey: Boolean(current.clearApiKey),
      modelName: current.modelName.trim(),
      embeddingDimensions: role === ROLES.EMBEDDING ? FIXED_EMBEDDING_DIMENSIONS : undefined,
      embeddingRequestsPerMinute: role === ROLES.EMBEDDING
        ? normalizeEmbeddingRequestsPerMinute(current.embeddingRequestsPerMinute)
        : undefined,
      embeddingTokensPerMinute: role === ROLES.EMBEDDING
        ? normalizeEmbeddingTokensPerMinute(current.embeddingTokensPerMinute)
        : undefined,
      embeddingBatchSize: role === ROLES.EMBEDDING
        ? normalizeEmbeddingBatchSize(current.embeddingBatchSize)
        : undefined,
      temperature: isGenerationRole(role) ? Number(current.temperature) : undefined,
      defaultTopK: role === ROLES.CHAT ? Number(current.defaultTopK) : undefined,
      contextWindowTokens: role === ROLES.CHAT
        ? normalizeContextWindowTokens(current.contextWindowTokens)
        : undefined
    }
  }

  function modelOptionsSignatureForRole(role = activeRole.value, formOverride = null) {
    const current = formOverride || roleState.value[role].form
    const selectedConfigId = roleState.value[role].selectedConfig?.id || 'new'
    return [
      role,
      selectedConfigId,
      normalizeProviderValue(current.provider, current.baseUrl),
      String(current.baseUrl || '').trim()
    ].join('|')
  }

  function setContextWindowTokens(value, role = activeRole.value) {
    if (role !== ROLES.CHAT) {
      return
    }
    replaceEditorForm(role, {
      ...roleState.value[role].form,
      contextWindowTokens: normalizeContextWindowTokens(value)
    })
    roleState.value[role].dirty = true
    roleState.value[role].revision += 1
  }

  function setEmbeddingRateLimitPreset(presetValue, role = activeRole.value) {
    if (role !== ROLES.EMBEDDING) {
      return
    }
    const preset = EMBEDDING_RATE_LIMIT_PRESETS.find(item => item.value === presetValue)
    if (!preset) {
      return
    }
    replaceEditorForm(role, {
      ...roleState.value[role].form,
      embeddingRateLimitPreset: preset.value,
      embeddingRequestsPerMinute: preset.requestsPerMinute,
      embeddingTokensPerMinute: preset.tokensPerMinute,
      embeddingBatchSize: preset.batchSize
    })
    roleState.value[role].dirty = true
    roleState.value[role].revision += 1
  }

  function autoSelectModel(role) {
    if (!isGenerationRole(role)) {
      return
    }
    const options = role === ROLES.CHAT ? chatModelOptions.value : visionModelOptions.value
    const state = roleState.value[role]
    if (options.length && !state.form.modelName) {
      replaceEditorForm(role, {
        ...state.form,
        modelName: options[0].id
      })
    }
  }

  function modelOptionsCountForRole(role) {
    return role === ROLES.CHAT
      ? chatModelOptions.value.length
      : role === ROLES.EMBEDDING
        ? embeddingModelOptions.value.length
        : visionModelOptions.value.length
  }

  function activeConfigFor(role) {
    if (role === ROLES.CHAT) {
      return activeSummary.value.chat
    }
    if (role === ROLES.EMBEDDING) {
      return activeSummary.value.embedding
    }
    return activeSummary.value.vision
  }

  function replaceEditorForm(role, nextForm) {
    // 每个 role 只保留一份编辑表单。组件通过 computed form 读取当前 role，
    // 避免“全局表单”和“role 表单”互相覆盖导致 Provider select 停在旧值。
    const normalizedForm = normalizeFormForRole(nextForm, role)
    roleState.value[role].form = { ...normalizedForm }
  }

  function invalidateModelOptions(role) {
    modelOptionsByRole.value[role] = []
    modelsFetchedAtByRole.value[role] = null
    modelOptionsSignatureByRole.value[role] = ''
  }

  function invalidateModelOptionsIfStale(role) {
    if (!hasFreshModelOptions(role)) {
      invalidateModelOptions(role)
    }
  }

  function roleSnapshot(role) {
    const state = roleState.value[role]
    return {
      active: activeSummary.value,
      role,
      configs: state.configs,
      selectedConfig: state.selectedConfig
    }
  }

  return {
    ROLES,
    activeRole,
    activeSummary,
    roleState,
    chatConfigs,
    embeddingConfigs,
    visionConfigs,
    activeChatConfig,
    activeEmbeddingConfig,
    activeVisionConfig,
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
    modelOptionsSignatureByRole,
    error,
    message,
    form,
    editingIdByRole,
    visibleApiKeyByRole,
    contextWindowPresets,
    embeddingRateLimitPresets: EMBEDDING_RATE_LIMIT_PRESETS,
    providerOptions,
    providerLabel,
    isOpenAiCompatible,
    apiKeyPlaceholder,
    activeList,
    selectedConfig,
    activeConfigFor,
    activeConfigForRole,
    isEditingExisting,
    roleLabel,
    chatModelOptions,
    embeddingModelOptions,
    visionModelOptions,
    fetchModelConfig,
    ensureModelConfigLoaded,
    enterModelSettings,
    initializeEditor,
    reloadEditor,
    selectActiveConfig,
    switchRole,
    startCreate,
    editConfig,
    markFormTouched,
    toggleApiKeyVisible,
    updateApiKey,
    clearApiKey,
    setContextWindowTokens,
    setEmbeddingRateLimitPreset,
    formatContextWindowTokens,
    copyApiKey,
    fetchModels,
    hasFreshModelOptions,
    saveModelConfig,
    testModelConfig,
    activateConfig,
    removeConfig,
    changeProvider
  }
})

function emptyRoleState(role) {
  return {
    configs: [],
    selectedConfig: null,
    form: defaultForm(role),
    loaded: false,
    loading: false,
    saving: false,
    deleting: false,
    activating: false,
    error: '',
    dirty: false,
    revision: 0,
    apiKeyRevision: 0
  }
}

function clearSubmittedApiKeyDraft(state, submittedApiKeyRevision) {
  if (state.apiKeyRevision !== submittedApiKeyRevision) {
    return
  }
  state.form.apiKey = ''
  state.form.clearApiKey = false
}

function defaultForm(role) {
  return {
    role,
    provider: 'DASHSCOPE',
    displayName: role === ROLES.CHAT
      ? 'DashScope Chat'
      : role === ROLES.EMBEDDING
        ? 'DashScope 向量模型'
        : 'DashScope Vision',
    baseUrl: 'https://dashscope.aliyuncs.com/api/v1',
    apiKey: '',
    clearApiKey: false,
    modelName: role === ROLES.VISION ? 'qwen3-vl-plus' : '',
    embeddingDimensions: role === ROLES.EMBEDDING ? FIXED_EMBEDDING_DIMENSIONS : null,
    embeddingRateLimitPreset: role === ROLES.EMBEDDING ? DEFAULT_EMBEDDING_RATE_LIMIT_PRESET : null,
    embeddingRequestsPerMinute: role === ROLES.EMBEDDING ? 300 : null,
    embeddingTokensPerMinute: role === ROLES.EMBEDDING ? 300000 : null,
    embeddingBatchSize: role === ROLES.EMBEDDING ? 16 : null,
    temperature: role === ROLES.CHAT ? 0.7 : role === ROLES.VISION ? 0.0 : null,
    defaultTopK: role === ROLES.CHAT ? 8 : null,
    contextWindowTokens: role === ROLES.CHAT ? DEFAULT_CONTEXT_WINDOW_TOKENS : null
  }
}

function formFromConfig(config) {
  const role = normalizeRoleValue(config.role)
  const defaults = defaultForm(role)
  const provider = normalizeProviderValue(config.provider, config.baseUrl)
  return {
    role,
    provider,
    displayName: config.displayName || defaults.displayName,
    baseUrl: config.baseUrl || defaults.baseUrl,
    apiKey: config.apiKey || '',
    clearApiKey: false,
    modelName: config.modelName || '',
    embeddingDimensions: role === ROLES.EMBEDDING
      ? FIXED_EMBEDDING_DIMENSIONS
      : null,
    embeddingRateLimitPreset: role === ROLES.EMBEDDING
      ? inferEmbeddingRateLimitPreset(config)
      : null,
    embeddingRequestsPerMinute: role === ROLES.EMBEDDING
      ? normalizeEmbeddingRequestsPerMinute(config.embeddingRequestsPerMinute ?? defaults.embeddingRequestsPerMinute)
      : null,
    embeddingTokensPerMinute: role === ROLES.EMBEDDING
      ? normalizeEmbeddingTokensPerMinute(config.embeddingTokensPerMinute ?? defaults.embeddingTokensPerMinute)
      : null,
    embeddingBatchSize: role === ROLES.EMBEDDING
      ? normalizeEmbeddingBatchSize(config.embeddingBatchSize ?? defaults.embeddingBatchSize)
      : null,
    temperature: isGenerationRole(role)
      ? normalizeTemperature(config.temperature ?? defaults.temperature, role)
      : null,
    defaultTopK: role === ROLES.CHAT
      ? (config.defaultTopK ?? defaults.defaultTopK)
      : null,
    contextWindowTokens: role === ROLES.CHAT
      ? normalizeContextWindowTokens(config.contextWindowTokens ?? defaults.contextWindowTokens)
      : null
  }
}

function normalizeRoleValue(role) {
  const normalized = String(role || '').trim().toUpperCase()
  if (normalized === ROLES.EMBEDDING || normalized === ROLES.VISION) {
    return normalized
  }
  return ROLES.CHAT
}

function normalizeConfigForRole(config, role = normalizeRoleValue(config?.role)) {
  if (!config) {
    return null
  }
  return {
    ...config,
    role,
    provider: normalizeProviderValue(config.provider, config.baseUrl),
    embeddingDimensions: role === ROLES.EMBEDDING ? FIXED_EMBEDDING_DIMENSIONS : null,
    embeddingRateLimitPreset: role === ROLES.EMBEDDING ? inferEmbeddingRateLimitPreset(config) : null,
    embeddingRequestsPerMinute: role === ROLES.EMBEDDING
      ? normalizeEmbeddingRequestsPerMinute(config.embeddingRequestsPerMinute)
      : null,
    embeddingTokensPerMinute: role === ROLES.EMBEDDING
      ? normalizeEmbeddingTokensPerMinute(config.embeddingTokensPerMinute)
      : null,
    embeddingBatchSize: role === ROLES.EMBEDDING
      ? normalizeEmbeddingBatchSize(config.embeddingBatchSize)
      : null,
    contextWindowTokens: role === ROLES.CHAT
      ? normalizeContextWindowTokens(config.contextWindowTokens)
      : null,
    temperature: isGenerationRole(role)
      ? normalizeTemperature(config.temperature, role)
      : null
  }
}

function normalizeFormForRole(nextForm, role = normalizeRoleValue(nextForm?.role)) {
  const defaults = defaultForm(role)
  const provider = normalizeProviderValue(nextForm?.provider, nextForm?.baseUrl)
  return {
    role,
    provider,
    displayName: nextForm?.displayName || defaults.displayName,
    baseUrl: nextForm?.baseUrl ?? defaults.baseUrl,
    apiKey: nextForm?.apiKey || '',
    clearApiKey: Boolean(nextForm?.clearApiKey),
    modelName: nextForm?.modelName || '',
    embeddingDimensions: role === ROLES.EMBEDDING ? FIXED_EMBEDDING_DIMENSIONS : null,
    embeddingRateLimitPreset: role === ROLES.EMBEDDING
      ? normalizeEmbeddingRateLimitPreset(nextForm?.embeddingRateLimitPreset, nextForm)
      : null,
    embeddingRequestsPerMinute: role === ROLES.EMBEDDING
      ? normalizeEmbeddingRequestsPerMinute(nextForm?.embeddingRequestsPerMinute ?? defaults.embeddingRequestsPerMinute)
      : null,
    embeddingTokensPerMinute: role === ROLES.EMBEDDING
      ? normalizeEmbeddingTokensPerMinute(nextForm?.embeddingTokensPerMinute ?? defaults.embeddingTokensPerMinute)
      : null,
    embeddingBatchSize: role === ROLES.EMBEDDING
      ? normalizeEmbeddingBatchSize(nextForm?.embeddingBatchSize ?? defaults.embeddingBatchSize)
      : null,
    temperature: isGenerationRole(role)
      ? normalizeTemperature(nextForm?.temperature ?? defaults.temperature, role)
      : null,
    defaultTopK: role === ROLES.CHAT ? (nextForm?.defaultTopK ?? defaults.defaultTopK) : null,
    contextWindowTokens: role === ROLES.CHAT
      ? normalizeContextWindowTokens(nextForm?.contextWindowTokens ?? defaults.contextWindowTokens)
      : null
  }
}

function inferEmbeddingRateLimitPreset(config) {
  return normalizeEmbeddingRateLimitPreset(config?.embeddingRateLimitPreset, config)
}

function normalizeEmbeddingRateLimitPreset(presetValue, source = {}) {
  const normalized = String(presetValue || '').trim()
  if (EMBEDDING_RATE_LIMIT_PRESETS.some(preset => preset.value === normalized)) {
    return normalized
  }
  const rpm = normalizeEmbeddingRequestsPerMinute(source?.embeddingRequestsPerMinute)
  const tpm = normalizeEmbeddingTokensPerMinute(source?.embeddingTokensPerMinute)
  const batch = normalizeEmbeddingBatchSize(source?.embeddingBatchSize)
  return EMBEDDING_RATE_LIMIT_PRESETS.find(preset =>
    preset.value !== 'custom'
    && preset.requestsPerMinute === rpm
    && preset.tokensPerMinute === tpm
    && preset.batchSize === batch
  )?.value || 'custom'
}

function normalizeEmbeddingRequestsPerMinute(value) {
  return normalizeIntegerInRange(value, 300, 1, 10000)
}

function normalizeEmbeddingTokensPerMinute(value) {
  return normalizeIntegerInRange(value, 300000, 1000, 10000000)
}

function normalizeEmbeddingBatchSize(value) {
  return normalizeIntegerInRange(value, 16, 1, 128)
}

function normalizeTemperature(value, role) {
  const fallback = role === ROLES.VISION ? 0.0 : 0.7
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    return fallback
  }
  return Math.min(2, Math.max(0, parsed))
}

function normalizeIntegerInRange(value, fallback, min, max) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    return fallback
  }
  return Math.min(max, Math.max(min, Math.trunc(parsed)))
}

function normalizeContextWindowTokens(value) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    return DEFAULT_CONTEXT_WINDOW_TOKENS
  }
  return Math.min(
    MAX_CONTEXT_WINDOW_TOKENS,
    Math.max(MIN_CONTEXT_WINDOW_TOKENS, Math.trunc(parsed))
  )
}

function formatContextWindowTokens(value) {
  const normalized = normalizeContextWindowTokens(value)
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

function normalizeProviderValue(provider, baseUrl = '') {
  const normalized = String(provider || '').trim().toUpperCase()
  if (normalized === 'OPENAI_COMPATIBLE' || normalized === 'OPENAI' || normalized.includes('OPENAI')) {
    return 'OPENAI_COMPATIBLE'
  }
  if (normalized === 'DASHSCOPE' || normalized.includes('DASH')) {
    return 'DASHSCOPE'
  }

  // 旧数据或手动导入数据可能只有自定义 Base URL。非 DashScope 地址按 OpenAI-compatible 渲染，
  // 避免 <select> 因未知 value 回落显示第一个 DashScope 选项。
  const normalizedBaseUrl = String(baseUrl || '').trim().toLowerCase()
  if (normalizedBaseUrl && !normalizedBaseUrl.includes('dashscope.aliyuncs.com')) {
    return 'OPENAI_COMPATIBLE'
  }
  return 'DASHSCOPE'
}

function sortedModelOptionsForRole(options, role) {
  return [...options].sort((left, right) => {
    const rankDiff = capabilityRank(left.capability, role) - capabilityRank(right.capability, role)
    if (rankDiff !== 0) {
      return rankDiff
    }
    return String(left.id || '').localeCompare(String(right.id || ''))
  })
}

function capabilityRank(capability, role) {
  if (role === ROLES.EMBEDDING) {
    return {
      EMBEDDING: 0,
      UNKNOWN: 1,
      CHAT: 2,
      VISION: 3
    }[capability] ?? 1
  }
  if (role === ROLES.VISION) {
    return {
      VISION: 0,
      UNKNOWN: 1,
      CHAT: 2,
      EMBEDDING: 3
    }[capability] ?? 1
  }
  return {
    CHAT: 0,
    UNKNOWN: 1,
    VISION: 2,
    EMBEDDING: 3
  }[capability] ?? 1
}

function isGenerationRole(role) {
  return role === ROLES.CHAT || role === ROLES.VISION
}

function roleDisplayName(role) {
  return {
    CHAT: '对话模型',
    EMBEDDING: '向量模型',
    VISION: '视觉识别模型'
  }[role] || '对话模型'
}
