export const UPDATE_CHANNELS = [
  { label: '正式版', value: 'stable' },
  { label: '测试版', value: 'preview' }
]

export async function checkDesktopUpdate(channel) {
  const { invoke } = await import('@tauri-apps/api/core')
  return invoke('check_desktop_update', { channel })
}

export async function installDesktopUpdate(channel) {
  const { invoke } = await import('@tauri-apps/api/core')
  return invoke('install_desktop_update', { channel })
}

export async function listenDesktopUpdateProgress(callback) {
  const { listen } = await import('@tauri-apps/api/event')
  return listen('desktop-update-progress', (event) => callback(event.payload))
}
