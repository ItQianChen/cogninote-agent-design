<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  BookOpen,
  Cloud,
  ExternalLink,
  FlaskConical,
  KeyRound,
  Languages,
  Save,
  ScanText,
  ShieldAlert,
  Trash2,
  WalletCards
} from 'lucide-vue-next'
import { useOcrSettingsStore } from '../stores/ocr-settings'

const ocrSettingsStore = useOcrSettingsStore()
const helpDialogVisible = ref(false)
const strategyHelpDialogVisible = ref(false)
const baiduDocsLinks = [
  {
    label: 'OCR 价格',
    description: '免费额度、标准版和高精度版价格以官方页面为准。',
    url: 'https://cloud.baidu.com/product-price/ocr.html'
  },
  {
    label: '通用文字识别',
    description: '查看标准版和高精度版接口参数。',
    url: 'https://cloud.baidu.com/doc/OCR/s/7kibizyfm'
  },
  {
    label: '应用鉴权',
    description: '查看 API Key / Secret Key 获取 access_token 的方式。',
    url: 'https://cloud.baidu.com/doc/OCR/s/Ck3h7y2ia'
  }
]
const keyStatusLabel = computed(() => {
  if (ocrSettingsStore.credentialsReady) {
    return '已填写'
  }
  if (ocrSettingsStore.settings.baidu.apiKeyConfigured || ocrSettingsStore.settings.baidu.secretKeyConfigured) {
    return '不完整'
  }
  return '未配置'
})
const modeLabel = computed(() => {
  return ocrSettingsStore.settings.baidu.recognitionMode === 'ACCURATE' ? '高精度版' : '标准版'
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
    ElMessage.warning('请先填写百度 OCR API Key 和 Secret Key')
    ocrSettingsStore.patchSettings({ enabled: false })
    return
  }
  ocrSettingsStore.patchSettings({ enabled: value })
}

async function handleClearKeys() {
  try {
    await ElMessageBox.confirm(
      '清空后 OCR 会自动关闭，旧的 OCR_REQUIRED 文档需要重新配置密钥后再解析。',
      '清空百度 OCR 密钥',
      {
        confirmButtonText: '清空',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch (err) {
    if (err === 'cancel' || err === 'close') {
      return
    }
    throw err
  }
  ocrSettingsStore.patchSettings({
    enabled: false,
    baidu: {
      apiKey: '',
      secretKey: ''
    }
  })
  await handleSave()
}
</script>

<template>
  <section class="settings-panel ocr-settings-panel" aria-labelledby="ocr-settings-title">
    <header class="settings-panel__header">
      <p class="eyebrow">策略</p>
      <h3 id="ocr-settings-title">OCR 识别</h3>
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
        <KeyRound aria-hidden="true" />
        <span>
          <small>密钥</small>
          <strong>{{ keyStatusLabel }}</strong>
        </span>
      </div>
      <div class="ocr-status-card__item">
        <Cloud aria-hidden="true" />
        <span>
          <small>Provider</small>
          <strong>BAIDU OCR</strong>
        </span>
      </div>
      <div class="ocr-status-card__item">
        <Languages aria-hidden="true" />
        <span>
          <small>模式</small>
          <strong>{{ modeLabel }}</strong>
        </span>
      </div>
    </article>

    <article class="settings-card ocr-form-card" v-loading="ocrSettingsStore.loading">
      <div class="ocr-notice">
        <ShieldAlert aria-hidden="true" />
        <p>启用公共 OCR 后，无文本层 PDF 会按页渲染为图片并上传到百度 OCR，可能产生按页调用费用。</p>
      </div>

      <div class="ocr-toggle-row">
        <div>
          <p class="eyebrow">基础配置</p>
          <h4>启用 PDF OCR</h4>
          <p class="hint-message">仅处理没有可抽取文本层的 PDF；文本型 PDF 仍直接读取文本层。</p>
        </div>
        <div class="ocr-toggle-control">
          <el-switch
            :model-value="ocrSettingsStore.settings.enabled"
            :disabled="!ocrSettingsStore.canEnable"
            :title="ocrSettingsStore.canEnable ? '' : '先填写百度 OCR API Key 和 Secret Key'"
            active-text="启用"
            inactive-text="关闭"
            @update:model-value="handleEnabledChange"
          />
          <p v-if="!ocrSettingsStore.canEnable" class="warning-message ocr-toggle-warning">
            API Key 和 Secret Key 都填写后才能启用。
          </p>
        </div>
      </div>

      <el-form label-position="top" class="ocr-form">
        <section class="ocr-form-section">
          <div class="ocr-section-heading ocr-section-heading--with-action">
            <div>
              <h4>百度 OCR 连接</h4>
              <p>密钥保存在本机 app_settings，保存后仍会在本页明文显示，便于核对和修改。</p>
            </div>
            <div class="ocr-heading-actions">
              <el-button class="ocr-help-button" @click="helpDialogVisible = true">
                <BookOpen aria-hidden="true" />
                配置说明
              </el-button>
              <el-button class="ocr-help-button" :disabled="!ocrSettingsStore.credentialsReady" @click="handleClearKeys">
                <Trash2 aria-hidden="true" />
                清空密钥
              </el-button>
            </div>
          </div>

          <div class="ocr-form__grid ocr-form__grid--connection">
            <el-form-item label="Provider">
              <el-input model-value="BAIDU_OCR" disabled />
            </el-form-item>
            <el-form-item label="API Key">
              <el-input
                :model-value="ocrSettingsStore.settings.baidu.apiKey"
                autocomplete="off"
                placeholder="粘贴百度 OCR API Key"
                @update:model-value="ocrSettingsStore.patchSettings({ baidu: { apiKey: $event } })"
              />
            </el-form-item>
            <el-form-item label="Secret Key">
              <el-input
                :model-value="ocrSettingsStore.settings.baidu.secretKey"
                autocomplete="off"
                placeholder="粘贴百度 OCR Secret Key"
                @update:model-value="ocrSettingsStore.patchSettings({ baidu: { secretKey: $event } })"
              />
            </el-form-item>
          </div>
        </section>

        <section class="ocr-form-section">
          <div class="ocr-section-heading ocr-section-heading--with-action">
            <div>
              <h4>识别策略</h4>
              <p>默认标准版适合本地知识库导入；高精度版更贵，建议只在识别质量不足时切换。</p>
            </div>
            <el-button class="ocr-help-button" @click="strategyHelpDialogVisible = true">
              <WalletCards aria-hidden="true" />
              策略说明
            </el-button>
          </div>

          <div class="ocr-form__grid ocr-form__grid--strategy">
            <el-form-item label="识别模式">
              <el-segmented
                :model-value="ocrSettingsStore.settings.baidu.recognitionMode"
                :options="[
                  { label: '标准版', value: 'STANDARD' },
                  { label: '高精度版', value: 'ACCURATE' }
                ]"
                @update:model-value="ocrSettingsStore.patchSettings({ baidu: { recognitionMode: $event } })"
              />
            </el-form-item>
            <el-form-item label="语言类型">
              <el-select
                :model-value="ocrSettingsStore.settings.baidu.languageType"
                @update:model-value="ocrSettingsStore.patchSettings({ baidu: { languageType: $event } })"
              >
                <el-option label="中英文混合 CHN_ENG" value="CHN_ENG" />
                <el-option label="英文 ENG" value="ENG" />
                <el-option label="葡萄牙语 POR" value="POR" />
                <el-option label="法语 FRE" value="FRE" />
                <el-option label="德语 GER" value="GER" />
                <el-option label="意大利语 ITA" value="ITA" />
                <el-option label="西班牙语 SPA" value="SPA" />
                <el-option label="俄语 RUS" value="RUS" />
                <el-option label="日语 JAP" value="JAP" />
                <el-option label="韩语 KOR" value="KOR" />
              </el-select>
            </el-form-item>
            <el-form-item label="检测朝向">
              <el-switch
                :model-value="ocrSettingsStore.settings.baidu.detectDirection"
                active-text="开启"
                inactive-text="关闭"
                @update:model-value="ocrSettingsStore.patchSettings({ baidu: { detectDirection: $event } })"
              />
            </el-form-item>
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
                :max="120"
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
      v-model="helpDialogVisible"
      class="ocr-help-dialog"
      title="百度 OCR 配置说明"
      width="min(720px, calc(100vw - 32px))"
      align-center
    >
      <section class="ocr-help">
        <div class="ocr-help__intro">
          <Cloud aria-hidden="true" />
          <div>
            <h4>Provider 固定为 BAIDU_OCR</h4>
            <p>在百度智能云创建 OCR 应用，复制 API Key 和 Secret Key 到本页。测试连接只验证鉴权，不上传你的文件。</p>
          </div>
        </div>

        <section class="ocr-help__docs" aria-label="百度 OCR 官方文档">
          <h4>百度官方文档</h4>
          <div class="ocr-help__links">
            <a
              v-for="link in baiduDocsLinks"
              :key="link.url"
              :href="link.url"
              target="_blank"
              rel="noreferrer"
            >
              <span>
                <strong>{{ link.label }}</strong>
                <small>{{ link.description }}</small>
              </span>
              <ExternalLink aria-hidden="true" />
            </a>
          </div>
        </section>
      </section>

      <template #footer>
        <div class="ocr-help-dialog__footer">
          <el-button type="primary" @click="helpDialogVisible = false">我知道了</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="strategyHelpDialogVisible"
      class="ocr-help-dialog"
      title="OCR 策略说明"
      width="min(720px, calc(100vw - 32px))"
      align-center
    >
      <section class="ocr-help">
        <div class="ocr-help__intro">
          <WalletCards aria-hidden="true" />
          <div>
            <h4>调用预算按 PDF 页数估算</h4>
            <p>当前实现按页渲染并识别；一页通常对应一次 OCR 调用。月调用预算用于本地提示，不等同于百度账单。</p>
          </div>
        </div>
        <div class="ocr-strategy-list">
          <article>
            <strong>标准版优先</strong>
            <p>日常资料入库先用标准版，速度和成本更稳。</p>
          </article>
          <article>
            <strong>高精度版谨慎开启</strong>
            <p>适合图片质量差或复杂排版，但费用通常更高。</p>
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

.ocr-form__grid--connection {
  grid-template-columns: minmax(160px, 210px) repeat(2, minmax(220px, 1fr));
}

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

.ocr-help__docs {
  display: grid;
  gap: 10px;
}

.ocr-help__links,
.ocr-strategy-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.ocr-help__links a,
.ocr-strategy-list article {
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
}

.ocr-help__links a {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 18px;
  gap: 10px;
  align-items: center;
  color: inherit;
  text-decoration: none;
}

.ocr-help__links a:hover,
.ocr-help__links a:focus-visible {
  border-color: var(--color-action-border);
  background: var(--color-action-soft);
}

.ocr-help__links a > span,
.ocr-strategy-list article {
  display: grid;
  gap: 4px;
}

.ocr-help__links strong,
.ocr-strategy-list strong {
  color: var(--color-text-strong);
  font-size: 13px;
  font-weight: 800;
  line-height: 1.4;
}

.ocr-help__links small,
.ocr-strategy-list p {
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 650;
  line-height: 1.55;
}

.ocr-help__links svg {
  width: 16px;
  height: 16px;
  color: var(--color-action-strong);
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
  .ocr-form__grid--strategy {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ocr-form__grid--connection {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .ocr-status-card,
  .ocr-form__grid--strategy,
  .ocr-help__links,
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
