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
     * 取指定部门的直接子部门 ID（仅下一层，不含自身）。
     * <p>
     * 用于总监多门店提成：总监挂在大区，只需取大区下的门店级 deptId，
     * 门店下组别级数据由上层 roll-up 汇总，避免一个门店出现多条提成行。
     *
     * @param deptId 部门 ID
     * @return 直接子部门 ID 列表（不含自身）；deptId 为 null 返回空列表
     */
    List<Long> findDirectChildIds(Long deptId);

    /**
     * 取客户根部门下的部门树（门店 → 组别，不含根节点本身）。
     *
     * @return 部门树节点
     */
    List<DeptNode> listDeptTree();

    /**
     * 按部门类别编码批量取正常状态部门 ID（2026-09-28 虚拟人挂靠用）。
     * <p>贝壳新签行经纪人为空时按行上店组编码匹配 sys_dept.dept_category
     * 找到门店部门，再挂到该部门的虚拟角色人上（工号 = 店组编码）。
     *
     * @param categories 部门类别编码集合
     * @return 类别编码 → dept_id；同一编码命中多个部门取第一个（dept_id 升序）；
     *         未配置/无正常状态部门的编码不在结果中；入参为空返回空 Map
     */
    Map<String, Long> findActiveDeptIdsByCategories(Collection<String> categories);

    /**
     * 批量解析部门所属的门店级锚点（顶级根的直接子部门为门店）。
     * <p>门店返回自身；店组返回祖先链中的门店；顶级根与不存在的部门不包含在结果中。
     *
     * @param deptIds 待解析部门 ID
     * @return 部门 ID → 门店锚点 ID
     */
    Map<Long, Long> findStoreAnchors(Collection<Long> deptIds);

    /**
     * 查全部门店级部门（顶级根的直接子部门）ID → 部门名。
     *
     * @return 门店 deptId → 部门名
     */
    Map<Long, String> findStoreDepts();
}
