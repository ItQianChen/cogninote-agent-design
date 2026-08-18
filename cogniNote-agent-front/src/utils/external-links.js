import { ElMessage } from 'element-plus'
import { isTauriRuntime } from '../api/desktop-api'

/**
 * 在桌面版将用户明确点击的网页交给系统默认浏览器。
 *
 * 仅接受 HTTP(S)，避免模型输出或网页来源借由链接触发本地协议。
 */
export async function openExternalUrl(rawUrl) {
  const url = normalizeExternalUrl(rawUrl)
  if (!url) {
    ElMessage.error('只能打开 HTTP 或 HTTPS 网页链接')
    return false
  }

  try {
    if (isTauriRuntime()) {
      const { openUrl } = await import('@tauri-apps/plugin-opener')
      await openUrl(url.href)
    } else {
      const openedWindow = window.open(url.href, '_blank', 'noopener,noreferrer')
      if (!openedWindow) {
        throw new Error('浏览器阻止了新窗口')
      }
    }
    return true
  } catch {
    ElMessage.error('无法在系统浏览器打开链接')
    return false
  }
}

/**
 * 在 Tauri WebView 内接管普通外链点击；浏览器运行态保留锚点的原生行为。
 */
export function installExternalLinkHandler() {
  if (!isTauriRuntime() || typeof document === 'undefined') {
    return () => {}
  }

  const handleClick = (event) => {
    if (event.defaultPrevented || event.button !== 0) {
      return
    }
    const anchor = findAnchor(event.target)
    if (!anchor || !normalizeExternalUrl(anchor.href)) {
      return
    }

    event.preventDefault()
    void openExternalUrl(anchor.href)
  }

  document.addEventListener('click', handleClick)
  return () => document.removeEventListener('click', handleClick)
}

function findAnchor(target) {
  if (!(target instanceof Element)) {
    return null
  }
  return target.closest('a[href]')
}

function normalizeExternalUrl(rawUrl) {
  if (typeof rawUrl !== 'string' || !rawUrl.trim()) {
    return null
  }
  try {
    const url = new URL(rawUrl, window.location.href)
    return url.protocol === 'http:' || url.protocol === 'https:' ? url : null
  } catch {
    return null
  }
}
