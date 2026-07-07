const ISSUE_SEVERITY_LABELS = {
  ERROR: '需修复',
  WARNING: '需关注',
  INFO: '提示'
}

const ISSUE_ACTION_LABELS = {
  REBUILD_INDEX: '重建索引',
  REPAIR_INDEX: '补写索引',
  CONFIGURE_EMBEDDING: '配置向量模型',
  REBUILD_GRAPH: '重建对应图谱',
  VIEW_CONFLICTS: '查看资料风险',
  SYNC_FOLDER: '同步目录',
  ENABLE_FOLDER: '启用目录',
  DISABLE_FOLDER: '停用目录'
}

const ISSUE_EXPLANATIONS = {
  PARSE_FAILED: '这些文件没有成功解析，修复源文件后重新同步目录即可重试。',
  PDF_OCR_REQUIRED: '这些 PDF 没有可抽取文本层，当前版本不会自动 OCR，需要先用外部工具生成文本层后重新同步。',
  INDEX_INCONSISTENT: '索引记录和实际 Lucene 内容不一致，部分资料可能搜不到。',
  EMBEDDING_UNCONFIGURED: '向量或混合检索会降级为关键词检索，语义相近的问题命中会变差。',
  GRAPH_STALE: '图谱是问答辅助视图；资料更新后，旧图谱可能还停留在上一次生成结果。',
  DUPLICATE_DOCUMENT_CONTENT: '重复资料不会阻止问答，但可能让同一来源在检索结果中反复出现。',
  POSSIBLE_VERSION_CONFLICT: '疑似旧版和新版同时在知识库中，回答时可能混用不同版本内容。'
}

const ISSUE_CATEGORY_DEFINITIONS = [
  {
    key: 'file-ingest',
    title: '资料未入库',
    subtitle: '解析失败和需 OCR 文档',
    ruleSummary: '判定依据：SQLite 文档状态为 FAILED 或 OCR_REQUIRED。',
    codes: ['PARSE_FAILED', 'PDF_OCR_REQUIRED'],
    tone: 'warning',
    rules: [
      '解析失败文档不会写入 chunk 或 Lucene 索引。',
      '无文本层 PDF 会标记为需 OCR；当前版本只提示，不自动执行 OCR。'
    ]
  },
  {
    key: 'retrieval',
    title: '可能搜不到',
    subtitle: '索引和检索基础能力',
    ruleSummary: '判定依据：SQLite 记录与 Lucene 实际索引计数。',
    codes: ['INDEX_INCONSISTENT'],
    tone: 'error',
    rules: [
      '比较 SQLite 中已解析/已索引记录与 Lucene 实际索引计数。',
      '两边文档数或 chunk 数不一致时提示重建索引。'
    ]
  },
  {
    key: 'capability',
    title: '检索能力降级',
    subtitle: '向量或混合检索能力',
    ruleSummary: '判定依据：有可检索资料，但向量模型不可用。',
    codes: ['EMBEDDING_UNCONFIGURED'],
    tone: 'warning',
    rules: [
      '知识库存在可检索资料，但没有可用向量模型时提示。',
      '提示只说明向量/混合检索会降级，不代表关键词检索不可用。'
    ]
  },
  {
    key: 'graph',
    title: '辅助图谱过期',
    subtitle: '知识图谱派生视图',
    ruleSummary: '判定依据：资料更新时间晚于图谱生成时间。',
    codes: ['GRAPH_STALE'],
    tone: 'warning',
    rules: [
      '比较已生成图谱时间与对应资料的同步/索引更新时间。',
      '资料更新晚于图谱生成时间时，只提示重建对应范围图谱。'
    ]
  },
  {
    key: 'content-risk',
    title: '可能干扰回答',
    subtitle: '重复资料和疑似版本冲突',
    ruleSummary: '判定依据：内容完全重复，或文件名带版本/日期证据且内容不同。',
    codes: ['DUPLICATE_DOCUMENT_CONTENT', 'POSSIBLE_VERSION_CONFLICT'],
    tone: 'warning',
    rules: [
      '重复资料：只在 content_hash 完全相同时提示。',
      '疑似版本冲突：排除 README、index、目录、toc、summary 等通用文件名。',
      '疑似版本冲突必须带 v1/v2、final、draft、新版、旧版、日期等版本证据才会比较。',
      '没有版本标记的普通同名文件不会被当作版本冲突。'
    ]
  }
]

/**
 * 把后端健康 issue 映射成稳定的界面文案。
 *
 * 后端 action/code 是协议字段，直接展示会把实现枚举暴露给用户；前端只在这里做展示翻译。
 */
export function issueMetaText(issue) {
  const severity = ISSUE_SEVERITY_LABELS[issue?.severity] || issue?.severity || '提示'
  const action = ISSUE_ACTION_LABELS[issue?.action] || issue?.action || '查看详情'
  return `${severity} · ${action}`
}

export function issueExplanation(issue) {
  return ISSUE_EXPLANATIONS[issue?.code] || ''
}

export function issueIdentity(issue) {
  const examples = issueExamples(issue)
    .map((example) => [
      example.type,
      example.label,
      example.scopeType,
      example.scopeId,
      ...(example.items || [])
    ].filter(Boolean).join('|'))
    .join('||')
  return [
    issue?.code || 'UNKNOWN',
    issue?.scopeType || '',
    issue?.scopeId || '',
    issue?.count ?? 0,
    examples
  ].join('::')
}

export function buildIssueCategories(issues, ignoredIssueKeys = new Set()) {
  return ISSUE_CATEGORY_DEFINITIONS.map((definition) => {
    const categoryIssues = (issues || []).filter((issue) => definition.codes.includes(issue.code))
    const activeIssues = categoryIssues.filter((issue) => !ignoredIssueKeys.has(issueIdentity(issue)))
    const ignoredCount = categoryIssues.length - activeIssues.length
    return {
      ...definition,
      issues: categoryIssues,
      activeIssues,
      ignoredCount,
      issueCount: categoryIssues.length,
      activeCount: activeIssues.reduce((total, issue) => total + (issue.count || 1), 0)
    }
  }).filter((category) => category.issueCount)
}

export function issueExamples(issue) {
  const details = issue?.exampleDetails || []
  if (details.length) {
    return details.map((detail, index) => normalizeExampleDetail(detail, index))
  }
  return (issue?.examples || []).map((example, index) => ({
    key: `${issue?.code || 'issue'}-${index}-${example}`,
    type: 'TEXT',
    label: example,
    description: '',
    scopeType: '',
    scopeId: '',
    items: []
  }))
}

export function canRebuildGraphExample(example) {
  return example?.type === 'GRAPH_SCOPE' && Boolean(example.scopeType)
}

export function graphScopeConfirmText(example) {
  const label = example?.label || '该图谱'
  return `确定重建“${label}”吗？\n\n系统会按这个范围重新抽取实体和关系，不会修改原始文件。资料较多时可能运行较久。`
}

function normalizeExampleDetail(detail, index) {
  const label = detail?.label || '诊断样例'
  return {
    key: `${detail?.type || 'detail'}-${detail?.scopeType || ''}-${detail?.scopeId || ''}-${index}-${label}`,
    type: detail?.type || 'TEXT',
    label,
    description: detail?.description || '',
    scopeType: detail?.scopeType || '',
    scopeId: detail?.scopeId || '',
    items: detail?.items || []
  }
}
