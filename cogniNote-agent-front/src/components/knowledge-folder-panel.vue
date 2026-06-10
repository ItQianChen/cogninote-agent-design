<script setup>
// knowledge-folder-panel 负责知识库目录导入、状态管理和目录级操作。
import { computed, ref } from 'vue'
import { ChevronDown, ChevronRight, FolderOpen, FolderPlus, RefreshCw, Trash2 } from 'lucide-vue-next'
import { useKnowledgeFoldersStore } from '../stores/knowledge-folders'
import { useSearchStore } from '../stores/search'
import { formatFileSize, formatTime } from '../utils/formatters'

const knowledgeStore = useKnowledgeFoldersStore()
const searchStore = useSearchStore()
const isImportFormCollapsed = ref(true)

const hasKnowledgeEntries = computed(() =>
  knowledgeStore.folders.length > 0 || knowledgeStore.unassignedDocuments.length > 0
)
const showImportForm = computed(() => !hasKnowledgeEntries.value || !isImportFormCollapsed.value)

/**
 * 全量重建是高成本操作，保留在资料管理页显式触发，避免刷新时产生副作用。
 */
async function rebuildAllIndexes() {
  await searchStore.rebuildIndex()
  if (!searchStore.indexError) {
    await knowledgeStore.fetchFolders()
  }
}

function toggleImportForm() {
  isImportFormCollapsed.value = !isImportFormCollapsed.value
}
</script>

<template>
  <section class="knowledge-pane knowledge-pane--folders" aria-label="知识库目录管理">
    <header class="knowledge-pane__header knowledge-pane__header--compact">
      <div>
        <p class="eyebrow">资料管理</p>
        <h3>目录</h3>
        <p class="muted-text">导入本地文件夹，查看目录状态。</p>
      </div>
      <div class="header-actions">
        <el-button v-if="hasKnowledgeEntries" @click="toggleImportForm">
          <FolderPlus aria-hidden="true" />
          <span>{{ showImportForm ? '收起导入' : '导入目录' }}</span>
        </el-button>
        <el-button :loading="searchStore.isRebuildingIndex" @click="rebuildAllIndexes">
          <RefreshCw aria-hidden="true" />
          <span>重建索引</span>
        </el-button>
      </div>
    </header>

    <form
      v-if="showImportForm"
      class="ingest-form knowledge-folder-import"
      @submit.prevent="knowledgeStore.importFolder"
    >
      <label class="field field--full">
        <span>本地目录</span>
        <el-input
          v-model="knowledgeStore.folderPath"
          placeholder="点击选择文件夹，或手动输入 D:/notes"
          autocomplete="off"
        />
      </label>

      <div class="knowledge-folder-import__actions">
        <el-button @click="knowledgeStore.chooseFolder">
          <FolderPlus aria-hidden="true" />
          <span>选择文件夹</span>
        </el-button>

        <el-checkbox v-model="knowledgeStore.recursive">递归扫描</el-checkbox>

        <el-button type="primary" native-type="submit" :loading="knowledgeStore.isImporting">
          导入目录
        </el-button>
      </div>
    </form>

    <el-alert
      v-if="knowledgeStore.error"
      class="settings-inline-alert"
      type="error"
      :title="knowledgeStore.error"
      :closable="false"
      show-icon
    />

    <el-alert
      v-if="searchStore.indexError"
      class="settings-inline-alert"
      type="error"
      :title="searchStore.indexError"
      :closable="false"
      show-icon
    />

    <div v-if="knowledgeStore.ingestResult" class="result-strip result-strip--compact">
      <span>扫描 {{ knowledgeStore.ingestResult.scannedCount }}</span>
      <span>解析 {{ knowledgeStore.ingestResult.parsedCount }}</span>
      <span>跳过 {{ knowledgeStore.ingestResult.skippedCount }}</span>
      <span>失败 {{ knowledgeStore.ingestResult.failedCount }}</span>
    </div>

    <div v-if="knowledgeStore.rebuildResult" class="result-strip result-strip--compact result-strip--three">
      <span>扫描 {{ knowledgeStore.rebuildResult.scannedCount }}</span>
      <span>索引文档 {{ knowledgeStore.rebuildResult.indexedDocumentCount }}</span>
      <span>失败 {{ knowledgeStore.rebuildResult.failedCount + knowledgeStore.rebuildResult.failedDocumentCount }}</span>
    </div>

    <div v-if="searchStore.rebuildResult" class="result-strip result-strip--compact result-strip--three">
      <span>索引文档 {{ searchStore.rebuildResult.indexedDocumentCount }}</span>
      <span>索引 chunks {{ searchStore.rebuildResult.indexedChunkCount }}</span>
      <span>耗时 {{ searchStore.rebuildResult.durationMs }} ms</span>
    </div>

    <ul v-if="knowledgeStore.rebuildResult?.failures?.length" class="failure-list">
      <li v-for="failure in knowledgeStore.rebuildResult.failures" :key="failure.sourcePath">
        <strong>{{ failure.sourcePath }}</strong>
        <span>{{ failure.message }}</span>
      </li>
    </ul>

    <section class="knowledge-folder-list">
      <p v-if="knowledgeStore.isLoading" class="panel-message">正在读取知识库目录...</p>
      <p v-else-if="knowledgeStore.folders.length === 0 && knowledgeStore.unassignedDocuments.length === 0" class="panel-message">
        还没有导入知识库目录。
      </p>

      <article v-for="folder in knowledgeStore.folders" :key="folder.id" class="knowledge-folder-card">
        <header class="knowledge-folder-header">
          <button class="folder-toggle-button" type="button" @click="knowledgeStore.toggleExpanded(folder.id)">
            <ChevronDown v-if="knowledgeStore.isExpanded(folder.id)" aria-hidden="true" />
            <ChevronRight v-else aria-hidden="true" />
            <FolderOpen aria-hidden="true" />
            <span>{{ folder.displayName }}</span>
          </button>

          <div class="folder-actions">
            <el-button
              :disabled="knowledgeStore.isFolderBusy(folder.id)"
              @click="knowledgeStore.toggleFolderEnabled(folder)"
            >
              {{ folder.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button
              :disabled="knowledgeStore.isFolderBusy(folder.id) || !folder.enabled"
              @click="knowledgeStore.rebuildFolder(folder.id)"
            >
              <RefreshCw aria-hidden="true" />
              <span>重建</span>
            </el-button>
            <el-popconfirm
              title="删除该知识库目录记录？本地原始文件不会被删除。"
              confirm-button-text="删除"
              cancel-button-text="取消"
              @confirm="knowledgeStore.deleteFolder(folder.id)"
            >
              <template #reference>
                <el-button
                  type="danger"
                  plain
                  :disabled="knowledgeStore.isFolderBusy(folder.id)"
                >
                  <Trash2 aria-hidden="true" />
                  <span>删除</span>
                </el-button>
              </template>
            </el-popconfirm>
          </div>
        </header>

        <p class="path-text">{{ folder.folderPath }}</p>

        <div class="folder-meta">
          <span :class="['status-chip', folder.enabled ? 'status-chip--parsed' : 'status-chip--skipped']">
            {{ folder.enabled ? '已启用' : '已停用' }}
          </span>
          <span>{{ folder.documentCount }} 文档</span>
          <span>{{ folder.chunkCount }} chunks</span>
          <span>{{ folder.unindexedCount }} 未索引</span>
          <span>导入 {{ formatTime(folder.lastIngestedAt) }}</span>
          <span>索引 {{ formatTime(folder.lastIndexedAt) }}</span>
        </div>

        <div v-if="knowledgeStore.isExpanded(folder.id)" class="folder-documents">
          <p v-if="folder.documents.length === 0" class="panel-message">该目录下还没有文档记录。</p>
          <article v-for="document in folder.documents" :key="document.id" class="document-row document-row--nested">
            <div class="document-main">
              <div class="document-title-line">
                <h4>{{ document.fileName }}</h4>
                <span :class="['status-chip', `status-chip--${document.status.toLowerCase()}`]">
                  {{ document.status }}
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
          </article>
        </div>
      </article>

      <article v-if="knowledgeStore.unassignedDocuments.length" class="knowledge-folder-card">
        <header class="knowledge-folder-header">
          <button class="folder-toggle-button" type="button" @click="knowledgeStore.toggleExpanded('unassigned')">
            <ChevronDown v-if="knowledgeStore.isExpanded('unassigned')" aria-hidden="true" />
            <ChevronRight v-else aria-hidden="true" />
            <FolderOpen aria-hidden="true" />
            <span>未归属文档</span>
          </button>
        </header>
        <p class="hint-message">这些是旧版本导入的散落文档。重新导入对应本地目录后，会自动归属到知识库文件夹。</p>
        <div v-if="knowledgeStore.isExpanded('unassigned')" class="folder-documents">
          <article v-for="document in knowledgeStore.unassignedDocuments" :key="document.id" class="document-row document-row--nested">
            <div class="document-main">
              <div class="document-title-line">
                <h4>{{ document.fileName }}</h4>
                <span :class="['status-chip', `status-chip--${document.status.toLowerCase()}`]">
                  {{ document.status }}
                </span>
              </div>
              <p class="path-text">{{ document.sourcePath }}</p>
              <div class="document-meta">
                <span>{{ document.fileType }}</span>
                <span>{{ formatFileSize(document.fileSize) }}</span>
                <span>{{ document.chunkCount }} chunks</span>
                <span>索引 {{ formatTime(document.indexedAt) }}</span>
              </div>
            </div>
          </article>
        </div>
      </article>
    </section>
  </section>
</template>
