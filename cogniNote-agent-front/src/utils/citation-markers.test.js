import { describe, expect, it } from 'vitest'

import {
  citationIndexFromHref,
  compactCitationPreview,
  decorateCitationMarkers
} from './citation-markers'

describe('citation markers', () => {
  it('decorates only source-backed citation numbers and skips code fences', () => {
    const result = decorateCitationMarkers(
      '结论 [1]，代码 ` [2] `。\n```js\nconst text = "[1]"\n```\n[3]',
      [{ index: 1, preview: '第一段内容' }, { index: 3 }]
    )

    expect(result).toContain('[1](#citation-1 "第一段内容")')
    expect(result).toContain('[3](#citation-3)')
    expect(result).toContain('代码 ` [2] `')
    expect(result).toContain('const text = "[1]"')
  })

  it('decorates adjacent citation markers independently', () => {
    const result = decorateCitationMarkers('结论 [9][20]', [
      { index: 9, preview: '第九段' },
      { index: 20, preview: '第二十段' }
    ])

    expect(result).toContain('[9](#citation-9 "第九段")')
    expect(result).toContain('[20](#citation-20 "第二十段")')
  })

  it('compacts previews at the fixed length and leaves short text intact', () => {
    expect(compactCitationPreview('  short   text ')).toBe('short text')
    expect(compactCitationPreview('x'.repeat(121))).toBe(`${'x'.repeat(120)}...`)
    expect(compactCitationPreview('')).toBe('')
  })

  it('parses citation hrefs without accepting arbitrary anchors', () => {
    expect(citationIndexFromHref('#citation-2')).toBe('2')
    expect(citationIndexFromHref('#other-2')).toBe('')
  })
})
