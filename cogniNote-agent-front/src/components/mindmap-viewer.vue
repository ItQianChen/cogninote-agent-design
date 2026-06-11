<script setup>
import { computed } from 'vue'

const props = defineProps({
  payload: {
    type: Object,
    default: null
  }
})

const nodes = computed(() => parseMarkdownMindmap(props.payload?.markdown || ''))

function parseMarkdownMindmap(markdown) {
  return markdown
    .split(/\r?\n/)
    .map((line, index) => {
      const match = /^(#{1,4})\s+(.+)$/.exec(line.trim())
      if (!match) {
        return null
      }
      return {
        id: `${index}-${match[1].length}`,
        level: match[1].length,
        text: match[2].trim()
      }
    })
    .filter(Boolean)
}
</script>

<template>
  <section class="mindmap-viewer" aria-label="思维导图">
    <p v-if="!nodes.length" class="panel-message">暂无思维导图数据。</p>
    <ol v-else class="mindmap-viewer__list">
      <li
        v-for="node in nodes"
        :key="node.id"
        class="mindmap-viewer__node"
        :class="`mindmap-viewer__node--level-${node.level}`"
        :style="{ '--mindmap-depth': Math.max(0, node.level - 1) }"
      >
        <span>{{ node.text }}</span>
      </li>
    </ol>
  </section>
</template>
