import { describe, expect, it } from 'vitest'

import {
  failureProgressLabel,
  failureStageLabel,
  failureTechnicalRows,
  groupFailuresByStage
} from './document-failures'

describe('document failure presentation', () => {
  it('uses known labels and preserves unknown backend stages', () => {
    expect(failureStageLabel({ stage: 'OCR' })).toBe('OCR 处理')
    expect(failureStageLabel({ stage: 'PROVIDER_QUEUE' })).toBe('PROVIDER_QUEUE')
    expect(failureStageLabel(null)).toBe('未知阶段')
  })

  it('formats resumable progress only for valid page counts', () => {
    expect(failureProgressLabel({ completedPages: 2, totalPages: 10, resumePage: 3 }))
      .toBe('已保存 2 / 10 页，下次同步将从第 3 页继续。')
    expect(failureProgressLabel({ completedPages: 2, totalPages: 10 })).toBe('已保存 2 / 10 页。')
    expect(failureProgressLabel({ completedPages: 2, totalPages: 0 })).toBe('')
  })

  it('omits empty technical fields and groups failures in encounter order', () => {
    expect(failureTechnicalRows({ code: 'OCR_TIMEOUT', pageNumber: 4, detail: 'timeout' }))
      .toEqual([
        ['错误码', 'OCR_TIMEOUT'],
        ['PDF 页码', '第 4 页'],
        ['技术详情', 'timeout']
      ])

    const grouped = groupFailuresByStage([
      { id: 'a', stage: 'INDEX' },
      { id: 'b', stage: 'READ' },
      { id: 'c', stage: 'INDEX' },
      { id: 'd' }
    ])
    expect(grouped.map((group) => [group.stage, group.items.map((item) => item.id)]))
      .toEqual([
        ['INDEX', ['a', 'c']],
        ['READ', ['b']],
        ['UNKNOWN', ['d']]
      ])
  })
})
