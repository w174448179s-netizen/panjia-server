package com.panjia.people.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.people.config.PeopleProperties;
import com.panjia.people.dto.DeptNode;
import com.panjia.people.port.DeptPort;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.enums.UserStatus;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.SysDept;
import org.dromara.system.mapper.SysDeptMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门端口 RuoYi 实现。
 * <p>
 * 按 "门店-组别" 路径在客户根部门下逐级建树（不存在才建），返回最末级 dept_id。
 * ancestors 遵循 RuoYi 约定：子部门 ancestors = 父 ancestors + "," + parentId。
 */
@Component
@RequiredArgsConstructor
public class RuoYiDeptAdapter implements DeptPort {

    /** 部门路径分隔符（导入路径拼接 / 全路径展示统一使用） */
    private static final String DEPT_PATH_SEPARATOR = "-";

    /** ancestors 祖级 ID 分隔符（RuoYi 约定） */
    private static final String ANCESTORS_SEPARATOR = ",";

    /** 自动建部门的显示排序 */
    private static final int AUTO_DEPT_ORDER = 0;

    private final SysDeptMapper sysDeptMapper;
    private final PeopleProperties peopleProperties;

    /** 根部门默认占位名称（Flyway 初始化时设置，首次导入时会被替换为大区名） */
    private static final String DEFAULT_ROOT_DEPT_NAME = "tenant_name";

    @Override
    public Long ensureDept(String deptFull) {
        if (deptFull == null || deptFull.isBlank()) {
            throw new ServiceException("部门路径不能为空");
        }
        SysDept root = sysDeptMapper.selectById(peopleProperties.getRootDeptId());
        if (root == null) {
            throw new ServiceException("客户根部门不存在，rootDeptId={}", peopleProperties.getRootDeptId());
        }

        String[] parts = deptFull.split(DEPT_PATH_SEPARATOR);
        int startIdx = 0;
        String firstPart = parts.length > 0 ? parts[0].trim() : "";

        if (!firstPart.isEmpty()) {
            // 1. 根部门仍是占位符 → 用第一个大区名重命名，大区直接作为根部门
            if (DEFAULT_ROOT_DEPT_NAME.equals(root.getDeptName())) {
                root.setDeptName(firstPart);
                sysDeptMapper.updateById(root);
                startIdx = 1;
            }
            // 2. 根部门名与大区名相同 → 大区就是根部门，跳过第一级
            else if (firstPart.equals(root.getDeptName())) {
                startIdx = 1;
            }
            // 3. 根部门名与大区名不同（多大区场景）→ 大区作为根的子部门，正常建树
        }

        Long parentId = root.getDeptId();
        String parentAncestors = root.getAncestors();
        for (int i = startIdx; i < parts.length; i++) {
            String name = parts[i].trim();
            if (name.isEmpty()) {
                continue;
            }
            SysDept child = ensureChild(parentId, parentAncestors, name);
            parentId = child.getDeptId();
            parentAncestors = child.getAncestors();
        }
        return parentId;
    }

    /**
     * 确保指定父节点下存在同名部门，不存在则新建。
     *
     * @param parentId        父部门 ID
     * @param parentAncestors 父部门祖级路径
     * @param name            部门名（门店或组别）
     * @return 已存在或新建的部门
     */
    private SysDept ensureChild(Long parentId, String parentAncestors, String name) {
        SysDept existing = sysDeptMapper.selectOne(new LambdaQueryWrapper<SysDept>()
            .eq(SysDept::getParentId, parentId)
            .eq(SysDept::getDeptName, name)
            .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        SysDept dept = new SysDept();
        dept.setParentId(parentId);
        dept.setDeptName(name);
        dept.setAncestors(parentAncestors + "," + parentId);
        dept.setOrderNum(AUTO_DEPT_ORDER);
        dept.setStatus(UserStatus.OK.getCode());
        sysDeptMapper.insert(dept);
        return dept;
    }

    @Override
    public String findDeptFullName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        SysDept dept = sysDeptMapper.selectById(deptId);
        if (dept == null) {
            return null;
        }
        Long rootId = peopleProperties.getRootDeptId();
        List<String> names = new ArrayList<>();
        // 祖级链 + 自身，跳过 0（顶级虚拟节点）
        List<Long> chain = new ArrayList<>();
        if (dept.getAncestors() != null && !dept.getAncestors().isBlank()) {
            for (String id : dept.getAncestors().split(ANCESTORS_SEPARATOR)) {
                chain.add(Long.valueOf(id.trim()));
            }
        }
        chain.add(dept.getDeptId());
        for (Long id : chain) {
            if (id == 0L) {
                continue;
            }
            SysDept node = sysDeptMapper.selectById(id);
            if (node != null) {
                // 跳过占位名称的根部门
                if (id.equals(rootId) && DEFAULT_ROOT_DEPT_NAME.equals(node.getDeptName())) {
                    continue;
                }
                // 无组级时组级会被建成与门店同名的节点，展示时去重相邻同名，
                // 避免「富房-西派少城店-西派少城店」，只保留「大区-门店」
                String name = node.getDeptName();
                if (!names.isEmpty() && names.get(names.size() - 1).equals(name)) {
                    continue;
                }
                names.add(name);
            }
        }
        return String.join(DEPT_PATH_SEPARATOR, names);
    }

    @Override
    public Map<Long, String> findDeptFullNames(Collection<Long> deptIds) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (deptIds == null) {
            return result;
        }
        for (Long deptId : deptIds) {
            result.put(deptId, findDeptFullName(deptId));
        }
        return result;
    }

    @Override
    public List<Long> findDeptAndChildIds(Long deptId) {
        if (deptId == null) {
            return null;
        }
        return sysDeptMapper.selectDeptAndChildById(deptId);
    }

    @Override
    public List<DeptNode> listDeptTree() {
        Long rootId = peopleProperties.getRootDeptId();
        SysDept root = sysDeptMapper.selectById(rootId);
        if (root == null) {
            return new ArrayList<>();
        }
        // 根部门仍为占位名称时，跳过根节点，直接展示子部门（大区→门店→小组）
        // 根部门已被重命名为实际大区名时，以根为树的根节点展示（大区→门店→小组）
        boolean rootHasRealName = !DEFAULT_ROOT_DEPT_NAME.equals(root.getDeptName());

        // BFS 逐层查子部门
        Map<Long, DeptNode> nodes = new HashMap<>();
        List<Long> parentIds = new ArrayList<>();
        parentIds.add(rootId);
        while (!parentIds.isEmpty()) {
            List<SysDept> children = sysDeptMapper.selectList(new LambdaQueryWrapper<SysDept>()
                .in(SysDept::getParentId, parentIds)
                .orderByAsc(SysDept::getOrderNum));
            List<Long> nextParentIds = new ArrayList<>();
            for (SysDept dept : children) {
                DeptNode node = new DeptNode();
                node.setDeptId(dept.getDeptId());
                node.setDeptName(dept.getDeptName());
                node.setParentId(dept.getParentId());
                nodes.put(dept.getDeptId(), node);
                nextParentIds.add(dept.getDeptId());
            }
            parentIds = nextParentIds;
        }

        List<DeptNode> roots = new ArrayList<>();
        if (rootHasRealName) {
            // 根部门就是大区，作为树的根
            DeptNode rootNode = new DeptNode();
            rootNode.setDeptId(root.getDeptId());
            rootNode.setDeptName(root.getDeptName());
            rootNode.setParentId(root.getParentId());
            // 挂载一级子节点
            for (DeptNode node : nodes.values()) {
                if (rootId.equals(node.getParentId())) {
                    rootNode.getChildren().add(node);
                }
            }
            roots.add(rootNode);
        } else {
            // 根部门仍是占位符，跳过根，直接以子节点为根
            for (DeptNode node : nodes.values()) {
                if (rootId.equals(node.getParentId())) {
                    roots.add(node);
                } else {
                    DeptNode parent = nodes.get(node.getParentId());
                    if (parent != null) {
                        parent.getChildren().add(node);
                    }
                }
            }
        }
        roots.sort(Comparator.comparing(DeptNode::getDeptName));
        return roots;
    }
}
