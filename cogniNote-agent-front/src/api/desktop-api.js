let cachedDesktopSessionToken
let desktopSessionTokenPromise = null
const DESKTOP_SESSION_TOKEN_GLOBAL = '__COGNINOTE_DESKTOP_SESSION_TOKEN__'

/**
 * 调用 Tauri 桌面端文件夹选择器。
 *
 * <p>浏览器开发模式没有 Tauri runtime，返回 null 让调用方回退到手动输入路径。</p>
 */
export async function pickKnowledgeFolder() {
  if (!isTauriRuntime()) {
    return null
  }
  try {
    const { invoke } = await import('@tauri-apps/api/core')
    return await invoke('pick_knowledge_folder')
  } catch {
    return null
  }
}

/**
 * 读取桌面壳注入的本机会话令牌。
 *
 * <p>Web 开发模式没有该 Tauri command，返回空字符串让 HTTP 客户端保持普通浏览器调试体验。</p>
 */
export async function getDesktopSessionToken() {
  const injectedToken = readInjectedDesktopSessionToken()
  if (injectedToken) {
    cachedDesktopSessionToken = injectedToken
    return cachedDesktopSessionToken
  }
  if (cachedDesktopSessionToken !== undefined) {
    return cachedDesktopSessionToken
  }
  if (!isTauriRuntime()) {
    cachedDesktopSessionToken = ''
    return cachedDesktopSessionToken
  }
  if (desktopSessionTokenPromise) {
    return desktopSessionTokenPromise
  }

  desktopSessionTokenPromise = loadDesktopSessionToken()
  return desktopSessionTokenPromise
}

/**
 * 判断当前页面是否运行在 Tauri WebView 中。
 *
 * <p>浏览器开发态先短路，避免每个 API 请求都重复尝试加载 Tauri command。</p>
 */
export function isTauriRuntime() {
  return typeof window !== 'undefined'
    && (Boolean(window.__TAURI_INTERNALS__) || Boolean(window.isTauri) || Boolean(readInjectedDesktopSessionToken()))
}

async function loadDesktopSessionToken() {
  const injectedToken = readInjectedDesktopSessionToken()
  if (injectedToken) {
    cachedDesktopSessionToken = injectedToken
    return cachedDesktopSessionToken
  }
  try {
    const { invoke } = await import('@tauri-apps/api/core')
    cachedDesktopSessionToken = await invoke('get_desktop_session_token') || ''
  } catch {
    cachedDesktopSessionToken = ''
  }
  return cachedDesktopSessionToken
}

function readInjectedDesktopSessionToken() {
  if (typeof window === 'undefined') {
    return ''
  }
  const token = window[DESKTOP_SESSION_TOKEN_GLOBAL]
  return typeof token === 'string' ? token : ''
}
