import { ref } from 'vue'
import { defineStore } from 'pinia'
import { getIndexStatus, rebuildSearchIndex, searchKnowledge as requestSearch } from '../api/search-api'

export const SEARCH_MODES = [
  { label: '关键词', value: 'KEYWORD' },
  { label: '向量', value: 'VECTOR' },
  { label: '混合', value: 'HYBRID' }
]

export const useSearchStore = defineStore('search', () => {
  const indexStatus = ref(null)
  const rebuildResult = ref(null)
  const searchResult = ref(null)
  const isLoadingIndexStatus = ref(false)
  const isRebuildingIndex = ref(false)
  const isSearching = ref(false)
  const indexError = ref('')
  const searchError = ref('')
  const query = ref('')
  const mode = ref('KEYWORD')
  const topK = ref(8)

  async function fetchIndexStatus() {
    isLoadingIndexStatus.value = true
    indexError.value = ''

    try {
      indexStatus.value = await getIndexStatus()
    } catch (err) {
      indexError.value = `索引状态读取失败：${err.message}`
    } finally {
      isLoadingIndexStatus.value = false
    }
  }

  function ensureIndexStatusLoaded() {
    if (indexStatus.value || isLoadingIndexStatus.value) {
      return Promise.resolve()
    }
    return fetchIndexStatus()
  }

  async function rebuildIndex() {
    isRebuildingIndex.value = true
    rebuildResult.value = null
    indexError.value = ''

    try {
      rebuildResult.value = await rebuildSearchIndex()
      await fetchIndexStatus()
    } catch (err) {
      indexError.value = `重建索引失败：${err.message}`
    } finally {
      isRebuildingIndex.value = false
    }
  }

  async function searchKnowledge() {
    const trimmedQuery = query.value.trim()
    if (!trimmedQuery) {
      searchError.value = '请输入检索关键词'
      return
    }

    isSearching.value = true
    searchResult.value = null
    searchError.value = ''

    try {
      searchResult.value = await requestSearch({
        query: trimmedQuery,
        mode: mode.value,
        topK: Number(topK.value)
      })
    } catch (err) {
      searchError.value = `检索失败：${err.message}`
    } finally {
      isSearching.value = false
    }
  }

  return {
    indexStatus,
    rebuildResult,
    searchResult,
    isLoadingIndexStatus,
    isRebuildingIndex,
    isSearching,
    indexError,
    searchError,
    query,
    mode,
    topK,
    fetchIndexStatus,
    ensureIndexStatusLoaded,
    rebuildIndex,
    searchKnowledge
  }
})
