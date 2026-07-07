import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  cancelMaintenanceRun,
  enqueueDeleteFolder,
  enqueueFolderEnabled,
  enqueueImportFolder,
  enqueueRebuildFolder,
  enqueueRebuildIndex,
  enqueueReparseFolder,
  enqueueRepairFolderIndex,
  enqueueRepairIndex,
  enqueueSyncFolder,
  getMaintenanceQueue,
  getMaintenanceRun,
  streamMaintenanceRun
} from '../api/knowledge-maintenance-api'

const ACTIVE_STATUSES = new Set(['QUEUED', 'RUNNING', 'CANCELLING'])
const RUNNING_STATUSES = new Set(['RUNNING', 'CANCELLING'])
const TERMINAL_STATUSES = new Set(['CANCELLED', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED'])
const COMPLETION_NOTICE_STATUSES = new Set(['COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED'])

/**
 * 知识库维护任务统一状态源。
 *
 * 后端的 knowledge_folder_runs 是事实源；本 store 只缓存队列快照和 SSE 事件。
 * 目录、可信状态和检索页面不能再用局部 busy 标记推断真实任务状态。
 */
export const useKnowledgeMaintenanceStore = defineStore('knowledgeMaintenance', () => {
  const currentRuns = ref([])
  const queuedRuns = ref([])
  const latestRun = ref(null)
  const error = ref('')
  const isLoadingQueue = ref(false)
  const isEnqueueing = ref(false)
  const cancellingRunIds = ref(new Set())
  const completionNoticeRunIds = ref(new Set())
  const completionNoticeRuns = ref([])
  let runAbortController = null
  let subscribedRunId = null
  let snapshotRefreshPromise = null

  const currentRun = computed(() => currentRuns.value[0] || null)
  const hasActiveRun = computed(() => Boolean(currentRun.value || queuedRuns.value.length))
  const completionNoticeRun = computed(() => completionNoticeRuns.value[0] || null)

  async function fetchQueue() {
    isLoadingQueue.value = true
    error.value = ''
    try {
      applyQueue(await getMaintenanceQueue())
      subscribeToMostRelevantRun()
      void syncPendingCompletionNotices()
    } catch (err) {
      error.value = `维护队列读取失败：${err.message}`
    } finally {
      isLoadingQueue.value = false
    }
  }

  function ensureQueueLoaded() {
    if (currentRuns.value.length || queuedRuns.value.length || latestRun.value || isLoadingQueue.value) {
      return Promise.resolve()
    }
    return fetchQueue()
  }

  async function importFolder(payload) {
    return enqueue(() => enqueueImportFolder(payload), { notifyOnComplete: true })
  }

  async function rebuildAllIndex() {
    return enqueue(enqueueRebuildIndex, { notifyOnComplete: true })
  }

  async function repairAllIndex() {
    return enqueue(enqueueRepairIndex, { notifyOnComplete: true })
  }

  async function syncFolder(id) {
    return enqueue(() => enqueueSyncFolder(id))
  }

  async function reparseFolder(id) {
    return enqueue(() => enqueueReparseFolder(id), { notifyOnComplete: true })
  }

  async function rebuildFolder(id) {
    return enqueue(() => enqueueRebuildFolder(id), { notifyOnComplete: true })
  }

  async function repairFolderIndex(id) {
    return enqueue(() => enqueueRepairFolderIndex(id), { notifyOnComplete: true })
  }

  async function setFolderEnabled(id, enabled) {
    return enqueue(() => enqueueFolderEnabled(id, enabled))
  }

  async function deleteFolder(id) {
    return enqueue(() => enqueueDeleteFolder(id))
  }

  async function enqueue(action, options = {}) {
    isEnqueueing.value = true
    error.value = ''
    try {
      const run = await action()
      if (options.notifyOnComplete) {
        trackCompletionNotice(run.id, true)
      }
      upsertRun(run)
      subscribeToRun(run.id)
      await fetchQueue()
      if (!allActiveRuns().some((activeRun) => activeRun.id === run.id)) {
        void refreshKnowledgeSnapshots()
      }
      return run
    } catch (err) {
      error.value = `维护任务入队失败：${err.message}`
      throw err
    } finally {
      isEnqueueing.value = false
    }
  }

  function clearCompletionNotice() {
    acknowledgeCompletionNotice()
  }

  function acknowledgeCompletionNotice() {
    const [, ...remainingRuns] = completionNoticeRuns.value
    completionNoticeRuns.value = remainingRuns
  }

  async function cancelRun(runId) {
    if (!runId) {
      return
    }
    const run = allActiveRuns().find((item) => item.id === runId)
    if (run?.status !== 'QUEUED') {
      error.value = '只能取消等待中的维护任务；正在运行的任务会自动执行到安全完成点。'
      return
    }
    setCancelling(runId, true)
    error.value = ''
    try {
      await cancelMaintenanceRun(runId)
      await fetchQueue()
    } catch (err) {
      error.value = `取消维护任务失败：${err.message}`
    } finally {
      setCancelling(runId, false)
    }
  }

  function activeRunForFolder(folderId) {
    return allActiveRuns().find((run) => run.scopeType === 'KNOWLEDGE_FOLDER' && run.scopeId === folderId) || null
  }

  function activeRunForOperation(operation, scopeType = 'ALL', scopeId = null) {
    return allActiveRuns().find((run) => {
      if (run.operation !== operation || run.scopeType !== scopeType) {
        return false
      }
      return scopeType === 'ALL' || run.scopeId === scopeId
    }) || null
  }

  function isFolderBusy(folderId) {
    return Boolean(activeRunForFolder(folderId))
  }

  function isRunCancelling(runId) {
    return cancellingRunIds.value.has(runId)
  }

  function applyQueue(queue) {
    const activeRuns = uniqueRuns([...(queue?.currentRuns || []), ...(queue?.queuedRuns || [])])
    currentRuns.value = activeRuns.filter((run) => RUNNING_STATUSES.has(run.status))
    queuedRuns.value = activeRuns.filter((run) => run.status === 'QUEUED').sort(queueSort)
    latestRun.value = queue?.latestRun || null
    // SSE 当前只跟随一个 run；队列刷新兜底捕获用户触发任务的终态。
    maybePublishCompletionNotice(latestRun.value)
  }

  function upsertRun(run) {
    if (!run?.id) {
      return
    }
    currentRuns.value = currentRuns.value.filter((item) => item.id !== run.id)
    queuedRuns.value = queuedRuns.value.filter((item) => item.id !== run.id)
    if (run.status === 'RUNNING' || run.status === 'CANCELLING') {
      currentRuns.value = [run, ...currentRuns.value]
    } else if (run.status === 'QUEUED') {
      queuedRuns.value = [...queuedRuns.value, run].sort(queueSort)
    } else if (TERMINAL_STATUSES.has(run.status)) {
      latestRun.value = run
    }
  }

  function handleRunEvent(eventName, payload) {
    if (eventName === 'maintenance-run-snapshot'
        || eventName === 'maintenance-run-queued'
        || eventName === 'maintenance-run-started'
        || eventName === 'maintenance-run-progress'
        || eventName === 'maintenance-run-cancelling') {
      upsertRun(payload)
      return
    }
    if (eventName === 'maintenance-queue-updated') {
      void fetchQueue()
      return
    }
    if (eventName === 'maintenance-run-completed'
        || eventName === 'maintenance-run-failed'
        || eventName === 'maintenance-run-cancelled') {
      upsertRun(payload)
      maybePublishCompletionNotice(payload)
      void refreshKnowledgeSnapshots()
    }
  }

  function subscribeToMostRelevantRun() {
    const target = currentRun.value || queuedRuns.value[0]
    if (target?.id) {
      subscribeToRun(target.id)
    }
  }

  function subscribeToRun(runId) {
    if (!runId) {
      return
    }
    if (subscribedRunId === runId && runAbortController) {
      return
    }
    stopRunStream()
    const controller = new AbortController()
    runAbortController = controller
    subscribedRunId = runId
    void streamMaintenanceRun(runId, {
      signal: controller.signal,
      onEvent: handleRunEvent
    }).then(() => {
      if (!controller.signal.aborted) {
        void refreshKnowledgeSnapshots()
      }
    }).catch((err) => {
      if (err.name === 'AbortError' || controller.signal.aborted) {
        return
      }
      error.value = `维护任务进度连接已断开：${err.message}`
      void refreshKnowledgeSnapshots()
    }).finally(() => {
      if (runAbortController === controller) {
        runAbortController = null
        subscribedRunId = null
      }
    })
  }

  function stopRunStream() {
    if (!runAbortController) {
      return
    }
    runAbortController.abort()
    runAbortController = null
    subscribedRunId = null
  }

  function refreshKnowledgeSnapshots() {
    if (snapshotRefreshPromise) {
      return snapshotRefreshPromise
    }
    /*
     * 维护任务会同时改 SQLite 文档、目录摘要、Lucene 索引和运行队列。
     * 所有页面共用这一个刷新入口，避免可信页、资料页和目录页各自保留过期快照。
     */
    snapshotRefreshPromise = refreshKnowledgeSnapshotsOnce()
      .finally(() => {
        snapshotRefreshPromise = null
      })
    return snapshotRefreshPromise
  }

  async function refreshKnowledgeSnapshotsOnce() {
    const { useKnowledgeFoldersStore } = await import('./knowledge-folders')
    const { useKnowledgeHealthStore } = await import('./knowledge-health')
    const { useSearchStore } = await import('./search')
    const knowledgeStore = useKnowledgeFoldersStore()
    const healthStore = useKnowledgeHealthStore()
    const searchStore = useSearchStore()
    const selectedFolderId = healthStore.selectedFolderId
    await Promise.all([
      fetchQueue(),
      knowledgeStore.fetchFolders(),
      healthStore.fetchHealth(),
      searchStore.fetchIndexStatus()
    ])
    if (healthStore.isDrawerOpen && selectedFolderId && healthStore.folderHealthById.has(selectedFolderId)) {
      await healthStore.fetchFolderHealth(selectedFolderId)
    }
  }

  function allActiveRuns() {
    return [...currentRuns.value, ...queuedRuns.value].filter((run) => ACTIVE_STATUSES.has(run.status))
  }

  function setCancelling(runId, cancelling) {
    const next = new Set(cancellingRunIds.value)
    if (cancelling) {
      next.add(runId)
    } else {
      next.delete(runId)
    }
    cancellingRunIds.value = next
  }

  function trackCompletionNotice(runId, enabled) {
    if (!runId) {
      return
    }
    const next = new Set(completionNoticeRunIds.value)
    if (enabled) {
      next.add(runId)
    } else {
      next.delete(runId)
    }
    completionNoticeRunIds.value = next
  }

  function maybePublishCompletionNotice(run) {
    if (!run?.id || !completionNoticeRunIds.value.has(run.id)) {
      return
    }
    if (COMPLETION_NOTICE_STATUSES.has(run.status)) {
      trackCompletionNotice(run.id, false)
      addCompletionNotice(run)
    }
  }

  async function syncPendingCompletionNotices() {
    const pendingIds = [...completionNoticeRunIds.value]
    await Promise.all(pendingIds.map(async (runId) => {
      try {
        maybePublishCompletionNotice(await getMaintenanceRun(runId))
      } catch (err) {
        // runId 兜底查询失败不应打断队列刷新；下一次刷新或 SSE 事件仍可补偿。
      }
    }))
  }

  function addCompletionNotice(run) {
    const existingIndex = completionNoticeRuns.value.findIndex((item) => item.id === run.id)
    if (existingIndex >= 0) {
      const next = [...completionNoticeRuns.value]
      next[existingIndex] = run
      completionNoticeRuns.value = next
      return
    }
    completionNoticeRuns.value = [...completionNoticeRuns.value, run]
  }

  function uniqueRuns(runs) {
    const byId = new Map()
    runs.forEach((run) => {
      if (run?.id) {
        byId.set(run.id, run)
      }
    })
    return [...byId.values()]
  }

  function queueSort(left, right) {
    return (left.queuePosition || 0) - (right.queuePosition || 0)
      || (left.queuedAt || left.createdAt || 0) - (right.queuedAt || right.createdAt || 0)
  }

  return {
    currentRuns,
    queuedRuns,
    latestRun,
    error,
    isLoadingQueue,
    isEnqueueing,
    completionNoticeRun,
    completionNoticeRuns,
    currentRun,
    hasActiveRun,
    fetchQueue,
    ensureQueueLoaded,
    refreshKnowledgeSnapshots,
    clearCompletionNotice,
    acknowledgeCompletionNotice,
    importFolder,
    rebuildAllIndex,
    repairAllIndex,
    syncFolder,
    reparseFolder,
    rebuildFolder,
    repairFolderIndex,
    setFolderEnabled,
    deleteFolder,
    cancelRun,
    activeRunForFolder,
    activeRunForOperation,
    isFolderBusy,
    isRunCancelling
  }
})
