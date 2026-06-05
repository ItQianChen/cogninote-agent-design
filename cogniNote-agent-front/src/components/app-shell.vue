<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Pencil, Trash2 } from 'lucide-vue-next'
import StatusPill from './status-pill.vue'
import { useChatStore } from '../stores/chat'
import { useSystemStore } from '../stores/system'

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const systemStore = useSystemStore()
const isSettingsRoute = computed(() => route.name === 'settings')
const canEditSessions = computed(() => !chatStore.isStreaming && !chatStore.isLoadingSessions)

const pillState = computed(() => {
  if (systemStore.isLoading) {
    return 'loading'
  }
  return systemStore.error ? 'error' : 'ok'
})

async function createSession() {
  await chatStore.startNewSession()
  router.push({ name: 'chat' })
}

async function openSession(sessionId) {
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
</script>

<template>
  <main v-if="isSettingsRoute" class="settings-shell">
    <slot />
  </main>

  <main v-else class="desktop-shell">
    <aside class="chat-sidebar" aria-label="对话列表">
      <section class="brand-panel">
        <div class="brand-mark">CN</div>
        <div>
          <p class="eyebrow">本地知识库智能体</p>
          <h1>CogniNote</h1>
        </div>
      </section>

      <button class="new-chat-button" type="button" :disabled="!canEditSessions" @click="createSession">
        新建对话
      </button>

      <section class="session-list" aria-label="会话">
        <div class="sidebar-section-title">
          <span>会话</span>
          <em>{{ chatStore.sessions.length }}</em>
        </div>
        <div
          v-for="session in chatStore.sessions"
          :key="session.id"
          class="session-item"
          :class="{ active: session.id === chatStore.activeSessionId && route.name === 'chat' }"
        >
          <button
            class="session-item__main"
            type="button"
            :disabled="chatStore.isStreaming && session.id !== chatStore.activeSessionId"
            @click="openSession(session.id)"
          >
            <strong>{{ session.title }}</strong>
            <span>{{ session.messageCount ? `${session.messageCount} 条消息` : '暂无消息' }}</span>
          </button>
          <div class="session-item__actions" aria-label="会话操作">
            <button
              class="session-action-button"
              type="button"
              title="重命名会话"
              aria-label="重命名会话"
              :disabled="!canEditSessions"
              @click="renameSession(session)"
            >
              <Pencil aria-hidden="true" />
            </button>
            <button
              class="session-action-button"
              type="button"
              title="删除会话"
              aria-label="删除会话"
              :disabled="!canEditSessions"
              @click="removeSession(session)"
            >
              <Trash2 aria-hidden="true" />
            </button>
          </div>
        </div>
      </section>

      <footer class="sidebar-footer">
        <div class="sidebar-status">
          <span>后端</span>
          <StatusPill :label="systemStore.connectionLabel" :state="pillState" />
        </div>
        <RouterLink class="settings-link" :to="{ name: 'settings' }">设置</RouterLink>
      </footer>
    </aside>

    <section class="desktop-workspace">
      <slot />
    </section>
  </main>
</template>
