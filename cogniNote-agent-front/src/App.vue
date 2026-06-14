<script setup>
import { onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppShell from './components/app-shell.vue'
import { useChatStore } from './stores/chat'
import { useDesktopUpdateStore } from './stores/desktop-update'
import { useKnowledgeFoldersStore } from './stores/knowledge-folders'
import { useModelConfigStore } from './stores/model-config'
import { useSearchStore } from './stores/search'
import { useSystemStore } from './stores/system'
import { useThemeStore } from './stores/theme'
import { APP_DISPLAY_NAME } from './config/brand'

const systemStore = useSystemStore()
const chatStore = useChatStore()
const knowledgeFoldersStore = useKnowledgeFoldersStore()
const searchStore = useSearchStore()
const modelConfigStore = useModelConfigStore()
const themeStore = useThemeStore()
const desktopUpdateStore = useDesktopUpdateStore()

/**
 * 应用启动时拉取首屏共享快照。
 *
 * <p>这些请求互不阻塞业务页面渲染；失败状态由各自 store 暴露给侧栏、设置页和知识库页。</p>
 */
onMounted(() => {
  themeStore.applyTheme()
  initializeDesktopUpdateCheck()
  chatStore.initializeSessions()
  systemStore.fetchStatus()
  knowledgeFoldersStore.fetchFolders()
  searchStore.fetchIndexStatus()
  modelConfigStore.fetchModelConfig()
})

async function initializeDesktopUpdateCheck() {
  await desktopUpdateStore.initializeUpdateListener()
  const update = await desktopUpdateStore.checkForUpdates({ silent: true })
  if (!update) {
    return
  }

  try {
    await ElMessageBox.confirm(
      `发现新版本 ${update.version}，安装后会重启${APP_DISPLAY_NAME}。`,
      '应用更新',
      {
        confirmButtonText: '安装并重启',
        cancelButtonText: '稍后',
        type: 'info'
      }
    )
    await desktopUpdateStore.installUpdate()
  } catch (err) {
    if (err !== 'cancel') {
      ElMessage.error(desktopUpdateStore.error || err?.message || '安装更新失败')
    }
  }
}
</script>

<template>
  <AppShell>
    <RouterView />
  </AppShell>
</template>
