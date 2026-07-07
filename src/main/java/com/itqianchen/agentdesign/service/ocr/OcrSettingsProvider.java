package com.itqianchen.agentdesign.service.ocr;

/**
 * OCR 设置读取边界。
 *
 * <p>PDF parser 只依赖该最小接口，单元测试可以注入内存快照而不加载 app_settings。</p>
 */
public interface OcrSettingsProvider {

    OcrSettingsSnapshot snapshot();
}
