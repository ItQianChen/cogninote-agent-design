<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import MarkdownRender, { enableMermaid, setCustomComponents } from 'markstream-vue'
import 'markstream-vue/index.css'
import { useThemeStore } from '../stores/theme'
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
  }
})

const themeStore = useThemeStore()
const markdownRoot = ref(null)
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

let mermaidSourceObserver = null
let mermaidSourceEnhanceQueued = false
const mermaidSourceCache = new WeakMap()

onMounted(() => {
  queueMermaidSourceHighlight()
  if (typeof MutationObserver === 'undefined' || !markdownRoot.value) {
    return
  }
  mermaidSourceObserver = new MutationObserver(() => {
    queueMermaidSourceHighlight()
  })
  mermaidSourceObserver.observe(markdownRoot.value, {
    childList: true,
    subtree: true,
    characterData: true
  })
})

onBeforeUnmount(() => {
  mermaidSourceObserver?.disconnect()
  mermaidSourceObserver = null
})

watch(
  () => [props.content, props.final, themeStore.isDark],
  () => {
    queueMermaidSourceHighlight()
  },
  { flush: 'post' }
)

function queueMermaidSourceHighlight() {
  if (mermaidSourceEnhanceQueued) {
    return
  }
  mermaidSourceEnhanceQueued = true
  nextTick(() => {
    mermaidSourceEnhanceQueued = false
    void highlightMermaidSourceBlocks()
  })
}

async function highlightMermaidSourceBlocks() {
  const root = markdownRoot.value
  if (!root) {
    return
  }
  const sourceBlocks = Array.from(root.querySelectorAll('.mermaid-source-code'))
  if (sourceBlocks.length === 0) {
    return
  }

  for (const sourceBlock of sourceBlocks) {
    enhanceMermaidSourceBlock(sourceBlock)
  }
}

function enhanceMermaidSourceBlock(sourceBlock) {
  const source = trimFenceBoundaryBlankLines(sourceBlock.textContent ?? '')
  if (!source) {
    return
  }
  if (sourceBlock.dataset.cogninoteMermaidHighlighted === 'true' && mermaidSourceCache.get(sourceBlock) === source) {
    return
  }

  try {
    // markstream-vue 的 Mermaid Source 不是普通 code_block；这里只替换源码内容，不接管预览、Open、导出等图表能力。
    sourceBlock.innerHTML = renderMermaidSourceHtml(source)
    sourceBlock.dataset.cogninoteMermaidHighlighted = 'true'
    mermaidSourceCache.set(sourceBlock, source)
    sourceBlock.classList.add('cogninote-mermaid-source-highlight', 'language-mermaid')
  } catch {
    sourceBlock.textContent = source
    sourceBlock.dataset.cogninoteMermaidHighlighted = 'false'
    mermaidSourceCache.set(sourceBlock, source)
    sourceBlock.classList.remove('cogninote-mermaid-source-highlight')
  }
}

function renderMermaidSourceHtml(source) {
  const parts = String(source).split(/(\r?\n)/)
  return parts.map((part) => {
    if (part === '\n' || part === '\r\n') {
      return part
    }
    return highlightMermaidLine(part)
  }).join('')
}

function highlightMermaidLine(line) {
  if (!line) {
    return ''
  }

  const leadingWhitespace = line.match(/^\s*/)?.[0] ?? ''
  const body = line.slice(leadingWhitespace.length)
  if (!body) {
    return escapeHtml(line)
  }
  if (body.trimStart().startsWith('%%')) {
    return `${escapeHtml(leadingWhitespace)}<span class="mermaid-token-comment">${escapeHtml(body)}</span>`
  }

  const tokens = tokenizeMermaidLine(body)
  return `${escapeHtml(leadingWhitespace)}${tokens.map(renderMermaidToken).join('')}`
}

function tokenizeMermaidLine(line) {
  const tokenPattern = /("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|\[[^\]]*]|\([^)]*\)|\{[^}]*}|-->|---|==>|-.->|~~>|-\.-|--|==|:::+|[A-Za-z_][\w.-]*|\d+(?:\.\d+)?|[^\sA-Za-z_\d]+)/g
  const tokens = []
  let lastIndex = 0
  let match = tokenPattern.exec(line)

  while (match) {
    if (match.index > lastIndex) {
      tokens.push({ value: line.slice(lastIndex, match.index), type: 'text' })
    }
    tokens.push({ value: match[0], type: classifyMermaidToken(match[0], tokens) })
    lastIndex = tokenPattern.lastIndex
    match = tokenPattern.exec(line)
  }

  if (lastIndex < line.length) {
    tokens.push({ value: line.slice(lastIndex), type: 'text' })
  }
  return tokens
}

function classifyMermaidToken(token, previousTokens) {
  const lower = token.toLowerCase()
  const previousMeaningful = [...previousTokens].reverse().find((item) => item.type !== 'text' && item.value.trim())

  if (token.startsWith('[') || token.startsWith('(') || token.startsWith('{')) {
    return 'label'
  }
  if (/^["'`]/.test(token)) {
    return 'string'
  }
  if (/^(-->|---|==>|-.->|~~>|-\.-|--|==)$/.test(token)) {
    return 'edge'
  }
  if (/^:::+$/.test(token)) {
    return 'punctuation'
  }
  if (/^\d/.test(token)) {
    return 'number'
  }
  if (MERMAID_DECLARATION_KEYWORDS.has(lower)) {
    return 'keyword'
  }
  if (MERMAID_SECTION_KEYWORDS.has(lower)) {
    return 'section'
  }
  if (previousMeaningful?.type === 'keyword' || previousMeaningful?.type === 'section') {
    return 'property'
  }
  if (/^[A-Za-z_][\w.-]*$/.test(token)) {
    return 'identifier'
  }
  return 'punctuation'
}

function renderMermaidToken(token) {
  if (token.type === 'text') {
    return escapeHtml(token.value)
  }
  return `<span class="mermaid-token-${token.type}">${escapeHtml(token.value)}</span>`
}

const MERMAID_DECLARATION_KEYWORDS = new Set([
  'accdescr',
  'acctitle',
  'architecture',
  'block',
  'block-beta',
  'c4component',
  'c4container',
  'c4context',
  'c4deployment',
  'c4dynamic',
  'classdiagram',
  'classdiagram-v2',
  'erdiagram',
  'flowchart',
  'gantt',
  'gitgraph',
  'graph',
  'journey',
  'mindmap',
  'pie',
  'quadrantchart',
  'requirementdiagram',
  'sankey-beta',
  'sequencediagram',
  'statediagram',
  'statediagram-v2',
  'timeline',
  'xychart-beta'
])

const MERMAID_SECTION_KEYWORDS = new Set([
  'activate',
  'alt',
  'and',
  'as',
  'autonumber',
  'axisformat',
  'bar',
  'class',
  'classdef',
  'click',
  'critical',
  'dateformat',
  'deactivate',
  'direction',
  'else',
  'end',
  'excludes',
  'from',
  'link',
  'linkstyle',
  'loop',
  'note',
  'opt',
  'par',
  'participant',
  'rect',
  'section',
  'state',
  'style',
  'subgraph',
  'task',
  'tb',
  'td',
  'title',
  'todaymarker'
])

function trimFenceBoundaryBlankLines(value) {
  return String(value).replace(/^(?:\r?\n)+/, '').replace(/(?:\r?\n)+$/, '')
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
</script>

<template>
  <div ref="markdownRoot" class="ai-markdown-content">
    <MarkdownRender
      custom-id="cogninote-chat"
      :content="props.content || props.emptyText || ''"
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
