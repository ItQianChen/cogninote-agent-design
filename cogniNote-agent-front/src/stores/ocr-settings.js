import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  getOcrSettings,
  testOcrSettings,
  updateOcrSettings
} from '../api/ocr-settings-api'

const DEFAULT_SETTINGS = {
  enabled: false,
  engine: 'MODEL_VISION',
  available: false,
  visionModel: {
    id: '',
    provider: 'DASHSCOPE',
    displayName: 'DashScope Vision',
    baseUrl: 'https://dashscope.aliyuncs.com/api/v1',
    apiKeyConfigured: false,
    modelName: 'qwen3-vl-plus'
  },
  limits: {
    maxPagesPerDocument: 200,
    timeoutPerPageSeconds: 20,
    monthlyCallBudget: 1000
  }
}

export const useOcrSettingsStore = defineStore('ocrSettings', () => {
  const settings = ref(normalizeSettings(DEFAULT_SETTINGS))
  const loaded = ref(false)
  const loading = ref(false)
  const saving = ref(false)
  const testing = ref(false)
  const error = ref('')
  const message = ref('')
  const lastTestResult = ref(null)

  const credentialsReady = computed(() => Boolean(settings.value.visionModel.apiKeyConfigured))
  const canEnable = computed(() => credentialsReady.value)
  const available = computed(() => Boolean(settings.value.enabled && settings.value.available))
  const statusLabel = computed(() => {
    if (!settings.value.enabled) {
      return 'OCR 未启用'
    }
    return available.value ? '模型 OCR 可用' : '视觉模型未配置'
  })

  async function fetchSettings({ force = false } = {}) {
    if (loaded.value && !force) {
      return settings.value
    }
    loading.value = true
    error.value = ''
    try {
      settings.value = normalizeSettings(await getOcrSettings())
      loaded.value = true
      return settings.value
    } catch (err) {
      error.value = `OCR 设置读取失败：${err.message}`
      return null
    } finally {
      loading.value = false
    }
  }

  function patchSettings(patch) {
    settings.value = normalizeSettings({
      ...settings.value,
      ...patch,
      visionModel: {
        ...settings.value.visionModel,
        ...(patch.visionModel || {})
      },
      limits: {
        ...settings.value.limits,
        ...(patch.limits || {})
      }
    })
    error.value = ''
    message.value = ''
  }

  async function saveSettings() {
    saving.value = true
    error.value = ''
    message.value = ''
    try {
      settings.value = normalizeSettings(await updateOcrSettings(toPayload(settings.value)))
      loaded.value = true
      message.value = 'OCR 设置已保存'
      return settings.value
    } catch (err) {
      error.value = `保存失败：${err.message}`
      return null
    } finally {
      saving.value = false
    }
  }

  async function testSettings() {
    testing.value = true
    error.value = ''
    message.value = ''
    try {
      lastTestResult.value = await testOcrSettings()
      if (!lastTestResult.value?.success) {
        error.value = lastTestResult.value?.message || 'OCR 连接测试失败'
        return lastTestResult.value
      }
      message.value = lastTestResult.value.message || 'OCR 连接测试通过'
      return lastTestResult.value
    } catch (err) {
      error.value = `测试失败：${err.message}`
      return null
    } finally {
      testing.value = false
    }
  }

  return {
    settings,
    loaded,
    loading,
    saving,
    testing,
    error,
    message,
    lastTestResult,
    credentialsReady,
    canEnable,
    available,
    statusLabel,
    fetchSettings,
    patchSettings,
    saveSettings,
    testSettings
  }
})

function normalizeSettings(value) {
  const engine = normalizeEngine(value?.engine || value?.provider)
  const visionModel = {
    ...DEFAULT_SETTINGS.visionModel,
    ...(value?.visionModel || {})
  }
  const limits = {
    ...DEFAULT_SETTINGS.limits,
    ...(value?.limits || {})
  }
  return {
    ...DEFAULT_SETTINGS,
    ...value,
    engine,
    visionModel: {
      ...visionModel,
      id: String(visionModel.id || ''),
      provider: String(visionModel.provider || DEFAULT_SETTINGS.visionModel.provider),
      displayName: String(visionModel.displayName || DEFAULT_SETTINGS.visionModel.displayName),
      baseUrl: String(visionModel.baseUrl || DEFAULT_SETTINGS.visionModel.baseUrl),
      apiKeyConfigured: Boolean(visionModel.apiKeyConfigured),
      modelName: String(visionModel.modelName || DEFAULT_SETTINGS.visionModel.modelName)
    },
    limits: {
      maxPagesPerDocument: clampInteger(limits.maxPagesPerDocument, 1, 500, DEFAULT_SETTINGS.limits.maxPagesPerDocument),
      timeoutPerPageSeconds: clampInteger(limits.timeoutPerPageSeconds, 3, 120, DEFAULT_SETTINGS.limits.timeoutPerPageSeconds),
      monthlyCallBudget: clampInteger(limits.monthlyCallBudget, 1, 1000000, DEFAULT_SETTINGS.limits.monthlyCallBudget)
    },
    enabled: Boolean(value?.enabled),
    available: Boolean(value?.enabled && value?.available && visionModel.apiKeyConfigured)
  }
}

function toPayload(value) {
  return {
    enabled: value.enabled,
    engine: 'MODEL_VISION',
    provider: 'MODEL_VISION',
    limits: {
      maxPagesPerDocument: value.limits.maxPagesPerDocument,
      timeoutPerPageSeconds: value.limits.timeoutPerPageSeconds,
      monthlyCallBudget: value.limits.monthlyCallBudget
    }
  }
}

function normalizeEngine(engine) {
  const normalized = String(engine || '').trim().toUpperCase()
  return normalized === 'BAIDU_OCR' || !normalized ? 'MODEL_VISION' : normalized
}

function clampInteger(value, min, max, fallback) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    return fallback
  }
  return Math.min(max, Math.max(min, Math.trunc(parsed)))
}
