import { jsonOptions, requestJson, withDesktopSessionHeader } from './http-client'
import { readSseStream } from './chat-stream'

export function enqueueImportFolder(payload) {
  return requestJson('/api/knowledge-maintenance/runs/import-folder', jsonOptions('POST', payload))
}

export function enqueueRebuildIndex() {
  return requestJson('/api/knowledge-maintenance/runs/rebuild-index', jsonOptions('POST', {}))
}

export function enqueueRepairIndex() {
  return requestJson('/api/knowledge-maintenance/runs/repair-index', jsonOptions('POST', {}))
}

export function enqueueSyncFolder(id) {
  return requestJson(`/api/knowledge-maintenance/runs/folders/${encodeURIComponent(id)}/sync`, jsonOptions('POST', {}))
}

export function enqueueReparseFolder(id) {
  return requestJson(`/api/knowledge-maintenance/runs/folders/${encodeURIComponent(id)}/reparse`, jsonOptions('POST', {}))
}

export function enqueueRebuildFolder(id) {
  return requestJson(`/api/knowledge-maintenance/runs/folders/${encodeURIComponent(id)}/rebuild`, jsonOptions('POST', {}))
}

export function enqueueRepairFolderIndex(id) {
  return requestJson(`/api/knowledge-maintenance/runs/folders/${encodeURIComponent(id)}/repair-index`, jsonOptions('POST', {}))
}

export function enqueueFolderEnabled(id, enabled) {
  return requestJson(
    `/api/knowledge-maintenance/runs/folders/${encodeURIComponent(id)}/enabled`,
    jsonOptions('POST', { enabled })
  )
}

export function enqueueDeleteFolder(id) {
  return requestJson(`/api/knowledge-maintenance/runs/folders/${encodeURIComponent(id)}/delete`, jsonOptions('POST', {}))
}

export function getMaintenanceQueue() {
  return requestJson('/api/knowledge-maintenance/runs/queue')
}

export function getMaintenanceRun(runId) {
  return requestJson(`/api/knowledge-maintenance/runs/${encodeURIComponent(runId)}`)
}

export function cancelMaintenanceRun(runId) {
  return requestJson(`/api/knowledge-maintenance/runs/${encodeURIComponent(runId)}/cancel`, jsonOptions('POST', {}))
}

/**
 * 订阅维护任务进度。
 *
 * 后端终态事件发送后会关闭连接；前端收到终态或连接异常后都应重新拉取队列快照，
 * 以数据库最终状态修正可能丢失的中间事件。
 */
export async function streamMaintenanceRun(runId, { signal, onEvent }) {
  const response = await fetch(
    `/api/knowledge-maintenance/runs/${encodeURIComponent(runId)}/events`,
    await withDesktopSessionHeader({
      method: 'GET',
      headers: {
        Accept: 'text/event-stream'
      },
      signal
    })
  )

  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.message || body?.code || `HTTP ${response.status}`)
  }

  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应')
  }

  await readSseStream(response.body, onEvent, {
    terminalEvents: ['maintenance-run-completed', 'maintenance-run-failed', 'maintenance-run-cancelled'],
    requireTerminalEvent: false
  })
}
