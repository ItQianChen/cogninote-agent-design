package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.domain.enums.chat.QueryContextualizerMode;
import com.itqianchen.agentdesign.domain.properties.chat.QueryContextualizerProperties;
import com.itqianchen.agentdesign.domain.dto.chat.ChatSettingsRequest;
import com.itqianchen.agentdesign.domain.dto.chat.ChatSettingsResponse;
import com.itqianchen.agentdesign.repository.settings.AppSettingRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 聊天设置服务承载全局聊天设置的读取和保存流程。
 * <p>这里集中处理 SQLite 用户设置、环境变量和旧开关之间的优先级。</p>
 */
@Service
public class ChatSettingsService {

    private static final String QUERY_CONTEXTUALIZER_MODE_KEY = "chat.query-contextualizer.mode";
    private static final String ASSISTANT_MESSAGE_WIDTH_KEY = "chat.assistant-message-width";
    private static final String USER_MESSAGE_WIDTH_KEY = "chat.user-message-width";
    private static final String COMPOSER_WIDTH_KEY = "chat.composer-width";
    private static final int DEFAULT_ASSISTANT_MESSAGE_WIDTH = 100;
    private static final int DEFAULT_USER_MESSAGE_WIDTH = 72;
    private static final int DEFAULT_COMPOSER_WIDTH = 100;
    private static final int MIN_MESSAGE_WIDTH = 50;
    private static final int MAX_MESSAGE_WIDTH = 100;

    private final AppSettingRepository appSettingRepository;
    private final QueryContextualizerProperties queryContextualizerProperties;

    /**
     * 注入聊天设置服务所需协作者。
     * <p>构造器只保存依赖，不读取数据库，避免启动阶段引入额外副作用。</p>
     *
     * @param appSettingRepository 全局设置仓储
     * @param queryContextualizerProperties 追问补全默认配置
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
     * <p>如果 SQLite 中没有用户设置，会先按配置兜底值写入数据库，再返回实际持久化值。</p>
     *
     * @return 聊天设置响应
     */
    @Transactional
    public ChatSettingsResponse settings() {
        QueryContextualizerMode mode = queryContextualizerMode();
        int assistantWidth = messageWidth(ASSISTANT_MESSAGE_WIDTH_KEY, DEFAULT_ASSISTANT_MESSAGE_WIDTH);
        int userWidth = messageWidth(USER_MESSAGE_WIDTH_KEY, DEFAULT_USER_MESSAGE_WIDTH);
        int composerWidth = messageWidth(COMPOSER_WIDTH_KEY, DEFAULT_COMPOSER_WIDTH);
        return new ChatSettingsResponse(mode, assistantWidth, userWidth, composerWidth);
    }

    /**
     * 解析当前生效的追问补全模式。
     * <p>优先级为：SQLite 用户设置、模式环境变量、旧 enabled=false、默认 AUTO。</p>
     *
     * @return 追问补全模式
     */
    @Transactional
    public QueryContextualizerMode queryContextualizerMode() {
        return appSettingRepository.findValue(QUERY_CONTEXTUALIZER_MODE_KEY)
                .map(QueryContextualizerMode::fromConfig)
                .orElseGet(this::initializeQueryContextualizerMode);
    }

    /**
     * 保存聊天设置。
     * <p>保存后立即影响后端知识库对话，不依赖浏览器 localStorage。</p>
     *
     * @param request 设置请求
     * @return 保存后的设置响应
     */
    @Transactional
    public ChatSettingsResponse update(ChatSettingsRequest request) {
        QueryContextualizerMode mode = request.queryContextualizerMode();
        appSettingRepository.save(QUERY_CONTEXTUALIZER_MODE_KEY, mode.name());
        if (request.assistantMessageWidth() != null) {
            appSettingRepository.save(ASSISTANT_MESSAGE_WIDTH_KEY, String.valueOf(request.assistantMessageWidth()));
        }
        if (request.userMessageWidth() != null) {
            appSettingRepository.save(USER_MESSAGE_WIDTH_KEY, String.valueOf(request.userMessageWidth()));
        }
        if (request.composerWidth() != null) {
            appSettingRepository.save(COMPOSER_WIDTH_KEY, String.valueOf(request.composerWidth()));
        }
        return settings();
    }

    /**
     * 初始化追问补全模式。
     * <p>数据库缺失时只在这里落一次默认值，避免前端刷新时看到的值和后端实际执行值来自不同来源。</p>
     *
     * @return 初始化后的追问补全模式
     */
    private QueryContextualizerMode initializeQueryContextualizerMode() {
        QueryContextualizerMode mode = queryContextualizerProperties.resolvedMode();
        appSettingRepository.save(QUERY_CONTEXTUALIZER_MODE_KEY, mode.name());
        return mode;
    }

    /**
     * 读取并初始化消息宽度，避免历史数据库缺少新字段时改变现有布局。
     */
    private int messageWidth(String key, int defaultValue) {
        Optional<String> storedValue = appSettingRepository.findValue(key);
        if (storedValue.isEmpty()) {
            appSettingRepository.save(key, String.valueOf(defaultValue));
            return defaultValue;
        }
        int width = parseMessageWidth(storedValue.get(), defaultValue);
        if (!String.valueOf(width).equals(storedValue.get().trim())) {
            appSettingRepository.save(key, String.valueOf(width));
        }
        return width;
    }

    private int parseMessageWidth(String value, int defaultValue) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.clamp(parsed, MIN_MESSAGE_WIDTH, MAX_MESSAGE_WIDTH);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
