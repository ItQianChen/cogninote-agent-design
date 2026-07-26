import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useDataProtectionStore } from './data-protection'

const systemApi = vi.hoisted(() => ({
  createBackup: vi.fn(),
  discardRestore: vi.fn(),
  getDataProtectionStatus: vi.fn(),
  preflightRestore: vi.fn(),
  scheduleRestore: vi.fn()
}))
const desktopApi = vi.hoisted(() => ({
  isTauriRuntime: vi.fn(() => true),
  restartAfterRestore: vi.fn(),
  saveBackupFile: vi.fn(),
  stageRestoreFile: vi.fn()
}))

vi.mock('../api/system-api', () => systemApi)
vi.mock('../api/desktop-api', () => desktopApi)

beforeEach(() => {
  vi.clearAllMocks()
  desktopApi.isTauriRuntime.mockReturnValue(true)
})

describe('data protection state', () => {
  it('creates a managed backup before asking Tauri to save it', async () => {
    systemApi.createBackup.mockResolvedValue({
      backupId: 'backup-id',
      suggestedFileName: 'CogniNote.cogninote-backup'
    })
    desktopApi.saveBackupFile.mockResolvedValue(true)
    systemApi.getDataProtectionStatus.mockResolvedValue({ schemaVersion: 2 })
    const store = useDataProtectionStore()

    const result = await store.createAndSaveBackup()

    expect(desktopApi.saveBackupFile).toHaveBeenCalledWith(
      'backup-id',
      'CogniNote.cogninote-backup'
    )
    expect(result.saved).toBe(true)
    expect(store.isBackingUp).toBe(false)
  })

  it('preflights a staged file before scheduling restart', async () => {
    desktopApi.stageRestoreFile.mockResolvedValue('import-id')
    systemApi.preflightRestore.mockResolvedValue({ restoreId: 'restore-id', phase: 'PREFLIGHTED' })
    systemApi.scheduleRestore.mockResolvedValue({ restartRequired: true })
    const store = useDataProtectionStore()

    await store.selectAndPreflightRestore()
    await store.scheduleAndRestart()

    expect(systemApi.preflightRestore).toHaveBeenCalledWith('import-id')
    expect(systemApi.scheduleRestore).toHaveBeenCalledWith('restore-id')
    expect(desktopApi.restartAfterRestore).toHaveBeenCalledOnce()
    expect(store.isRestoring).toBe(false)
  })

  it('does not call preflight when the file dialog is cancelled', async () => {
    desktopApi.stageRestoreFile.mockResolvedValue(null)
    const store = useDataProtectionStore()

    expect(await store.selectAndPreflightRestore()).toBeNull()
    expect(systemApi.preflightRestore).not.toHaveBeenCalled()
    expect(store.isRestoring).toBe(false)
  })

  it('discards the sensitive work copy when restore confirmation is cancelled', async () => {
    desktopApi.stageRestoreFile.mockResolvedValue('import-id')
    systemApi.preflightRestore.mockResolvedValue({ restoreId: 'restore-id', phase: 'PREFLIGHTED' })
    systemApi.discardRestore.mockResolvedValue({ restoreId: 'restore-id', phase: 'DISCARDED' })
    const store = useDataProtectionStore()

    await store.selectAndPreflightRestore()
    const result = await store.discardPreflightedRestore()

    expect(systemApi.discardRestore).toHaveBeenCalledWith('restore-id')
    expect(result.phase).toBe('DISCARDED')
    expect(store.restore).toBeNull()
    expect(store.isRestoring).toBe(false)
  })
})
