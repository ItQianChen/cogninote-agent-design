package com.itqianchen.agentdesign.domain.enums.document;

/**
 * Document Status 枚举 文档管理 的稳定取值。
 * <p>枚举值可能进入数据库或 API 响应，修改时需要考虑兼容性。</p>
 */
public enum DocumentStatus {
    /** 已成功解析并写入 chunk，可进入检索索引。 */
    PARSED,

    /** 文件内容未变化，本轮导入跳过重新解析。 */
    SKIPPED,

    /** 解析失败，通常需要修复源文件后重新同步。 */
    FAILED,

    /** PDF 没有可抽取文本层，当前版本需要外部 OCR 后重新同步。 */
    OCR_REQUIRED
}


