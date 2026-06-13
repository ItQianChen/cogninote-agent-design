import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  UPDATE_CHANNELS,
  checkDesktopUpdate,
  installDesktopUpdate,
  listenDesktopUpdateProgress
} from '../api/desktop-update-api'
import { isTauriRuntime } from '../api/desktop-api'

const UPDATE_CHANNEL_STORAGE_KEY = 'cogninote-update-channel'
const DEFAULT_UPDATE_CHANNEL = 'stable'

/**
 * 管理桌面自动更新状态。
 *
 * <p>更新检查只在 Tauri 桌面环境可用；浏览器开发模式会降级为未配置状态，不影响普通前端调试。</p>
 */
export const useDesktopUpdateStore = defineStore('desktop-update', () => {
  const channel = ref(readInitialChannel())
  const updateInfo = ref(null)
  const isChecking = ref(false)
  const isInstalling = ref(false)
  const error = ref('')
  const message = ref('')
  const progress = ref(null)
  const listenerReady = ref(false)
  let unlistenProgress = null

  const channelLabel = computed(() =>
    UPDATE_CHANNELS.find((item) => item.value === channel.value)?.label || '正式版'
  )
  const progressPercent = computed(() => {
    const downloaded = progress.value?.downloaded
    const contentLength = progress.value?.contentLength
    if (!downloaded || !contentLength) {
      return 0
    }
    return Math.min(100, Math.round((downloaded / contentLength) * 100))
  })

  function setChannel(nextChannel) {
    channel.value = normalizeChannel(nextChannel)
    updateInfo.value = null
    message.value = ''
    error.value = ''
    window.localStorage.setItem(UPDATE_CHANNEL_STORAGE_KEY, channel.value)
  }

  async function initializeUpdateListener() {
    if (listenerReady.value) {
      return
    }
    try {
      unlistenProgress = await listenDesktopUpdateProgress((payload) => {
        progress.value = payload
        if (payload?.event === 'Error') {
          error.value = payload.message || '更新失败'
          isInstalling.value = false
        }
      })
      listenerReady.value = true
    } catch (err) {
      if (isTauriRuntime()) {
        console.warn('[DesktopUpdate] 进度监听注册失败:', err)
      }
      listenerReady.value = false
    }
  }

  async function checkForUpdates(options = {}) {
    isChecking.value = true
    error.value = ''
    if (!options.silent) {
      message.value = ''
    }
    try {
      const update = await checkDesktopUpdate(channel.value)
      updateInfo.value = update
      if (!options.silent) {
        message.value = update ? `发现新版本 ${update.version}` : '当前已是最新版本'
      }
      return update
    } catch (err) {
      updateInfo.value = null
      if (!options.silent) {
        error.value = normalizeUpdateError(err)
      }
      return null
    } finally {
      isChecking.value = false
    }
  }

  async function installUpdate() {
    isInstalling.value = true
    error.value = ''
    message.value = ''
    progress.value = null
    await initializeUpdateListener()
    try {
      const result = await installDesktopUpdate(channel.value)
      if (!result?.installed) {
        isInstalling.value = false
        message.value = '当前已是最新版本'
      }
      return result
    } catch (err) {
      isInstalling.value = false
      error.value = normalizeUpdateError(err)
      throw err
    }
  }

  function cleanupUpdateListener() {
    if (unlistenProgress) {
      unlistenProgress()
      unlistenProgress = null
    }
    listenerReady.value = false
  }

  return {
    channels: UPDATE_CHANNELS,
    channel,
    channelLabel,
    updateInfo,
    isChecking,
    isInstalling,
    error,
    message,
    progress,
    progressPercent,
    setChannel,
    initializeUpdateListener,
    checkForUpdates,
    installUpdate,
    cleanupUpdateListener
  }
})

function normalizeChannel(value) {
  return UPDATE_CHANNELS.some((item) => item.value === value) ? value : DEFAULT_UPDATE_CHANNEL
}

function readInitialChannel() {
  if (typeof window === 'undefined') {
    return DEFAULT_UPDATE_CHANNEL
  }
  return normalizeChannel(window.localStorage.getItem(UPDATE_CHANNEL_STORAGE_KEY))
}

function normalizeUpdateError(error) {
  return error?.message || String(error || '自动更新不可用')
}
