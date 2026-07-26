import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useKnowledgeMaintenanceStore } from './knowledge-maintenance'

const mocks = vi.hoisted(() => ({
  api: {
    cancelMaintenanceRun: vi.fn(),
    enqueueDeleteFolder: vi.fn(),
    enqueueFolderEnabled: vi.fn(),
    enqueueImportFolder: vi.fn(),
    enqueueRebuildFolder: vi.fn(),
    enqueueRebuildIndex: vi.fn(),
    enqueueReparseFolder: vi.fn(),
    enqueueRepairFolderIndex: vi.fn(),
    enqueueRepairIndex: vi.fn(),
    enqueueSyncFolder: vi.fn(),
    getMaintenanceQueue: vi.fn(),
    getMaintenanceRun: vi.fn(),
    streamMaintenanceRun: vi.fn()
  },
  folders: { fetchFolders: vi.fn() },
  health: {
    selectedFolderId: null,
    isDrawerOpen: false,
    folderHealthById: new Map(),
    fetchHealth: vi.fn(),
    fetchFolderHealth: vi.fn()
  },
  search: { fetchIndexStatus: vi.fn() }
}))

vi.mock('../api/knowledge-maintenance-api', () => mocks.api)
vi.mock('./knowledge-folders', () => ({ useKnowledgeFoldersStore: () => mocks.folders }))
vi.mock('./knowledge-health', () => ({ useKnowledgeHealthStore: () => mocks.health }))
vi.mock('./search', () => ({ useSearchStore: () => mocks.search }))

beforeEach(() => {
  vi.clearAllMocks()
  mocks.api.getMaintenanceQueue.mockResolvedValue({ currentRuns: [], queuedRuns: [], latestRun: null })
  mocks.api.streamMaintenanceRun.mockImplementation(() => new Promise(() => {}))
  mocks.folders.fetchFolders.mockResolvedValue(undefined)
  mocks.health.fetchHealth.mockResolvedValue(undefined)
  mocks.health.fetchFolderHealth.mockResolvedValue(undefined)
  mocks.search.fetchIndexStatus.mockResolvedValue(undefined)
})

describe('knowledge maintenance state transitions', () => {
  it('coalesces concurrent snapshot refreshes into one request set', async () => {
    const store = useKnowledgeMaintenanceStore()

    await Promise.all([
      store.refreshKnowledgeSnapshots(),
      store.refreshKnowledgeSnapshots()
    ])

    expect(mocks.api.getMaintenanceQueue).toHaveBeenCalledTimes(1)
    expect(mocks.folders.fetchFolders).toHaveBeenCalledTimes(1)
    expect(mocks.health.fetchHealth).toHaveBeenCalledTimes(1)
    expect(mocks.search.fetchIndexStatus).toHaveBeenCalledTimes(1)
  })

  it('refreshes queue, folders, health, and search after a terminal SSE event', async () => {
    let onEvent
    mocks.api.enqueueRebuildIndex.mockResolvedValue({ id: 'run-1', status: 'QUEUED', operation: 'REBUILD_INDEX' })
    mocks.api.getMaintenanceQueue.mockResolvedValue({
      currentRuns: [],
      queuedRuns: [{ id: 'run-1', status: 'QUEUED', operation: 'REBUILD_INDEX' }],
      latestRun: null
    })
    mocks.api.streamMaintenanceRun.mockImplementation((_runId, options) => {
      onEvent = options.onEvent
      return new Promise(() => {})
    })
    const store = useKnowledgeMaintenanceStore()
    await store.rebuildAllIndex()
    vi.clearAllMocks()
    mocks.api.getMaintenanceQueue.mockResolvedValue({
      currentRuns: [],
      queuedRuns: [],
      latestRun: { id: 'run-1', status: 'COMPLETED', operation: 'REBUILD_INDEX' }
    })

    onEvent('maintenance-run-completed', {
      id: 'run-1',
      status: 'COMPLETED',
      operation: 'REBUILD_INDEX'
    })

    await vi.waitFor(() => expect(mocks.search.fetchIndexStatus).toHaveBeenCalledTimes(1))
    expect(mocks.api.getMaintenanceQueue).toHaveBeenCalledTimes(1)
    expect(mocks.folders.fetchFolders).toHaveBeenCalledTimes(1)
    expect(mocks.health.fetchHealth).toHaveBeenCalledTimes(1)
    expect(store.completionNoticeRun?.id).toBe('run-1')
  })

  it('does not report an intentional stream cancellation as a disconnect error', async () => {
    mocks.api.enqueueSyncFolder
      .mockResolvedValueOnce({ id: 'run-1', status: 'QUEUED', scopeType: 'KNOWLEDGE_FOLDER', scopeId: 'folder-1' })
      .mockResolvedValueOnce({ id: 'run-2', status: 'QUEUED', scopeType: 'KNOWLEDGE_FOLDER', scopeId: 'folder-2' })
    mocks.api.getMaintenanceQueue.mockResolvedValue({ currentRuns: [], queuedRuns: [], latestRun: null })
    mocks.api.streamMaintenanceRun.mockImplementation((_runId, { signal }) => new Promise((resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    }))
    const store = useKnowledgeMaintenanceStore()

    await store.syncFolder('folder-1')
    await store.syncFolder('folder-2')
    await Promise.resolve()

    expect(store.error).not.toContain('进度连接已断开')
  })
})
