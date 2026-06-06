package com.itqianchen.agentdesign.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan({
        "com.itqianchen.agentdesign.mapper.chat",
        "com.itqianchen.agentdesign.mapper.document",
        "com.itqianchen.agentdesign.mapper.knowledge",
        "com.itqianchen.agentdesign.mapper.model",
        "com.itqianchen.agentdesign.mapper.schema"
})
public class MyBatisConfig {
}
