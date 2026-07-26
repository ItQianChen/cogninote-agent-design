export const DEFAULT_RETRIEVAL_MODE = 'HYBRID'
export const DEFAULT_TOP_K = 8

const MAX_PENDING_REFERENCES = 5
const MAX_REFERENCE_SNIPPET_CHARS = 1200
const MAX_REFERENCE_TOTAL_CHARS = 4000

let localIdSeed = 0

export function nextId(prefix) {
  localIdSeed += 1
  return `${prefix}-${Date.now()}-${localIdSeed}`
}

export function normalizeKnowledgeBaseFlag(value) {
  if (typeof value === 'boolean') {
    return value
  }
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    return normalized !== '' && normalized !== 'false' && normalized !== '0' && normalized !== 'off'
  }
  return value !== false
}

export function normalizeTopK(value) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    return DEFAULT_TOP_K
  }
  return Math.min(50, Math.max(1, Math.trunc(parsed)))
}

export function normalizeMessage(message, fallbackRole = 'assistant') {
  const role = normalizeRole(message?.role || fallbackRole)
  return {
    id: message?.id || nextId(role),
    role,
    content: message?.content || '',
    status: normalizeStatus(message?.status, role),
    sources: normalizeSources(message?.sources),
    references: normalizeReferences(message?.references),
    retrievalMode: message?.retrievalMode || '',
    conversationId: message?.conversationId || '',
    requestId: message?.requestId || '',
    createdAt: message?.createdAt || Date.now()
  }
}

export function normalizeSources(sources) {
  if (!Array.isArray(sources)) {
    return []
  }
  return sources.map((source) => ({
    ...source,
    sourceType: source?.sourceType || 'LOCAL',
    fileName: source?.fileName || source?.title || source?.url || '来源',
    sourcePath: source?.sourcePath || source?.url || '',
    chunkId: source?.chunkId || source?.url || nextId('source'),
    preview: source?.preview || ''
  }))
}

export function normalizeReferences(references) {
  if (!Array.isArray(references)) {
    return []
  }
  const seen = new Set()
  const normalized = []
  let totalChars = 0
  for (const reference of references) {
    if (!reference || normalized.length >= MAX_PENDING_REFERENCES) {
      continue
    }
    const messageId = normalizeText(reference.messageId)
    let snippet = truncateText(normalizeText(reference.snippet), MAX_REFERENCE_SNIPPET_CHARS)
    if (!messageId || !snippet) {
      continue
    }
    const remaining = MAX_REFERENCE_TOTAL_CHARS - totalChars
    if (remaining <= 0) {
      break
    }
    snippet = truncateText(snippet, remaining)
    const dedupeKey = `${messageId}\n${snippet}`
    if (seen.has(dedupeKey)) {
      continue
    }
    seen.add(dedupeKey)
    normalized.push({
      id: normalizeText(reference.id) || nextId('reference'),
      messageId,
      snippet
    })
    totalChars += snippet.length
  }
  return normalized
}

export function normalizeContextUsage(usage) {
  if (!usage) {
    return null
  }
  const contextWindowTokens = normalizeNonNegativeInteger(usage.contextWindowTokens)
  const usedTokens = normalizeNonNegativeInteger(usage.usedTokens)
  const usageRatio = normalizeRatio(
    usage.usageRatio,
    contextWindowTokens > 0 ? usedTokens / contextWindowTokens : 0
  )
  return {
    contextWindowTokens,
    usedTokens,
    availableTokens: normalizeNonNegativeInteger(usage.availableTokens),
    usageRatio,
    compressed: Boolean(usage.compressed),
    summaryTokens: normalizeNonNegativeInteger(usage.summaryTokens),
    recentMessageTokens: normalizeNonNegativeInteger(usage.recentMessageTokens),
    recentMessageCount: normalizeNonNegativeInteger(usage.recentMessageCount),
    totalMessageCount: normalizeNonNegativeInteger(usage.totalMessageCount),
    summaryMessageSequence: normalizeNonNegativeInteger(usage.summaryMessageSequence),
    estimationMethod: String(usage.estimationMethod || '')
  }
}

export function normalizeSession(session) {
  return {
    id: session?.id || nextId('session'),
    title: session?.title || '新对话',
    summary: session?.summary || '',
    createdAt: session?.createdAt || Date.now(),
    updatedAt: session?.updatedAt || Date.now(),
    useKnowledgeBase: normalizeKnowledgeBaseFlag(session?.useKnowledgeBase),
    mode: session?.mode || DEFAULT_RETRIEVAL_MODE,
    topK: normalizeTopK(session?.topK),
    messageCount: Number(session?.messageCount || session?.messages?.length || 0),
    contextUsage: normalizeContextUsage(session?.contextUsage),
    messages: (session?.messages || []).map((message) => normalizeMessage(message))
  }
}

export function createLocalMessage(role, content = '') {
  return normalizeMessage({
    id: nextId(role),
    role,
    content,
    status: role === 'assistant' ? 'streaming' : 'done',
    createdAt: Date.now()
  }, role)
}

export function mergeSources(existingSources, incomingSources) {
  const merged = normalizeSources(existingSources)
  const seen = new Set(merged.map((source) => source.chunkId))
  for (const source of normalizeSources(incomingSources)) {
    if (seen.has(source.chunkId)) {
      continue
    }
    seen.add(source.chunkId)
    merged.push(source)
  }
  return merged
}

function normalizeRole(role) {
  const value = String(role || '').toUpperCase()
  if (value === 'USER') {
    return 'user'
  }
  if (value === 'ASSISTANT') {
    return 'assistant'
  }
  return value.toLowerCase() || 'assistant'
}

function normalizeStatus(status, role) {
  if (role === 'user') {
    return 'done'
  }
  return String(status || 'done').toLowerCase()
}

function normalizeText(value) {
  return String(value || '').replace(/\s+/g, ' ').trim()
}

function truncateText(value, maxLength) {
  return value.length <= maxLength ? value : value.slice(0, maxLength)
}

function normalizeNonNegativeInteger(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.max(0, Math.trunc(parsed)) : 0
}

function normalizeRatio(value, fallback = 0) {
  const parsed = Number(value)
  const ratio = Number.isFinite(parsed) ? parsed : fallback
  return Math.min(1, Math.max(0, ratio))
}
