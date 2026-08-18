import { expect, test } from '@playwright/test'

import { apiData } from './support/api'

const configPayload = {
  role: 'CHAT',
  provider: 'DASHSCOPE',
  displayName: 'Phase 37 E2E Chat',
  baseUrl: 'https://dashscope.aliyuncs.com/api/v1',
  apiKey: 'e2e-placeholder-key',
  modelName: 'qwen-plus',
  temperature: 0.7,
  defaultTopK: 8,
  contextWindowTokens: 128000,
  clearApiKey: false
}

test('model config can be saved, reloaded, and connection-tested without a provider', async ({ page, request }) => {
  await apiData(await request.post('/api/model-configs/settings/configs', { data: configPayload }))
  let connectionAttempt = 0
  await page.route('**/api/model-configs/test', async (route) => {
    connectionAttempt += 1
    if (connectionAttempt === 1) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ success: true, data: { success: true, message: 'E2E mock connection succeeded' } })
      })
      return
    }
    await route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ success: false, code: 'E2E_PROVIDER_DOWN', message: 'E2E mock connection failed' })
    })
  })

  await page.goto('/settings?item=model-chat')
  await page.getByRole('button', { name: 'Phase 37 E2E Chat' }).click()
  const displayNameInput = page.locator('label.field').filter({ hasText: '配置名称' }).locator('input')
  await displayNameInput.fill('Phase 37 E2E Chat Updated')
  await page.getByRole('button', { name: '保存配置' }).click()
  await expect(page.getByText('对话模型配置已保存')).toBeVisible()

  await page.reload()
  await page.getByRole('button', { name: 'Phase 37 E2E Chat Updated' }).click()
  await expect(displayNameInput).toHaveValue('Phase 37 E2E Chat Updated')

  await page.getByRole('button', { name: '测试连接' }).click()
  await expect(page.getByText('E2E mock connection succeeded')).toBeVisible()
  await page.getByRole('button', { name: '测试连接' }).click()
  await expect(page.getByText(/E2E mock connection failed/)).toBeVisible()
})

test('model overview opens the matching editor and new configurations stay local until confirmed', async ({ page }) => {
  let createRequestCount = 0
  const activeChat = {
    id: 'chat-active-e2e',
    role: 'CHAT',
    provider: 'DASHSCOPE',
    displayName: 'DashScope Chat',
    baseUrl: 'https://dashscope.aliyuncs.com/api/v1',
    modelName: 'qwen-plus',
    apiKeyConfigured: true,
    temperature: 0.7,
    defaultTopK: 8,
    contextWindowTokens: 128000,
    active: true
  }
  const createdChat = {
    ...activeChat,
    id: 'chat-created-e2e',
    displayName: 'New E2E Chat',
    active: false
  }
  await page.route('**/api/model-configs/settings/configs', async (route) => {
    if (route.request().method() === 'POST') {
      createRequestCount += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          data: {
            role: 'CHAT',
            active: { chat: activeChat, embedding: null, vision: null },
            configs: [createdChat, activeChat],
            selectedConfig: createdChat
          }
        })
      })
      return
    }
    await route.continue()
  })

  await page.goto('/settings?item=model-overview')
  await expect(page.getByRole('heading', { name: '当前启用模型' })).toBeVisible()
  await expect(page.getByRole('button', { name: /编辑对话模型/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /编辑向量模型/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /编辑视觉识别模型/ })).toBeVisible()

  await page.getByRole('button', { name: /编辑对话模型/ }).click()
  await expect(page).toHaveURL(/\/settings\?item=model-chat/)

  await page.getByRole('button', { name: '新建对话模型' }).click()
  await expect(page.getByText('未保存')).toBeVisible()
  await expect(page.getByRole('button', { name: '确认创建' })).toBeVisible()
  expect(createRequestCount).toBe(0)

  await page.getByRole('button', { name: '确认创建' }).click()

  await expect.poll(() => createRequestCount).toBe(1)
  await expect(page.getByText('未保存')).not.toBeVisible()
  await expect(page.getByRole('button', { name: 'New E2E Chat' })).toBeVisible()
})
