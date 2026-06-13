<script setup>
import { computed, ref } from 'vue'
import { Search } from 'lucide-vue-next'

const props = defineProps({
  payload: {
    type: Object,
    default: null
  },
  fullscreen: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['open-evidence'])
const keyword = ref('')
const relationType = ref('')
const sortDirection = ref('desc')

const nodeById = computed(() => new Map((props.payload?.nodes || []).map((node) => [node.id, node])))
const relationOptions = computed(() => {
  const values = new Set((props.payload?.edges || []).map((edge) => edge.label || 'RELATED_TO'))
  return [...values].sort((left, right) => left.localeCompare(right))
})
const rows = computed(() =>
  (props.payload?.edges || []).map((edge) => ({
    ...edge,
    sourceNode: nodeById.value.get(edge.source),
    targetNode: nodeById.value.get(edge.target),
    sourceLabel: edge.sourceLabel || nodeById.value.get(edge.source)?.label || edge.source,
    targetLabel: edge.targetLabel || nodeById.value.get(edge.target)?.label || edge.target
  }))
)
const filteredRows = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  const rowsToRender = rows.value.filter((row) => {
    const matchesRelation = !relationType.value || row.label === relationType.value
    const haystack = `${row.sourceLabel} ${row.label} ${row.targetLabel}`.toLowerCase()
    return matchesRelation && (!query || haystack.includes(query))
  })
  return [...rowsToRender].sort((left, right) => {
    const delta = Number(left.weight || 0) - Number(right.weight || 0)
    return sortDirection.value === 'asc' ? delta : -delta
  })
})

function openEdge(row) {
  emit('open-evidence', {
    type: 'edge',
    id: row.id,
    label: row.label,
    meta: `${row.sourceLabel} -> ${row.targetLabel}`
  })
}
</script>

<template>
  <section class="graph-adjacency-list" :class="{ 'graph-adjacency-list--fullscreen': fullscreen }" aria-label="图谱邻接表">
    <div class="graph-adjacency-list__toolbar">
      <label class="graph-list-search">
        <Search aria-hidden="true" />
        <input v-model="keyword" type="search" placeholder="搜索起点、关系或终点" />
      </label>
      <label>
        <span>关系</span>
        <select v-model="relationType">
          <option value="">全部关系</option>
          <option v-for="option in relationOptions" :key="option" :value="option">{{ option }}</option>
        </select>
      </label>
      <label>
        <span>排序</span>
        <select v-model="sortDirection">
          <option value="desc">证据数从高到低</option>
          <option value="asc">证据数从低到高</option>
        </select>
      </label>
    </div>

    <p v-if="!filteredRows.length" class="panel-message">当前筛选下没有关系数据。</p>
    <table v-else>
      <thead>
        <tr>
          <th>起点</th>
          <th>关系</th>
          <th>终点</th>
          <th>证据数</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in filteredRows"
          :key="row.id"
          role="button"
          tabindex="0"
          @click="openEdge(row)"
          @keyup.enter="openEdge(row)"
          @keyup.space.prevent="openEdge(row)"
        >
          <td>{{ row.sourceLabel }}</td>
          <td><span class="relation-chip">{{ row.label }}</span></td>
          <td>{{ row.targetLabel }}</td>
          <td>{{ row.weight }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
