<script setup>
import { ref } from 'vue'
import { formatScore } from '../utils/formatters'

defineProps({
  sources: {
    type: Array,
    required: true
  },
  compact: {
    type: Boolean,
    default: false
  }
})

defineEmits(['ask-source'])

const isExpanded = ref(false)
</script>

<template>
  <section class="sources-panel" :class="{ 'sources-panel--compact': compact }" aria-label="引用来源">
    <div class="section-title-line">
      <h3>引用来源</h3>
      <div class="source-panel-actions">
        <span>{{ sources.length }} sources</span>
        <button
          class="collapse-button"
          type="button"
          :aria-expanded="isExpanded"
          @click="isExpanded = !isExpanded"
        >
          {{ isExpanded ? '收起' : '展开' }}
        </button>
      </div>
    </div>

    <template v-if="isExpanded">
      <p v-if="sources.length === 0" class="panel-message">发送问题后会列出本次检索命中的文档片段。</p>
      <article v-for="source in sources" :key="source.chunkId" class="source-row">
        <div class="source-index">[{{ source.index }}]</div>
        <div class="source-main">
          <div class="document-title-line">
            <h4>{{ source.fileName }}</h4>
            <span class="score-chip">{{ formatScore(source.score) }}</span>
          </div>
          <p class="path-text">{{ source.sourcePath }}</p>
          <p class="hit-preview">{{ source.preview }}</p>
          <div class="document-meta">
            <span v-if="source.heading">标题：{{ source.heading }}</span>
            <span v-if="source.pageNumber">页码：{{ source.pageNumber }}</span>
            <span>chunk：{{ source.chunkId }}</span>
          </div>
        </div>
        <button class="text-button" type="button" @click="$emit('ask-source', source)">追问</button>
      </article>
    </template>
  </section>
</template>
