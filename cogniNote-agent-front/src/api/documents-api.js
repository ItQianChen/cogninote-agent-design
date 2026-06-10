import { jsonOptions, requestJson, requestNoContent } from './http-client'

/**
 * 加载 list Documents 对应的数据。
 * <p>接口结果会被转换为页面或 Store 可直接消费的结构。</p>
 */
export function listDocuments() {
  // 后端请求统一走 API helper，避免各组件重复处理错误。
  return requestJson('/api/documents')
}

/**
 * 加载 get Document Chunk 对应的数据。
 * <p>来源预览需要完整片段内容，不能只依赖聊天消息里携带的摘要。</p>
 */
export function getDocumentChunk(chunkId) {
  // chunkId 来自后端检索结果，仍通过 encodeURIComponent 保护 URL 边界。
  return requestJson(`/api/documents/chunks/${encodeURIComponent(chunkId)}`)
}

/**
 * 执行 文档管理 中的 ingest Documents 步骤。
 * <p>该函数是当前组件或模块中的一个明确维护边界。</p>
 */
export function ingestDocuments(payload) {
  // 后端请求统一走 API helper，避免各组件重复处理错误。
  return requestJson('/api/documents/ingest', jsonOptions('POST', payload))
}

/**
 * 删除或清理 delete Document Record 对应的数据。
 * <p>清理时同步处理本地缓存，避免界面保留过期状态。</p>
 */
export function deleteDocumentRecord(id) {
  // 后端请求统一走 API helper，避免各组件重复处理错误。
  return requestNoContent(`/api/documents/${id}`, { method: 'DELETE' })
}
