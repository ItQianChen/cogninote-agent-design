import { describe, expect, it } from 'vitest'

import {
  DEFAULT_KNOWLEDGE_PANEL,
  normalizeKnowledgePanel
} from './knowledge-navigation'

describe('knowledge navigation normalization', () => {
  it('accepts known ids and the first Vue Router query value', () => {
    expect(normalizeKnowledgePanel('health')).toBe('health')
    expect(normalizeKnowledgePanel(['search', 'graph'])).toBe('search')
  })

  it('falls back for invalid, empty, and empty-array values', () => {
    expect(normalizeKnowledgePanel('unknown')).toBe(DEFAULT_KNOWLEDGE_PANEL)
    expect(normalizeKnowledgePanel('')).toBe(DEFAULT_KNOWLEDGE_PANEL)
    expect(normalizeKnowledgePanel([])).toBe(DEFAULT_KNOWLEDGE_PANEL)
  })
})
