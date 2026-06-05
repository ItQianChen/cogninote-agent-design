<script setup>
import { computed } from 'vue'
import SegmentedControl from './segmented-control.vue'

const props = defineProps({
  useKnowledgeBase: {
    type: Boolean,
    default: true
  },
  mode: {
    type: String,
    default: 'HYBRID'
  },
  topK: {
    type: Number,
    default: 8
  },
  modes: {
    type: Array,
    required: true
  }
})

const emit = defineEmits(['update:useKnowledgeBase', 'update:mode', 'update:topK'])

// 弹层只通过事件写回 chat store，避免原生控件直接绑定 Pinia 后出现摘要与表单不同步。
const activeModeLabel = computed(() =>
  props.modes.find((item) => item.value === props.mode)?.label || props.mode
)

function updateTopK(event) {
  emit('update:topK', event.target.value)
}
</script>

<template>
  <div class="composer-settings-popover">
    <div class="composer-settings__summary">
      <span>{{ useKnowledgeBase ? '使用知识库' : '纯模型对话' }}</span>
      <span>{{ activeModeLabel }}</span>
      <span>Top K {{ topK }}</span>
    </div>

    <div class="composer-settings__body">
      <button
        class="knowledge-switch"
        type="button"
        role="switch"
        :aria-checked="useKnowledgeBase"
        @click="emit('update:useKnowledgeBase', !useKnowledgeBase)"
      >
        <span class="knowledge-switch__track" aria-hidden="true">
          <span class="knowledge-switch__thumb"></span>
        </span>
        <span>使用知识库</span>
      </button>

      <SegmentedControl
        :model-value="mode"
        :options="modes"
        label="RAG 检索模式"
        @update:model-value="emit('update:mode', $event)"
      />

      <label class="field field--small topk-field">
        <span>Top K</span>
        <input :value="topK" type="number" min="1" max="50" @input="updateTopK" />
      </label>
    </div>
  </div>
</template>
