package com.itqianchen.agentdesign.service.ocr;

import java.nio.file.Path;

/**
 * 待识别的 PDF 页面图片。
 *
 * <p>imageBytes 是渲染后的单页图片二进制，不包含用户原始 PDF 文件。</p>
 */
public record OcrPageImage(
        Path sourcePath,
        int pageNumber,
        byte[] imageBytes
) {
}
