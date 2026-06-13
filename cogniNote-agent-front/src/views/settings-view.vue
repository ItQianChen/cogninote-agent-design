<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChevronUp } from 'lucide-vue-next'
import DesktopUpdateSettingsPanel from '../components/desktop-update-settings-panel.vue'
import QueryContextualizerSettingsPanel from '../components/query-contextualizer-settings-panel.vue'
import SystemStatusCard from '../components/system-status-card.vue'
import { DEFAULT_SETTINGS_ITEM, SETTINGS_NAV_GROUPS, normalizeSettingsItem } from '../config/settings-navigation'
import ModelConfigView from './model-config-view.vue'
import { useChatSettingsStore } from '../stores/chat-settings'
import { useModelConfigStore } from '../stores/model-config'
import { useSearchStore } from '../stores/search'
import { useSystemStore } from '../stores/system'
import { THEME_OPTIONS, useThemeStore } from '../stores/theme'

const GITHUB_URL = 'https://github.com/ItQianChen/cogninote-agent-design'
const FRONTEND_VERSION = typeof __COGNINOTE_FRONTEND_VERSION__ === 'string'
  ? __COGNINOTE_FRONTEND_VERSION__
  : '-'

const route = useRoute()
const router = useRouter()
const systemStore = useSystemStore()
const searchStore = useSearchStore()
const modelConfigStore = useModelConfigStore()
const chatSettingsStore = useChatSettingsStore()
const themeStore = useThemeStore()
const contentRef = ref(null)
const showBackToTop = ref(false)
const desktopVersion = ref('-')

const activeItem = computed(() => normalizeSettingsItem(readRouteItem()))
const activeGroup = computed(() => {
  return SETTINGS_NAV_GROUPS.find((group) =>
    group.items.some((item) => item.id === activeItem.value)
  ) || SETTINGS_NAV_GROUPS[0]
})
const activeTitle = computed(() => {
  return activeGroup.value.items.find((item) => item.id === activeItem.value)?.label || '设置'
})
const systemDescriptions = computed(() => [
  { label: '应用', value: systemStore.status?.appName || '-' },
  { label: '后端版本', value: systemStore.status?.version || '-' },
  { label: '前端版本', value: FRONTEND_VERSION },
  { label: '桌面壳版本', value: desktopVersion.value },
  { label: '桌面模式', value: systemStore.status?.desktopMode ? '是' : '否' },
  { label: '状态', value: systemStore.status?.status || '-' },
  { label: '后端连接', value: systemStore.connectionLabel },
  { label: '数据目录', value: systemStore.status?.dataDir || '-', mono: true },
  { label: '索引目录', value: searchStore.indexStatus?.indexPath || '-', mono: true },
  { label: '当前能力', value: '导入 / 检索 / RAG 对话' }
])

watch(
  () => route.query.item,
  (item) => {
    const normalized = normalizeSettingsItem(readRouteItem(item))
    if (item !== normalized) {
      router.replace({
        name: 'settings',
        query: {
          ...route.query,
          item: normalized
        }
      })
      return
    }
    showBackToTop.value = false
    contentRef.value?.scrollTo({ top: 0 })
    loadActiveItemData(normalized)
  },
  { immediate: true }
)

onMounted(() => {
  loadDesktopVersion()
})

function setTheme(theme) {
  themeStore.setTheme(theme)
}

function handleSettingsScroll(event) {
  showBackToTop.value = event.target.scrollTop > 280
}

function scrollSettingsToTop() {
  contentRef.value?.scrollTo({ top: 0, behavior: 'smooth' })
}

async function loadDesktopVersion() {
  try {
    const { getVersion } = await import('@tauri-apps/api/app')
    desktopVersion.value = await getVersion()
  } catch {
    desktopVersion.value = '-'
  }
}

async function loadActiveItemData(item) {
  if (item === 'system-info') {
    await Promise.all([
      systemStore.fetchStatus(),
      searchStore.fetchIndexStatus()
    ])
    return
  }

  if (item === 'chat-retrieval') {
    await chatSettingsStore.fetchSettings({ force: true })
    return
  }

  if (item === 'model-chat') {
    await modelConfigStore.switchRole(modelConfigStore.ROLES.CHAT)
    return
  }

  if (item === 'model-embedding') {
    await modelConfigStore.switchRole(modelConfigStore.ROLES.EMBEDDING)
  }
}

function readRouteItem(item = route.query.item) {
  const value = Array.isArray(item) ? item[0] : item
  return String(value || DEFAULT_SETTINGS_ITEM)
}
</script>

<template>
  <section class="settings-page settings-center-page">
    <header class="settings-center-header">
      <div class="settings-header__title">
        <div>
          <p class="eyebrow">设置中心</p>
          <h2>{{ activeGroup.label }} / {{ activeTitle }}</h2>
        </div>
      </div>
    </header>

    <main ref="contentRef" class="settings-center-content settings-center-content--single" @scroll.passive="handleSettingsScroll">
      <section v-if="activeItem === 'appearance'" class="settings-panel">
        <header class="settings-panel__header">
          <p class="eyebrow">系统</p>
          <h3>外观</h3>
        </header>
        <div class="settings-card settings-theme-card">
          <div>
            <h4>显示风格</h4>
            <p class="hint-message">默认跟随操作系统；旧版 dark/light 本地值会继续兼容。</p>
          </div>
          <el-radio-group :model-value="themeStore.theme" @change="setTheme">
            <el-radio-button
              v-for="option in THEME_OPTIONS"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
        </div>
      </section>

      <section v-else-if="activeItem === 'system-info'" class="settings-panel">
        <header class="settings-panel__header">
          <p class="eyebrow">系统</p>
          <h3>系统信息</h3>
        </header>
        <SystemStatusCard
          :descriptions="systemDescriptions"
          :github-url="GITHUB_URL"
          :is-system-loading="systemStore.isLoading"
          :is-index-loading="searchStore.isLoadingIndexStatus"
          @refresh-system="systemStore.fetchStatus"
          @refresh-index="searchStore.fetchIndexStatus"
        />
      </section>

      <DesktopUpdateSettingsPanel v-else-if="activeItem === 'app-update'" />
      <QueryContextualizerSettingsPanel v-else-if="activeItem === 'chat-retrieval'" />
      <ModelConfigView
        v-else-if="activeItem === 'model-chat'"
        key="model-chat"
        :initial-role="modelConfigStore.ROLES.CHAT"
      />
      <ModelConfigView
        v-else
        key="model-embedding"
        :initial-role="modelConfigStore.ROLES.EMBEDDING"
      />

      <button
        v-show="showBackToTop"
        class="settings-back-to-top"
        type="button"
        aria-label="回到顶部"
        title="回到顶部"
        @click="scrollSettingsToTop"
      >
        <ChevronUp aria-hidden="true" />
      </button>
    </main>
  </section>
</template>
