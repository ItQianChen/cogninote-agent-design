<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StatusPill from './status-pill.vue'
import { useChatStore } from '../stores/chat'
import { useSystemStore } from '../stores/system'

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const systemStore = useSystemStore()
const isSettingsRoute = computed(() => route.name === 'settings')

const pillState = computed(() => {
  if (systemStore.isLoading) {
    return 'loading'
  }
  return systemStore.error ? 'error' : 'ok'
})

function createSession() {
  chatStore.startNewSession()
  router.push({ name: 'chat' })
}

function openSession(sessionId) {
  chatStore.selectSession(sessionId)
  router.push({ name: 'chat' })
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

      <button class="new-chat-button" type="button" :disabled="chatStore.isStreaming" @click="createSession">
        新建对话
      </button>

      <section class="session-list" aria-label="临时会话">
        <div class="sidebar-section-title">
          <span>对话</span>
          <em>{{ chatStore.sessions.length }}</em>
        </div>
        <button
          v-for="session in chatStore.sessions"
          :key="session.id"
          class="session-item"
          :class="{ active: session.id === chatStore.activeSessionId && route.name === 'chat' }"
          type="button"
          :disabled="chatStore.isStreaming && session.id !== chatStore.activeSessionId"
          @click="openSession(session.id)"
        >
          <strong>{{ session.title }}</strong>
          <span>{{ session.messages.length ? `${session.messages.length} 条消息` : '暂无消息' }}</span>
        </button>
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
