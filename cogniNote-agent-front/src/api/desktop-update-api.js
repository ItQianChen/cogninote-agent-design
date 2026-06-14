import { isTauriRuntime } from './desktop-api'

export const UPDATE_CHANNELS = [
  { label: '正式版', value: 'stable' },
  { label: '测试版', value: 'preview' }
]

export const DESKTOP_UPDATE_UNAVAILABLE_MESSAGE = '自动更新仅在桌面版中可用'

export async function checkDesktopUpdate(channel) {
  ensureDesktopUpdateRuntime()
  const { invoke } = await import('@tauri-apps/api/core')
  return invoke('check_desktop_update', { channel })
}

export async function installDesktopUpdate(channel) {
  ensureDesktopUpdateRuntime()
  const { invoke } = await import('@tauri-apps/api/core')
  return invoke('install_desktop_update', { channel })
}

export async function listenDesktopUpdateProgress(callback) {
  if (!isTauriRuntime()) {
    return () => {}
  }
  const { listen } = await import('@tauri-apps/api/event')
  return listen('desktop-update-progress', (event) => callback(event.payload))
}

function ensureDesktopUpdateRuntime() {
  if (!isTauriRuntime()) {
    throw new Error(DESKTOP_UPDATE_UNAVAILABLE_MESSAGE)
  }
}
