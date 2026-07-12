export const FAILURE_STAGE_LABELS = {
  SCAN: '目录扫描',
  READ: '文件读取',
  PARSE: '文档解析',
  OCR: 'OCR 处理',
  MODEL_CONFIG: '模型配置',
  MODEL_CALL: '模型调用',
  CHUNK: '文本切块',
  PERSIST: '本地存储',
  INDEX: '检索索引',
  UNKNOWN: '未知阶段'
}

export function failureStageLabel(failure) {
  return FAILURE_STAGE_LABELS[failure?.stage] || failure?.stage || '未知阶段'
}

export function failureTechnicalRows(failure) {
  if (!failure) {
    return []
  }
  return [
    ['错误码', failure.code],
    ['Provider', failure.provider],
    ['模型', failure.modelName],
    ['PDF 页码', failure.pageNumber ? `第 ${failure.pageNumber} 页` : ''],
    ['HTTP 状态', failure.httpStatus],
    ['服务商错误码', failure.providerErrorCode],
    ['技术详情', failure.detail]
  ].filter(([, value]) => value !== null && value !== undefined && String(value).trim())
}

export function groupFailuresByStage(failures) {
  const groups = new Map()
  for (const failure of failures || []) {
    const stage = failure?.stage || 'UNKNOWN'
    if (!groups.has(stage)) {
      groups.set(stage, [])
    }
    groups.get(stage).push(failure)
  }
  return [...groups.entries()].map(([stage, items]) => ({
    stage,
    label: FAILURE_STAGE_LABELS[stage] || stage,
    items
  }))
}
