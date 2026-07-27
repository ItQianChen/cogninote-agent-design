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
    retryMaintenanceRun: vi.fn(),
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

  it('keeps retry wait runs active and allows cancelling them', async () => {
    const waitingRun = { id: 'run-retry-wait', status: 'RETRY_WAIT', operation: 'SYNC' }
    mocks.api.getMaintenanceQueue
      .mockResolvedValueOnce({ currentRuns: [], queuedRuns: [waitingRun], latestRun: null })
      .mockResolvedValueOnce({ currentRuns: [], queuedRuns: [], latestRun: { ...waitingRun, status: 'CANCELLED' } })
    mocks.api.cancelMaintenanceRun.mockResolvedValue(true)
    const store = useKnowledgeMaintenanceStore()

    await store.fetchQueue()
    expect(store.queuedRuns).toEqual([waitingRun])
    await store.cancelRun(waitingRun.id)

    expect(mocks.api.cancelMaintenanceRun).toHaveBeenCalledWith(waitingRun.id)
  })

  it('creates a new run through the manual retry endpoint', async () => {
    const retriedRun = { id: 'run-new', status: 'QUEUED', operation: 'SYNC', retryOfRunId: 'run-old' }
    mocks.api.retryMaintenanceRun.mockResolvedValue(retriedRun)
    mocks.api.getMaintenanceQueue.mockResolvedValue({ currentRuns: [], queuedRuns: [retriedRun], latestRun: null })
    const store = useKnowledgeMaintenanceStore()

    const result = await store.retryRun('run-old')

    expect(result).toEqual(retriedRun)
    expect(mocks.api.retryMaintenanceRun).toHaveBeenCalledWith('run-old')
  })

  it('treats interrupted SSE payloads as terminal and refreshes snapshots', async () => {
    let onEvent
    mocks.api.getMaintenanceQueue.mockResolvedValue({
      currentRuns: [{ id: 'run-1', status: 'RUNNING', operation: 'SYNC' }],
      queuedRuns: [],
      latestRun: null
    })
    mocks.api.streamMaintenanceRun.mockImplementation((_runId, options) => {
      onEvent = options.onEvent
      return new Promise(() => {})
    })
    const store = useKnowledgeMaintenanceStore()
    await store.fetchQueue()
    vi.clearAllMocks()
    mocks.api.getMaintenanceQueue.mockResolvedValue({
      currentRuns: [],
      queuedRuns: [],
      latestRun: { id: 'run-1', status: 'INTERRUPTED', operation: 'SYNC' }
    })

    onEvent('maintenance-run-failed', { id: 'run-1', status: 'INTERRUPTED', operation: 'SYNC' })

    await vi.waitFor(() => expect(mocks.api.getMaintenanceQueue).toHaveBeenCalledTimes(1))
    expect(store.latestRun.status).toBe('INTERRUPTED')
  })

  it('refreshes the snapshot and reconnects SSE after an unexpected disconnect', async () => {
    const activeRun = { id: 'run-reconnect', status: 'RUNNING', operation: 'SYNC' }
    mocks.api.getMaintenanceQueue.mockResolvedValue({ currentRuns: [activeRun], queuedRuns: [], latestRun: null })
    mocks.api.streamMaintenanceRun
      .mockRejectedValueOnce(new Error('offline'))
      .mockImplementationOnce(() => new Promise(() => {}))
    const store = useKnowledgeMaintenanceStore()

    await store.fetchQueue()

    await vi.waitFor(() => expect(mocks.api.streamMaintenanceRun).toHaveBeenCalledTimes(2), { timeout: 2500 })
    expect(mocks.api.getMaintenanceQueue).toHaveBeenCalledTimes(2)
    expect(store.currentRun).toEqual(activeRun)
  })

  it('accepts legacy run snapshots without durability metadata', async () => {
    const legacyRun = { id: 'legacy-run', status: 'QUEUED', operation: 'SYNC' }
    mocks.api.getMaintenanceQueue.mockResolvedValue({ currentRuns: [], queuedRuns: [legacyRun], latestRun: null })
    const store = useKnowledgeMaintenanceStore()

    await store.fetchQueue()

    expect(store.queuedRuns).toEqual([legacyRun])
    expect(store.error).toBe('')
  })
})
