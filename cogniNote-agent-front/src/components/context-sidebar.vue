<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Database, Pencil, Plus, Settings2, Trash2 } from 'lucide-vue-next'
import { DEFAULT_SETTINGS_ITEM, SETTINGS_NAV_GROUPS, normalizeSettingsItem } from '../config/settings-navigation'
import { useChatStore } from '../stores/chat'
import { useKnowledgeFoldersStore } from '../stores/knowledge-folders'
import { useLayoutStore } from '../stores/layout'
import { useSearchStore } from '../stores/search'

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const knowledgeStore = useKnowledgeFoldersStore()
const searchStore = useSearchStore()
const layoutStore = useLayoutStore()

const canEditSessions = computed(() =>
  !chatStore.isStreaming && !chatStore.isSubmittingChat && !chatStore.isLoadingSessions
)
const activeSettingsItem = computed(() =>
  normalizeSettingsItem(String(route.query.item || DEFAULT_SETTINGS_ITEM))
)
const contextMode = computed(() => {
  if (route.name === 'knowledge') {
    return 'knowledge'
  }
  if (route.name === 'settings') {
    return 'settings'
  }
  return 'chat'
})
const indexHealthLabel = computed(() => {
  if (searchStore.isLoadingIndexStatus) {
    return '读取中'
  }
  if (searchStore.indexError) {
    return '异常'
  }
  return searchStore.indexStatus?.embeddingConfigured ? '向量可用' : '关键词可用'
})
const knowledgeSummary = computed(() =>
  `${knowledgeStore.stats.folderCount} 目录 · ${knowledgeStore.stats.documentCount} 文档 · ${knowledgeStore.stats.chunks} chunks`
)

async function createSession() {
  if (!canEditSessions.value) {
    return
  }
  await chatStore.startNewSession()
  router.push({ name: 'chat' })
}

async function openSession(sessionId) {
  if (chatStore.isSubmittingChat) {
    return
  }
  await chatStore.selectSession(sessionId)
  router.push({ name: 'chat' })
}

async function renameSession(session) {
  if (!canEditSessions.value) {
    return
  }
  const title = window.prompt('重命名会话', session.title)
  if (title == null) {
    return
  }
  await chatStore.renameSession(session.id, title)
}

async function removeSession(session) {
  if (!canEditSessions.value) {
    return
  }
  const confirmed = window.confirm(`删除会话“${session.title}”？本地原始文件和知识库索引不会受影响。`)
  if (!confirmed) {
    return
  }
  await chatStore.removeSession(session.id)
}

function selectSettingsItem(itemId) {
  router.replace({
    name: 'settings',
    query: {
      ...route.query,
      item: normalizeSettingsItem(itemId)
    }
  })
}

</script>

<template>
  <aside
    id="workspace-context-sidebar"
    class="context-sidebar"
    :class="`context-sidebar--${contextMode}`"
    aria-label="当前模块上下文"
    :aria-hidden="layoutStore.isContextSidebarCollapsed"
    :inert="layoutStore.isContextSidebarCollapsed ? '' : null"
  >
    <template v-if="contextMode === 'chat'">
      <header class="context-sidebar__header">
        <div>
          <p class="eyebrow">会话</p>
          <h2>对话记录</h2>
        </div>
        <span>{{ chatStore.sessions.length }}</span>
      </header>

      <button class="context-primary-action" type="button" :disabled="!canEditSessions" @click="createSession">
        <Plus aria-hidden="true" />
        <span>新建对话</span>
      </button>

      <section class="context-session-list" aria-label="会话列表">
        <p v-if="chatStore.isLoadingSessions" class="panel-message">正在读取会话...</p>
        <p v-else-if="!chatStore.sessions.length" class="panel-message">暂无会话，发送第一条消息后会出现在这里。</p>

        <article
          v-for="session in chatStore.sessions"
          :key="session.id"
          class="context-session-item"
          :class="{ active: session.id === chatStore.activeSessionId && route.name === 'chat' }"
        >
          <button
            class="context-session-item__main"
            type="button"
            :disabled="chatStore.isSubmittingChat || (chatStore.isStreaming && session.id !== chatStore.activeSessionId)"
            @click="openSession(session.id)"
          >
            <strong>{{ session.title }}</strong>
            <span>{{ session.messageCount ? `${session.messageCount} 条消息` : '暂无消息' }}</span>
          </button>
          <div class="context-session-item__actions" aria-label="会话操作">
            <button
              class="icon-button"
              type="button"
              title="重命名会话"
              aria-label="重命名会话"
              :disabled="!canEditSessions"
              @click="renameSession(session)"
            >
              <Pencil aria-hidden="true" />
            </button>
            <button
              class="icon-button"
              type="button"
              title="删除会话"
              aria-label="删除会话"
              :disabled="!canEditSessions"
              @click="removeSession(session)"
            >
              <Trash2 aria-hidden="true" />
            </button>
          </div>
        </article>
      </section>
    </template>

    <template v-else-if="contextMode === 'knowledge'">
      <header class="context-sidebar__header">
        <div>
          <p class="eyebrow">知识库</p>
          <h2>本地资料</h2>
        </div>
        <Database aria-hidden="true" />
      </header>

      <section class="context-compact-summary" aria-label="知识库概览">
        <strong>{{ knowledgeSummary }}</strong>
        <span>索引：{{ indexHealthLabel }}</span>
      </section>
    </template>

    <template v-else>
      <header class="context-sidebar__header">
        <div>
          <p class="eyebrow">设置</p>
          <h2>配置中心</h2>
        </div>
        <Settings2 aria-hidden="true" />
      </header>

      <nav class="context-settings-nav" aria-label="设置导航">
        <section v-for="group in SETTINGS_NAV_GROUPS" :key="group.id" class="context-settings-group">
          <h3>{{ group.label }}</h3>
          <button
            v-for="item in group.items"
            :key="item.id"
            type="button"
            :class="{ active: activeSettingsItem === item.id }"
            @click="selectSettingsItem(item.id)"
          >
            {{ item.label }}
          </button>
        </section>
      </nav>
    </template>
  </aside>
</template>
