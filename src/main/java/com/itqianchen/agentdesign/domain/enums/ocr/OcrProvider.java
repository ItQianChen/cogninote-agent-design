package com.itqianchen.agentdesign.domain.enums.ocr;

/**
 * OCR Provider 类型。
 *
 * <p>枚举名会写入 app_settings 的 ocr.settings JSON；新增或改名都需要兼容已有本地配置。</p>
 */
public enum OcrProvider {
    /** 使用用户配置的多模态视觉模型识别页面图片。 */
    MODEL_VISION,

    /**
     * 旧版百度 OCR 配置哨兵。
     *
     * <p>运行时不再支持该 Provider，只保留用于反序列化旧的 ocr.settings 并迁移为 MODEL_VISION。</p>
     */
    @Deprecated
    BAIDU_OCR
}
