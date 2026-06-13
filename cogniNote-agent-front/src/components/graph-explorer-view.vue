<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import cytoscape from 'cytoscape'
import fcose from 'cytoscape-fcose'
import { LocateFixed, RotateCcw, Search, SlidersHorizontal } from 'lucide-vue-next'
import { formatScore } from '../utils/formatters'

let isFcoseRegistered = false

function ensureFcoseRegistered() {
  if (isFcoseRegistered) {
    return
  }
  try {
    cytoscape.use(fcose)
  } catch {
    // Vite HMR 可能保留 cytoscape 全局扩展；重复注册失败时继续复用已有扩展。
  }
  isFcoseRegistered = true
}

const props = defineProps({
  payload: {
    type: Object,
    default: null
  },
  mode: {
    type: String,
    required: true
  },
  fullscreen: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['open-evidence'])

const canvasRef = ref(null)
const searchQuery = ref('')
const selectedNodeTypes = ref([])
const selectedRelationTypes = ref([])
const minWeight = ref(0)
const selectedItem = ref(null)
let cy = null

const isGraphMode = computed(() => props.mode === 'GRAPH')
const rawElements = computed(() => isGraphMode.value ? graphElements() : mindmapElements())
const typeOptions = computed(() => countOptions(rawElements.value.nodes.map((node) => node.data.nodeType)))
const relationOptions = computed(() => countOptions(rawElements.value.edges.map((edge) => edge.data.relationType)))
const filteredElements = computed(() => isGraphMode.value ? filteredGraphElements() : filteredMindmapElements())
const hasCanvasData = computed(() => filteredElements.value.nodes.length > 0)
const summaryText = computed(() =>
  `${filteredElements.value.nodes.length} 节点 · ${filteredElements.value.edges.length} 关系`
)

watch(typeOptions, (options) => {
  selectedNodeTypes.value = options.map((option) => option.value)
}, { immediate: true })

watch(relationOptions, (options) => {
  selectedRelationTypes.value = options.map((option) => option.value)
}, { immediate: true })

watch(
  () => [filteredElements.value, props.fullscreen],
  () => {
    void renderGraph()
  },
  { deep: true }
)

watch(filteredElements, () => {
  if (!selectedItem.value) {
    return
  }
  const group = selectedItem.value.group === 'edge' ? 'edges' : 'nodes'
  const exists = filteredElements.value[group].some((item) => item.data.id === selectedItem.value.id)
  if (!exists) {
    selectedItem.value = null
  }
})

onMounted(() => {
  ensureFcoseRegistered()
  void renderGraph()
})

onBeforeUnmount(() => {
  destroyGraph()
})

function graphElements() {
  const rawNodes = props.payload?.nodes || []
  const nodeLabelById = new Map(rawNodes.map((node) => [node.id, node.label || node.id]))
  const nodes = rawNodes.map((node) => ({
    data: {
      id: node.id,
      label: node.label || node.id,
      nodeType: node.type || 'ENTITY',
      kind: 'entity',
      evidenceId: node.id,
      mentionCount: node.mentionCount || 0,
      degree: node.degree || 0,
      confidence: node.confidence || 0,
      size: nodeSize(node),
      color: colorForType(node.type),
      shape: 'ellipse'
    }
  }))
  const edges = (props.payload?.edges || []).map((edge) => ({
    data: {
      id: edge.id,
      source: edge.source,
      target: edge.target,
      label: edge.label || 'RELATED_TO',
      relationType: edge.label || 'RELATED_TO',
      sourceLabel: edge.sourceLabel || nodeLabelById.get(edge.source) || edge.source,
      targetLabel: edge.targetLabel || nodeLabelById.get(edge.target) || edge.target,
      weight: edge.weight || 1,
      confidence: edge.confidence || 0,
      width: Math.min(7, 1.5 + Number(edge.weight || 1)),
      color: edgeColor(edge.label)
    }
  }))
  return { nodes, edges }
}

function mindmapElements() {
  if (Array.isArray(props.payload?.documents)) {
    return structuredMindmapElements()
  }
  return markdownMindmapElements(props.payload?.markdown || '')
}

function structuredMindmapElements() {
  const root = props.payload?.root || { id: 'scope', label: '知识图谱', type: 'SCOPE' }
  const rootId = root.id || 'scope'
  const nodes = [{
    data: {
      id: rootId,
      label: root.label || '知识图谱',
      nodeType: 'SCOPE',
      kind: 'scope',
      mentionCount: 0,
      degree: 0,
      confidence: 0,
      size: 58,
      color: '#0f766e',
      shape: 'round-rectangle'
    }
  }]
  const edges = []
  for (const document of props.payload.documents || []) {
    const documentId = `document:${document.id}`
    nodes.push({
      data: {
        id: documentId,
        label: document.label || document.fileName || '未命名文档',
        nodeType: 'DOCUMENT',
        kind: 'document',
        mentionCount: 0,
        degree: 0,
        confidence: 0,
        size: 44,
        color: '#2563eb',
        shape: 'round-rectangle'
      }
    })
    edges.push(mindmapEdge(`edge:${rootId}:${documentId}`, rootId, documentId))
    for (const heading of document.headings || []) {
      const headingId = `heading:${heading.id}`
      nodes.push({
        data: {
          id: headingId,
          label: heading.label || '未命名片段',
          nodeType: 'HEADING',
          kind: 'heading',
          mentionCount: 0,
          degree: 0,
          confidence: 0,
          size: 36,
          color: '#e2e8f0',
          shape: 'round-rectangle'
        }
      })
      edges.push(mindmapEdge(`edge:${documentId}:${headingId}`, documentId, headingId))
      for (const entity of heading.entities || []) {
        const entityId = `entity:${heading.id}:${entity.id}`
        nodes.push({
          data: {
            id: entityId,
            label: entity.label || entity.id,
            nodeType: entity.type || 'ENTITY',
            kind: 'entity',
            evidenceId: entity.id,
            mentionCount: entity.count || 0,
            degree: 0,
            confidence: 0,
            size: Math.min(42, 24 + Number(entity.count || 0) * 3),
            color: colorForType(entity.type),
            shape: 'ellipse'
          }
        })
        edges.push(mindmapEdge(`edge:${headingId}:${entityId}`, headingId, entityId))
      }
    }
  }
  return { nodes, edges }
}

function markdownMindmapElements(markdown) {
  const lines = markdown.split(/\r?\n/)
  const nodes = []
  const edges = []
  const stack = []
  for (let index = 0; index < lines.length; index += 1) {
    const match = /^(#{1,4})\s+(.+)$/.exec(lines[index].trim())
    if (!match) {
      continue
    }
    const level = match[1].length
    const text = match[2].trim()
    const id = `markdown:${index}:${level}`
    const entityMatch = /^(.+?)\s+\[([^\]]+)]\s+x(\d+)$/i.exec(text)
    const nodeType = level === 1 ? 'SCOPE' : level === 2 ? 'DOCUMENT' : level === 3 ? 'HEADING' : entityMatch?.[2] || 'ENTITY'
    nodes.push({
      data: {
        id,
        label: entityMatch?.[1] || text,
        nodeType,
        kind: level === 4 ? 'entity' : nodeType.toLowerCase(),
        mentionCount: Number(entityMatch?.[3] || 0),
        degree: 0,
        confidence: 0,
        size: level === 1 ? 58 : level === 2 ? 44 : level === 3 ? 36 : 28,
        color: level === 4 ? colorForType(nodeType) : structuralColor(nodeType),
        shape: level === 4 ? 'ellipse' : 'round-rectangle'
      }
    })
    const parent = stack[level - 2]
    if (parent) {
      edges.push(mindmapEdge(`edge:${parent}:${id}`, parent, id))
    }
    stack[level - 1] = id
    stack.length = level
  }
  return { nodes, edges }
}

function mindmapEdge(id, source, target) {
  return {
    data: {
      id,
      source,
      target,
      label: '',
      relationType: 'CONTAINS',
      weight: 1,
      width: 1.5,
      color: '#94a3b8'
    }
  }
}

function filteredGraphElements() {
  const query = searchQuery.value.trim().toLowerCase()
  const activeTypes = new Set(selectedNodeTypes.value)
  const activeRelations = new Set(selectedRelationTypes.value)
  const nodes = rawElements.value.nodes.filter((node) => {
    const matchesType = !activeTypes.size || activeTypes.has(node.data.nodeType)
    const matchesQuery = !query || node.data.label.toLowerCase().includes(query)
    return matchesType && matchesQuery
  })
  const nodeIds = new Set(nodes.map((node) => node.data.id))
  const edges = rawElements.value.edges.filter((edge) => {
    const matchesRelation = !activeRelations.size || activeRelations.has(edge.data.relationType)
    const matchesWeight = Number(edge.data.weight || 0) >= Number(minWeight.value || 0)
    return nodeIds.has(edge.data.source) && nodeIds.has(edge.data.target) && matchesRelation && matchesWeight
  })
  return { nodes, edges }
}

function filteredMindmapElements() {
  const query = searchQuery.value.trim().toLowerCase()
  const activeTypes = new Set(selectedNodeTypes.value)
  const included = new Set()
  const parentByNode = new Map(rawElements.value.edges.map((edge) => [edge.data.target, edge.data.source]))
  const childByNode = new Map()
  for (const edge of rawElements.value.edges) {
    childByNode.set(edge.data.source, [...(childByNode.get(edge.data.source) || []), edge.data.target])
  }

  for (const node of rawElements.value.nodes) {
    const isStructural = node.data.kind !== 'entity'
    const matchesType = isStructural || !activeTypes.size || activeTypes.has(node.data.nodeType)
    const matchesQuery = !query || node.data.label.toLowerCase().includes(query)
    if (!matchesType || !matchesQuery) {
      continue
    }
    includeNodeWithContext(node.data.id, parentByNode, childByNode, included)
  }

  const nodes = rawElements.value.nodes.filter((node) => included.has(node.data.id))
  const edges = rawElements.value.edges.filter((edge) => included.has(edge.data.source) && included.has(edge.data.target))
  return {
    nodes: nodes.length || query ? nodes : rawElements.value.nodes,
    edges: nodes.length || query ? edges : rawElements.value.edges
  }
}

function includeNodeWithContext(nodeId, parentByNode, childByNode, included) {
  let current = nodeId
  while (current && !included.has(current)) {
    included.add(current)
    current = parentByNode.get(current)
  }
  for (const child of childByNode.get(nodeId) || []) {
    included.add(child)
  }
}

async function renderGraph() {
  await nextTick()
  if (!canvasRef.value) {
    return
  }
  destroyGraph()
  if (!hasCanvasData.value) {
    return
  }
  cy = cytoscape({
    container: canvasRef.value,
    elements: [...filteredElements.value.nodes, ...filteredElements.value.edges],
    minZoom: 0.25,
    maxZoom: 2.5,
    wheelSensitivity: 0.18,
    style: graphStyle()
  })
  cy.on('tap', 'node, edge', (event) => {
    selectElement(event.target)
  })
  cy.on('tap', (event) => {
    if (event.target === cy) {
      selectedItem.value = null
      clearFocus()
    }
  })
  runLayout()
}

function destroyGraph() {
  if (cy) {
    cy.destroy()
    cy = null
  }
}

function runLayout() {
  if (!cy) {
    return
  }
  const layout = isGraphMode.value
    ? {
        name: 'fcose',
        animate: false,
        fit: true,
        padding: props.fullscreen ? 72 : 40,
        nodeRepulsion: 6500,
        idealEdgeLength: 110,
        quality: 'default'
      }
    : {
        name: 'breadthfirst',
        directed: true,
        animate: false,
        fit: true,
        padding: props.fullscreen ? 72 : 40,
        spacingFactor: 1.35
      }
  cy.layout(layout).run()
}

function fitGraph() {
  cy?.fit(undefined, props.fullscreen ? 72 : 40)
}

function resetExplorer() {
  searchQuery.value = ''
  selectedNodeTypes.value = typeOptions.value.map((option) => option.value)
  selectedRelationTypes.value = relationOptions.value.map((option) => option.value)
  minWeight.value = 0
  selectedItem.value = null
  void renderGraph()
}

function selectElement(element) {
  selectedItem.value = {
    group: element.isEdge() ? 'edge' : 'node',
    ...element.data()
  }
  focusElement(element)
}

function focusElement(element) {
  if (!cy) {
    return
  }
  clearFocus()
  cy.elements().addClass('is-faded')
  if (element.isNode()) {
    element.closedNeighborhood().removeClass('is-faded').addClass('is-highlighted')
    return
  }
  element.connectedNodes().add(element).removeClass('is-faded').addClass('is-highlighted')
}

function clearFocus() {
  cy?.elements().removeClass('is-faded is-highlighted')
}

function openSelectedEvidence() {
  if (!selectedItem.value) {
    return
  }
  if (selectedItem.value.group === 'edge') {
    emit('open-evidence', {
      type: 'edge',
      id: selectedItem.value.id,
      label: selectedItem.value.label,
      meta: `${selectedItem.value.sourceLabel || selectedItem.value.source} -> ${selectedItem.value.targetLabel || selectedItem.value.target}`
    })
    return
  }
  if (selectedItem.value.kind === 'entity' && selectedItem.value.evidenceId) {
    emit('open-evidence', {
      type: 'node',
      id: selectedItem.value.evidenceId,
      label: selectedItem.value.label,
      meta: selectedItem.value.nodeType
    })
  }
}

function graphStyle() {
  return [
    {
      selector: 'node',
      style: {
        label: 'data(label)',
        width: 'data(size)',
        height: 'data(size)',
        shape: 'data(shape)',
        'background-color': 'data(color)',
        'border-color': '#ffffff',
        'border-width': 2,
        color: '#0f172a',
        'font-size': props.fullscreen ? 12 : 11,
        'font-weight': 700,
        'text-wrap': 'wrap',
        'text-max-width': props.fullscreen ? 120 : 92,
        'text-valign': 'bottom',
        'text-halign': 'center',
        'text-margin-y': 8,
        'overlay-padding': 8,
        'transition-property': 'opacity, border-width',
        'transition-duration': 120
      }
    },
    {
      selector: 'edge',
      style: {
        label: isGraphMode.value ? 'data(label)' : '',
        width: 'data(width)',
        'line-color': 'data(color)',
        'target-arrow-color': 'data(color)',
        'target-arrow-shape': isGraphMode.value ? 'triangle' : 'none',
        'curve-style': 'bezier',
        color: '#475569',
        'font-size': 10,
        'font-weight': 700,
        'text-background-color': '#ffffff',
        'text-background-opacity': 0.88,
        'text-background-padding': 3,
        'text-rotation': 'autorotate',
        opacity: 0.72
      }
    },
    {
      selector: '.is-faded',
      style: {
        opacity: 0.18
      }
    },
    {
      selector: '.is-highlighted',
      style: {
        opacity: 1,
        'border-color': '#0f766e',
        'border-width': 4
      }
    },
    {
      selector: 'edge.is-highlighted',
      style: {
        'line-color': '#0f766e',
        'target-arrow-color': '#0f766e',
        width: 4
      }
    }
  ]
}

function nodeSize(node) {
  const degree = Number(node.degree || 0)
  const mentions = Number(node.mentionCount || 0)
  return Math.min(58, 28 + degree * 3 + mentions)
}

function colorForType(type) {
  const palette = ['#0f766e', '#2563eb', '#7c3aed', '#b45309', '#15803d', '#be123c', '#475569']
  const text = String(type || 'ENTITY')
  let hash = 0
  for (let index = 0; index < text.length; index += 1) {
    hash = (hash + text.charCodeAt(index) * (index + 1)) % palette.length
  }
  return palette[hash]
}

function structuralColor(type) {
  if (type === 'SCOPE') {
    return '#0f766e'
  }
  if (type === 'DOCUMENT') {
    return '#2563eb'
  }
  return '#e2e8f0'
}

function edgeColor(type) {
  const palette = ['#64748b', '#0f766e', '#2563eb', '#7c3aed', '#b45309']
  const text = String(type || 'RELATED_TO')
  let hash = 0
  for (let index = 0; index < text.length; index += 1) {
    hash = (hash + text.charCodeAt(index)) % palette.length
  }
  return palette[hash]
}

function countOptions(values) {
  const counts = new Map()
  for (const value of values) {
    const key = value || 'UNKNOWN'
    counts.set(key, (counts.get(key) || 0) + 1)
  }
  return [...counts.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([value, count]) => ({ value, count }))
}
</script>

<template>
  <section class="graph-explorer" :class="{ 'graph-explorer--fullscreen': fullscreen }" aria-label="图谱探索器">
    <aside class="graph-explorer__filters" aria-label="图谱筛选">
      <div class="graph-explorer__filter-title">
        <SlidersHorizontal aria-hidden="true" />
        <strong>筛选</strong>
      </div>

      <label class="graph-explorer__search">
        <span>搜索实体</span>
        <span>
          <Search aria-hidden="true" />
          <input v-model="searchQuery" type="search" placeholder="输入节点名称" />
        </span>
      </label>

      <section v-if="typeOptions.length" class="graph-explorer__filter-group">
        <strong>节点类型</strong>
        <label v-for="option in typeOptions" :key="option.value">
          <input v-model="selectedNodeTypes" type="checkbox" :value="option.value" />
          <span>{{ option.value }}</span>
          <em>{{ option.count }}</em>
        </label>
      </section>

      <section v-if="isGraphMode && relationOptions.length" class="graph-explorer__filter-group">
        <strong>关系类型</strong>
        <label v-for="option in relationOptions" :key="option.value">
          <input v-model="selectedRelationTypes" type="checkbox" :value="option.value" />
          <span>{{ option.value }}</span>
          <em>{{ option.count }}</em>
        </label>
      </section>

      <label v-if="isGraphMode" class="graph-explorer__number-filter">
        <span>最小证据数</span>
        <input v-model.number="minWeight" type="number" min="0" />
      </label>

      <button type="button" class="secondary-button graph-explorer__reset" @click="resetExplorer">
        <RotateCcw aria-hidden="true" />
        <span>重置</span>
      </button>
    </aside>

    <div class="graph-explorer__stage">
      <div class="graph-explorer__stage-toolbar">
        <div>
          <strong>{{ isGraphMode ? '关系图' : '思维导图' }}</strong>
          <span>{{ summaryText }}</span>
          <span v-if="isGraphMode && payload?.hiddenNodeCount">隐藏 {{ payload.hiddenNodeCount }} 节点</span>
        </div>
        <div>
          <button type="button" class="icon-button" aria-label="适配图谱视图" title="适配图谱视图" @click="fitGraph">
            <LocateFixed aria-hidden="true" />
          </button>
          <button type="button" class="icon-button" aria-label="重新布局图谱" title="重新布局图谱" @click="runLayout">
            <RotateCcw aria-hidden="true" />
          </button>
        </div>
      </div>

      <div class="graph-explorer__legend" aria-label="图谱图例">
        <span v-for="option in typeOptions.slice(0, 6)" :key="option.value">
          <i :style="{ background: colorForType(option.value) }"></i>
          {{ option.value }}
        </span>
        <span v-if="isGraphMode">
          <i class="graph-explorer__legend-line"></i>
          边越粗证据越多，箭头表示方向
        </span>
      </div>

      <div class="graph-explorer__canvas-wrap">
        <p v-if="!hasCanvasData" class="panel-message">当前筛选下没有可展示的图谱节点。</p>
        <div ref="canvasRef" class="graph-explorer__canvas" role="img" :aria-label="isGraphMode ? '知识图谱关系图' : '知识图谱思维导图'"></div>
      </div>
    </div>

    <aside class="graph-explorer__inspector" aria-label="图谱详情">
      <p class="eyebrow">Inspector</p>
      <template v-if="selectedItem">
        <h4>{{ selectedItem.label }}</h4>
        <div class="graph-inspector__meta">
          <span v-if="selectedItem.group === 'node'">{{ selectedItem.nodeType }}</span>
          <span v-else>{{ selectedItem.relationType }}</span>
          <span v-if="selectedItem.mentionCount">提及 {{ selectedItem.mentionCount }}</span>
          <span v-if="selectedItem.degree">连接 {{ selectedItem.degree }}</span>
          <span v-if="selectedItem.weight">证据 {{ selectedItem.weight }}</span>
          <span v-if="selectedItem.confidence">score {{ formatScore(selectedItem.confidence) }}</span>
        </div>
        <p v-if="selectedItem.group === 'edge'" class="graph-inspector__path">
          {{ selectedItem.sourceLabel || selectedItem.source }} -> {{ selectedItem.label }} -> {{ selectedItem.targetLabel || selectedItem.target }}
        </p>
        <button
          v-if="selectedItem.group === 'edge' || (selectedItem.kind === 'entity' && selectedItem.evidenceId)"
          type="button"
          class="primary-button graph-inspector__evidence"
          @click="openSelectedEvidence"
        >
          查看证据
        </button>
      </template>
      <template v-else>
        <h4>选择节点或关系</h4>
        <p class="muted-text">点击画布中的实体、标题或关系，查看类型、证据数和回链入口。</p>
      </template>
    </aside>
  </section>
</template>
