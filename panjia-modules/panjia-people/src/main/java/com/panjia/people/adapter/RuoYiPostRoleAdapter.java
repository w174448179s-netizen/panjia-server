package com.panjia.people.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.people.config.PeopleProperties;
import com.panjia.people.dto.PostOption;
import com.panjia.people.port.PostRolePort;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.enums.UserStatus;
import org.dromara.system.domain.SysPost;
import org.dromara.system.domain.SysRole;
import org.dromara.system.mapper.SysPostMapper;
import org.dromara.system.mapper.SysRoleMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 岗位/角色端口 RuoYi 实现。
 * <p>
 * V5.2 约定：岗位挂客户根部门节点；岗位名=角色名（同名精确匹配）；
 * 岗位查不到兜底动态建（挂根节点），角色查不到返回 null（不绑不报错）。
 * <p>
 * 本类是 people 域唯一接触 sys_post / sys_role 的地方之一。
 */
@Component
@RequiredArgsConstructor
public class RuoYiPostRoleAdapter implements PostRolePort {

    /** 兜底新建岗位的排序号（排在初始化岗位之后） */
    private static final int FALLBACK_POST_SORT = 99;

    /** 名称解析缓存 TTL：导入场景同名岗位/角色被逐行重复解析，60s 内直接复用解析结果 */
    private static final long RESOLVE_CACHE_TTL_MS = 60_000L;

    private final SysPostMapper sysPostMapper;
    private final SysRoleMapper sysRoleMapper;
    private final PeopleProperties peopleProperties;

    /** 岗位名 → postId 缓存（带过期时间） */
    private final Map<String, CachedId> postCache = new ConcurrentHashMap<>();

    /** 岗位名 → roleId 缓存（带过期时间） */
    private final Map<String, CachedId> roleCache = new ConcurrentHashMap<>();

    /** 带过期时间的解析结果；id 为 null 表示"查询时不存在"，不缓存 null */
    private record CachedId(Long id, long expireAt) {
    }

    private Long cachedResolve(Map<String, CachedId> cache, String name, Supplier<Long> loader) {
        CachedId hit = cache.get(name);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expireAt() > now) {
            return hit.id();
        }
        Long id = loader.get();
        if (id != null) {
            cache.put(name, new CachedId(id, now + RESOLVE_CACHE_TTL_MS));
        }
        return id;
    }

    @Override
    public Long resolvePost(String postName) {
        return cachedResolve(postCache, postName, () -> {
            SysPost existing = sysPostMapper.selectOne(
                new LambdaQueryWrapper<SysPost>().eq(SysPost::getPostName, postName).last("LIMIT 1"));
            if (existing != null) {
                return existing.getPostId();
            }
            // 初始化缺失 → 兜底动态建（挂客户根节点）
            SysPost post = new SysPost();
            post.setDeptId(peopleProperties.getRootDeptId());
            post.setPostCode(postName);
            post.setPostName(postName);
            post.setPostSort(FALLBACK_POST_SORT);
            post.setStatus(UserStatus.OK.getCode());
            post.setRemark("员工域兜底自动创建岗位（初始化缺失，挂客户根节点）");
            sysPostMapper.insert(post);
            return post.getPostId();
        });
    }

    @Override
    public Long resolveRole(String postName) {
        // 角色查不到返回 null：不缓存 null（角色可能随后在后台被创建），下次仍走查询
        return cachedResolve(roleCache, postName, () -> {
            SysRole role = sysRoleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleName, postName).last("LIMIT 1"));
            // 查不到同名角色：不绑、不报错、不抛异常——尊重后台手动赋权
            return role == null ? null : role.getRoleId();
        });
    }

    @Override
    public List<PostOption> listAllPosts() {
        return sysPostMapper.selectList(new LambdaQueryWrapper<SysPost>()
                .orderByAsc(SysPost::getPostSort))
            .stream()
            .map(post -> new PostOption(post.getPostId(), post.getPostName()))
            .toList();
    }
}
