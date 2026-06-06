import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  cancelChatAnswer,
  clearChatSessionMessages,
  createChatSession,
  deleteChatSession,
  getChatSession,
  listChatSessions,
  streamChatAnswer,
  updateChatSession
} from '../api/chat-stream'

const DEFAULT_RETRIEVAL_MODE = 'HYBRID'
const DEFAULT_TOP_K = 8
const POST_ERROR_REFRESH_DELAYS = [600, 1800, 4200, 9000, 18000, 36000]

let localIdSeed = 0

function nextId(prefix) {
  localIdSeed += 1
  return `${prefix}-${Date.now()}-${localIdSeed}`
}

function normalizeKnowledgeBaseFlag(value) {
  if (typeof value === 'boolean') {
    return value
  }
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    return normalized !== '' && normalized !== 'false' && normalized !== '0' && normalized !== 'off'
  }
  return value !== false
}

function normalizeTopK(value) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    return DEFAULT_TOP_K
  }
  return Math.min(50, Math.max(1, Math.trunc(parsed)))
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

function normalizeMessage(message, fallbackRole = 'assistant') {
  const role = normalizeRole(message?.role || fallbackRole)
  return {
    id: message?.id || nextId(role),
    role,
    content: message?.content || '',
    status: normalizeStatus(message?.status, role),
    sources: message?.sources || [],
    retrievalMode: message?.retrievalMode || '',
    conversationId: message?.conversationId || '',
    requestId: message?.requestId || '',
    createdAt: message?.createdAt || Date.now()
  }
}

function normalizeSession(session) {
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
    messages: (session?.messages || []).map((message) => normalizeMessage(message))
  }
}

function createLocalMessage(role, content = '') {
  return normalizeMessage({
    id: nextId(role),
    role,
    content,
    status: role === 'assistant' ? 'streaming' : 'done',
    createdAt: Date.now()
  }, role)
}

function delay(ms) {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms)
  })
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref([])
  const activeSessionId = ref('')
  const draft = ref('')
  const useKnowledgeBaseValue = ref(true)
  const modeValue = ref(DEFAULT_RETRIEVAL_MODE)
  const topKValue = ref(DEFAULT_TOP_K)
  const isLoadingSessions = ref(false)
  const isLoadingActiveSession = ref(false)
  const isStreaming = ref(false)
  const error = ref('')
  const abortController = ref(null)
  const streamingContext = ref(null)

  const activeSession = computed(() =>
    sessions.value.find((session) => session.id === activeSessionId.value) || sessions.value[0] || null
  )
  const activeMessages = computed(() => activeSession.value?.messages || [])
  const hasMessages = computed(() => activeMessages.value.length > 0)
  const canSend = computed(() => draft.value.trim().length > 0 && !isStreaming.value && !!activeSession.value)
  const useKnowledgeBase = computed({
    get: () => normalizeKnowledgeBaseFlag(useKnowledgeBaseValue.value),
    set: (value) => {
      useKnowledgeBaseValue.value = normalizeKnowledgeBaseFlag(value)
      syncSessionOptions()
    }
  })
  const mode = computed({
    get: () => modeValue.value || DEFAULT_RETRIEVAL_MODE,
    set: (value) => {
      modeValue.value = value || DEFAULT_RETRIEVAL_MODE
      syncSessionOptions()
    }
  })
  const topK = computed({
    get: () => normalizeTopK(topKValue.value),
    set: (value) => {
      topKValue.value = normalizeTopK(value)
      syncSessionOptions()
    }
  })
  const knowledgeDisabledHint = computed(() => '')

  async function initializeSessions() {
    isLoadingSessions.value = true
    error.value = ''
    try {
      const response = await listChatSessions()
      sessions.value = (response || []).map(normalizeSession)
      if (!sessions.value.length) {
        const created = await createChatSession(defaultSessionPayload())
        sessions.value = [normalizeSession(created)]
      }
      const nextActiveId = activeSessionId.value && sessions.value.some((item) => item.id === activeSessionId.value)
        ? activeSessionId.value
        : sessions.value[0].id
      await selectSession(nextActiveId, { force: true })
    } catch (err) {
      error.value = `读取会话失败：${err.message}`
    } finally {
      isLoadingSessions.value = false
    }
  }

  async function startNewSession() {
    if (isStreaming.value) {
      return
    }
    error.value = ''
    try {
      const created = normalizeSession(await createChatSession(defaultSessionPayload()))
      upsertSession(created)
      await selectSession(created.id, { force: true })
      draft.value = ''
    } catch (err) {
      error.value = `新建会话失败：${err.message}`
    }
  }

  async function selectSession(sessionId, options = {}) {
    if (isStreaming.value && !options.force) {
      return
    }
    if (!sessionId) {
      return
    }
    activeSessionId.value = sessionId
    isLoadingActiveSession.value = true
    error.value = ''
    try {
      const detail = normalizeSession(await getChatSession(sessionId))
      upsertSession(detail)
      applySessionOptions(detail)
    } catch (err) {
      error.value = `读取会话详情失败：${err.message}`
    } finally {
      isLoadingActiveSession.value = false
    }
  }

  async function renameSession(sessionId, title) {
    const session = sessions.value.find((item) => item.id === sessionId)
    if (!session) {
      return
    }
    const nextTitle = String(title || '').trim()
    if (!nextTitle || nextTitle === session.title) {
      return
    }
    try {
      const updated = normalizeSession(await updateChatSession(sessionId, { title: nextTitle }))
      upsertSession(updated)
    } catch (err) {
      error.value = `重命名会话失败：${err.message}`
    }
  }

  async function removeSession(sessionId) {
    if (isStreaming.value) {
      return
    }
    try {
      await deleteChatSession(sessionId)
      sessions.value = sessions.value.filter((item) => item.id !== sessionId)
      if (!sessions.value.length) {
        const created = normalizeSession(await createChatSession(defaultSessionPayload()))
        sessions.value = [created]
      }
      await selectSession(sessions.value[0].id, { force: true })
    } catch (err) {
      error.value = `删除会话失败：${err.message}`
    }
  }

  async function clearActiveMessages() {
    if (!activeSession.value || isStreaming.value) {
      return
    }
    try {
      const updated = normalizeSession(await clearChatSessionMessages(activeSession.value.id))
      upsertSession(updated)
      applySessionOptions(updated)
    } catch (err) {
      error.value = `清空会话失败：${err.message}`
    }
  }

  async function streamChat() {
    const trimmedQuestion = draft.value.trim()
    if (!trimmedQuestion) {
      error.value = '请输入问题'
      return
    }
    if (!activeSession.value) {
      await startNewSession()
    }

    const session = activeSession.value
    syncSessionOptions(session)
    const userMessage = createLocalMessage('user', trimmedQuestion)
    appendMessage(session, userMessage)
    const assistantMessage = createLocalMessage('assistant')
    const requestId = nextId('request')
    assistantMessage.requestId = requestId
    appendMessage(session, assistantMessage)
    updateSessionTitle(session, trimmedQuestion)

    draft.value = ''
    error.value = ''
    isStreaming.value = true
    abortController.value = new AbortController()
    streamingContext.value = {
      sessionId: session.id,
      messageId: assistantMessage.id,
      requestId,
      cancelPromise: null
    }

    try {
      await updateChatSession(session.id, sessionPayload(session))
      await streamChatAnswer(
        {
          conversationId: session.id,
          question: trimmedQuestion,
          mode: mode.value,
          topK: Number(topK.value),
          useKnowledgeBase: useKnowledgeBase.value,
          requestId
        },
        {
          signal: abortController.value.signal,
          onEvent: handleEvent
        }
      )
      updateAssistantMessage((message) => {
        if (message.status !== 'error') {
          message.status = 'done'
        }
      })
      await refreshActiveSession()
      await refreshSessionList()
    } catch (err) {
      if (err.name === 'AbortError') {
        await streamingContext.value?.cancelPromise?.catch(() => {})
        updateAssistantMessage((message) => {
          message.status = message.content ? 'stopped' : 'error'
          if (!message.content) {
            message.content = '已停止生成。'
          }
        })
        await refreshActiveSession()
        await refreshSessionList()
      } else {
        const failedSessionId = streamingContext.value?.sessionId || session.id
        const failedRequestId = streamingContext.value?.requestId || requestId
        markAssistantError(err.message || '模型返回错误')
        void refreshSessionAfterStreamError(failedSessionId, failedRequestId)
      }
    } finally {
      isStreaming.value = false
      abortController.value = null
      streamingContext.value = null
    }
  }

  function stopChat() {
    const requestId = streamingContext.value?.requestId
    if (requestId) {
      streamingContext.value.cancelPromise = cancelChatAnswer(requestId).catch(() => {})
    }
    abortController.value?.abort()
  }

  function handleEvent(eventName, payload) {
    if (eventName === 'meta') {
      updateAssistantMessage((message) => {
        message.requestId = payload.requestId || message.requestId
        message.conversationId = payload.conversationId || activeSessionId.value
        message.retrievalMode = payload.retrievalMode || ''
        message.sources = payload.sources || []
        if (payload.requestId && streamingContext.value) {
          streamingContext.value.requestId = payload.requestId
        }
      })
      return
    }

    if (eventName === 'delta') {
      updateAssistantMessage((message) => {
        message.content += payload.text || ''
      })
      return
    }

    if (eventName === 'error') {
      const messageText = payload.message || '模型返回错误'
      markAssistantError(messageText)
    }
  }

  function markAssistantError(messageText) {
    error.value = `对话失败：${messageText}`
    updateAssistantMessage((message) => {
      message.status = 'error'
      if (message.content) {
        message.content = appendErrorNotice(message.content, messageText)
        return
      }
      message.content = messageText
    })
  }

  function appendErrorNotice(content, messageText) {
    if (content.includes(messageText)) {
      return content
    }
    return `${content}\n\n> ${messageText}`
  }

  function askAboutSource(source) {
    draft.value = `请解释 ${source.fileName} 中和这段内容相关的要点。`
  }

  function setUseKnowledgeBase(value) {
    useKnowledgeBase.value = value
  }

  function setMode(value) {
    mode.value = value
  }

  function setTopK(value) {
    topK.value = value
  }

  async function refreshActiveSession() {
    if (!activeSessionId.value) {
      return
    }
    await refreshSessionById(activeSessionId.value)
  }

  async function refreshSessionById(sessionId, options = {}) {
    if (!sessionId) {
      return false
    }
    const detail = normalizeSession(await getChatSession(sessionId))
    if (options.requiredAssistantRequestId && !hasAssistantMessage(detail, options.requiredAssistantRequestId)) {
      return false
    }
    upsertSession(detail)
    if (activeSessionId.value === sessionId) {
      applySessionOptions(detail)
    }
    return true
  }

  async function refreshSessionAfterStreamError(sessionId, requestId) {
    if (!sessionId || !requestId) {
      return
    }
    for (const waitMs of POST_ERROR_REFRESH_DELAYS) {
      await delay(waitMs)
      if (isStreaming.value) {
        continue
      }
      try {
        const refreshed = await refreshSessionById(sessionId, {
          requiredAssistantRequestId: requestId
        })
        if (refreshed) {
          await refreshSessionList().catch(() => {})
          return
        }
      } catch {
        // 错误态气泡已经展示给用户；后台同步失败不应再覆盖当前可见错误。
      }
    }
  }

  function hasAssistantMessage(session, requestId) {
    return session.messages.some((message) =>
      message.role === 'assistant' && message.requestId === requestId
    )
  }

  async function refreshSessionList() {
    const response = await listChatSessions()
    const summaries = (response || []).map(normalizeSession)
    sessions.value = summaries.map((summary) => {
      const existing = sessions.value.find((item) => item.id === summary.id)
      return existing ? { ...summary, messages: existing.messages } : summary
    })
  }

  function appendMessage(session, message) {
    session.messages.push(message)
    session.messageCount = session.messages.length
    session.updatedAt = Date.now()
  }

  function updateSessionTitle(session, question) {
    if (session.title !== '新对话') {
      return
    }
    session.title = question.length > 18 ? `${question.slice(0, 18)}...` : question
  }

  function syncSessionOptions(session = activeSession.value) {
    if (!session) {
      return
    }
    session.useKnowledgeBase = useKnowledgeBase.value
    session.mode = mode.value
    session.topK = topK.value
    session.updatedAt = Date.now()
  }

  function updateAssistantMessage(updater) {
    const context = streamingContext.value
    if (!context) {
      return
    }
    const session = sessions.value.find((item) => item.id === context.sessionId)
    const message = session?.messages.find((item) => item.id === context.messageId)
    if (!message) {
      return
    }
    updater(message)
    session.updatedAt = Date.now()
  }

  function upsertSession(session) {
    const index = sessions.value.findIndex((item) => item.id === session.id)
    if (index >= 0) {
      sessions.value[index] = session
    } else {
      sessions.value.unshift(session)
    }
    sessions.value.sort((left, right) => Number(right.updatedAt) - Number(left.updatedAt))
  }

  function applySessionOptions(session) {
    useKnowledgeBaseValue.value = normalizeKnowledgeBaseFlag(session.useKnowledgeBase)
    modeValue.value = session.mode || DEFAULT_RETRIEVAL_MODE
    topKValue.value = normalizeTopK(session.topK)
  }

  function defaultSessionPayload() {
    return {
      useKnowledgeBase: useKnowledgeBase.value,
      mode: mode.value,
      topK: topK.value
    }
  }

  function sessionPayload(session) {
    return {
      title: session.title,
      useKnowledgeBase: session.useKnowledgeBase,
      mode: session.mode,
      topK: session.topK
    }
  }

  return {
    sessions,
    activeSessionId,
    activeSession,
    activeMessages,
    hasMessages,
    draft,
    useKnowledgeBase,
    mode,
    topK,
    isLoadingSessions,
    isLoadingActiveSession,
    isStreaming,
    error,
    canSend,
    knowledgeDisabledHint,
    initializeSessions,
    setUseKnowledgeBase,
    setMode,
    setTopK,
    startNewSession,
    selectSession,
    renameSession,
    removeSession,
    clearActiveMessages,
    streamChat,
    stopChat,
    askAboutSource
  }
})
