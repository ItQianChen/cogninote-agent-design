package com.itqianchen.agentdesign.domain.interfaces.ingestion;


import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedDocument;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentParseRequest;
import com.itqianchen.agentdesign.domain.enums.document.FileType;
import java.nio.file.Path;

/**
 * 解析本地文件为 ingestion 后续可消费的结构。
 *
 * <p>实现只负责读取和抽取文本，不写数据库或索引。</p>
 */
public interface DocumentParser {

    /**
     * 声明当前解析器支持的文件类型。
     *
     * <p>注册表只根据这个契约路由解析器；新增文件类型时实现类必须保持该判断与 FileType 枚举一致。</p>
     *
     * @param fileType 已从文件名解析出的类型
     * @return 当前解析器是否能安全处理该类型
     */
    boolean supports(FileType fileType);

    /**
     * 解析指定路径，失败时抛出 DocumentParseException 并保留路径上下文。
     *
     * @param path 本地文件路径，调用方负责确保文件存在且类型已通过 supports 校验
     * @return 供切块流程消费的文档文本结构
     * @throws DocumentParseException 当文件读取或格式解析失败时抛出
     */
    ParsedDocument parse(Path path);

    /**
     * 使用可选增量检查点解析文档。
     *
     * <p>默认实现保持现有 parser 的一次性解析行为；长耗时 parser 可以覆盖该方法，在每个稳定
     * section 完成后报告进度，但不得直接依赖数据库或索引实现。</p>
     *
     * @param request 解析路径和检查点边界
     * @return 供切块流程消费的完整文档文本结构
     */
    default ParsedDocument parse(DocumentParseRequest request) {
        return parse(request.path());
    }
}


