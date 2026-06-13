<script setup>
import GraphExplorerView from './graph-explorer-view.vue'

defineProps({
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
</script>

<template>
  <GraphExplorerView
    :payload="payload"
    mode="MINDMAP"
    :fullscreen="fullscreen"
    @open-evidence="emit('open-evidence', $event)"
  />
</template>
