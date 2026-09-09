package com.panjia.people.port;

import com.panjia.people.dto.AccountUserInfo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 系统账户端口（people 域 → sys_user 体系）。
 * <p>
 * 员工与系统账户同事务同步：建员工即建账户，改部门/岗位即时同步，离职禁用。
 * 实现见 adapter 层 {@code RuoYiAccountAdapter}（唯一接触 org.dromara 系统类的地方）。
 */
public interface AccountPort {

    /**
     * 创建系统账户并绑定岗位/角色。
     *
     * @param username  登录账号（= 员工工号）
     * @param nickname  用户昵称（= 员工姓名）
     * @param deptId    归属部门 ID
     * @param postNames 岗位名集合（多岗位 1:1 展开角色）
     * @return 新建 sys_user.user_id
     */
    Long createUser(String username, String nickname, Long deptId, List<String> postNames);

    /**
     * 修改账户归属部门（员工部门变更时同步）。
     *
     * @param userId 系统用户 ID
     * @param deptId 新归属部门 ID
     */
    void updateDept(Long userId, Long deptId);

    /**
     * 重建账户的岗位与角色（★先清后绑）。
     * <p>
     * sys_user_post 按岗位名集合重建；sys_user_role 由岗位名 1:1 推导同名角色重建；
     * 岗位查不到兜底动态建，角色查不到不绑不报错。
     *
     * @param userId    系统用户 ID
     * @param postNames 岗位名集合
     */
    void rebuildPostsAndRoles(Long userId, List<String> postNames);

    /**
     * 禁用账户（员工离职）。
     *
     * @param userId 系统用户 ID
     */
    void disableUser(Long userId);

    /**
     * 读取账户对账视图（部门/启用状态/岗位 ID 与名称）。
     *
     * @param userId 系统用户 ID
     * @return 账户读模型；账户不存在返回 null
     */
    AccountUserInfo loadUser(Long userId);

    /**
     * 批量读取用户已绑定的岗位名集合（列表/详情展示用）。
     *
     * @param userIds 系统用户 ID 集合
     * @return userId → 岗位名集合
     */
    Map<Long, List<String>> findPostNamesByUserIds(Collection<Long> userIds);

    /**
     * 按岗位名查已绑定该岗位的用户 ID（列表岗位筛选，纯只读，不触发兜底建岗）。
     *
     * @param postName 岗位名
     * @return 用户 ID 集合；岗位名不存在时返回空集合
     */
    List<Long> findUserIdsByPostName(String postName);

    /**
     * 批量查用户昵称（变更日志操作人名展示用）。
     *
     * @param userIds 用户 ID 集合
     * @return userId → 昵称
     */
    Map<Long, String> findNicknamesByIds(Collection<Long> userIds);
}
