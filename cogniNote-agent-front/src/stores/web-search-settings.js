import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  getWebSearchSettings,
  testWebSearchSettings,
  updateWebSearchSettings
} from '../api/web-search-settings-api'

const DEFAULT_SETTINGS = {
  enabled: false,
  provider: 'EXA',
  apiKey: '',
  apiKeyConfigured: false,
  maxResults: 5,
  maxCallsPerTurn: 2,
  timeoutMs: 10000,
  searchMode: 'auto'
}

/**
 * 联网搜索全局设置 Store。
 * <p>API Key 明文只短暂存在于输入框编辑态，保存成功后立即清空。</p>
 */
export const useWebSearchSettingsStore = defineStore('webSearchSettings', () => {
  const settings = ref({ ...DEFAULT_SETTINGS })
  const loaded = ref(false)
  const loading = ref(false)
  const saving = ref(false)
  const testing = ref(false)
  const error = ref('')
  const message = ref('')
  const lastTestResult = ref(null)
  let draftRevision = 0
  let apiKeyRevision = 0

  const canEnable = computed(() => Boolean(settings.value.apiKeyConfigured || settings.value.apiKey.trim()))
  const available = computed(() => Boolean(settings.value.enabled && settings.value.apiKeyConfigured))
  const statusLabel = computed(() => {
    if (!settings.value.enabled) {
      return '联网未启用'
    }
    if (!settings.value.apiKeyConfigured && settings.value.apiKey.trim()) {
      return '待保存'
    }
    return settings.value.apiKeyConfigured ? '联网可用' : '联网未配置'
  })

  async function fetchSettings({ force = false } = {}) {
    if (loaded.value && !force) {
      return settings.value
    }
    loading.value = true
    error.value = ''
    try {
      const remoteSettings = normalizeSettings(await getWebSearchSettings())
      settings.value = draftRevision > 0
        ? mergeRemoteWithDraft(remoteSettings, settings.value)
        : remoteSettings
      loaded.value = true
      return settings.value
    } catch (err) {
      error.value = `联网搜索设置读取失败：${err.message}`
      return null
    } finally {
      loading.value = false
    }
  }

  function patchSettings(patch) {
    settings.value = normalizeSettings({
      ...settings.value,
      ...patch
    }, { keepDraftApiKey: true })
    draftRevision += 1
    if (Object.prototype.hasOwnProperty.call(patch, 'apiKey')) {
      apiKeyRevision += 1
    }
    error.value = ''
    message.value = ''
  }

  async function saveSettings() {
    const submittedRevision = draftRevision
    const submittedApiKeyRevision = apiKeyRevision
    saving.value = true
    error.value = ''
    message.value = ''
    try {
      const payload = {
        enabled: settings.value.enabled,
        provider: settings.value.provider,
        apiKey: settings.value.apiKey,
        maxResults: settings.value.maxResults,
        maxCallsPerTurn: settings.value.maxCallsPerTurn,
        timeoutMs: settings.value.timeoutMs,
        searchMode: settings.value.searchMode
      }
      const remoteSettings = normalizeSettings(await updateWebSearchSettings(payload))
      if (draftRevision === submittedRevision) {
        settings.value = remoteSettings
        draftRevision = 0
      } else {
        // 普通字段与密钥分别跟踪；旧响应不能覆盖新编辑，也不能保留已提交的明文密钥。
        settings.value = mergeRemoteWithDraft(remoteSettings, settings.value, {
          keepDraftApiKey: apiKeyRevision !== submittedApiKeyRevision
        })
      }
      loaded.value = true
      message.value = '联网搜索设置已保存'
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
      lastTestResult.value = await testWebSearchSettings()
      if (!lastTestResult.value?.success) {
        error.value = lastTestResult.value?.message || '联网搜索测试失败'
        return lastTestResult.value
      }
      message.value = `联网搜索测试通过，返回 ${lastTestResult.value.resultCount || 0} 条结果`
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
    canEnable,
    available,
    statusLabel,
    fetchSettings,
    patchSettings,
    saveSettings,
    testSettings
  }
})

function normalizeSettings(value, { keepDraftApiKey = false } = {}) {
  const apiKey = keepDraftApiKey ? String(value?.apiKey || '') : ''
  const apiKeyConfigured = Boolean(value?.apiKeyConfigured)
  return {
    ...DEFAULT_SETTINGS,
    ...value,
    apiKey,
    provider: 'EXA',
    enabled: Boolean(value?.enabled && (apiKeyConfigured || apiKey.trim())),
    apiKeyConfigured,
    maxResults: clampInteger(value?.maxResults, 1, 10, DEFAULT_SETTINGS.maxResults),
    maxCallsPerTurn: clampInteger(value?.maxCallsPerTurn, 1, 3, DEFAULT_SETTINGS.maxCallsPerTurn),
    timeoutMs: clampInteger(value?.timeoutMs, 1000, 30000, DEFAULT_SETTINGS.timeoutMs),
    searchMode: String(value?.searchMode || '').toLowerCase() === 'fast' ? 'fast' : 'auto'
  }
}

function mergeRemoteWithDraft(remoteSettings, draftSettings, { keepDraftApiKey = true } = {}) {
  return normalizeSettings({
    ...remoteSettings,
    ...draftSettings,
    apiKey: keepDraftApiKey ? draftSettings.apiKey : '',
    apiKeyConfigured: remoteSettings.apiKeyConfigured
  }, { keepDraftApiKey })
}

function clampInteger(value, min, max, fallback) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    return fallback
  }
  return Math.min(max, Math.max(min, Math.trunc(parsed)))
}
