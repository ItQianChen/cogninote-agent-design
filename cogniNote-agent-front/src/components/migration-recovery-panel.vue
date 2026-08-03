<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useSystemStore } from '../stores/system'

const systemStore = useSystemStore()
const busy = ref(false)

async function run(action, success) {
  busy.value = true
  try {
    const result = await action()
    ElMessage.success(success(result))
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section v-if="systemStore.status?.mode === 'MIGRATION_RECOVERY'" class="settings-panel migration-recovery-panel">
    <header class="settings-panel__header">
      <p class="eyebrow">数据库恢复</p>
      <h3>迁移未完成</h3>
    </header>
    <el-alert type="warning" :closable="false" title="原始数据库已保留，业务功能暂时停用">
      <p>检测到结构：{{ systemStore.status.detectedSchemaFamily }} / schema {{ systemStore.status.detectedSchemaVersion }}</p>
      <p v-if="systemStore.status.migrationErrorMessage">{{ systemStore.status.migrationErrorMessage }}</p>
    </el-alert>
    <div class="button-row">
      <el-button :loading="busy" @click="run(systemStore.backupMigrationDatabase, result => '原始数据库已备份：' + result.path)">备份原始数据库</el-button>
      <el-button :loading="busy" @click="run(systemStore.exportMigrationDiagnosticsFile, result => '诊断已导出：' + result.path)">导出诊断</el-button>
      <el-button type="primary" :loading="busy" @click="run(systemStore.retryMigration, () => '迁移重试完成，请刷新状态')">重试迁移</el-button>
    </div>
  </section>
</template>
