import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useWebSearchSettingsStore } from './web-search-settings'

const api = vi.hoisted(() => ({
  getWebSearchSettings: vi.fn(),
  testWebSearchSettings: vi.fn(),
  updateWebSearchSettings: vi.fn()
}))

vi.mock('../api/web-search-settings-api', () => api)

beforeEach(() => {
  vi.clearAllMocks()
})

function createDeferred() {
  let resolve
  const promise = new Promise((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

describe('web search settings state', () => {
  it('normalizes remote settings without overwriting a local draft', async () => {
    api.getWebSearchSettings.mockResolvedValue({
      enabled: false,
      apiKeyConfigured: true,
      maxResults: 1,
      maxCallsPerTurn: 1,
      timeoutMs: 1000,
      searchMode: 'fast'
    })
    const store = useWebSearchSettingsStore()
    store.patchSettings({ enabled: true, apiKey: 'draft-key', maxResults: 9 })

    await store.fetchSettings({ force: true })

    expect(store.settings).toMatchObject({
      enabled: true,
      apiKey: 'draft-key',
      apiKeyConfigured: true,
      maxResults: 9
    })
  })

  it('clears the key draft and restores saving state after a successful save', async () => {
    api.updateWebSearchSettings.mockResolvedValue({
      enabled: true,
      apiKeyConfigured: true,
      maxResults: 10,
      maxCallsPerTurn: 3,
      timeoutMs: 30000,
      searchMode: 'fast'
    })
    const store = useWebSearchSettingsStore()
    store.patchSettings({ enabled: true, apiKey: 'secret', maxResults: 99, timeoutMs: 99999 })

    const result = await store.saveSettings()

    expect(api.updateWebSearchSettings).toHaveBeenCalledWith(expect.objectContaining({
      apiKey: 'secret',
      maxResults: 10,
      timeoutMs: 30000
    }))
    expect(result.apiKey).toBe('')
    expect(store.saving).toBe(false)
    expect(store.error).toBe('')
  })

  it('clears a submitted key when only non-secret fields change during save', async () => {
    const pendingSave = createDeferred()
    api.updateWebSearchSettings
      .mockReturnValueOnce(pendingSave.promise)
      .mockResolvedValueOnce({ apiKeyConfigured: true, maxResults: 8 })
    const store = useWebSearchSettingsStore()
    store.patchSettings({ apiKey: 'submitted-key', maxResults: 5 })

    const firstSave = store.saveSettings()
    store.patchSettings({ maxResults: 8 })
    pendingSave.resolve({ apiKeyConfigured: true, maxResults: 5 })
    await firstSave

    expect(store.settings.apiKey).toBe('')
    expect(store.settings.maxResults).toBe(8)
    await store.saveSettings()
    expect(api.updateWebSearchSettings).toHaveBeenLastCalledWith(expect.objectContaining({ apiKey: '' }))
  })

  it('preserves a new key entered while an older key is being saved', async () => {
    const pendingSave = createDeferred()
    api.updateWebSearchSettings.mockReturnValue(pendingSave.promise)
    const store = useWebSearchSettingsStore()
    store.patchSettings({ apiKey: 'submitted-key' })

    const save = store.saveSettings()
    store.patchSettings({ apiKey: 'next-key' })
    pendingSave.resolve({ apiKeyConfigured: true })
    await save

    expect(store.settings.apiKey).toBe('next-key')
  })

  it('restores saving and testing flags after API failures', async () => {
    api.updateWebSearchSettings.mockRejectedValue(new Error('save unavailable'))
    api.testWebSearchSettings.mockRejectedValue(new Error('test unavailable'))
    const store = useWebSearchSettingsStore()

    expect(await store.saveSettings()).toBeNull()
    expect(store.saving).toBe(false)
    expect(store.error).toContain('save unavailable')

    expect(await store.testSettings()).toBeNull()
    expect(store.testing).toBe(false)
    expect(store.error).toContain('test unavailable')
  })
})
