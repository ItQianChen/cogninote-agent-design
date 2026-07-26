import { expect, test } from '@playwright/test'

import { apiData } from './support/api'

test('empty storage starts on a usable chat workspace', async ({ page, request }) => {
  const status = await apiData(await request.get('/api/system/status'))
  const folders = await apiData(await request.get('/api/knowledge-folders'))

  expect(status.status).toBe('UP')
  expect(status.desktopMode).toBe(false)
  expect(status.dataDir).toContain('cogninote-e2e-')
  expect(folders.folders).toEqual([])

  await page.goto('/chat')
  const primaryNavigation = page.getByRole('navigation', { name: '主要模块' })
  await expect(primaryNavigation).toBeVisible()
  await expect(primaryNavigation.getByRole('link', { name: '对话', exact: true }))
    .toHaveAttribute('aria-current', 'page')
  await expect(page.getByRole('heading', { name: '新对话' })).toBeVisible()
  await expect(page.getByPlaceholder('向知识库提问...')).toBeEditable()
  await expect(page.getByRole('status', { name: /后端/ })).toBeVisible()
})
