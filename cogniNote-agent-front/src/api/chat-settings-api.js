import { jsonOptions, requestJson } from './http-client'

/**
 * 读取聊天设置。
 * <p>返回后端实际生效的全局聊天设置，避免前端本地状态和后端行为不一致。</p>
 */
export function getChatSettings() {
  return requestJson('/api/chat/settings')
}

/**
 * 更新聊天设置。
 * <p>当前用于保存知识库追问补全策略，保存后立即影响后端检索 query 补全。</p>
 */
export function updateChatSettings(payload) {
  return requestJson('/api/chat/settings', jsonOptions('PUT', payload))
}
