import { jsonOptions, requestJson, withDesktopSessionHeader } from './http-client'
import { readSseStream } from './chat-stream'

/**
 * 知识图谱 API。
 *
 * <p>scopeType/scopeId 与后端图谱缓存键保持一致，viewType 只在读取视图时传递。</p>
 */
export function rebuildKnowledgeGraph(payload) {
  return requestJson('/api/knowledge-graphs/rebuild', jsonOptions('POST', payload))
}

export function getKnowledgeGraphStatus(scope) {
  const params = scopeParams(scope)
  return requestJson(`/api/knowledge-graphs/status?${params}`)
}

export function getKnowledgeGraphView(scope, viewType) {
  const params = scopeParams({ ...scope, viewType })
  return requestJson(`/api/knowledge-graphs/view?${params}`)
}

export function getKnowledgeGraphRun(runId) {
  return requestJson(`/api/knowledge-graphs/runs/${encodeURIComponent(runId)}`)
}

export function cancelKnowledgeGraphRun(runId) {
  return requestJson(`/api/knowledge-graphs/runs/${encodeURIComponent(runId)}/cancel`, jsonOptions('POST', {}))
}

export function listNodeEvidence(nodeId) {
  return requestJson(`/api/knowledge-graphs/nodes/${encodeURIComponent(nodeId)}/evidence`)
}

export function listEdgeEvidence(edgeId) {
  return requestJson(`/api/knowledge-graphs/edges/${encodeURIComponent(edgeId)}/evidence`)
}

/**
 * 订阅图谱生成进度流。
 *
 * <p>后端可能在终止事件前已经完成状态写入，前端收到 completed/failed/cancelled 后仍要重新拉取状态快照。</p>
 */
export async function streamKnowledgeGraphRun(runId, { signal, onEvent }) {
  const response = await fetch(`/api/knowledge-graphs/runs/${encodeURIComponent(runId)}/events`, await withDesktopSessionHeader({
    method: 'GET',
    headers: {
      Accept: 'text/event-stream'
    },
    signal
  }))

  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.message || body?.code || `HTTP ${response.status}`)
  }

  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应')
  }

  await readSseStream(response.body, onEvent, {
    terminalEvents: ['graph-run-completed', 'graph-run-failed', 'graph-run-cancelled'],
    requireTerminalEvent: false
  })
}

function scopeParams(scope) {
  const params = new URLSearchParams()
  params.set('scopeType', scope.scopeType || 'ALL')
  // scopeId 为空代表全库范围；不要把空字符串传给目录或文档范围。
  if (scope.scopeId) {
    params.set('scopeId', scope.scopeId)
  }
  if (scope.viewType) {
    params.set('viewType', scope.viewType)
  }
  return params.toString()
}
