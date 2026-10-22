package org.dromara.web.listener;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.redis.utils.CacheUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 字典缓存启动刷新钩子。
 *
 * 背景：Flyway 迁移脚本直接写 sys_dict_data 新增字典项时，不会经过
 * 应用层缓存注解，Redis 中 sys_dict 哈希里对应 dictType 仍是旧数据，
 * 导致前端下拉/翻译拿不到新字典项（例如曾出现「虚拟」状态不显示、显示成 VIRTUAL）。
 *
 * 方案：应用启动完成（ApplicationReadyEvent，晚于 Flyway 迁移）后清空字典缓存，
 * 下次请求按库重建。这样「部署 = 迁移 + 缓存失效」自动闭环，无需任何手工清理。
 *
 * 注意：清空整个 sys_dict/sys_dict_type 哈希，重建为懒加载，影响可忽略。
 */
@Slf4j
@Component
public class DictCacheStartupRefresher {

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            CacheUtils.clear(CacheNames.SYS_DICT);
            CacheUtils.clear(CacheNames.SYS_DICT_TYPE);
            log.info("[DictCacheStartupRefresher] 已清空字典缓存（sys_dict / sys_dict_type）");
        } catch (Exception e) {
            // 缓存清理失败不应阻断启动
            log.warn("[DictCacheStartupRefresher] 清空字典缓存失败：{}", e.getMessage());
        }
    }
}
