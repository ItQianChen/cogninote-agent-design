<script setup>
import { h, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn.mjs'
import AppShell from './components/app-shell.vue'
import MarkdownRenderer from './components/markdown-renderer.vue'
import { useChatStore } from './stores/chat'
import { useChatSettingsStore } from './stores/chat-settings'
import { useDesktopUpdateStore } from './stores/desktop-update'
import { useKnowledgeFoldersStore } from './stores/knowledge-folders'
import { useModelConfigStore } from './stores/model-config'
import { useSearchStore } from './stores/search'
import { useSystemStore } from './stores/system'
import { useThemeStore } from './stores/theme'
import { APP_DISPLAY_NAME } from './config/brand'

const systemStore = useSystemStore()
const chatStore = useChatStore()
const chatSettingsStore = useChatSettingsStore()
const knowledgeFoldersStore = useKnowledgeFoldersStore()
const searchStore = useSearchStore()
const modelConfigStore = useModelConfigStore()
const themeStore = useThemeStore()
const desktopUpdateStore = useDesktopUpdateStore()
const elementLocale = zhCn
const STARTUP_UPDATE_NOTE_LIMIT = 5

/**
 * 应用启动时拉取首屏共享快照。
 *
 * <p>这些请求互不阻塞业务页面渲染；失败状态由各自 store 暴露给侧栏、设置页和知识库页。</p>
 */
onMounted(() => {
  themeStore.applyTheme()
  initializeDesktopUpdateCheck()
  chatStore.initializeSessions()
  chatSettingsStore.fetchSettings()
  systemStore.fetchStatus()
  knowledgeFoldersStore.fetchFolders()
  searchStore.fetchIndexStatus()
  modelConfigStore.fetchModelConfig()
})

async function initializeDesktopUpdateCheck() {
  await Promise.all([
    desktopUpdateStore.loadCurrentVersion(),
    desktopUpdateStore.initializeUpdateListener()
  ])
  const update = await desktopUpdateStore.checkStartupStableUpdate()
  if (!update) {
    return
  }

  let shouldDismissVersion = false
  let shouldInstallUpdate = false
  try {
    await ElMessageBox.confirm(
      h('div', { class: 'startup-update-dialog' }, [
        h('p', { class: 'startup-update-dialog__summary' }, `发现正式版 ${update.version}，安装后会重启${APP_DISPLAY_NAME}。`),
        h('section', { class: 'startup-update-dialog__notes' }, [
          h('strong', '更新说明'),
          h(MarkdownRenderer, {
            class: 'startup-update-dialog__markdown',
            content: summarizeStartupUpdateNotes(update.body),
            emptyText: '暂无更新说明。'
          })
        ]),
        h('label', { class: 'startup-update-dialog__dismiss' }, [
          h('input', {
            type: 'checkbox',
            onChange: (event) => {
              shouldDismissVersion = Boolean(event.target?.checked)
            }
          }),
          h('span', '不再提示此版本')
        ])
      ]),
      '应用更新',
      {
        confirmButtonText: '安装并重启',
        cancelButtonText: '稍后',
        type: 'info'
      }
    )
    shouldInstallUpdate = true
    await desktopUpdateStore.installUpdate({ channel: update.channel })
  } catch (err) {
    if (!shouldInstallUpdate && shouldDismissVersion) {
      desktopUpdateStore.dismissStableUpdate(update.version)
    }
    if (err !== 'cancel' && err !== 'close') {
      ElMessage.error(desktopUpdateStore.error || err?.message || '安装更新失败')
    }
  }
}

function summarizeStartupUpdateNotes(notes) {
  const content = notes?.trim()
  if (!content) {
    return ''
  }

  const lines = content.split(/\r?\n/)
  const allItems = lines.filter((line) => /^-\s+/.test(line.trim()))
  if (allItems.length <= STARTUP_UPDATE_NOTE_LIMIT) {
    return content
  }

  const result = []
  const title = lines.find((line) => /^##\s+/.test(line.trim()))
  if (title) {
    result.push(title.trim(), '')
  }

  let currentHeading = ''
  let renderedHeading = ''
  let itemCount = 0
  for (const rawLine of lines) {
    const line = rawLine.trim()
    if (/^###\s+/.test(line)) {
      currentHeading = line
      continue
    }
    if (!/^-\s+/.test(line)) {
      continue
    }
    if (currentHeading && currentHeading !== renderedHeading) {
      if (result.length > 0 && result[result.length - 1] !== '') {
        result.push('')
      }
      result.push(currentHeading, '')
      renderedHeading = currentHeading
    }
    result.push(line)
    itemCount += 1
    if (itemCount >= STARTUP_UPDATE_NOTE_LIMIT) {
      break
    }
  }

  if (itemCount === 0) {
    return content
  }
  result.push('', '更多更新可在设置页查看完整说明。')
  return result.join('\n')
}
</script>

<template>
  <el-config-provider :locale="elementLocale">
    <AppShell>
      <RouterView />
    </AppShell>
  </el-config-provider>
</template>
