import { fileURLToPath } from 'node:url'

import { expect, test } from '@playwright/test'

import { apiData } from './support/api'

const fixtureDirectory = fileURLToPath(new URL('./fixtures/knowledge-base', import.meta.url))

test('folders, sessions, and model config survive a backend restart on the same storage', async ({ request }) => {
  await apiData(await request.post('/api/knowledge-folders/import', {
    data: { folderPath: fixtureDirectory, recursive: true }
  }))
  const session = await apiData(await request.post('/api/chat/sessions', {
    data: { title: 'Phase 37 restart session', useKnowledgeBase: true, mode: 'HYBRID', topK: 8 }
  }))
  await apiData(await request.post('/api/model-configs/settings/configs', {
    data: {
      role: 'CHAT',
      provider: 'DASHSCOPE',
      displayName: 'Phase 37 Restart Config',
      baseUrl: 'https://dashscope.aliyuncs.com/api/v1',
      apiKey: 'restart-placeholder-key',
      modelName: 'qwen-plus',
      temperature: 0.7,
      defaultTopK: 8,
      contextWindowTokens: 128000,
      clearApiKey: false
    }
  }))

  const restartResponse = await request.post(
    `http://127.0.0.1:${process.env.COGNINOTE_E2E_CONTROL_PORT}/restart`,
    { headers: { Authorization: `Bearer ${process.env.COGNINOTE_E2E_CONTROL_TOKEN}` } }
  )
  expect(restartResponse.status()).toBe(204)

  const status = await apiData(await request.get('/api/system/status'))
  const folders = await apiData(await request.get('/api/knowledge-folders'))
  const sessions = await apiData(await request.get('/api/chat/sessions'))
  const modelSettings = await apiData(await request.get('/api/model-configs/settings?role=CHAT'))

  expect(status.status).toBe('UP')
  expect(folders.folders.some((folder) =>
    folder.documents.some((document) => document.fileName === 'release-gate.md'))).toBe(true)
  expect(sessions.some((item) => item.id === session.id && item.title === 'Phase 37 restart session')).toBe(true)
  expect(modelSettings.configs.some((config) => config.displayName === 'Phase 37 Restart Config')).toBe(true)
})
