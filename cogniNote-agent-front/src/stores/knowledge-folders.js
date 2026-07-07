import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { pickKnowledgeFolder } from '../api/desktop-api'
import { listKnowledgeFolders } from '../api/knowledge-folders-api'
import { useKnowledgeMaintenanceStore } from './knowledge-maintenance'

const FAILED_DOCUMENT_STATUSES = new Set(['FAILED', 'OCR_REQUIRED'])

/**
 * 管理知识库目录树、目录级操作和展开状态。
 *
 * <p>folders/unassignedDocuments 是后端快照；维护任务状态统一来自 knowledgeMaintenance store，
 * 这里不再维护目录级 busy 事实，避免和后端队列状态分裂。</p>
 */
export const useKnowledgeFoldersStore = defineStore('knowledgeFolders', () => {
  const folders = ref([])
  const unassignedDocuments = ref([])
  const folderPath = ref('')
  const recursive = ref(true)
  const ingestResult = ref(null)
  const rebuildResult = ref(null)
  const error = ref('')
  const isLoading = ref(false)
  const expandedFolderIds = ref(new Set())
  const maintenanceStore = useKnowledgeMaintenanceStore()
  const isImporting = computed(() => maintenanceStore.isEnqueueing)

  const allDocuments = computed(() => [
    ...folders.value.flatMap((folder) => folder.documents || []),
    ...unassignedDocuments.value
  ])

  const stats = computed(() => {
    const parsed = allDocuments.value.filter((document) => document.status === 'PARSED').length
    const failed = allDocuments.value.filter((document) => FAILED_DOCUMENT_STATUSES.has(document.status)).length
    const chunks = allDocuments.value.reduce((total, document) => total + document.chunkCount, 0)
    return {
      folderCount: folders.value.length,
      documentCount: allDocuments.value.length,
      parsed,
      failed,
      chunks
    }
  })

  async function fetchFolders() {
    isLoading.value = true
    error.value = ''

    try {
      const response = await listKnowledgeFolders()
      folders.value = response?.folders || []
      unassignedDocuments.value = response?.unassignedDocuments || []
    } catch (err) {
      folders.value = []
      unassignedDocuments.value = []
      error.value = `知识库目录读取失败：${err.message}`
    } finally {
      isLoading.value = false
    }
  }

  function ensureFoldersLoaded() {
    if (folders.value.length || unassignedDocuments.value.length || isLoading.value) {
      return Promise.resolve()
    }
    return fetchFolders()
  }

  async function chooseFolder() {
    error.value = ''
    try {
      const selected = await pickKnowledgeFolder()
      if (selected) {
        folderPath.value = selected
      }
    } catch (err) {
      error.value = `打开系统文件夹选择器失败，请手动输入路径：${err.message}`
    }
  }

  async function importFolder() {
    const trimmedFolderPath = folderPath.value.trim()
    if (!trimmedFolderPath) {
      error.value = '请选择或输入要导入的本地目录路径'
      return
    }

    ingestResult.value = null
    rebuildResult.value = null
    error.value = ''

    try {
      ingestResult.value = await maintenanceStore.importFolder({
        folderPath: trimmedFolderPath,
        recursive: recursive.value
      })
    } catch (err) {
      error.value = `导入知识库目录失败：${err.message}`
    }
  }

  async function rebuildFolder(id) {
    rebuildResult.value = null
    error.value = ''

    try {
      rebuildResult.value = await maintenanceStore.rebuildFolder(id)
    } catch (err) {
      error.value = `重建目录索引失败：${err.message}`
    }
  }

  async function repairFolderIndex(id) {
    rebuildResult.value = null
    error.value = ''

    try {
      rebuildResult.value = await maintenanceStore.repairFolderIndex(id)
    } catch (err) {
      error.value = `补写目录索引失败：${err.message}`
    }
  }

  async function syncFolder(id) {
    ingestResult.value = null
    rebuildResult.value = null
    error.value = ''

    try {
      ingestResult.value = await maintenanceStore.syncFolder(id)
    } catch (err) {
      error.value = `同步目录文件失败：${err.message}`
    }
  }

  async function toggleFolderEnabled(folder) {
    error.value = ''

    try {
      await maintenanceStore.setFolderEnabled(folder.id, !folder.enabled)
    } catch (err) {
      error.value = `${folder.enabled ? '停用' : '启用'}知识库目录失败：${err.message}`
    }
  }

  async function deleteFolder(id) {
    error.value = ''

    try {
      await maintenanceStore.deleteFolder(id)
      expandedFolderIds.value.delete(id)
    } catch (err) {
      error.value = `删除知识库目录失败：${err.message}`
    }
  }

  function toggleExpanded(id) {
    if (expandedFolderIds.value.has(id)) {
      expandedFolderIds.value.delete(id)
    } else {
      expandedFolderIds.value.add(id)
    }
  }

  function isExpanded(id) {
    return expandedFolderIds.value.has(id)
  }

  function isFolderBusy(id) {
    return maintenanceStore.isFolderBusy(id)
  }

  return {
    folders,
    unassignedDocuments,
    folderPath,
    recursive,
    ingestResult,
    rebuildResult,
    error,
    isLoading,
    isImporting,
    stats,
    fetchFolders,
    ensureFoldersLoaded,
    chooseFolder,
    importFolder,
    rebuildFolder,
    repairFolderIndex,
    syncFolder,
    toggleFolderEnabled,
    deleteFolder,
    toggleExpanded,
    isExpanded,
    isFolderBusy
  }
})
