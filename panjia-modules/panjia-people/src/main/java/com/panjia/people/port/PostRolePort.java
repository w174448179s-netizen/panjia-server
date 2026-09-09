package com.panjia.people.port;

import com.panjia.people.dto.PostOption;

import java.util.List;

/**
 * 岗位/角色端口（people 域 → sys_post / sys_role）。
 * <p>
 * 岗位挂客户根部门节点；岗位名 = 角色名（同名精确匹配）。
 */
public interface PostRolePort {

    /**
     * 岗位名 → sys_post.post_id；查不到视为初始化缺失，兜底动态建（挂客户根节点）。
     *
     * @param postName 岗位名（如 "经纪人"）
     * @return 岗位 ID
     */
    Long resolvePost(String postName);

    /**
     * 岗位名 → 同名 sys_role.role_id；查不到返回 null（不绑、不报错、不抛异常）。
     *
     * @param postName 岗位名（与角色名一致）
     * @return 角色 ID；无同名角色返回 null
     */
    Long resolveRole(String postName);

    /**
     * 列出全部岗位选项（岗位多选下拉用，纯只读）。
     *
     * @return 岗位选项列表
     */
    List<PostOption> listAllPosts();
}
