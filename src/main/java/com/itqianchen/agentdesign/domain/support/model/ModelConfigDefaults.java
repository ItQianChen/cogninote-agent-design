package com.itqianchen.agentdesign.domain.support.model;


import com.itqianchen.agentdesign.domain.enums.model.ModelProvider;
/**
 * 模型配置的应用级默认值。
 *
 * <p>这些默认值用于新装应用、旧数据兼容和缺省字段兜底，修改时需要同步前端默认表单与测试用例。</p>
 */
public final class ModelConfigDefaults {

    public static final String ACTIVE_CONFIG_ID = "active";
    public static final String ACTIVE_CHAT_CONFIG_ID = "active-chat";
    public static final String ACTIVE_EMBEDDING_CONFIG_ID = "active-embedding";
    public static final String ACTIVE_VISION_CONFIG_ID = "active-vision";
    public static final ModelProvider PROVIDER = ModelProvider.DASHSCOPE;
    public static final String DISPLAY_NAME = "DashScope";
    public static final String CHAT_DISPLAY_NAME = "DashScope Chat";
    public static final String EMBEDDING_DISPLAY_NAME = "DashScope Embedding";
    public static final String VISION_DISPLAY_NAME = "DashScope Vision";
    /**
     * DashScope Provider 对用户展示原生 HTTP API Root。
     * Spring AI Alibaba 内部 path 已包含 /api/v1，实际构造客户端时会转换成裸域名。
     */
    public static final String BASE_URL = "https://dashscope.aliyuncs.com/api/v1";
    public static final String CHAT_MODEL = "qwen-plus";
    public static final String EMBEDDING_MODEL = "text-embedding-v4";
    public static final String VISION_MODEL = "qwen3-vl-plus";
    public static final int EMBEDDING_DIMENSIONS = 1024;
    public static final int EMBEDDING_REQUESTS_PER_MINUTE = 300;
    public static final int EMBEDDING_TOKENS_PER_MINUTE = 300_000;
    public static final int EMBEDDING_BATCH_SIZE = 16;
    public static final int MIN_EMBEDDING_REQUESTS_PER_MINUTE = 1;
    public static final int MAX_EMBEDDING_REQUESTS_PER_MINUTE = 10_000;
    public static final int MIN_EMBEDDING_TOKENS_PER_MINUTE = 1_000;
    public static final int MAX_EMBEDDING_TOKENS_PER_MINUTE = 10_000_000;
    public static final int MIN_EMBEDDING_BATCH_SIZE = 1;
    public static final int MAX_EMBEDDING_BATCH_SIZE = 128;
    public static final double TEMPERATURE = 0.7;
    public static final double VISION_TEMPERATURE = 0.0;
    public static final int TOP_K = 8;
    public static final int CONTEXT_WINDOW_TOKENS = 128_000;
    public static final int MIN_CONTEXT_WINDOW_TOKENS = 1_024;
    public static final int MAX_CONTEXT_WINDOW_TOKENS = 2_000_000;

    /**
     * 工具类不允许实例化。
     */
    private ModelConfigDefaults() {
    }
}


