package com.panjia.importutil.dict;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.system.domain.bo.SysDictDataBo;
import org.dromara.system.domain.vo.SysDictDataVo;
import org.dromara.system.service.ISysDictDataService;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 系统字典中心适配器（common-import-util 内置默认实现）。
 * <p>
 * 把 {@link DictDataPort} 桥到 RuoYi 的 {@link ISysDictDataService}，
 * 走 sys_dict_data 表实时查询。V6.0.1 起作为公共设计内嵌到工具层，
 * 业务域（import / people / performance 等）无需再各自写 Adapter。
 * <p>
 * 性能考虑：
 * <ul>
 *   <li>5 分钟本地 cache（dict_type → dict_values）</li>
 *   <li>校验期命中快（一次导入有几万行 × 几十列，全部进 hashmap）</li>
 *   <li>字典中心新增枚举项时最多等 5 分钟自动生效</li>
 * </ul>
 * <p>
 * 设计取舍：cache 命中率优先，<b>不严格 fresh</b>。如果业务方需要「字典变更立即生效」，
 * 应当调 {@link #refresh(String)} 或重启服务。这里就不暴露为 public 了。
 *
 * @author panjia
 * @since V6.0.1
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultSysDictDataAdapter implements DictDataPort {

    /** cache TTL（与 BI 字典查询常规 cache 同步 5 分钟） */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final ISysDictDataService sysDictDataService;

    /** dictType → { values, expireAt } */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Override
    public Set<String> getDictValues(String dictType) {
        if (dictType == null || dictType.isBlank()) {
            return Collections.emptySet();
        }
        CacheEntry entry = cache.get(dictType);
        long now = System.currentTimeMillis();
        if (entry != null && entry.expireAt > now) {
            return entry.values;
        }
        try {
            // 懒加载 sys_dict_data，按 dict_type 全量拉一次
            // SysDictDataBo 的 setDictType 是关键，其他字段留 default
            SysDictDataBo bo = new SysDictDataBo();
            bo.setDictType(dictType);
            List<SysDictDataVo> vos = sysDictDataService.selectDictDataList(bo);
            Set<String> values = vos == null ? Collections.emptySet()
                : vos.stream()
                    .map(SysDictDataVo::getDictValue)
                    .filter(v -> v != null && !v.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            cache.put(dictType, new CacheEntry(values, now + CACHE_TTL_MS));
            return values;
        } catch (Exception e) {
            log.warn("查询字典失败 dictType={}: {}", dictType, e.getMessage());
            // 异常时 cache 5 分钟空集合，避免反复崩
            cache.put(dictType, new CacheEntry(Collections.emptySet(), now + CACHE_TTL_MS));
            return Collections.emptySet();
        }
    }

    /** 缓存条目 */
    private record CacheEntry(Set<String> values, long expireAt) {}
}
