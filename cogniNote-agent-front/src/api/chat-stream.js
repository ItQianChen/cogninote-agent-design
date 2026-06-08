import { jsonOptions, requestJson } from './http-client'

/**
 * 发起 聊天会话 的流式请求。
 * <p>SSE 数据会被逐段解析并交给调用方处理。</p>
 */
export async function streamChatAnswer(payload, { signal, onEvent }) {
  // 这里进入浏览器网络请求边界，后续统一解析响应和错误。
  const response = await fetch('/api/chat/stream', {
    ...jsonOptions('POST', payload),
    signal
  })

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
  // 后端请求统一走 API helper，避免各组件重复处理错误。
  return requestJson(`/api/chat/stream/${encodeURIComponent(requestId)}/cancel`, jsonOptions('POST', {}))
}

/**
 * 加载 list Chat Sessions 对应的数据。
 * <p>接口结果会被转换为页面或 Store 可直接消费的结构。</p>
 */
export function listChatSessions() {
  // 后端请求统一走 API helper，避免各组件重复处理错误。
  return requestJson('/api/chat/sessions')
}

/**
 * 创建或启动 create Chat Session 对应的前端流程。
 * <p>该方法通常会同步本地响应式状态和后端快照。</p>
 */
export function createChatSession(payload = {}) {
  // 后端请求统一走 API helper，避免各组件重复处理错误。
  return requestJson('/api/chat/sessions', jsonOptions('POST', payload))
}

/**
 * 加载 get Chat Session 对应的数据。
 * <p>接口结果会被转换为页面或 Store 可直接消费的结构。</p>
 */
export function getChatSession(conversationId) {
  // 后端请求统一走 API helper，避免各组件重复处理错误。
  return requestJson(`/api/chat/sessions/${encodeURIComponent(conversationId)}`)
}

/**
 * 更新 update Chat Session 对应的状态。
 * <p>状态写入后需要保持控件、Store 和后端快照一致。</p>
 */
export function updateChatSession(conversationId, payload, options = {}) {
  // 后端请求统一走 API helper，避免各组件重复处理错误。
  return requestJson(
    `/api/chat/sessions/${encodeURIComponent(conversationId)}`,
    {
      ...jsonOptions('PATCH', payload),
      // 会话开关这类小请求可能发生在刷新页面前，调用方可启用 keepalive 提高落库成功率。
      ...options
    }
  )
}

/**
 * 删除或清理 delete Chat Session 对应的数据。
 * <p>清理时同步处理本地缓存，避免界面保留过期状态。</p>
 */
export function deleteChatSession(conversationId) {
  // 后端请求统一走 API helper，避免各组件重复处理错误。
  return requestJson(`/api/chat/sessions/${encodeURIComponent(conversationId)}`, { method: 'DELETE' })
}

/**
 * 删除或清理 clear Chat Session Messages 对应的数据。
 * <p>清理时同步处理本地缓存，避免界面保留过期状态。</p>
 */
export function clearChatSessionMessages(conversationId) {
  // 后端请求统一走 API helper，避免各组件重复处理错误。
  return requestJson(`/api/chat/sessions/${encodeURIComponent(conversationId)}/messages`, { method: 'DELETE' })
}

/**
 * 执行 聊天会话 中的 read Sse Stream 步骤。
 * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
 */
async function readSseStream(body, onEvent) {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = 'message'
  let dataLines = []
  let terminalEventReceived = false

  /**
   * 执行 聊天会话 中的 dispatch 事件 步骤。
   * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
   */
  const dispatchEvent = () => {
    if (dataLines.length === 0) {
      eventName = 'message'
      return
    }

    if (eventName === 'done' || eventName === 'error') {
      terminalEventReceived = true
    }
    onEvent(eventName, parsePayload(dataLines.join('\n')))
    eventName = 'message'
    dataLines = []
  }

  /**
   * 处理 handle Line 交互。
   * <p>事件处理函数只保留必要副作用，复杂状态交给 Store 维护。</p>
   */
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
  if (!terminalEventReceived) {
    // 没有收到后端的 done/error 终止帧，说明连接中途断开或流被上游截断。
    // 这种情况下不能把已有内容当作完整回答。
    throw new Error('流式回答连接提前结束，当前回答可能不完整。请重试或让模型继续回答。')
  }
}

/**
 * 执行 聊天会话 中的 parse Payload 步骤。
 * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
 */
function parsePayload(rawData) {
  try {
    return JSON.parse(rawData)
  } catch {
    return { text: rawData }
  }
}
