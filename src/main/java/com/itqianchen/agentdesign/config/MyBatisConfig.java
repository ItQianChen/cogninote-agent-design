package com.itqianchen.agentdesign.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatisConfig 集中维护 MyBatis Mapper 扫描配置。
 * <p>这里的 Bean 或扫描配置会影响应用启动阶段的基础设施装配。</p>
 */
@Configuration
@MapperScan({
        "com.itqianchen.agentdesign.mapper.chat",
        "com.itqianchen.agentdesign.mapper.document",
        "com.itqianchen.agentdesign.mapper.graph",
        "com.itqianchen.agentdesign.mapper.knowledge",
        "com.itqianchen.agentdesign.mapper.model",
        "com.itqianchen.agentdesign.mapper.schema",
        "com.itqianchen.agentdesign.mapper.settings"
})
public class MyBatisConfig {
}
