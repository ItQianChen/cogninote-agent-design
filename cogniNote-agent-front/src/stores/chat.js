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

/**
 * 生成本地临时 id。
 * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
 */
function nextId(prefix) {
  localIdSeed += 1
  return `${prefix}-${Date.now()}-${localIdSeed}`
}

/**
 * 规范化知识库开关输入。
 * <p>把后端、表单或浏览器传入的异常值收敛为安全范围。</p>
 */
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

/**
 * 规范化 Top K 输入。
 * <p>把后端、表单或浏览器传入的异常值收敛为安全范围。</p>
 */
function normalizeTopK(value) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    return DEFAULT_TOP_K
  }
  return Math.min(50, Math.max(1, Math.trunc(parsed)))
}

/**
 * 规范化消息角色输入。
 * <p>把后端、表单或浏览器传入的异常值收敛为安全范围。</p>
 */
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

/**
 * 规范化消息状态输入。
 * <p>把后端、表单或浏览器传入的异常值收敛为安全范围。</p>
 */
function normalizeStatus(status, role) {
  if (role === 'user') {
    return 'done'
  }
  return String(status || 'done').toLowerCase()
}

/**
 * 规范化消息快照输入。
 * <p>把后端、表单或浏览器传入的异常值收敛为安全范围。</p>
 */
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

/**
 * 规范化上下文用量输入。
 * <p>把后端、表单或浏览器传入的异常值收敛为安全范围。</p>
 */
function normalizeContextUsage(usage) {
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

/**
 * 规范化非负整数输入。
 * <p>把后端、表单或浏览器传入的异常值收敛为安全范围。</p>
 */
function normalizeNonNegativeInteger(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.max(0, Math.trunc(parsed)) : 0
}

/**
 * 规范化比例输入。
 * <p>把后端、表单或浏览器传入的异常值收敛为安全范围。</p>
 */
function normalizeRatio(value, fallback = 0) {
  const parsed = Number(value)
  const ratio = Number.isFinite(parsed) ? parsed : fallback
  return Math.min(1, Math.max(0, ratio))
}

/**
 * 规范化会话快照输入。
 * <p>把后端、表单或浏览器传入的异常值收敛为安全范围。</p>
 */
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
    contextUsage: normalizeContextUsage(session?.contextUsage),
    messages: (session?.messages || []).map((message) => normalizeMessage(message))
  }
}

/**
 * 创建本地消息快照。
 * <p>该方法通常会同步本地响应式状态和后端快照。</p>
 */
function createLocalMessage(role, content = '') {
  return normalizeMessage({
    id: nextId(role),
    role,
    content,
    status: role === 'assistant' ? 'streaming' : 'done',
    createdAt: Date.now()
  }, role)
}

/**
 * 等待指定毫秒数。
 * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
 */
function delay(ms) {
  return new Promise((resolve) => {
    // 等待下一轮渲染后再读写 DOM，避免滚动位置计算使用旧布局。
    window.setTimeout(resolve, ms)
  })
}

/**
 * 定义 聊天会话 的 Pinia Store。
 * <p>集中维护响应式状态、派生值和异步动作，组件只消费 Store 暴露的接口。</p>
 */
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
  const sessionScrollPositions = ref({})
  const pendingSessionOptionPayloads = new Map()
  const savingSessionOptionIds = new Set()

  const activeSession = computed(() =>
    sessions.value.find((session) => session.id === activeSessionId.value) || sessions.value[0] || null
  )
  const activeMessages = computed(() => activeSession.value?.messages || [])
  const activeContextUsage = computed(() => activeSession.value?.contextUsage || null)
  const hasMessages = computed(() => activeMessages.value.length > 0)
  const canSend = computed(() => draft.value.trim().length > 0 && !isStreaming.value && !!activeSession.value)
  const useKnowledgeBase = computed({
    get: () => normalizeKnowledgeBaseFlag(useKnowledgeBaseValue.value),
    set: (value) => {
      useKnowledgeBaseValue.value = normalizeKnowledgeBaseFlag(value)
      syncSessionOptions()
      persistActiveSessionOptions()
    }
  })
  const mode = computed({
    get: () => modeValue.value || DEFAULT_RETRIEVAL_MODE,
    set: (value) => {
      modeValue.value = value || DEFAULT_RETRIEVAL_MODE
      syncSessionOptions()
      persistActiveSessionOptions()
    }
  })
  const topK = computed({
    get: () => normalizeTopK(topKValue.value),
    set: (value) => {
      topKValue.value = normalizeTopK(value)
      syncSessionOptions()
      persistActiveSessionOptions()
    }
  })
  const knowledgeDisabledHint = computed(() => '')

  /**
   * 初始化会话列表并选中当前会话。
   * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
   */
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

  /**
   * 创建一个新的聊天会话。
   * <p>该方法通常会同步本地响应式状态和后端快照。</p>
   */
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

  /**
   * 切换当前聊天会话。
   * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
   */
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

  /**
   * 重命名指定聊天会话。
   * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
   */
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

  /**
   * 删除指定聊天会话。
   * <p>清理时同步处理本地缓存，避免界面保留过期状态。</p>
   */
  async function removeSession(sessionId) {
    if (isStreaming.value) {
      return
    }
    try {
      await deleteChatSession(sessionId)
      forgetSessionScrollPosition(sessionId)
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

  /**
   * 清空当前会话的消息。
   * <p>清理时同步处理本地缓存，避免界面保留过期状态。</p>
   */
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

  /**
   * 发起 聊天会话 的流式请求。
   * <p>SSE 数据会被逐段解析并交给调用方处理。</p>
   */
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
      // 这里建立对话 SSE 流，后端事件会持续驱动助手消息更新。
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

  /**
   * 停止当前正在生成的回答。
   * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
   */
  function stopChat() {
    const requestId = streamingContext.value?.requestId
    if (requestId) {
      // 停止生成需要同时通知后端并中止当前浏览器请求。
      streamingContext.value.cancelPromise = cancelChatAnswer(requestId).catch(() => {})
    }
    // 停止生成需要同时通知后端并中止当前浏览器请求。
    abortController.value?.abort()
  }

  /**
   * 处理后端 SSE 事件。
   * <p>事件处理函数只保留必要副作用，复杂状态交给 Store 维护。</p>
   */
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
      updateSessionContextUsage(payload.conversationId || activeSessionId.value, payload.contextUsage)
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
      return
    }

    if (eventName === 'done') {
      updateSessionContextUsage(streamingContext.value?.sessionId || activeSessionId.value, payload.contextUsage)
    }
  }

  /**
   * 标记助手消息为错误状态。
   * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
   */
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

  /**
   * 追加流式错误提示。
   * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
   */
  function appendErrorNotice(content, messageText) {
    if (content.includes(messageText)) {
      return content
    }
    return `${content}\n\n> ${messageText}`
  }

  /**
   * 根据来源片段生成追问草稿。
   * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
   */
  function askAboutSource(source) {
    draft.value = `请解释 ${source.fileName} 中和这段内容相关的要点。`
  }

  /**
   * 保存会话滚动位置。
   * <p>状态写入后需要保持控件、Store 和后端快照一致。</p>
   */
  function saveSessionScrollPosition(sessionId, position) {
    if (!sessionId || !position) {
      return
    }
    sessionScrollPositions.value = {
      ...sessionScrollPositions.value,
      [sessionId]: {
        scrollTop: Math.max(0, Number(position.scrollTop) || 0),
        scrollHeight: Math.max(0, Number(position.scrollHeight) || 0),
        clientHeight: Math.max(0, Number(position.clientHeight) || 0),
        distanceFromBottom: Math.max(0, Number(position.distanceFromBottom) || 0),
        anchorMessageId: String(position.anchorMessageId || ''),
        anchorMessageIndex: Math.max(0, Number(position.anchorMessageIndex) || 0),
        anchorOffsetTop: Number(position.anchorOffsetTop) || 0,
        anchorProgress: Math.min(1, Math.max(0, Number(position.anchorProgress) || 0)),
        anchorViewportRatio: Math.min(1, Math.max(0, Number(position.anchorViewportRatio) || 0))
      }
    }
  }

  /**
   * 读取会话滚动位置。
   * <p>接口结果会被转换为页面或 Store 可直接消费的结构。</p>
   */
  function getSessionScrollPosition(sessionId) {
    return sessionScrollPositions.value[sessionId] || null
  }

  /**
   * 维护聊天滚动锚点。
   * <p>长对话切换、流式输出和 DOM 高度变化时都依赖该逻辑恢复阅读位置。</p>
   */
  function forgetSessionScrollPosition(sessionId) {
    if (!sessionId || !sessionScrollPositions.value[sessionId]) {
      return
    }
    const nextPositions = { ...sessionScrollPositions.value }
    delete nextPositions[sessionId]
    sessionScrollPositions.value = nextPositions
  }

  /**
   * 设置是否使用知识库。
   * <p>状态写入后需要保持控件、Store 和后端快照一致。</p>
   */
  function setUseKnowledgeBase(value) {
    useKnowledgeBase.value = value
  }

  /**
   * 设置检索模式。
   * <p>状态写入后需要保持控件、Store 和后端快照一致。</p>
   */
  function setMode(value) {
    mode.value = value
  }

  /**
   * 设置检索 Top K。
   * <p>状态写入后需要保持控件、Store 和后端快照一致。</p>
   */
  function setTopK(value) {
    topK.value = value
  }

  /**
   * 刷新当前会话详情。
   * <p>接口结果会被转换为页面或 Store 可直接消费的结构。</p>
   */
  async function refreshActiveSession() {
    if (!activeSessionId.value) {
      return
    }
    await refreshSessionById(activeSessionId.value)
  }

  /**
   * 按会话 id 刷新会话详情。
   * <p>接口结果会被转换为页面或 Store 可直接消费的结构。</p>
   */
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

  /**
   * 流式错误后延迟刷新会话详情。
   * <p>接口结果会被转换为页面或 Store 可直接消费的结构。</p>
   */
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

  /**
   * 判断会话内是否存在指定助手消息。
   * <p>集中维护 UI 分支使用的同一套判定规则。</p>
   */
  function hasAssistantMessage(session, requestId) {
    return session.messages.some((message) =>
      message.role === 'assistant' && message.requestId === requestId
    )
  }

  /**
   * 刷新会话摘要列表。
   * <p>接口结果会被转换为页面或 Store 可直接消费的结构。</p>
   */
  async function refreshSessionList() {
    const response = await listChatSessions()
    const summaries = (response || []).map(normalizeSession)
    sessions.value = summaries.map((summary) => {
      const existing = sessions.value.find((item) => item.id === summary.id)
      return existing ? { ...summary, messages: existing.messages } : summary
    })
  }

  /**
   * 向会话追加本地消息。
   * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
   */
  function appendMessage(session, message) {
    session.messages.push(message)
    session.messageCount = session.messages.length
    session.updatedAt = Date.now()
  }

  /**
   * 根据首条问题更新会话标题。
   * <p>状态写入后需要保持控件、Store 和后端快照一致。</p>
   */
  function updateSessionTitle(session, question) {
    if (session.title !== '新对话') {
      return
    }
    session.title = question.length > 18 ? `${question.slice(0, 18)}...` : question
  }

  /**
   * 同步当前会话的检索选项。
   * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
   */
  function syncSessionOptions(session = activeSession.value) {
    if (!session) {
      return
    }
    session.useKnowledgeBase = useKnowledgeBase.value
    session.mode = mode.value
    session.topK = topK.value
    session.updatedAt = Date.now()
  }

  /**
   * 持久化当前会话的检索选项。
   * <p>知识库开关属于会话级状态，用户切换后必须立即写库；否则刷新页面会被旧快照覆盖。</p>
   */
  function persistActiveSessionOptions(session = activeSession.value) {
    if (!session?.id) {
      return
    }
    pendingSessionOptionPayloads.set(session.id, sessionOptionsPayload(session))
    void flushSessionOptions(session.id)
  }

  /**
   * 串行保存指定会话的检索选项。
   * <p>连续点击开关时只允许同一会话一个保存循环运行，保证最终落库的是最后一次本地状态。</p>
   */
  async function flushSessionOptions(sessionId) {
    if (savingSessionOptionIds.has(sessionId)) {
      return
    }
    savingSessionOptionIds.add(sessionId)
    try {
      while (pendingSessionOptionPayloads.has(sessionId)) {
        const payload = pendingSessionOptionPayloads.get(sessionId)
        pendingSessionOptionPayloads.delete(sessionId)
        try {
          const updated = normalizeSession(await updateChatSession(sessionId, payload, { keepalive: true }))
          if (!pendingSessionOptionPayloads.has(sessionId)) {
            applyPersistedSessionOptions(updated)
          }
        } catch (err) {
          error.value = `保存会话设置失败：${err.message}`
        }
      }
    } finally {
      savingSessionOptionIds.delete(sessionId)
      if (pendingSessionOptionPayloads.has(sessionId)) {
        void flushSessionOptions(sessionId)
      }
    }
  }

  /**
   * 应用后端保存后的会话快照。
   * <p>保存期间如果又产生了新的本地修改，旧响应不回写 UI，避免开关视觉状态闪回。</p>
   */
  function applyPersistedSessionOptions(updated) {
    const existing = sessions.value.find((item) => item.id === updated.id)
    const merged = existing
      ? { ...updated, messages: updated.messages.length ? updated.messages : existing.messages }
      : updated
    upsertSession(merged)
    if (activeSessionId.value === updated.id) {
      applySessionOptions(merged)
    }
  }

  /**
   * 更新当前流式助手消息。
   * <p>状态写入后需要保持控件、Store 和后端快照一致。</p>
   */
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

  /**
   * 更新会话上下文占用快照。
   * <p>状态写入后需要保持控件、Store 和后端快照一致。</p>
   */
  function updateSessionContextUsage(sessionId, contextUsage) {
    const normalized = normalizeContextUsage(contextUsage)
    if (!sessionId || !normalized) {
      return
    }
    const session = sessions.value.find((item) => item.id === sessionId)
    if (!session) {
      return
    }
    session.contextUsage = normalized
    if (normalized.totalMessageCount > 0) {
      session.messageCount = normalized.totalMessageCount
    }
  }

  /**
   * 插入或替换会话快照。
   * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
   */
  function upsertSession(session) {
    const index = sessions.value.findIndex((item) => item.id === session.id)
    if (index >= 0) {
      sessions.value[index] = session
    } else {
      sessions.value.unshift(session)
    }
    sessions.value.sort((left, right) => Number(right.updatedAt) - Number(left.updatedAt))
  }

  /**
   * 应用会话保存的检索选项。
   * <p>状态写入后需要保持控件、Store 和后端快照一致。</p>
   */
  function applySessionOptions(session) {
    useKnowledgeBaseValue.value = normalizeKnowledgeBaseFlag(session.useKnowledgeBase)
    modeValue.value = session.mode || DEFAULT_RETRIEVAL_MODE
    topKValue.value = normalizeTopK(session.topK)
  }

  /**
   * 构造新会话默认请求载荷。
   * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
   */
  function defaultSessionPayload() {
    return {
      useKnowledgeBase: useKnowledgeBase.value,
      mode: mode.value,
      topK: topK.value
    }
  }

  /**
   * 构造会话更新请求载荷。
   * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
   */
  function sessionPayload(session) {
    return {
      title: session.title,
      useKnowledgeBase: session.useKnowledgeBase,
      mode: session.mode,
      topK: session.topK
    }
  }

  /**
   * 构造会话检索选项更新载荷。
   * <p>这里只写会话级检索配置，不携带标题，避免和重命名、首问改标题互相覆盖。</p>
   */
  function sessionOptionsPayload(session) {
    return {
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
    activeContextUsage,
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
    getSessionScrollPosition,
    saveSessionScrollPosition,
    forgetSessionScrollPosition,
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
