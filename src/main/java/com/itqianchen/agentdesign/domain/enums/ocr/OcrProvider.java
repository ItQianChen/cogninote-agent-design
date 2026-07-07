package com.itqianchen.agentdesign.domain.enums.ocr;

/**
 * OCR Provider 类型。
 *
 * <p>枚举名会写入 app_settings 的 ocr.settings JSON；新增或改名都需要兼容已有本地配置。</p>
 */
public enum OcrProvider {
    /** 百度智能云 OCR。 */
    BAIDU_OCR
}
