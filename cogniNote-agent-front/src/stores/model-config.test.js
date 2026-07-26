import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useModelConfigStore } from './model-config'

const mocks = vi.hoisted(() => ({
  api: {
    activateSettingsModelConfig: vi.fn(),
    createSettingsModelConfig: vi.fn(),
    deleteSettingsModelConfig: vi.fn(),
    fetchModelOptions: vi.fn(),
    getActiveModelConfigs: vi.fn(),
    getModelConfigSettings: vi.fn(),
    testModelConfig: vi.fn(),
    updateSettingsModelConfig: vi.fn()
  },
  search: { fetchIndexStatus: vi.fn() }
}))

vi.mock('../api/model-config-api', () => mocks.api)
vi.mock('./search', () => ({ useSearchStore: () => mocks.search }))

function snapshot(displayName = 'Remote chat') {
  const selectedConfig = {
    id: 'chat-1',
    role: 'CHAT',
    provider: 'DASHSCOPE',
    displayName,
    baseUrl: 'https://dashscope.aliyuncs.com/api/v1',
    modelName: 'qwen-plus',
    apiKeyConfigured: true,
    defaultTopK: 8,
    contextWindowTokens: 128000,
    temperature: 0.7
  }
  return {
    role: 'CHAT',
    active: { chat: selectedConfig, embedding: null, vision: null },
    configs: [selectedConfig],
    selectedConfig
  }
}

function createDeferred() {
  let resolve
  const promise = new Promise((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.api.getActiveModelConfigs.mockResolvedValue({ chat: null, embedding: null, vision: null })
  mocks.search.fetchIndexStatus.mockResolvedValue(undefined)
})

describe('model config editor state', () => {
  it('does not replace a dirty form when a reload response arrives', async () => {
    mocks.api.getModelConfigSettings
      .mockResolvedValueOnce(snapshot('Initial remote'))
      .mockResolvedValueOnce(snapshot('New remote'))
    const store = useModelConfigStore()
    await store.enterModelSettings()
    store.form.displayName = 'Local draft'
    store.markFormTouched()

    await store.reloadEditor()

    expect(store.form.displayName).toBe('Local draft')
    expect(store.selectedConfig.displayName).toBe('New remote')
    expect(store.isLoadingModelConfig).toBe(false)
  })

  it('restores saving state after success and failure', async () => {
    mocks.api.getModelConfigSettings.mockResolvedValue(snapshot())
    mocks.api.updateSettingsModelConfig.mockResolvedValue(snapshot('Saved chat'))
    const store = useModelConfigStore()
    await store.enterModelSettings()

    expect(await store.saveModelConfig()).toEqual(snapshot('Saved chat'))
    expect(store.isSavingModelConfig).toBe(false)
    expect(store.error).toBe('')
    expect(store.form.displayName).toBe('Saved chat')

    mocks.api.updateSettingsModelConfig.mockRejectedValue(new Error('save unavailable'))
    expect(await store.saveModelConfig()).toBeNull()
    expect(store.isSavingModelConfig).toBe(false)
    expect(store.error).toContain('save unavailable')
  })

  it('clears a submitted key when only non-secret fields change during save', async () => {
    const pendingSave = createDeferred()
    mocks.api.getModelConfigSettings.mockResolvedValue(snapshot())
    mocks.api.updateSettingsModelConfig
      .mockReturnValueOnce(pendingSave.promise)
      .mockResolvedValueOnce(snapshot('Saved again'))
    const store = useModelConfigStore()
    await store.enterModelSettings()
    store.updateApiKey('submitted-key')

    const firstSave = store.saveModelConfig()
    store.form.displayName = 'New local draft'
    store.markFormTouched()
    pendingSave.resolve(snapshot('Saved remote'))
    await firstSave

    expect(store.form.displayName).toBe('New local draft')
    expect(store.form.apiKey).toBe('')
    expect(store.form.clearApiKey).toBe(false)
    await store.saveModelConfig()
    expect(mocks.api.updateSettingsModelConfig).toHaveBeenLastCalledWith(
      'chat-1',
      expect.objectContaining({ apiKey: '', clearApiKey: false })
    )
  })

  it('preserves a new key entered while an older key is being saved', async () => {
    const pendingSave = createDeferred()
    mocks.api.getModelConfigSettings.mockResolvedValue(snapshot())
    mocks.api.updateSettingsModelConfig.mockReturnValue(pendingSave.promise)
    const store = useModelConfigStore()
    await store.enterModelSettings()
    store.updateApiKey('submitted-key')

    const save = store.saveModelConfig()
    store.updateApiKey('next-key')
    pendingSave.resolve(snapshot('Saved remote'))
    await save

    expect(store.form.apiKey).toBe('next-key')
  })

  it('restores connection-test state after a rejected request', async () => {
    mocks.api.testModelConfig.mockRejectedValue(new Error('provider unavailable'))
    const store = useModelConfigStore()

    await store.testModelConfig()

    expect(store.isTestingModelConfig).toBe(false)
    expect(store.error).toContain('provider unavailable')
  })
})
