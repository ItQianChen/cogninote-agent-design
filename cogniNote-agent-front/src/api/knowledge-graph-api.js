import { jsonOptions, requestJson } from './http-client'
import { readSseStream } from './chat-stream'

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

export async function streamKnowledgeGraphRun(runId, { signal, onEvent }) {
  const response = await fetch(`/api/knowledge-graphs/runs/${encodeURIComponent(runId)}/events`, {
    method: 'GET',
    headers: {
      Accept: 'text/event-stream'
    },
    signal
  })

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
  if (scope.scopeId) {
    params.set('scopeId', scope.scopeId)
  }
  if (scope.viewType) {
    params.set('viewType', scope.viewType)
  }
  return params.toString()
}
