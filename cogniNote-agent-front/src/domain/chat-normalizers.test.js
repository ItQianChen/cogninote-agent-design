import { describe, expect, it } from 'vitest'

import {
  createLocalMessage,
  mergeSources,
  normalizeContextUsage,
  normalizeKnowledgeBaseFlag,
  normalizeMessage,
  normalizeReferences,
  normalizeSession,
  normalizeSources,
  normalizeTopK
} from './chat-normalizers'

describe('chat protocol normalization', () => {
  it('normalizes session options and message roles at protocol boundaries', () => {
    const session = normalizeSession({
      id: 'session-1',
      useKnowledgeBase: 'off',
      topK: 100,
      messages: [
        { id: 'user-1', role: 'USER', status: 'STREAMING', content: 'hello' },
        { id: 'assistant-1', role: 'ASSISTANT', status: 'ERROR' }
      ]
    })

    expect(session.useKnowledgeBase).toBe(false)
    expect(session.topK).toBe(50)
    expect(session.messages).toEqual([
      expect.objectContaining({ id: 'user-1', role: 'user', status: 'done', content: 'hello' }),
      expect.objectContaining({ id: 'assistant-1', role: 'assistant', status: 'error', content: '' })
    ])
    expect(normalizeKnowledgeBaseFlag('0')).toBe(false)
    expect(normalizeKnowledgeBaseFlag('yes')).toBe(true)
    expect(normalizeTopK('invalid')).toBe(8)
    expect(normalizeTopK(-3)).toBe(1)
  })

  it('normalizes source fallbacks and merges SSE sources by stable chunk id', () => {
    const existing = normalizeSources([{ chunkId: 'chunk-1', fileName: 'local.md' }])
    const merged = mergeSources(existing, [
      { chunkId: 'chunk-1', fileName: 'duplicate.md' },
      { sourceType: 'WEB', url: 'https://example.com/source', title: 'Web source' }
    ])

    expect(merged).toHaveLength(2)
    expect(merged[0].fileName).toBe('local.md')
    expect(merged[1]).toMatchObject({
      sourceType: 'WEB',
      fileName: 'Web source',
      sourcePath: 'https://example.com/source',
      chunkId: 'https://example.com/source'
    })
  })

  it('deduplicates, bounds, and cleans pending references', () => {
    const longSnippet = 'x'.repeat(1500)
    const references = normalizeReferences([
      { id: 'r1', messageId: ' m1 ', snippet: ' first   snippet ' },
      { id: 'r2', messageId: 'm1', snippet: 'first snippet' },
      { id: 'r3', messageId: '', snippet: 'ignored' },
      ...Array.from({ length: 8 }, (_, index) => ({
        id: `long-${index}`,
        messageId: `message-${index}`,
        snippet: longSnippet
      }))
    ])

    expect(references).toHaveLength(5)
    expect(references[0]).toMatchObject({ id: 'r1', messageId: 'm1', snippet: 'first snippet' })
    expect(references[1].snippet).toHaveLength(1200)
    expect(references.reduce((total, reference) => total + reference.snippet.length, 0)).toBeLessThanOrEqual(4000)
  })

  it('clamps context usage and creates explicit local lifecycle states', () => {
    expect(normalizeContextUsage({
      contextWindowTokens: 100,
      usedTokens: 150,
      usageRatio: 2,
      availableTokens: -2,
      totalMessageCount: 4.9
    })).toMatchObject({
      contextWindowTokens: 100,
      usedTokens: 150,
      usageRatio: 1,
      availableTokens: 0,
      totalMessageCount: 4
    })
    expect(normalizeContextUsage(null)).toBeNull()
    expect(createLocalMessage('assistant')).toMatchObject({ role: 'assistant', status: 'streaming' })
    expect(normalizeMessage({
      role: 'ASSISTANT',
      reasoningContent: '先分析问题',
      reasoningStatus: 'DONE'
    })).toMatchObject({
      role: 'assistant',
      reasoningContent: '先分析问题',
      reasoningStatus: 'done'
    })
    expect(normalizeMessage({ role: 'USER', status: 'error' })).toMatchObject({ role: 'user', status: 'done' })
  })
})
