import { jsonOptions, requestJson } from './http-client'

/**
 * 后端健康检查 API。
 *
 * <p>该接口用于工作区导航的连接状态提示，应保持轻量，不承载模型或知识库连通性测试。</p>
 */
export function getSystemStatus() {
  return requestJson('/api/system/status')
}

export function getMigrationStatus() {
  return requestJson('/api/system/migration/status')
}

export function createMigrationBackup() {
  return requestJson('/api/system/migration/backup', jsonOptions('POST', {}))
}

export function exportMigrationDiagnostics() {
  return requestJson('/api/system/migration/export', jsonOptions('POST', {}))
}

export function retryMigration() {
  return requestJson('/api/system/migration/retry', jsonOptions('POST', {}))
}

export function getDataProtectionStatus() {
  return requestJson('/api/system/data-protection/status')
}

export function createBackup() {
  return requestJson('/api/system/backups', jsonOptions('POST', {}))
}

export function preflightRestore(importId) {
  return requestJson('/api/system/restores/preflight', jsonOptions('POST', { importId }))
}

export function scheduleRestore(restoreId) {
  return requestJson(`/api/system/restores/${encodeURIComponent(restoreId)}/schedule`, jsonOptions('POST', {}))
}

export function discardRestore(restoreId) {
  return requestJson(`/api/system/restores/${encodeURIComponent(restoreId)}`, { method: 'DELETE' })
}

export function getRestoreStatus(restoreId) {
  return requestJson(`/api/system/restores/${encodeURIComponent(restoreId)}`)
}
