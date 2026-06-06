<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowLeft, ChevronUp } from 'lucide-vue-next'
import KnowledgeFolderPanel from '../components/knowledge-folder-panel.vue'
import KnowledgeSearchPanel from '../components/knowledge-search-panel.vue'
import ModelConfigView from './model-config-view.vue'
import { useKnowledgeFoldersStore } from '../stores/knowledge-folders'
import { useModelConfigStore } from '../stores/model-config'
import { useSearchStore } from '../stores/search'
import { useSystemStore } from '../stores/system'
import { THEME_OPTIONS, useThemeStore } from '../stores/theme'

const GITHUB_URL = 'https://github.com/ItQianChen/cogninote-agent-design'
const FRONTEND_VERSION = typeof __COGNINOTE_FRONTEND_VERSION__ === 'string'
  ? __COGNINOTE_FRONTEND_VERSION__
  : '-'

const systemStore = useSystemStore()
const searchStore = useSearchStore()
const knowledgeFoldersStore = useKnowledgeFoldersStore()
const modelConfigStore = useModelConfigStore()
const themeStore = useThemeStore()
const activeItem = ref('system-theme')
const pageRef = ref(null)
const contentRef = ref(null)
const showBackToTop = ref(false)
const desktopVersion = ref('-')

const sidebarGroups = [
  {
    id: 'system',
    label: '系统',
    items: [
      { id: 'system-theme', label: '主题设置' },
      { id: 'system-info', label: '系统相关信息' }
    ]
  },
  {
    id: 'knowledge',
    label: '知识库',
    items: [
      { id: 'knowledge-folders', label: '知识库配置' },
      { id: 'knowledge-search', label: '知识库检索测试' }
    ]
  },
  {
    id: 'model',
    label: '模型',
    items: [
      { id: 'model-chat', label: '对话模型' },
      { id: 'model-embedding', label: '向量模型' }
    ]
  }
]

const activeGroup = computed(() => {
  return sidebarGroups.find(group => group.items.some(item => item.id === activeItem.value)) || sidebarGroups[0]
})
const activeTitle = computed(() => {
  return activeGroup.value.items.find(item => item.id === activeItem.value)?.label || '设置'
})
const isKnowledgePage = computed(() => activeGroup.value.id === 'knowledge')
const embeddingReady = computed(() => {
  const config = modelConfigStore.activeEmbeddingConfig
  return Boolean(config?.apiKeyConfigured && config?.modelName)
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

watch(activeItem, (item) => {
  showBackToTop.value = false
  pageRef.value?.scrollTo({ top: 0 })
  contentRef.value?.scrollTo({ top: 0 })
  loadActiveItemData(item)
}, { immediate: true })

onMounted(() => {
  loadDesktopVersion()
})

function isItemActive(itemId) {
  return activeItem.value === itemId
}

function selectItem(itemId) {
  activeItem.value = itemId
}

function setTheme(theme) {
  themeStore.setTheme(theme)
}

function handleSettingsScroll(event) {
  showBackToTop.value = event.target.scrollTop > 280
}

function scrollSettingsToTop() {
  pageRef.value?.scrollTo({ top: 0, behavior: 'smooth' })
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

  if (item === 'knowledge-folders') {
    await Promise.all([
      modelConfigStore.ensureModelConfigLoaded(),
      knowledgeFoldersStore.fetchFolders(),
      searchStore.fetchIndexStatus()
    ])
    return
  }

  if (item === 'knowledge-search') {
    await Promise.all([
      modelConfigStore.ensureModelConfigLoaded(),
      searchStore.fetchIndexStatus()
    ])
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
</script>

<template>
  <section ref="pageRef" class="settings-page settings-center-page" @scroll.passive="handleSettingsScroll">
    <header class="settings-center-header">
      <div class="settings-header__title">
        <RouterLink class="back-link" :to="{ name: 'chat' }" aria-label="返回对话">
          <ArrowLeft aria-hidden="true" />
          <span>返回对话</span>
        </RouterLink>
        <div>
          <p class="eyebrow">设置中心</p>
          <h2>{{ activeGroup.label }} / {{ activeTitle }}</h2>
        </div>
      </div>
    </header>

    <div class="settings-center-shell">
      <aside class="settings-center-sidebar" aria-label="设置导航">
        <section v-for="group in sidebarGroups" :key="group.id" class="settings-nav-group">
          <h3>{{ group.label }}</h3>
          <button
            v-for="item in group.items"
            :key="item.id"
            type="button"
            :class="{ active: isItemActive(item.id) }"
            @click="selectItem(item.id)"
          >
            {{ item.label }}
          </button>
        </section>
      </aside>

      <main ref="contentRef" class="settings-center-content" @scroll.passive="handleSettingsScroll">
        <el-alert
          v-if="isKnowledgePage && !embeddingReady"
          class="settings-embedding-alert"
          type="warning"
          title="请先配置并启用向量模型，再导入和检索知识库。"
          :closable="false"
          show-icon
        />

        <section v-if="activeItem === 'system-theme'" class="settings-panel">
          <header class="settings-panel__header">
            <p class="eyebrow">系统</p>
            <h3>主题设置</h3>
          </header>
          <div class="settings-card settings-theme-card">
            <div>
              <h4>显示风格</h4>
              <p class="hint-message">主题设置保存在本机浏览器存储中。</p>
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
            <h3>系统相关信息</h3>
          </header>

          <el-descriptions class="settings-descriptions" :column="2" border>
            <el-descriptions-item
              v-for="item in systemDescriptions"
              :key="item.label"
              :label="item.label"
            >
              <span :class="{ 'path-text': item.mono }">{{ item.value }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="GitHub仓库">
              <el-link :href="GITHUB_URL" target="_blank" type="primary">
                ItQianChen/cogninote-agent-design
              </el-link>
            </el-descriptions-item>
          </el-descriptions>

          <div class="button-row">
            <el-button :loading="systemStore.isLoading" @click="systemStore.fetchStatus">刷新系统状态</el-button>
            <el-button :loading="searchStore.isLoadingIndexStatus" @click="searchStore.fetchIndexStatus">刷新索引状态</el-button>
          </div>
        </section>

        <KnowledgeFolderPanel v-else-if="activeItem === 'knowledge-folders'" />
        <KnowledgeSearchPanel v-else-if="activeItem === 'knowledge-search'" />
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
    </div>
  </section>
</template>
