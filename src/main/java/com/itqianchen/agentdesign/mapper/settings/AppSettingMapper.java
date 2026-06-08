package com.itqianchen.agentdesign.mapper.settings;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 应用设置 Mapper 声明全局 key-value 设置的 MyBatis SQL 操作。
 * <p>当前用于保存聊天级设置，后续新增全局设置时复用同一张表。</p>
 */
public interface AppSettingMapper {

    /**
     * 按设置键读取值。
     * <p>返回列表是为了兼容 MyBatis 查询结果；仓储层会收敛为 Optional。</p>
     */
    List<String> findValue(@Param("key") String key);

    /**
     * 写入或更新设置值。
     * <p>SQLite 的 ON CONFLICT 保证同一个设置键只有一条最新记录。</p>
     */
    void upsert(
            @Param("key") String key,
            @Param("value") String value,
            @Param("updatedAt") long updatedAt
    );

    /**
     * 删除指定设置。
     * <p>当前业务主要使用 upsert，该方法给后续重置为默认值预留。</p>
     */
    void delete(@Param("key") String key);
}
