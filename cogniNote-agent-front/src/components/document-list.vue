<script setup>
// document-list 负责 文档管理 页面或组件的状态组织、用户交互和后端同步。
import { formatFileSize, formatTime } from '../utils/formatters'

const DOCUMENT_STATUS_LABELS = {
  PARSED: '已解析',
  SKIPPED: '已跳过',
  FAILED: '失败',
  OCR_REQUIRED: '需 OCR'
}

defineProps({
  documents: {
    type: Array,
    required: true
  },
  isLoading: {
    type: Boolean,
    default: false
  }
})

defineEmits(['refresh', 'delete'])

function documentStatusLabel(status) {
  return DOCUMENT_STATUS_LABELS[status] || status || '未知'
}
</script>

<template>
  <section class="document-list">
    <div class="section-title-line">
      <h3>文档列表</h3>
      <button class="secondary-button" type="button" :disabled="isLoading" @click="$emit('refresh')">
        刷新列表
      </button>
    </div>
    <p v-if="isLoading" class="panel-message">正在读取文档列表...</p>
    <p v-else-if="documents.length === 0" class="panel-message">还没有导入文档。</p>

    <template v-else>
      <article v-for="document in documents" :key="document.id" class="document-row">
        <div class="document-main">
          <div class="document-title-line">
            <h4>{{ document.fileName }}</h4>
            <span :class="['status-chip', `status-chip--${document.status.toLowerCase()}`]">
              {{ documentStatusLabel(document.status) }}
            </span>
          </div>
          <p class="path-text">{{ document.sourcePath }}</p>
          <div class="document-meta">
            <span>{{ document.fileType }}</span>
            <span>{{ formatFileSize(document.fileSize) }}</span>
            <span>{{ document.chunkCount }} chunks</span>
            <span>索引 {{ formatTime(document.indexedAt) }}</span>
            <span>{{ formatTime(document.updatedAt) }}</span>
          </div>
        </div>
        <button class="text-button" type="button" @click="$emit('delete', document.id)">删除记录</button>
      </article>
    </template>
  </section>
</template>
