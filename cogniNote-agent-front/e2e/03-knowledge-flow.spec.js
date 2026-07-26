import { fileURLToPath } from 'node:url'

import { expect, test } from '@playwright/test'

import { apiData, waitForMaintenanceRun } from './support/api'

const fixtureDirectory = fileURLToPath(new URL('./fixtures/knowledge-base', import.meta.url))

test('imports fixtures, searches a unique term, and completes a maintenance sync', async ({ page, request }) => {
  await page.goto('/knowledge?panel=directories')
  await expect(page.getByText('还没有导入知识库目录。')).toBeVisible()
  await page.getByRole('button', { name: '导入目录' }).click()
  await page.getByPlaceholder('点击选择文件夹，或手动输入 D:/notes').fill(fixtureDirectory)
  const importResponse = page.waitForResponse((response) =>
    response.url().includes('/api/knowledge-maintenance/runs/import-folder')
      && response.request().method() === 'POST')
  await page.getByRole('button', { name: '导入目录', exact: true }).last().click()
  const importRun = await apiData(await importResponse)
  expect((await waitForMaintenanceRun(request, importRun.id)).status).toBe('COMPLETED')

  const completionAcknowledge = page.getByRole('button', { name: '知道了' })
  if (await completionAcknowledge.isVisible().catch(() => false)) {
    await completionAcknowledge.click()
  }
  await page.goto('/knowledge?panel=directories')
  await expect(page.getByRole('button', { name: 'knowledge-base' })).toBeVisible()

  const syncResponse = page.waitForResponse((response) =>
    /\/api\/knowledge-maintenance\/runs\/folders\/[^/]+\/sync$/.test(new URL(response.url()).pathname)
      && response.request().method() === 'POST')
  await page.getByRole('button', { name: '同步', exact: true }).click()
  await page.getByRole('button', { name: '确认同步' }).click()
  const syncRun = await apiData(await syncResponse)
  expect((await waitForMaintenanceRun(request, syncRun.id)).status).toBe('COMPLETED')

  await page.goto('/knowledge?panel=search')
  await page.getByPlaceholder('输入关键词或问题片段').fill('PHASE37_DETERMINISTIC_NEEDLE')
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'release-gate.md' })).toBeVisible()
  await expect(page.getByText(/PHASE37_DETERMINISTIC_NEEDLE/)).toBeVisible()
  await expect(page.getByText(/KEYWORD \/ [1-9]\d* hits/)).toBeVisible()
})
