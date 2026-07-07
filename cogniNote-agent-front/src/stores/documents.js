import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { deleteDocumentRecord, ingestDocuments as requestIngest, listDocuments } from '../api/documents-api'
import { useSearchStore } from './search'

const FAILED_DOCUMENT_STATUSES = new Set(['FAILED', 'OCR_REQUIRED'])

/**
 * 管理未归属文档列表和目录导入表单。
 *
 * <p>文档导入/删除会改变后端 SQLite 与 Lucene 索引，完成后必须刷新搜索索引状态，避免知识库页显示旧可用性。</p>
 */
export const useDocumentsStore = defineStore('documents', () => {
  const documents = ref([])
  const ingestResult = ref(null)
  const isLoadingDocuments = ref(false)
  const isIngesting = ref(false)
  const documentError = ref('')
  const folderPath = ref('')
  const recursive = ref(true)

  const stats = computed(() => {
    const parsed = documents.value.filter((document) => document.status === 'PARSED').length
    const failed = documents.value.filter((document) => FAILED_DOCUMENT_STATUSES.has(document.status)).length
    const chunks = documents.value.reduce((total, document) => total + document.chunkCount, 0)
    return { parsed, failed, chunks }
  })

  async function fetchDocuments() {
    isLoadingDocuments.value = true
    documentError.value = ''

    try {
      documents.value = await listDocuments()
    } catch (err) {
      documents.value = []
      documentError.value = `文档列表读取失败：${err.message}`
    } finally {
      isLoadingDocuments.value = false
    }
  }

  function ensureDocumentsLoaded() {
    if (documents.value.length || isLoadingDocuments.value) {
      return Promise.resolve()
    }
    return fetchDocuments()
  }

  async function ingestDocuments() {
    const trimmedFolderPath = folderPath.value.trim()
    if (!trimmedFolderPath) {
      documentError.value = '请输入要导入的本地目录路径'
      return
    }

    const searchStore = useSearchStore()
    isIngesting.value = true
    ingestResult.value = null
    documentError.value = ''

    try {
      ingestResult.value = await requestIngest({
        folderPath: trimmedFolderPath,
        recursive: recursive.value
      })
      await fetchDocuments()
      await searchStore.fetchIndexStatus()
    } catch (err) {
      documentError.value = `导入失败：${err.message}`
    } finally {
      isIngesting.value = false
    }
  }

  async function deleteDocument(id) {
    const searchStore = useSearchStore()
    documentError.value = ''

    try {
      await deleteDocumentRecord(id)
      await fetchDocuments()
      await searchStore.fetchIndexStatus()
      // 已展示的检索结果可能包含被删 chunk，删除后主动重搜才能让结果和索引状态同步。
      if (searchStore.searchResult?.hits?.length) {
        await searchStore.searchKnowledge()
      }
    } catch (err) {
      documentError.value = `删除索引记录失败：${err.message}`
    }
  }

  return {
    documents,
    ingestResult,
    isLoadingDocuments,
    isIngesting,
    documentError,
    folderPath,
    recursive,
    stats,
    fetchDocuments,
    ensureDocumentsLoaded,
    ingestDocuments,
    deleteDocument
  }
})
