<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import MarkdownRender, { enableMermaid, setCustomComponents } from 'markstream-vue'
import 'markstream-vue/index.css'
import { useThemeStore } from '../stores/theme'
import { useMermaidEnhancer } from '../composables/use-mermaid-enhancer'
import {
  citationIndexFromHref,
  decorateCitationMarkers
} from '../utils/citation-markers'
import AiCodeBlock from './ai-code-block.vue'

// markstream-vue 把 Mermaid 作为可选 peer，必须显式启用 loader 才会渲染 ```mermaid 代码块。
enableMermaid()
setCustomComponents('cogninote-chat', {
  code_block: AiCodeBlock
})

const props = defineProps({
  content: {
    type: String,
    default: ''
  },
  emptyText: {
    type: String,
    default: ''
  },
  final: {
    type: Boolean,
    default: true
  },
  sources: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['citation-click'])
const themeStore = useThemeStore()
const markdownRoot = ref(null)
const renderedContent = computed(() => sanitizeMermaidFences(
  decorateCitationMarkers(props.content || props.emptyText || '', props.sources)
))
const isStreaming = computed(() => !props.final)
const codeBlockProps = computed(() => ({
  // 代码块固定使用暗色背景，避免日间/夜间主题覆盖后削弱语法高亮对比度。
  theme: {
    light: 'vitesse-dark',
    dark: 'vitesse-dark'
  },
  lightTheme: 'vitesse-dark',
  darkTheme: 'vitesse-dark',
  themes: ['vitesse-dark'],
  showHeader: true,
  showCopyButton: true,
  showExpandButton: true,
  showCollapseButton: false,
  showFontSizeButtons: false,
  showTooltips: true,
  maxWidth: '100%',
  monacoOptions: {
    MAX_HEIGHT: '70vh',
    fontSize: 14,
    lineHeight: 22,
    wordWrap: 'off'
  }
}))
const mermaidProps = {
  maxHeight: 'none',
  estimatedPreviewHeightPx: 360,
  showHeader: true,
  showModeToggle: true,
  showCopyButton: true,
  showExportButton: true,
  showFullscreenButton: true,
  showCollapseButton: true,
  showZoomControls: true,
  enableWheelZoom: true,
  isStrict: true,
  enableMermaidInteractions: false
}
const mermaidEnhancer = useMermaidEnhancer(markdownRoot)

onMounted(() => {
  mermaidEnhancer.mount()
})

onBeforeUnmount(() => {
  mermaidEnhancer.unmount()
})

watch(
  () => [props.content, props.final, themeStore.isDark],
  () => {
    mermaidEnhancer.queueEnhance()
  },
  { flush: 'post' }
)

function sanitizeMermaidFences(content) {
  return String(content).replace(
    /```(mermaid)\s*\r?\n([\s\S]*?)```/gi,
    (_, language, source) => `\`\`\`${language}\n${sanitizeMermaidNodeLabels(source)}\`\`\``
  )
}

function sanitizeMermaidNodeLabels(source) {
  return source.replace(
    /([A-Za-z_][\w-]*)\[([^\]\r\n]*)\]/g,
    (_, nodeId, label) => `${nodeId}[${label.replaceAll('"', '#quot;')}]`
  )
}

function sourceForCitationIndex(index) {
  return props.sources.find((source, sourceIndex) => String(source?.index ?? sourceIndex + 1) === index)
}

function handleMarkdownClick(event) {
  const link = event.target?.closest?.('a[href^="#citation-"]')
  if (!link || !markdownRoot.value?.contains(link)) {
    return
  }
  const index = citationIndexFromHref(link.getAttribute('href'))
  const source = sourceForCitationIndex(index)
  if (!source) {
    return
  }
  event.preventDefault()
  emit('citation-click', source)
}

function handleMarkdownKeydown(event) {
  const link = event.target?.closest?.('a[href^="#citation-"]')
  if (event.key !== ' ' || !link || !markdownRoot.value?.contains(link)) {
    return
  }
  event.preventDefault()
  link.click()
}
</script>

<template>
  <div
    ref="markdownRoot"
    class="ai-markdown-content"
    @click="handleMarkdownClick"
    @keydown="handleMarkdownKeydown"
  >
    <MarkdownRender
      custom-id="cogninote-chat"
      :content="renderedContent"
      :final="props.final"
      :is-dark="themeStore.isDark"
      code-renderer="shiki"
      :code-block-props="codeBlockProps"
      :mermaid-props="mermaidProps"
      html-policy="escape"
      :max-live-nodes="isStreaming ? 0 : 200"
      :batch-rendering="isStreaming"
      :render-batch-size="16"
      :render-batch-delay="8"
      :render-batch-budget-ms="4"
      :fade="false"
      :typewriter="isStreaming"
      :smooth-streaming="isStreaming"
    />
  </div>
</template>
