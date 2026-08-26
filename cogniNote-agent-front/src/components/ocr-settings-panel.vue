<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Bot,
  Cloud,
  FlaskConical,
  Save,
  ScanText,
  ShieldAlert,
  SlidersHorizontal,
  WalletCards
} from 'lucide-vue-next'
import { useOcrSettingsStore } from '../stores/ocr-settings'

const ocrSettingsStore = useOcrSettingsStore()
const router = useRouter()
const route = useRoute()
const strategyHelpDialogVisible = ref(false)

const visionModel = computed(() => ocrSettingsStore.settings.visionModel)
const keyStatusLabel = computed(() => visionModel.value.apiKeyConfigured ? '已配置' : '未配置')
const providerLabel = computed(() => {
  return {
    OPENAI_COMPATIBLE: 'OpenAI-compatible'
  }[visionModel.value.provider] || visionModel.value.provider || '-'
})
const modelSummary = computed(() => {
  return visionModel.value.modelName
    ? `${providerLabel.value} · ${visionModel.value.modelName}`
    : providerLabel.value
})

onMounted(() => {
  ocrSettingsStore.fetchSettings({ force: true })
})

async function handleSave() {
  await ocrSettingsStore.saveSettings()
  if (ocrSettingsStore.error) {
    ElMessage.error(ocrSettingsStore.error)
    return
  }
  ElMessage.success(ocrSettingsStore.message || 'OCR 设置已保存')
}

async function handleTest() {
  await ocrSettingsStore.testSettings()
  if (ocrSettingsStore.error) {
    ElMessage.error(ocrSettingsStore.error)
    return
  }
  ElMessage.success(ocrSettingsStore.message || 'OCR 连接测试通过')
}

function handleEnabledChange(value) {
  if (value && !ocrSettingsStore.canEnable) {
    ElMessage.warning('请先配置视觉识别模型 API Key')
    ocrSettingsStore.patchSettings({ enabled: false })
    return
  }
  ocrSettingsStore.patchSettings({ enabled: value })
}

function openVisionModelSettings() {
  router.push({
    name: 'settings',
    query: {
      ...route.query,
      item: 'model-vision'
    }
  })
}
</script>

<template>
  <section class="settings-panel ocr-settings-panel" aria-labelledby="ocr-settings-title">
    <header class="settings-panel__header">
      <p class="eyebrow">策略</p>
      <h3 id="ocr-settings-title">模型 OCR</h3>
    </header>

    <article class="settings-card ocr-status-card" aria-label="OCR 状态">
      <div class="ocr-status-card__item">
        <ScanText aria-hidden="true" />
        <span>
          <small>当前状态</small>
          <strong>{{ ocrSettingsStore.statusLabel }}</strong>
        </span>
      </div>
      <div class="ocr-status-card__item">
        <Bot aria-hidden="true" />
        <span>
          <small>视觉模型</small>
          <strong>{{ visionModel.modelName || '-' }}</strong>
        </span>
      </div>
      <div class="ocr-status-card__item">
        <Cloud aria-hidden="true" />
        <span>
          <small>Provider</small>
          <strong>{{ providerLabel }}</strong>
        </span>
      </div>
      <div class="ocr-status-card__item">
        <WalletCards aria-hidden="true" />
        <span>
          <small>月预算</small>
          <strong>{{ ocrSettingsStore.settings.limits.monthlyCallBudget }} 页</strong>
        </span>
      </div>
    </article>

    <article class="settings-card ocr-form-card" v-loading="ocrSettingsStore.loading">
      <div class="ocr-notice">
        <ShieldAlert aria-hidden="true" />
        <p>启用后，无文本层 PDF 页面图片会上传到所选多模态模型服务商，可能产生模型 token 或图片费用。</p>
      </div>

      <div class="ocr-toggle-row">
        <div>
          <p class="eyebrow">基础配置</p>
          <h4>启用 PDF 模型 OCR</h4>
          <p class="hint-message">仅处理没有可抽取文本层的 PDF；文本型 PDF 仍直接读取文本层。</p>
        </div>
        <div class="ocr-toggle-control">
          <el-switch
            :model-value="ocrSettingsStore.settings.enabled"
            :disabled="!ocrSettingsStore.canEnable"
            :title="ocrSettingsStore.canEnable ? '' : '先配置视觉识别模型 API Key'"
            active-text="启用"
            inactive-text="关闭"
            @update:model-value="handleEnabledChange"
          />
          <p v-if="!ocrSettingsStore.canEnable" class="warning-message ocr-toggle-warning">
            视觉识别模型配置 API Key 后才能启用。
          </p>
        </div>
      </div>

      <el-form label-position="top" class="ocr-form">
        <section class="ocr-form-section">
          <div class="ocr-section-heading ocr-section-heading--with-action">
            <div>
              <h4>视觉识别模型</h4>
              <p>OCR 使用独立 VISION 模型配置，不复用当前对话模型。</p>
            </div>
            <div class="ocr-heading-actions">
              <el-button class="ocr-help-button" @click="openVisionModelSettings">
                <Bot aria-hidden="true" />
                配置视觉模型
              </el-button>
            </div>
          </div>

          <div class="ocr-form__grid ocr-form__grid--connection">
            <el-form-item label="当前模型">
              <el-input :model-value="modelSummary" disabled />
            </el-form-item>
            <el-form-item label="API Key">
              <el-input :model-value="keyStatusLabel" disabled />
            </el-form-item>
            <el-form-item label="Base URL">
              <el-input :model-value="visionModel.baseUrl || '-'" disabled />
            </el-form-item>
          </div>
        </section>

        <section class="ocr-form-section">
          <div class="ocr-section-heading ocr-section-heading--with-action">
            <div>
              <h4>识别策略</h4>
              <p>按页渲染 PDF 图片并逐页识别，页码会进入解析 section，便于 chunk 和来源对齐。</p>
            </div>
            <el-button class="ocr-help-button" @click="strategyHelpDialogVisible = true">
              <SlidersHorizontal aria-hidden="true" />
              策略说明
            </el-button>
          </div>

          <div class="ocr-form__grid ocr-form__grid--strategy">
            <el-form-item label="单文档页数上限">
              <el-input-number
                :model-value="ocrSettingsStore.settings.limits.maxPagesPerDocument"
                :min="1"
                :max="500"
                @update:model-value="ocrSettingsStore.patchSettings({ limits: { maxPagesPerDocument: $event } })"
              />
            </el-form-item>
            <el-form-item label="单页超时秒数">
              <el-input-number
                :model-value="ocrSettingsStore.settings.limits.timeoutPerPageSeconds"
                :min="3"
                :max="600"
                @update:model-value="ocrSettingsStore.patchSettings({ limits: { timeoutPerPageSeconds: $event } })"
              />
            </el-form-item>
            <el-form-item label="月调用预算提示">
              <el-input-number
                :model-value="ocrSettingsStore.settings.limits.monthlyCallBudget"
                :min="1"
                :max="1000000"
                :step="100"
                @update:model-value="ocrSettingsStore.patchSettings({ limits: { monthlyCallBudget: $event } })"
              />
            </el-form-item>
          </div>
        </section>
      </el-form>

      <div class="ocr-footer">
        <p v-if="ocrSettingsStore.error" class="error-message ocr-feedback">
          {{ ocrSettingsStore.error }}
        </p>
        <p v-else-if="ocrSettingsStore.message" class="hint-message ocr-feedback">
          {{ ocrSettingsStore.message }}
        </p>
        <span v-else class="ocr-feedback" aria-hidden="true"></span>

        <div class="ocr-footer__actions">
          <el-button
            :loading="ocrSettingsStore.testing"
            :disabled="ocrSettingsStore.saving || ocrSettingsStore.loading"
            @click="handleTest"
          >
            <FlaskConical aria-hidden="true" />
            测试连接
          </el-button>
          <el-button
            type="primary"
            :loading="ocrSettingsStore.saving"
            :disabled="ocrSettingsStore.testing || ocrSettingsStore.loading"
            @click="handleSave"
          >
            <Save aria-hidden="true" />
            保存
          </el-button>
        </div>
      </div>
    </article>

    <el-dialog
      v-model="strategyHelpDialogVisible"
      class="ocr-help-dialog"
      title="模型 OCR 策略说明"
      width="min(720px, calc(100vw - 32px))"
      align-center
    >
      <section class="ocr-help">
        <div class="ocr-help__intro">
          <WalletCards aria-hidden="true" />
          <div>
            <h4>调用预算按 PDF 页数估算</h4>
            <p>月调用预算用于本地提示，不等同于模型服务商账单，也不会强制拦截调用。</p>
          </div>
        </div>
        <div class="ocr-strategy-list">
          <article>
            <strong>整篇无文本层才识别</strong>
            <p>只要 PDF 有可抽取文本页，当前版本不会混合 OCR 空白页。</p>
          </article>
          <article>
            <strong>逐页识别</strong>
            <p>每页渲染为图片后单独发送给视觉模型，识别结果保留原页码。</p>
          </article>
          <article>
            <strong>页数上限</strong>
            <p>限制单个 PDF 的最大 OCR 页数，避免一次重解析消耗过多调用。</p>
          </article>
          <article>
            <strong>重新解析</strong>
            <p>开启 OCR 后，对旧的需 OCR 文档使用目录管理里的重新解析。</p>
          </article>
        </div>
      </section>

      <template #footer>
        <div class="ocr-help-dialog__footer">
          <el-button type="primary" @click="strategyHelpDialogVisible = false">我知道了</el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.settings-panel.ocr-settings-panel {
  max-width: 980px;
  gap: 16px;
}

.ocr-status-card {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  overflow: hidden;
  padding: 0;
}

.ocr-status-card__item {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  min-width: 0;
  padding: 14px 16px;
  border-right: 1px solid var(--color-border);
}

.ocr-status-card__item:last-child {
  border-right: 0;
}

.ocr-status-card__item svg {
  width: 18px;
  height: 18px;
  color: var(--color-text-muted);
}

.ocr-status-card__item small,
.ocr-status-card__item strong {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ocr-status-card__item small {
  color: var(--color-text);
  font-size: 13px;
  font-weight: 700;
  line-height: 1.35;
}

.ocr-status-card__item strong {
  margin-top: 4px;
  color: var(--color-text-strong);
  font-size: 14px;
  font-weight: 850;
  line-height: 1.35;
}

.ocr-form-card {
  display: grid;
  gap: 0;
  overflow: hidden;
  padding: 0;
}

.ocr-notice {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  padding: 12px 20px;
  border-bottom: 1px solid var(--color-warning-border, var(--color-border));
  background: color-mix(in srgb, var(--color-warning-soft, #fff6df) 64%, var(--color-surface));
}

.ocr-notice svg {
  width: 18px;
  height: 18px;
  color: var(--color-warning-strong, #9a5a00);
}

.ocr-notice p {
  margin: 0;
  color: var(--color-text);
  font-size: 13px;
  font-weight: 700;
  line-height: 1.55;
}

.ocr-toggle-row {
  display: flex;
  min-width: 0;
  gap: 20px;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px;
  border-bottom: 1px solid var(--color-border);
  background: color-mix(in srgb, var(--color-surface) 92%, transparent);
}

.ocr-toggle-row > div {
  min-width: 0;
}

.ocr-toggle-row h4,
.ocr-section-heading h4 {
  margin: 0;
  color: var(--color-text-strong);
  font-size: 16px;
  font-weight: 850;
  line-height: 1.35;
}

.ocr-toggle-row .eyebrow {
  margin-bottom: 6px;
}

.ocr-toggle-row .hint-message {
  max-width: 660px;
  margin-top: 6px;
}

.ocr-toggle-control {
  display: grid;
  flex: 0 0 auto;
  gap: 6px;
  justify-items: end;
}

.ocr-toggle-warning {
  max-width: 260px;
  margin: 0;
  font-size: 12px;
  font-weight: 650;
  line-height: 1.45;
  text-align: right;
}

.ocr-form {
  display: grid;
  min-width: 0;
}

.ocr-form-section {
  display: grid;
  min-width: 0;
  gap: 14px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--color-border);
}

.ocr-section-heading {
  display: grid;
  gap: 6px;
}

.ocr-section-heading--with-action {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 16px;
}

.ocr-section-heading p {
  max-width: 680px;
  margin: 0;
  color: var(--color-text-muted);
  font-size: 13px;
  font-weight: 650;
  line-height: 1.6;
}

.ocr-heading-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

.ocr-help-button svg,
.ocr-footer__actions svg {
  width: 16px;
  height: 16px;
}

.ocr-form__grid {
  display: grid;
  min-width: 0;
  gap: 14px;
  align-items: end;
}

.ocr-form__grid--connection,
.ocr-form__grid--strategy {
  grid-template-columns: repeat(3, minmax(160px, 1fr));
}

.ocr-form :deep(.el-form-item) {
  min-width: 0;
  margin-bottom: 0;
}

.ocr-form :deep(.el-input-number),
.ocr-form :deep(.el-select) {
  width: 100%;
}

.ocr-footer {
  display: flex;
  min-width: 0;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
}

.ocr-feedback {
  min-width: 0;
  margin: 0;
}

.ocr-footer__actions {
  display: flex;
  flex: 0 0 auto;
  gap: 10px;
  align-items: center;
  justify-content: flex-end;
}

.ocr-help {
  display: grid;
  gap: 14px;
}

.ocr-help__intro {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  padding: 12px;
  border: 1px solid var(--color-action-border);
  border-radius: 8px;
  background: color-mix(in srgb, var(--color-action-soft) 58%, var(--color-surface));
}

.ocr-help__intro > svg {
  width: 20px;
  height: 20px;
  color: var(--color-action-strong);
}

.ocr-help h4 {
  margin: 0;
  color: var(--color-text-strong);
  font-size: 15px;
  font-weight: 850;
  line-height: 1.4;
}

.ocr-help p {
  margin: 6px 0 0;
  color: var(--color-text);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.65;
}

.ocr-strategy-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.ocr-strategy-list article {
  display: grid;
  min-width: 0;
  gap: 4px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
}

.ocr-strategy-list strong {
  color: var(--color-text-strong);
  font-size: 13px;
  font-weight: 800;
  line-height: 1.4;
}

.ocr-strategy-list p {
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 650;
  line-height: 1.55;
}

.ocr-help-dialog__footer {
  display: flex;
  justify-content: flex-end;
}

:global(.ocr-help-dialog.el-dialog),
:global(.ocr-help-dialog .el-dialog) {
  border: 1px solid var(--color-border);
  color: var(--color-text);
  background: var(--color-surface);
}

:global(.ocr-help-dialog .el-dialog__header),
:global(.ocr-help-dialog .el-dialog__footer) {
  border-color: var(--color-border);
  color: var(--color-text-strong);
  background: var(--color-surface);
}

:global(.ocr-help-dialog .el-dialog__body) {
  max-height: min(72vh, 640px);
  overflow: auto;
  padding-top: 8px;
}

@media (max-width: 960px) {
  .settings-panel.ocr-settings-panel {
    max-width: none;
  }

  .ocr-status-card,
  .ocr-form__grid--connection,
  .ocr-form__grid--strategy {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .ocr-status-card,
  .ocr-form__grid--connection,
  .ocr-form__grid--strategy,
  .ocr-strategy-list {
    grid-template-columns: 1fr;
  }

  .ocr-status-card__item {
    border-right: 0;
    border-bottom: 1px solid var(--color-border);
  }

  .ocr-status-card__item:last-child {
    border-bottom: 0;
  }

  .ocr-section-heading--with-action {
    grid-template-columns: 1fr;
  }

  .ocr-heading-actions {
    justify-content: flex-start;
  }

  .ocr-toggle-row,
  .ocr-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .ocr-toggle-control {
    justify-items: start;
  }

  .ocr-toggle-warning {
    max-width: none;
    text-align: left;
  }

  .ocr-footer__actions {
    justify-content: flex-start;
  }
}
</style>
