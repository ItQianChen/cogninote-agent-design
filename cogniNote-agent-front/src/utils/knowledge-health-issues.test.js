import { describe, expect, it } from 'vitest'

import {
  buildIssueCategories,
  canRebuildGraphExample,
  graphScopeConfirmText,
  issueExamples,
  issueExplanation,
  issueIdentity,
  issueMetaText
} from './knowledge-health-issues'

describe('knowledge health issue normalization', () => {
  it('maps protocol fields to stable user-facing text', () => {
    expect(issueMetaText({ severity: 'ERROR', action: 'REPAIR_INDEX' })).toBe('需修复 · 补写索引')
    expect(issueMetaText({ severity: 'CUSTOM', action: 'CUSTOM_ACTION' })).toBe('CUSTOM · CUSTOM_ACTION')
    expect(issueExplanation({ code: 'INDEX_INCONSISTENT' })).toContain('Lucene')
  })

  it('groups issues and excludes ignored identities from active totals', () => {
    const ignored = { code: 'PARSE_FAILED', count: 2, examples: ['broken.pdf'] }
    const active = { code: 'PDF_OCR_REQUIRED', count: 3, examples: ['scan.pdf'] }
    const categories = buildIssueCategories(
      [ignored, active],
      new Set([issueIdentity(ignored)])
    )

    expect(categories).toHaveLength(1)
    expect(categories[0]).toMatchObject({
      key: 'file-ingest',
      issueCount: 2,
      ignoredCount: 1,
      activeCount: 3
    })
    expect(categories[0].activeIssues).toEqual([active])
  })

  it('normalizes structured and legacy examples without mutating input', () => {
    const detail = {
      type: 'GRAPH_SCOPE',
      label: '项目图谱',
      scopeType: 'KNOWLEDGE_FOLDER',
      scopeId: 'folder-1'
    }
    const structured = issueExamples({ code: 'GRAPH_STALE', exampleDetails: [detail] })
    const legacy = issueExamples({ code: 'PARSE_FAILED', examples: ['legacy.txt'] })

    expect(structured[0]).toMatchObject({ ...detail, description: '', items: [] })
    expect(legacy[0]).toMatchObject({ type: 'TEXT', label: 'legacy.txt' })
    expect(canRebuildGraphExample(structured[0])).toBe(true)
    expect(canRebuildGraphExample(legacy[0])).toBe(false)
    expect(graphScopeConfirmText(structured[0])).toContain('项目图谱')
    expect(detail).not.toHaveProperty('items')
  })
})
