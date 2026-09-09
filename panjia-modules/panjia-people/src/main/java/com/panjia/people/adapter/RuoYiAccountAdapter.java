package com.panjia.people.adapter;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.people.dto.AccountUserInfo;
import com.panjia.people.port.AccountPort;
import com.panjia.people.port.PostRolePort;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.enums.UserStatus;
import org.dromara.system.domain.SysPost;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.SysUserPost;
import org.dromara.system.domain.SysUserRole;
import org.dromara.system.mapper.SysPostMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.mapper.SysUserPostMapper;
import org.dromara.system.mapper.SysUserRoleMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 系统账户端口 RuoYi 实现（员工 ↔ sys_user 同事务同步）。
 * <p>
 * 本类是 people 域唯一接触 sys_user / sys_user_post / sys_user_role 的地方。
 * 岗位/角色绑定严格"先清后绑"，多岗位 1:1 展开多角色，不取主岗位。
 */
@Component
@RequiredArgsConstructor
public class RuoYiAccountAdapter implements AccountPort {

    /** 新建员工账户的初始密码（HR 交付后由员工自行修改） */
    private static final String DEFAULT_INITIAL_PASSWORD = "123456";

    /** RuoYi 系统用户类型 */
    private static final String USER_TYPE_SYS = "sys_user";

    private final SysUserMapper sysUserMapper;
    private final SysUserPostMapper sysUserPostMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysPostMapper sysPostMapper;
    private final PostRolePort postRolePort;

    @Override
    public Long createUser(String username, String nickname, Long deptId, List<String> postNames) {
        SysUser user = new SysUser();
        user.setUserName(username);
        user.setNickName(nickname);
        user.setDeptId(deptId);
        user.setPassword(BCrypt.hashpw(DEFAULT_INITIAL_PASSWORD));
        user.setStatus(UserStatus.OK.getCode());
        user.setUserType(USER_TYPE_SYS);
        sysUserMapper.insert(user);
        rebuildPostsAndRoles(user.getUserId(), postNames);
        return user.getUserId();
    }

    @Override
    public void updateDept(Long userId, Long deptId) {
        sysUserMapper.lambda()
            .set(SysUser::getDeptId, deptId)
            .eq(SysUser::getUserId, userId)
            .update();
    }

    @Override
    public void rebuildPostsAndRoles(Long userId, List<String> postNames) {
        List<String> names = postNames == null ? List.of() : postNames;

        // 1. 重建 sys_user_post（先清后绑，多岗位；岗位查不到兜底建）
        sysUserPostMapper.delete(new LambdaQueryWrapper<SysUserPost>()
            .eq(SysUserPost::getUserId, userId));
        if (!names.isEmpty()) {
            List<SysUserPost> binds = new ArrayList<>(names.size());
            for (String name : names) {
                SysUserPost up = new SysUserPost();
                up.setUserId(userId);
                up.setPostId(postRolePort.resolvePost(name));
                binds.add(up);
            }
            sysUserPostMapper.insertBatch(binds);
        }

        // 2. 由岗位名集合 1:1 推导同名角色，重建 sys_user_role（先清后绑；查不到角色不绑不报错）
        List<Long> roleIds = names.stream()
            .map(postRolePort::resolveRole)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
            .eq(SysUserRole::getUserId, userId));
        if (!CollectionUtils.isEmpty(roleIds)) {
            List<SysUserRole> binds = new ArrayList<>(roleIds.size());
            for (Long roleId : roleIds) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                binds.add(ur);
            }
            sysUserRoleMapper.insertBatch(binds);
        }
    }

    @Override
    public void disableUser(Long userId) {
        sysUserMapper.lambda()
            .set(SysUser::getStatus, UserStatus.DISABLE.getCode())
            .eq(SysUser::getUserId, userId)
            .update();
    }

    @Override
    public AccountUserInfo loadUser(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        List<SysUserPost> binds = sysUserPostMapper.selectList(
            new LambdaQueryWrapper<SysUserPost>().eq(SysUserPost::getUserId, userId));
        List<Long> postIds = binds.stream().map(SysUserPost::getPostId).toList();
        List<String> postNames = postIds.isEmpty()
            ? List.of()
            : sysPostMapper.selectBatchIds(postIds).stream().map(SysPost::getPostName).toList();

        AccountUserInfo info = new AccountUserInfo();
        info.setUserId(userId);
        info.setDeptId(user.getDeptId());
        info.setEnabled(UserStatus.OK.getCode().equals(user.getStatus()));
        info.setPostIds(postIds);
        info.setPostNames(postNames);
        return info;
    }

    @Override
    public Map<Long, List<String>> findPostNamesByUserIds(Collection<Long> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            return Collections.emptyMap();
        }
        List<SysUserPost> binds = sysUserPostMapper.selectList(
            new LambdaQueryWrapper<SysUserPost>().in(SysUserPost::getUserId, userIds));
        if (binds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> postIdToName = sysPostMapper.selectBatchIds(
                binds.stream().map(SysUserPost::getPostId).distinct().toList())
            .stream()
            .collect(LinkedHashMap::new, (m, p) -> m.put(p.getPostId(), p.getPostName()), Map::putAll);

        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (SysUserPost bind : binds) {
            String name = postIdToName.get(bind.getPostId());
            if (name != null) {
                result.computeIfAbsent(bind.getUserId(), k -> new ArrayList<>()).add(name);
            }
        }
        return result;
    }

    @Override
    public List<Long> findUserIdsByPostName(String postName) {
        SysPost post = sysPostMapper.selectOne(
            new LambdaQueryWrapper<SysPost>().eq(SysPost::getPostName, postName).last("LIMIT 1"));
        if (post == null) {
            return List.of();
        }
        return sysUserPostMapper.selectList(
                new LambdaQueryWrapper<SysUserPost>().eq(SysUserPost::getPostId, post.getPostId()))
            .stream()
            .map(SysUserPost::getUserId)
            .distinct()
            .toList();
    }

    @Override
    public Map<Long, String> findNicknamesByIds(Collection<Long> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            return Collections.emptyMap();
        }
        Map<Long, String> result = new LinkedHashMap<>();
        sysUserMapper.selectByIds(userIds)
            .forEach(u -> result.put(u.getUserId(), u.getNickName()));
        return result;
    }
}
