import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { deleteDocumentRecord, ingestDocuments as requestIngest, listDocuments } from '../api/documents-api'
import { useSearchStore } from './search'

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
    const failed = documents.value.filter((document) => document.status === 'FAILED').length
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
