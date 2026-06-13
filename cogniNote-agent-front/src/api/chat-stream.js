import { jsonOptions, requestJson, withDesktopSessionHeader } from './http-client'

/**
 * 发起聊天流式请求。
 *
 * SSE 需要 POST JSON body，不能直接使用浏览器 EventSource，因此这里手动读取响应流。
 */
export async function streamChatAnswer(payload, { signal, onEvent }) {
  const response = await fetch('/api/chat/stream', await withDesktopSessionHeader({
    ...jsonOptions('POST', payload),
    signal
  }))

  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.message || body?.code || `HTTP ${response.status}`)
  }

  if (!response.body) {
    throw new Error('当前浏览器不支持流式响应')
  }

  await readSseStream(response.body, onEvent)
}

/**
 * 取消正在进行的 聊天会话 任务。
 * <p>后端取消和浏览器 Abort 需要配合，避免留下悬挂请求。</p>
 */
export async function cancelChatAnswer(requestId) {
  if (!requestId) {
    return false
  }
  return requestJson(`/api/chat/stream/${encodeURIComponent(requestId)}/cancel`, jsonOptions('POST', {}))
}

export function listChatSessions() {
  return requestJson('/api/chat/sessions')
}

export function createChatSession(payload = {}) {
  return requestJson('/api/chat/sessions', jsonOptions('POST', payload))
}

export function getChatSession(conversationId) {
  return requestJson(`/api/chat/sessions/${encodeURIComponent(conversationId)}`)
}

export function updateChatSession(conversationId, payload, options = {}) {
  return requestJson(
    `/api/chat/sessions/${encodeURIComponent(conversationId)}`,
    {
      ...jsonOptions('PATCH', payload),
      // 会话开关这类小请求可能发生在刷新页面前，调用方可启用 keepalive 提高落库成功率。
      ...options
    }
  )
}

export function deleteChatSession(conversationId) {
  return requestJson(`/api/chat/sessions/${encodeURIComponent(conversationId)}`, { method: 'DELETE' })
}

export function clearChatSessionMessages(conversationId) {
  return requestJson(`/api/chat/sessions/${encodeURIComponent(conversationId)}/messages`, { method: 'DELETE' })
}

/**
 * 按 SSE 协议解析 fetch 响应体。
 *
 * 解析器必须等待 done/error 终止帧；连接提前断开时保留错误状态，不能把半截回答当成完成。
 */
export async function readSseStream(body, onEvent, options = {}) {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = 'message'
  let dataLines = []
  let terminalEventReceived = false
  const terminalEvents = new Set(options.terminalEvents || ['done', 'error'])
  const requireTerminalEvent = options.requireTerminalEvent ?? true

  const dispatchEvent = () => {
    if (dataLines.length === 0) {
      eventName = 'message'
      return
    }

    if (terminalEvents.has(eventName)) {
      terminalEventReceived = true
    }
    onEvent(eventName, parsePayload(dataLines.join('\n')))
    eventName = 'message'
    dataLines = []
  }

  const handleLine = (line) => {
    if (line === '') {
      dispatchEvent()
      return
    }
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
      return
    }
    if (line.startsWith('data:')) {
      const data = line.slice(5)
      // SSE 允许 "data: value" 里的一个分隔空格；内容本身的前导空白必须保留，
      // 否则流式 Markdown 中单独返回的空格、缩进和换行会被吃掉。
      dataLines.push(data.startsWith(' ') ? data.slice(1) : data)
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }

    // 对话接口需要 POST JSON body，不能使用只支持 GET 的 EventSource。
    // 这里手动按 SSE 空行分帧，确保半包数据不会提前触发 UI 更新。
    buffer += decoder.decode(value, { stream: true })
    let newlineIndex = buffer.indexOf('\n')
    while (newlineIndex >= 0) {
      const line = buffer.slice(0, newlineIndex).replace(/\r$/, '')
      buffer = buffer.slice(newlineIndex + 1)
      handleLine(line)
      newlineIndex = buffer.indexOf('\n')
    }
  }

  buffer += decoder.decode()
  if (buffer.length > 0) {
    handleLine(buffer.replace(/\r$/, ''))
  }
  dispatchEvent()
  if (requireTerminalEvent && !terminalEventReceived) {
    // 没有收到后端的 done/error 终止帧，说明连接中途断开或流被上游截断。
    // 这种情况下不能把已有内容当作完整回答。
    throw new Error('流式回答连接提前结束，当前回答可能不完整。请重试或让模型继续回答。')
  }
}

function parsePayload(rawData) {
  try {
    return JSON.parse(rawData)
  } catch {
    return { text: rawData }
  }
}
