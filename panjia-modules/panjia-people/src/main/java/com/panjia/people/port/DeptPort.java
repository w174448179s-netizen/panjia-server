package com.panjia.people.port;

import com.panjia.people.dto.DeptNode;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 部门端口（people 域 → sys_dept）。
 * <p>
 * 导入/新增时按 "门店-组别" 路径自动建树，挂在客户根部门节点下。
 */
public interface DeptPort {

    /**
     * 按 "门店-组别" 全路径确保部门存在（不存在则逐级新建），返回最末级部门 ID。
     *
     * @param deptFull 部门全路径，如 "云庭店-花照云庭店A组"
     * @return 最末级（组别）dept_id
     */
    Long ensureDept(String deptFull);

    /**
     * 取部门全路径展示名（门店/组别，跳过客户根节点），如 "云庭店/花照云庭店A组"。
     *
     * @param deptId 部门 ID
     * @return 全路径名；部门不存在返回 null
     */
    String findDeptFullName(Long deptId);

    /**
     * 批量取部门全路径展示名。
     *
     * @param deptIds 部门 ID 集合
     * @return deptId → 全路径名
     */
    Map<Long, String> findDeptFullNames(Collection<Long> deptIds);

    /**
     * 取指定部门及其所有下级部门 ID（列表部门树筛选用）。
     *
     * @param deptId 部门 ID
     * @return 含自身在内的部门 ID 集合；deptId 为 null 返回 null（表示不筛选）
     */
    List<Long> findDeptAndChildIds(Long deptId);

    /**
     * 取客户根部门下的部门树（门店 → 组别，不含根节点本身）。
     *
     * @return 部门树节点
     */
    List<DeptNode> listDeptTree();
}
