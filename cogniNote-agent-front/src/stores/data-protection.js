import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { isTauriRuntime, restartAfterRestore, saveBackupFile, stageRestoreFile } from '../api/desktop-api'
import {
  createBackup,
  discardRestore,
  getDataProtectionStatus,
  preflightRestore,
  scheduleRestore
} from '../api/system-api'

/**
 * 管理备份生成、恢复预检和重启调度。
 *
 * <p>文件系统路径只存在于 Tauri 命令中；store 和 REST 只持有随机 ID。</p>
 */
export const useDataProtectionStore = defineStore('data-protection', () => {
  const status = ref(null)
  const restore = ref(null)
  const isLoading = ref(false)
  const isBackingUp = ref(false)
  const isRestoring = ref(false)
  const error = ref('')

  const isDesktopRuntime = computed(() => isTauriRuntime())

  async function fetchStatus() {
    isLoading.value = true
    error.value = ''
    try {
      status.value = await getDataProtectionStatus()
      return status.value
    } catch (err) {
      error.value = err.message || '无法读取备份恢复状态'
      return null
    } finally {
      isLoading.value = false
    }
  }

  async function createAndSaveBackup() {
    isBackingUp.value = true
    error.value = ''
    try {
      const backup = await createBackup()
      const saved = await saveBackupFile(backup.backupId, backup.suggestedFileName)
      await fetchStatus()
      return { backup, saved }
    } catch (err) {
      error.value = err.message || '备份失败'
      throw err
    } finally {
      isBackingUp.value = false
    }
  }

  async function selectAndPreflightRestore() {
    isRestoring.value = true
    error.value = ''
    restore.value = null
    try {
      const importId = await stageRestoreFile()
      if (!importId) {
        return null
      }
      restore.value = await preflightRestore(importId)
      return restore.value
    } catch (err) {
      error.value = err.message || '恢复包校验失败'
      throw err
    } finally {
      isRestoring.value = false
    }
  }

  async function discardPreflightedRestore() {
    if (!restore.value?.restoreId) {
      return null
    }
    isRestoring.value = true
    error.value = ''
    try {
      const discarded = await discardRestore(restore.value.restoreId)
      restore.value = null
      return discarded
    } catch (err) {
      error.value = err.message || '清理恢复临时数据失败'
      throw err
    } finally {
      isRestoring.value = false
    }
  }

  async function scheduleAndRestart() {
    if (!restore.value?.restoreId) {
      throw new Error('恢复任务尚未通过预检')
    }
    isRestoring.value = true
    error.value = ''
    try {
      await scheduleRestore(restore.value.restoreId)
      await restartAfterRestore()
    } catch (err) {
      error.value = err.message || '安排恢复失败'
      throw err
    } finally {
      isRestoring.value = false
    }
  }

  return {
    status,
    restore,
    isLoading,
    isBackingUp,
    isRestoring,
    error,
    isDesktopRuntime,
    fetchStatus,
    createAndSaveBackup,
    selectAndPreflightRestore,
    discardPreflightedRestore,
    scheduleAndRestart
  }
})
