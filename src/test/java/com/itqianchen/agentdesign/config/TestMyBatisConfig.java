package com.itqianchen.agentdesign.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.itqianchen.agentdesign.mapper.test")
public class TestMyBatisConfig {
}
