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

    private final SysPostMapper sysPostMapper;
    private final SysRoleMapper sysRoleMapper;
    private final PeopleProperties peopleProperties;

    @Override
    public Long resolvePost(String postName) {
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
    }

    @Override
    public Long resolveRole(String postName) {
        SysRole role = sysRoleMapper.selectOne(
            new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleName, postName).last("LIMIT 1"));
        // 查不到同名角色：不绑、不报错、不抛异常——尊重后台手动赋权
        return role == null ? null : role.getRoleId();
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
