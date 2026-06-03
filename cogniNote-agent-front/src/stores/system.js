import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getSystemStatus } from '../api/system-api'

export const useSystemStore = defineStore('system', () => {
  const status = ref(null)
  const isLoading = ref(false)
  const error = ref('')

  const connectionLabel = computed(() => {
    if (isLoading.value) {
      return '连接中'
    }
    return error.value ? '未连接' : '已连接'
  })

  async function fetchStatus() {
    isLoading.value = true
    error.value = ''

    try {
      status.value = await getSystemStatus()
    } catch (err) {
      error.value = `后端服务暂不可用：${err.message}`
    } finally {
      isLoading.value = false
    }
  }

  function ensureStatusLoaded() {
    if (status.value || isLoading.value) {
      return Promise.resolve()
    }
    return fetchStatus()
  }

  return {
    status,
    isLoading,
    error,
    connectionLabel,
    fetchStatus,
    ensureStatusLoaded
  }
})
