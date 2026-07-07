import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  getOcrSettings,
  testOcrSettings,
  updateOcrSettings
} from '../api/ocr-settings-api'

const DEFAULT_SETTINGS = {
  enabled: false,
  provider: 'BAIDU_OCR',
  available: false,
  baidu: {
    apiKey: '',
    secretKey: '',
    apiKeyConfigured: false,
    secretKeyConfigured: false,
    recognitionMode: 'STANDARD',
    languageType: 'CHN_ENG',
    detectDirection: true
  },
  limits: {
    maxPagesPerDocument: 200,
    timeoutPerPageSeconds: 20,
    monthlyCallBudget: 1000
  }
}

export const useOcrSettingsStore = defineStore('ocrSettings', () => {
  const settings = ref({ ...DEFAULT_SETTINGS, baidu: { ...DEFAULT_SETTINGS.baidu }, limits: { ...DEFAULT_SETTINGS.limits } })
  const loaded = ref(false)
  const loading = ref(false)
  const saving = ref(false)
  const testing = ref(false)
  const error = ref('')
  const message = ref('')
  const lastTestResult = ref(null)

  const credentialsReady = computed(() => Boolean(
    settings.value.baidu.apiKey.trim() && settings.value.baidu.secretKey.trim()
  ))
  const canEnable = computed(() => credentialsReady.value)
  const available = computed(() => Boolean(settings.value.enabled && settings.value.available))
  const statusLabel = computed(() => {
    if (!settings.value.enabled) {
      return 'OCR 未启用'
    }
    return available.value ? 'OCR 可用' : 'OCR 未配置'
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
      baidu: {
        ...settings.value.baidu,
        ...(patch.baidu || {})
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
  const baidu = {
    ...DEFAULT_SETTINGS.baidu,
    ...(value?.baidu || {})
  }
  const apiKey = String(baidu.apiKey || '')
  const secretKey = String(baidu.secretKey || '')
  const apiKeyConfigured = Boolean(baidu.apiKeyConfigured || apiKey.trim())
  const secretKeyConfigured = Boolean(baidu.secretKeyConfigured || secretKey.trim())
  const limits = {
    ...DEFAULT_SETTINGS.limits,
    ...(value?.limits || {})
  }
  return {
    ...DEFAULT_SETTINGS,
    ...value,
    provider: 'BAIDU_OCR',
    baidu: {
      ...baidu,
      apiKey,
      secretKey,
      apiKeyConfigured,
      secretKeyConfigured,
      recognitionMode: baidu.recognitionMode === 'ACCURATE' ? 'ACCURATE' : 'STANDARD',
      languageType: String(baidu.languageType || DEFAULT_SETTINGS.baidu.languageType).toUpperCase(),
      detectDirection: baidu.detectDirection !== false
    },
    limits: {
      maxPagesPerDocument: clampInteger(limits.maxPagesPerDocument, 1, 500, DEFAULT_SETTINGS.limits.maxPagesPerDocument),
      timeoutPerPageSeconds: clampInteger(limits.timeoutPerPageSeconds, 3, 120, DEFAULT_SETTINGS.limits.timeoutPerPageSeconds),
      monthlyCallBudget: clampInteger(limits.monthlyCallBudget, 1, 1000000, DEFAULT_SETTINGS.limits.monthlyCallBudget)
    },
    enabled: Boolean(value?.enabled && apiKey.trim() && secretKey.trim()),
    available: Boolean(value?.available && apiKey.trim() && secretKey.trim())
  }
}

function toPayload(value) {
  return {
    enabled: value.enabled,
    provider: value.provider,
    baidu: {
      apiKey: value.baidu.apiKey,
      secretKey: value.baidu.secretKey,
      recognitionMode: value.baidu.recognitionMode,
      languageType: value.baidu.languageType,
      detectDirection: value.baidu.detectDirection
    },
    limits: {
      maxPagesPerDocument: value.limits.maxPagesPerDocument,
      timeoutPerPageSeconds: value.limits.timeoutPerPageSeconds,
      monthlyCallBudget: value.limits.monthlyCallBudget
    }
  }
}

function clampInteger(value, min, max, fallback) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    return fallback
  }
  return Math.min(max, Math.max(min, Math.trunc(parsed)))
}
