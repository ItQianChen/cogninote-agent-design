import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getChatSettings, updateChatSettings } from '../api/chat-settings-api'

const MODES = {
  AUTO: 'AUTO',
  ALWAYS: 'ALWAYS',
  OFF: 'OFF'
}

/**
 * 定义聊天设置 Pinia Store。
 * <p>设置持久化在后端 SQLite，前端只维护当前页面编辑态和异步状态。</p>
 */
export const useChatSettingsStore = defineStore('chatSettings', () => {
  const queryContextualizerMode = ref(MODES.AUTO)
  const loaded = ref(false)
  const loading = ref(false)
  const saving = ref(false)
  const error = ref('')
  const message = ref('')

  const queryContextualizerModeOptions = [
    {
      value: MODES.AUTO,
      label: '自动',
      description: '推荐。只有像追问或检索较弱时才补全检索问题。'
    },
    {
      value: MODES.ALWAYS,
      label: '始终',
      description: '每轮知识库问答都先判断是否需要补全，准确性更稳但更慢。'
    },
    {
      value: MODES.OFF,
      label: '关闭',
      description: '不补全追问，成本最低，但“继续/给个例子”等追问可能检索不准。'
    }
  ]

  const activeModeOption = computed(() => {
    return queryContextualizerModeOptions.find(option => option.value === queryContextualizerMode.value)
      || queryContextualizerModeOptions[0]
  })

  /**
   * 读取聊天设置。
   * <p>进入设置页时调用，确保展示值来自后端持久化配置。</p>
   */
  async function fetchSettings({ force = false } = {}) {
    if (loaded.value && !force) {
      return { queryContextualizerMode: queryContextualizerMode.value }
    }
    loading.value = true
    error.value = ''
    message.value = ''
    try {
      const settings = await getChatSettings()
      queryContextualizerMode.value = normalizeMode(settings?.queryContextualizerMode)
      loaded.value = true
      return settings
    } catch (err) {
      error.value = `聊天设置读取失败：${err.message}`
      return null
    } finally {
      loading.value = false
    }
  }

  /**
   * 更新页面中的追问补全模式。
   * <p>这里只改变编辑态，真正持久化由 saveSettings 完成。</p>
   */
  function setQueryContextualizerMode(mode) {
    queryContextualizerMode.value = normalizeMode(mode)
    error.value = ''
    message.value = ''
  }

  /**
   * 保存聊天设置。
   * <p>保存成功后后端立即使用新模式控制知识库检索 query 补全。</p>
   */
  async function saveSettings() {
    saving.value = true
    error.value = ''
    message.value = ''
    try {
      const settings = await updateChatSettings({
        queryContextualizerMode: queryContextualizerMode.value
      })
      queryContextualizerMode.value = normalizeMode(settings?.queryContextualizerMode)
      loaded.value = true
      message.value = '追问补全策略已保存'
      return settings
    } catch (err) {
      error.value = `保存失败：${err.message}`
      return null
    } finally {
      saving.value = false
    }
  }

  return {
    MODES,
    queryContextualizerMode,
    queryContextualizerModeOptions,
    activeModeOption,
    loaded,
    loading,
    saving,
    error,
    message,
    fetchSettings,
    saveSettings,
    setQueryContextualizerMode
  }
})

/**
 * 规范化追问补全模式。
 * <p>前端异常状态统一回到 AUTO，后端仍会做最终校验。</p>
 */
function normalizeMode(mode) {
  const normalized = String(mode || '').trim().toUpperCase()
  return Object.values(MODES).includes(normalized) ? normalized : MODES.AUTO
}
