import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { streamChatAnswer } from '../api/chat-stream'

let localIdSeed = 0

function nextId(prefix) {
  localIdSeed += 1
  return `${prefix}-${Date.now()}-${localIdSeed}`
}

function createMessage(role, content = '') {
  return {
    id: nextId(role),
    role,
    content,
    status: role === 'assistant' ? 'streaming' : 'done',
    sources: [],
    retrievalMode: '',
    conversationId: '',
    createdAt: Date.now()
  }
}

function createSession(title = '新对话') {
  return {
    id: nextId('session'),
    title,
    createdAt: Date.now(),
    updatedAt: Date.now(),
    useKnowledgeBase: true,
    mode: 'HYBRID',
    topK: 8,
    messages: []
  }
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref([createSession()])
  const activeSessionId = ref(sessions.value[0].id)
  const draft = ref('')
  const useKnowledgeBase = ref(true)
  const mode = ref('HYBRID')
  const topK = ref(8)
  const isStreaming = ref(false)
  const error = ref('')
  const abortController = ref(null)
  const streamingContext = ref(null)

  const activeSession = computed(() =>
    sessions.value.find((session) => session.id === activeSessionId.value) || sessions.value[0]
  )
  const activeMessages = computed(() => activeSession.value?.messages || [])
  const hasMessages = computed(() => activeMessages.value.length > 0)
  const canSend = computed(() => draft.value.trim().length > 0 && !isStreaming.value)
  const knowledgeDisabledHint = computed(() =>
    useKnowledgeBase.value ? '' : '纯对话将在第八阶段接入后端聊天记忆后启用。'
  )

  function startNewSession() {
    const session = createSession()
    sessions.value.unshift(session)
    selectSession(session.id)
    draft.value = ''
    error.value = ''
  }

  function selectSession(sessionId) {
    if (isStreaming.value) {
      return
    }
    const session = sessions.value.find((item) => item.id === sessionId)
    if (!session) {
      return
    }
    activeSessionId.value = session.id
    useKnowledgeBase.value = session.useKnowledgeBase
    mode.value = session.mode
    topK.value = session.topK
    error.value = ''
  }

  async function streamChat() {
    const trimmedQuestion = draft.value.trim()
    if (!trimmedQuestion) {
      error.value = '请输入问题'
      return
    }
    if (!useKnowledgeBase.value) {
      error.value = knowledgeDisabledHint.value
      return
    }

    const session = activeSession.value
    syncSessionOptions(session)
    appendMessage(session, createMessage('user', trimmedQuestion))
    const assistantMessage = createMessage('assistant')
    appendMessage(session, assistantMessage)
    updateSessionTitle(session, trimmedQuestion)

    draft.value = ''
    error.value = ''
    isStreaming.value = true
    abortController.value = new AbortController()
    streamingContext.value = {
      sessionId: session.id,
      messageId: assistantMessage.id
    }

    try {
      await streamChatAnswer(
        {
          question: trimmedQuestion,
          mode: mode.value,
          topK: Number(topK.value)
        },
        {
          signal: abortController.value.signal,
          onEvent: handleEvent
        }
      )
      updateAssistantMessage((message) => {
        message.status = 'done'
      })
    } catch (err) {
      if (err.name === 'AbortError') {
        updateAssistantMessage((message) => {
          message.status = message.content ? 'stopped' : 'error'
          if (!message.content) {
            message.content = '已停止生成。'
          }
        })
      } else {
        error.value = `对话失败：${err.message}`
        updateAssistantMessage((message) => {
          message.status = 'error'
          message.content = err.message || '模型返回错误'
        })
      }
    } finally {
      isStreaming.value = false
      abortController.value = null
      streamingContext.value = null
    }
  }

  function stopChat() {
    abortController.value?.abort()
  }

  function handleEvent(eventName, payload) {
    // SSE 的 meta/delta/error 在 store 中归档，页面只消费消息状态，
    // 第八阶段接入 SQLite 会话时可以复用同一套前端消息模型。
    if (eventName === 'meta') {
      updateAssistantMessage((message) => {
        message.conversationId = payload.conversationId || ''
        message.retrievalMode = payload.retrievalMode || ''
        message.sources = payload.sources || []
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
      updateAssistantMessage((message) => {
        message.status = 'error'
        message.content = payload.message || '模型返回错误'
      })
    }
  }

  function askAboutSource(source) {
    draft.value = `请解释 ${source.fileName} 中和这段内容相关的要点。`
  }

  function appendMessage(session, message) {
    session.messages.push(message)
    session.updatedAt = Date.now()
  }

  function updateSessionTitle(session, question) {
    if (session.title !== '新对话') {
      return
    }
    session.title = question.length > 18 ? `${question.slice(0, 18)}...` : question
  }

  function syncSessionOptions(session) {
    session.useKnowledgeBase = useKnowledgeBase.value
    session.mode = mode.value
    session.topK = Number(topK.value)
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
    isStreaming,
    error,
    canSend,
    knowledgeDisabledHint,
    startNewSession,
    selectSession,
    streamChat,
    stopChat,
    askAboutSource
  }
})
