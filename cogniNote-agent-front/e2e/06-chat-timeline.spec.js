import { expect, test } from '@playwright/test'

test('chat timeline previews turns and jumps to the selected user message', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 820 })
  const session = timelineSessionFixture()
  await page.route('**/api/chat/sessions**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (request.method() !== 'GET') {
      await route.fallback()
      return
    }
    if (url.pathname === '/api/chat/sessions') {
      await json(route, [{ ...session, messages: [] }])
      return
    }
    if (url.pathname === `/api/chat/sessions/${session.id}`) {
      await json(route, session)
      return
    }
    await route.fallback()
  })

  await page.goto('/chat')
  await expect(page.locator('.conversation-timeline-marker')).toHaveCount(0)
  await page.getByText(session.title, { exact: true }).click()

  const markers = page.locator('.conversation-timeline-marker')
  await expect(markers).toHaveCount(2)
  const timeline = page.getByRole('navigation', { name: '对话回合时间轴' })
  await expect(timeline).toBeVisible()
  const firstMarker = markers.nth(0)
  const secondMarker = markers.nth(1)
  const [timelineBox, streamBox, firstMarkerBox, secondMarkerBox] = await Promise.all([
    timeline.boundingBox(),
    page.locator('.message-stream').boundingBox(),
    firstMarker.boundingBox(),
    secondMarker.boundingBox()
  ])
  expect(timelineBox).not.toBeNull()
  expect(streamBox).not.toBeNull()
  expect(firstMarkerBox).not.toBeNull()
  expect(secondMarkerBox).not.toBeNull()
  expect(timelineBox.x - streamBox.x).toBeLessThanOrEqual(12)
  expect(secondMarkerBox.y).toBeGreaterThan(firstMarkerBox.y)
  await expect(firstMarker).toHaveAttribute('aria-label', /第一轮用户问题.*第一轮模型回答/)

  await firstMarker.focus()
  const firstPreview = firstMarker.locator('.conversation-timeline-marker__preview')
  await expect(firstPreview).toBeVisible()
  await expect(firstPreview.getByText(/第一轮用户问题/)).toBeVisible()
  await expect(firstPreview.getByText(/第一轮模型回答/)).toBeVisible()
  const focusedLineWidths = await markers.locator('.conversation-timeline-marker__line').evaluateAll(
    (lines) => lines.map((line) => line.style.width)
  )
  expect(focusedLineWidths[0]).toBe('30px')
  expect(focusedLineWidths[1]).toBe('24px')

  await firstMarker.click()
  await expectMessageInsideStream(page, 'user-timeline-1')
  await expect(firstMarker).toHaveAttribute('aria-current', 'true')

  await secondMarker.click()
  await expectMessageInsideStream(page, 'user-timeline-2')
  await expect(secondMarker).toHaveAttribute('aria-current', 'true')

  await page.setViewportSize({ width: 375, height: 812 })
  await secondMarker.hover()
  const mobilePreviewBox = await secondMarker.locator('.conversation-timeline-marker__preview').boundingBox()
  expect(mobilePreviewBox).not.toBeNull()
  expect(mobilePreviewBox.x).toBeGreaterThanOrEqual(0)
  expect(mobilePreviewBox.x + mobilePreviewBox.width).toBeLessThanOrEqual(375)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375)
})

async function expectMessageInsideStream(page, messageId) {
  await expect.poll(async () => {
    return page.locator(`[data-message-id="${messageId}"]`).evaluate((message) => {
      const messageRect = message.getBoundingClientRect()
      const streamRect = message.closest('.message-stream').getBoundingClientRect()
      return messageRect.top >= streamRect.top && messageRect.bottom <= streamRect.bottom
    })
  }).toBe(true)
}

function timelineSessionFixture() {
  const now = Date.now()
  const longAnswer = (label) => Array.from(
    { length: 20 },
    (_, index) => `${label}，这是用于验证长对话滚动定位的第 ${index + 1} 段内容。`
  ).join('\n\n')
  return {
    id: 'timeline-session',
    title: '时间轴回归会话',
    summary: '',
    createdAt: now,
    updatedAt: now,
    useKnowledgeBase: true,
    mode: 'HYBRID',
    topK: 8,
    messageCount: 4,
    messages: [
      {
        id: 'user-timeline-1',
        role: 'USER',
        status: 'DONE',
        content: '第一轮用户问题：请总结系统设计。',
        createdAt: now
      },
      {
        id: 'assistant-timeline-1',
        role: 'ASSISTANT',
        status: 'DONE',
        content: longAnswer('第一轮模型回答'),
        createdAt: now + 1
      },
      {
        id: 'user-timeline-2',
        role: 'USER',
        status: 'DONE',
        content: '第二轮用户问题：继续说明测试策略。',
        createdAt: now + 2
      },
      {
        id: 'assistant-timeline-2',
        role: 'ASSISTANT',
        status: 'DONE',
        content: longAnswer('第二轮模型回答'),
        createdAt: now + 3
      }
    ]
  }
}

async function json(route, data) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data })
  })
}
