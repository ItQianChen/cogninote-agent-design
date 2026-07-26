import { expect, test } from '@playwright/test'

test('mocked RAG SSE merges sources and restores them after a page reload', async ({ page }) => {
  let session = null
  await page.route('**/api/chat/sessions**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const isCollection = url.pathname === '/api/chat/sessions'
    if (request.method() === 'POST' && isCollection) {
      const payload = request.postDataJSON() || {}
      session = createSession(payload)
      await json(route, session)
      return
    }
    if (request.method() === 'GET' && isCollection) {
      await json(route, session ? [session] : [])
      return
    }
    if (request.method() === 'PATCH' && session) {
      session = { ...session, ...request.postDataJSON(), updatedAt: Date.now() }
      await json(route, session)
      return
    }
    if (request.method() === 'GET' && session) {
      await json(route, session)
      return
    }
    await route.fallback()
  })
  await page.route('**/api/chat/stream', async (route) => {
    const payload = route.request().postDataJSON()
    const sources = sourceFixtures()
    session = {
      ...session,
      title: payload.question,
      updatedAt: Date.now(),
      messageCount: 2,
      messages: [
        { id: 'user-e2e', role: 'USER', status: 'DONE', content: payload.question, createdAt: Date.now() },
        {
          id: 'assistant-e2e',
          role: 'ASSISTANT',
          status: 'DONE',
          content: 'The repeatable gate blocks packaging when verification fails.',
          requestId: payload.requestId,
          conversationId: session.id,
          sources,
          createdAt: Date.now() + 1
        }
      ]
    }
    const frames = [
      ['meta', { conversationId: session.id, requestId: payload.requestId, retrievalMode: 'HYBRID', sources: [sources[0]] }],
      ['delta', { text: 'The repeatable gate blocks packaging ' }],
      ['tool', { sources: [sources[0], sources[1]] }],
      ['delta', { text: 'when verification fails.' }],
      ['done', { contextUsage: { contextWindowTokens: 128000, usedTokens: 64, totalMessageCount: 2 } }]
    ].map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join('')
    await route.fulfill({ status: 200, contentType: 'text/event-stream', body: frames })
  })

  await page.goto('/chat')
  await page.getByPlaceholder('向知识库提问...').fill('Phase 37 RAG source question')
  await page.getByRole('button', { name: '发送信息' }).click()
  await expect(page.getByText('The repeatable gate blocks packaging when verification fails.')).toBeVisible()
  const answerSources = page.getByLabel('回答来源')
  await expect(answerSources.getByRole('button', { name: '2 个来源' })).toBeVisible()
  await expect(answerSources.getByRole('button', { name: /release-gate\.md/ })).toBeVisible()

  await page.reload()
  await page.getByText('Phase 37 RAG source question', { exact: true }).first().click()
  await expect(page.getByText('The repeatable gate blocks packaging when verification fails.')).toBeVisible()
  await expect(page.getByLabel('回答来源').getByRole('button', { name: '2 个来源' })).toBeVisible()
})

function createSession(payload) {
  const now = Date.now()
  return {
    id: 'session-e2e',
    title: payload.title || '新对话',
    summary: '',
    createdAt: now,
    updatedAt: now,
    useKnowledgeBase: payload.useKnowledgeBase ?? true,
    mode: payload.mode || 'HYBRID',
    topK: payload.topK || 8,
    messageCount: 0,
    messages: []
  }
}

function sourceFixtures() {
  return [
    {
      index: 1,
      sourceType: 'LOCAL',
      chunkId: 'chunk-release-gate',
      fileName: 'release-gate.md',
      sourcePath: 'e2e/fixtures/knowledge-base/release-gate.md',
      preview: 'PHASE37_DETERMINISTIC_NEEDLE'
    },
    {
      index: 2,
      sourceType: 'LOCAL',
      chunkId: 'chunk-operations',
      fileName: 'operations.txt',
      sourcePath: 'e2e/fixtures/knowledge-base/operations.txt',
      preview: 'queued, running, and completed'
    }
  ]
}

async function json(route, data) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data })
  })
}
