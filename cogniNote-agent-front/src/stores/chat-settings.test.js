import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useChatSettingsStore } from './chat-settings'

const api = vi.hoisted(() => ({
  getChatSettings: vi.fn(),
  updateChatSettings: vi.fn()
}))

vi.mock('../api/chat-settings-api', () => api)

beforeEach(() => {
  vi.clearAllMocks()
})

describe('chat settings state', () => {
  it('uses the current layout defaults before settings load', () => {
    const store = useChatSettingsStore()

    expect(store.assistantMessageWidth).toBe(100)
    expect(store.userMessageWidth).toBe(72)
    expect(store.composerWidth).toBe(100)
  })

  it('normalizes persisted widths from the database', async () => {
    api.getChatSettings.mockResolvedValue({
      queryContextualizerMode: 'OFF',
      assistantMessageWidth: 48,
      userMessageWidth: 86.8,
      composerWidth: 104
    })
    const store = useChatSettingsStore()

    await store.fetchSettings({ force: true })

    expect(store.queryContextualizerMode).toBe('OFF')
    expect(store.assistantMessageWidth).toBe(50)
    expect(store.userMessageWidth).toBe(87)
    expect(store.composerWidth).toBe(100)
  })

  it('saves both widths together without losing the current mode', async () => {
    api.updateChatSettings.mockResolvedValue({
      queryContextualizerMode: 'ALWAYS',
      assistantMessageWidth: 84,
      userMessageWidth: 68,
      composerWidth: 76
    })
    const store = useChatSettingsStore()
    store.setQueryContextualizerMode('ALWAYS')
    store.setAssistantMessageWidth(84)
    store.setUserMessageWidth(68)
    store.setComposerWidth(76)

    await store.saveSettings()

    expect(api.updateChatSettings).toHaveBeenCalledWith({
      queryContextualizerMode: 'ALWAYS',
      assistantMessageWidth: 84,
      userMessageWidth: 68,
      composerWidth: 76
    })
    expect(store.assistantMessageWidth).toBe(84)
    expect(store.userMessageWidth).toBe(68)
    expect(store.composerWidth).toBe(76)
  })

  it('keeps the last valid widths when saving fails', async () => {
    api.updateChatSettings.mockRejectedValue(new Error('save unavailable'))
    const store = useChatSettingsStore()
    store.setAssistantMessageWidth(90)
    store.setUserMessageWidth(70)
    store.setComposerWidth(74)

    expect(await store.saveSettings()).toBeNull()
    expect(store.assistantMessageWidth).toBe(90)
    expect(store.userMessageWidth).toBe(70)
    expect(store.composerWidth).toBe(74)
    expect(store.saving).toBe(false)
    expect(store.error).toContain('save unavailable')
  })
})
