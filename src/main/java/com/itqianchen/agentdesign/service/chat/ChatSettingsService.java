package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.domain.chat.QueryContextualizerMode;
import com.itqianchen.agentdesign.domain.chat.QueryContextualizerProperties;
import com.itqianchen.agentdesign.dto.chat.ChatSettingsRequest;
import com.itqianchen.agentdesign.dto.chat.ChatSettingsResponse;
import com.itqianchen.agentdesign.repository.settings.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 聊天设置服务承载全局聊天设置的读取和保存流程。
 * <p>这里集中处理 SQLite 用户设置、环境变量和旧开关之间的优先级。</p>
 */
@Service
public class ChatSettingsService {

    private static final String QUERY_CONTEXTUALIZER_MODE_KEY = "chat.query-contextualizer.mode";

    private final AppSettingRepository appSettingRepository;
    private final QueryContextualizerProperties queryContextualizerProperties;

    /**
     * 注入聊天设置服务所需协作者。
     * <p>构造器只保存依赖，不读取数据库，避免启动阶段引入额外副作用。</p>
     */
    public ChatSettingsService(
            AppSettingRepository appSettingRepository,
            QueryContextualizerProperties queryContextualizerProperties
    ) {
        this.appSettingRepository = appSettingRepository;
        this.queryContextualizerProperties = queryContextualizerProperties;
    }

    /**
     * 返回前端设置页使用的聊天设置快照。
     * <p>如果 SQLite 中没有用户设置，则按环境变量和旧开关规则计算默认值。</p>
     */
    public ChatSettingsResponse settings() {
        return new ChatSettingsResponse(queryContextualizerMode());
    }

    /**
     * 解析当前生效的追问补全模式。
     * <p>优先级为：SQLite 用户设置、模式环境变量、旧 enabled=false、默认 AUTO。</p>
     */
    public QueryContextualizerMode queryContextualizerMode() {
        return appSettingRepository.findValue(QUERY_CONTEXTUALIZER_MODE_KEY)
                .map(QueryContextualizerMode::fromConfig)
                .orElseGet(queryContextualizerProperties::resolvedMode);
    }

    /**
     * 保存聊天设置。
     * <p>保存后立即影响后端知识库对话，不依赖浏览器 localStorage。</p>
     */
    @Transactional
    public ChatSettingsResponse update(ChatSettingsRequest request) {
        QueryContextualizerMode mode = request.queryContextualizerMode();
        appSettingRepository.save(QUERY_CONTEXTUALIZER_MODE_KEY, mode.name());
        return new ChatSettingsResponse(mode);
    }
}
