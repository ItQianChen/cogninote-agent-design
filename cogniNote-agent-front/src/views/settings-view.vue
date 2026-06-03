<script setup>
import { ref, watch } from 'vue'
import { ArrowLeft } from 'lucide-vue-next'
import KnowledgeView from './knowledge-view.vue'
import ModelConfigView from './model-config-view.vue'
import StatGrid from '../components/stat-grid.vue'
import { useDocumentsStore } from '../stores/documents'
import { useModelConfigStore } from '../stores/model-config'
import { useSearchStore } from '../stores/search'
import { useSystemStore } from '../stores/system'
import { THEME_OPTIONS, useThemeStore } from '../stores/theme'

const systemStore = useSystemStore()
const searchStore = useSearchStore()
const documentsStore = useDocumentsStore()
const modelConfigStore = useModelConfigStore()
const themeStore = useThemeStore()
const activeSection = ref('system')

const sections = [
  { id: 'system', label: '系统' },
  { id: 'knowledge', label: '知识库' },
  { id: 'model', label: '模型' }
]

watch(activeSection, (section) => {
  loadActiveSectionData(section)
}, { immediate: true })

function loadActiveSectionData(section) {
  if (section === 'system') {
    systemStore.fetchStatus()
    searchStore.fetchIndexStatus()
    return
  }

  if (section === 'knowledge') {
    searchStore.fetchIndexStatus()
    documentsStore.fetchDocuments()
    return
  }

  if (section === 'model') {
    modelConfigStore.fetchModelConfig()
  }
}
</script>

<template>
  <section class="settings-page">
    <header class="settings-header">
      <div class="settings-header__title">
        <RouterLink class="back-link" :to="{ name: 'chat' }" aria-label="返回对话">
          <ArrowLeft aria-hidden="true" />
          <span>返回对话</span>
        </RouterLink>
        <div>
          <p class="eyebrow">设置</p>
          <h2>系统与知识库配置</h2>
        </div>
      </div>
      <div class="settings-tabs" role="tablist" aria-label="设置分类">
        <button
          v-for="section in sections"
          :key="section.id"
          type="button"
          :class="{ active: activeSection === section.id }"
          @click="activeSection = section.id"
        >
          {{ section.label }}
        </button>
      </div>
    </header>

    <section v-if="activeSection === 'system'" class="settings-section">
      <section class="settings-card">
        <div>
          <h3>主题</h3>
          <p class="hint-message">选择桌面界面的显示风格，设置会保存在本机浏览器存储中。</p>
        </div>
        <div class="theme-choice" role="radiogroup" aria-label="主题选择">
          <button
            v-for="option in THEME_OPTIONS"
            :key="option.value"
            type="button"
            :class="{ active: themeStore.theme === option.value }"
            @click="themeStore.setTheme(option.value)"
          >
            {{ option.label }}
          </button>
        </div>
      </section>

      <StatGrid
        class="settings-grid"
        :columns="3"
        :items="[
          { label: '应用', value: systemStore.status?.appName || '-' },
          { label: '版本', value: systemStore.status?.version || '-' },
          { label: '状态', value: systemStore.status?.status || '-' },
          { label: '数据目录', value: systemStore.status?.dataDir || '-', mono: true },
          { label: '索引目录', value: searchStore.indexStatus?.indexPath || '-', mono: true },
          { label: '当前能力', value: '导入 / 检索 / RAG 对话' }
        ]"
      />

      <div class="button-row">
        <button class="secondary-button" type="button" @click="systemStore.fetchStatus">刷新系统状态</button>
        <button class="secondary-button" type="button" @click="searchStore.fetchIndexStatus">刷新索引状态</button>
      </div>
    </section>

    <section v-else-if="activeSection === 'knowledge'" class="settings-section">
      <KnowledgeView />
    </section>

    <section v-else class="settings-section">
      <ModelConfigView />
    </section>
  </section>
</template>
