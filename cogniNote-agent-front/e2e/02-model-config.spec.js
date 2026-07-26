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
