<script setup>
import { computed, onMounted } from 'vue'
import { HardDriveDownload, RotateCcw } from 'lucide-vue-next'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useDataProtectionStore } from '../stores/data-protection'

const dataProtectionStore = useDataProtectionStore()

const lastResult = computed(() => {
  const status = dataProtectionStore.status
  if (!status?.lastOperation) {
    return '-'
  }
  const operation = { BACKUP: '备份', RESTORE: '恢复', MIGRATION: '迁移' }[status.lastOperation] || status.lastOperation
  const result = {
    COMPLETED: '已完成',
    FAILED: '失败',
    SCHEDULED: '等待重启',
    PREFLIGHTED: '已通过预检',
    DISCARDED: '已取消',
    REINDEXING: '正在重建索引',
    ROLLED_BACK: '已回滚',
    REINDEX_FAILED: '索引重建失败'
  }[status.lastStatus] || status.lastStatus
  return `${operation} / ${result}`
})

onMounted(() => {
  dataProtectionStore.fetchStatus()
})

async function handleBackup() {
  try {
    await ElMessageBox.confirm(
      '备份包含模型和联网搜索 API Key 明文。请只保存到可信位置，不要上传到公开网盘或发送给他人。',
      '创建完整备份',
      {
        confirmButtonText: '继续备份',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    const result = await dataProtectionStore.createAndSaveBackup()
    if (result?.saved) {
      ElMessage.success('备份已保存')
    }
  } catch (err) {
    if (err !== 'cancel') {
      ElMessage.error(dataProtectionStore.error || err?.message || '备份失败')
    }
  }
}

async function handleRestore() {
  let preflighted = false
  try {
    const restore = await dataProtectionStore.selectAndPreflightRestore()
    if (!restore) {
      return
    }
    preflighted = true
    await ElMessageBox.confirm(
      `将恢复 ${restore.documentCount} 份文档、${restore.chatSessionCount} 个会话和 ${restore.graphNodeCount} 个图谱节点。备份包含明文 API Key，当前数据会保留回滚副本。`,
      '确认恢复并重启',
      {
        confirmButtonText: '恢复并重启',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    await dataProtectionStore.scheduleAndRestart()
  } catch (err) {
    if (preflighted && (err === 'cancel' || err === 'close')) {
      try {
        await dataProtectionStore.discardPreflightedRestore()
      } catch (cleanupError) {
        ElMessage.error(dataProtectionStore.error || cleanupError?.message || '清理恢复临时数据失败')
      }
    } else if (err !== 'cancel' && err !== 'close') {
      ElMessage.error(dataProtectionStore.error || err?.message || '恢复失败')
    }
  }
}
</script>

<template>
  <section class="settings-panel data-protection-panel">
    <header class="settings-panel__header">
      <p class="eyebrow">系统</p>
      <h3>备份与恢复</h3>
    </header>

    <div class="settings-card data-protection-card">
      <el-alert
        title="备份文件包含 API Key 明文，只应保存在可信位置。"
        type="warning"
        :closable="false"
        show-icon
      />

      <el-descriptions class="settings-descriptions" :column="2" border>
        <el-descriptions-item label="数据库版本">
          {{ dataProtectionStore.status?.schemaVersion ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="最近结果">{{ lastResult }}</el-descriptions-item>
        <el-descriptions-item label="待恢复">
          {{ dataProtectionStore.status?.pendingRestore ? '重启后执行' : '无' }}
        </el-descriptions-item>
        <el-descriptions-item label="运行环境">
          {{ dataProtectionStore.isDesktopRuntime ? '桌面版' : '浏览器模式' }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="button-row">
        <el-button
          :disabled="!dataProtectionStore.isDesktopRuntime || dataProtectionStore.isRestoring"
          :loading="dataProtectionStore.isBackingUp"
          @click="handleBackup"
        >
          <HardDriveDownload aria-hidden="true" />
          创建备份
        </el-button>
        <el-button
          type="danger"
          plain
          :disabled="!dataProtectionStore.isDesktopRuntime || dataProtectionStore.isBackingUp"
          :loading="dataProtectionStore.isRestoring"
          @click="handleRestore"
        >
          <RotateCcw aria-hidden="true" />
          从备份恢复
        </el-button>
      </div>
    </div>
  </section>
</template>
