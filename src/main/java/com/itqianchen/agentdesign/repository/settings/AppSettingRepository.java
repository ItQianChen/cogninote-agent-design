package com.itqianchen.agentdesign.repository.settings;

import com.itqianchen.agentdesign.mapper.settings.AppSettingMapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 应用设置仓储是全局 key-value 配置的持久化边界。
 * <p>服务层不直接依赖 MyBatis Mapper，便于后续替换存储或增加缓存。</p>
 */
@Repository
public class AppSettingRepository {

    private final AppSettingMapper appSettingMapper;

    /**
     * 注入应用设置 Mapper。
     * <p>构造器只建立依赖关系，不做数据库读写。</p>
     */
    public AppSettingRepository(AppSettingMapper appSettingMapper) {
        this.appSettingMapper = appSettingMapper;
    }

    /**
     * 按设置键读取设置值。
     * <p>缺失时返回 Optional.empty，由业务服务决定默认值和兼容规则。</p>
     */
    public Optional<String> findValue(String key) {
        return appSettingMapper.findValue(key).stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    /**
     * 保存设置值。
     * <p>更新时间使用毫秒时间戳，和项目其他 SQLite 表保持一致。</p>
     */
    public void save(String key, String value) {
        appSettingMapper.upsert(key, value, System.currentTimeMillis());
    }

    /**
     * 删除设置值。
     * <p>删除后会回到配置文件和环境变量兜底值。</p>
     */
    public void delete(String key) {
        appSettingMapper.delete(key);
    }
}
