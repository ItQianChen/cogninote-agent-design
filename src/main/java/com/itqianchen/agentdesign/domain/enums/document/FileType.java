package com.itqianchen.agentdesign.domain.enums.document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 文档导入支持的文件类型。
 *
 * <p>枚举值可能进入数据库或 API 响应，新增或改名时需要同步解析器注册和前端展示。</p>
 */
public enum FileType {
    /** Markdown 文档。 */
    MARKDOWN(".md", ".markdown"),

    /** 纯文本或日志类文档。 */
    TEXT(".txt"),

    /** Word OpenXML 文档。 */
    DOCX(".docx"),

    /** Word 97-2003 二进制文档。 */
    DOC(".doc"),

    /** PDF 文档。 */
    PDF(".pdf"),

    /** 本地 HTML 文档。 */
    HTML(".html", ".htm");

    private final List<String> extensions;

    /**
     * 绑定文件类型和一个或多个扩展名。
     *
     * @param extension 主扩展名，小写且包含前导点
     * @param additionalExtensions 兼容扩展名，小写且包含前导点
     */
    FileType(String extension, String... additionalExtensions) {
        List<String> values = new ArrayList<>(1 + additionalExtensions.length);
        values.add(extension);
        for (String additionalExtension : additionalExtensions) {
            values.add(additionalExtension);
        }
        this.extensions = List.copyOf(values);
    }

    /**
     * 返回用于兼容旧调用的主扩展名。
     *
     * @return 小写扩展名，包含前导点
     */
    public String extension() {
        return extensions.get(0);
    }

    /**
     * 返回文件类型支持的全部扩展名。
     *
     * @return 不可变扩展名列表，元素均为小写且包含前导点
     */
    public List<String> extensions() {
        return extensions;
    }

    /**
     * 根据文件名后缀解析支持的文档类型。
     *
     * <p>匹配使用 ROOT locale，避免土耳其语等区域设置影响大小写转换。</p>
     *
     * @param fileName 本地文件名或路径
     * @return 支持的文件类型；不支持时为空
     */
    public static Optional<FileType> fromFileName(String fileName) {
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        for (FileType fileType : values()) {
            for (String extension : fileType.extensions) {
                if (lowerFileName.endsWith(extension)) {
                    return Optional.of(fileType);
                }
            }
        }

        return Optional.empty();
    }
}


