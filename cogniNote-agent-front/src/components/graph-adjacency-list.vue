<script setup>
import { computed } from 'vue'

const props = defineProps({
  payload: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['open-evidence'])
const nodeById = computed(() => new Map((props.payload?.nodes || []).map((node) => [node.id, node])))
const rows = computed(() =>
  (props.payload?.edges || []).map((edge) => ({
    ...edge,
    sourceNode: nodeById.value.get(edge.source),
    targetNode: nodeById.value.get(edge.target)
  }))
)

function openEdge(row) {
  emit('open-evidence', {
    type: 'edge',
    id: row.id,
    label: row.label,
    meta: `${row.sourceNode?.label || row.source} -> ${row.targetNode?.label || row.target}`
  })
}
</script>

<template>
  <section class="graph-adjacency-list" aria-label="图谱邻接表">
    <p v-if="!rows.length" class="panel-message">暂无关系数据。</p>
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
          v-for="row in rows"
          :key="row.id"
          tabindex="0"
          @click="openEdge(row)"
          @keyup.enter="openEdge(row)"
        >
          <td>{{ row.sourceNode?.label || row.source }}</td>
          <td>{{ row.label }}</td>
          <td>{{ row.targetNode?.label || row.target }}</td>
          <td>{{ row.weight }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
