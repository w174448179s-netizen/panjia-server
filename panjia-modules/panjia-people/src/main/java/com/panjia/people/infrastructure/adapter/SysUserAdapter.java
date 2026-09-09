package com.panjia.people.infrastructure.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.system.domain.SysUser;
import org.dromara.system.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * sys_user 读取适配器 —— 只读引用，禁止写入。
 * <p>
 * 🚨 铁律：
 * <ol>
 *   <li>只允许 SELECT sys_user，禁止 INSERT/UPDATE/DELETE</li>
 *   <li>people 域不持有 SysUser 实体离开本类，只取需要的字段</li>
 *   <li>sys_user 不添加任何业务字段（base_salary / rank_id 等）</li>
 * </ol>
 */
@Component
public class SysUserAdapter {

    @Autowired
    private SysUserMapper sysUserMapper;

    /**
     * 根据登录名查询 sys_user（用于员工创建时绑定账号）。
     *
     * @param loginName 登录名
     * @return sys_user 实体，不存在返回 null
     */
    public SysUser findByLoginName(String loginName) {
        return sysUserMapper.selectOne(
            new LambdaQueryWrapper<SysUser>().eq(SysUser::getUserName, loginName));
    }

    /**
     * 根据部门 ID 查询部门下所有 sys_user（用于批量绑定校验）。
     *
     * @param deptId 部门 ID
     * @return sys_user 列表
     */
    public List<SysUser> findByDeptId(Long deptId) {
        return sysUserMapper.selectList(
            new LambdaQueryWrapper<SysUser>().eq(SysUser::getDeptId, deptId));
    }
}
