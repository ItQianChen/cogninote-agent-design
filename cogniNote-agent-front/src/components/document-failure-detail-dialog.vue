<script setup>
import { computed } from 'vue'
import { AlertTriangle } from 'lucide-vue-next'
import { failureStageLabel, failureTechnicalRows } from '../utils/document-failures'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  failure: {
    type: Object,
    default: null
  },
  documentName: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['update:modelValue'])
const isOpen = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})
const technicalRows = computed(() => failureTechnicalRows(props.failure))
</script>

<template>
  <el-dialog
    v-model="isOpen"
    title="文档失败诊断"
    width="min(680px, calc(100vw - 32px))"
    align-center
  >
    <section v-if="failure" class="document-failure-detail">
      <header>
        <AlertTriangle aria-hidden="true" />
        <div>
          <strong>{{ documentName || failure.sourcePath }}</strong>
          <span>{{ failureStageLabel(failure) }}</span>
        </div>
      </header>
      <p class="document-failure-detail__path">{{ failure.sourcePath }}</p>
      <section class="document-failure-detail__summary">
        <strong>{{ failure.message }}</strong>
        <p v-if="failure.suggestion">{{ failure.suggestion }}</p>
      </section>
      <details v-if="technicalRows.length" class="document-failure-detail__technical">
        <summary>查看技术详情</summary>
        <dl>
          <div v-for="row in technicalRows" :key="row[0]">
            <dt>{{ row[0] }}</dt>
            <dd>{{ row[1] }}</dd>
          </div>
        </dl>
      </details>
    </section>
  </el-dialog>
</template>

<style scoped>
.document-failure-detail {
  display: grid;
  gap: 14px;
}

.document-failure-detail header {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.document-failure-detail header svg {
  width: 20px;
  color: var(--color-danger);
}

.document-failure-detail header div {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.document-failure-detail header span,
.document-failure-detail__path {
  color: var(--color-text-muted);
  font-size: 12px;
}

.document-failure-detail__path {
  margin: 0;
  overflow-wrap: anywhere;
}

.document-failure-detail__summary {
  padding: 12px;
  border: 1px solid var(--color-danger-border);
  border-radius: 8px;
  background: var(--color-danger-soft);
}

.document-failure-detail__summary p {
  margin: 6px 0 0;
  color: var(--color-text);
  line-height: 1.5;
}

.document-failure-detail__technical summary {
  cursor: pointer;
  color: var(--color-primary);
  font-weight: 700;
}

.document-failure-detail__technical dl {
  display: grid;
  gap: 8px;
  margin: 12px 0 0;
}

.document-failure-detail__technical dl div {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: 10px;
}

.document-failure-detail__technical dt {
  color: var(--color-text-muted);
}

.document-failure-detail__technical dd {
  margin: 0;
  overflow-wrap: anywhere;
}
</style>
