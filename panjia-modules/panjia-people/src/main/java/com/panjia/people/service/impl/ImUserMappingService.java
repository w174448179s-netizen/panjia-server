package com.panjia.people.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.contracts.dto.ImUserMappingSyncResult;
import com.panjia.contracts.dto.ImUserSyncDTO;
import com.panjia.contracts.port.ImUserMappingSyncPort;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.ImUserMapping;
import com.panjia.people.dto.ImUserMappingQuery;
import com.panjia.people.dto.ImUserMappingVO;
import com.panjia.people.mapper.EmployeeMapper;
import com.panjia.people.mapper.ImUserMappingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.domain.SysUser;
import org.dromara.system.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * IM 账号映射服务（实现 {@link ImUserMappingSyncPort}）。
 * <p>
 * 渠道模块（钉钉/企微/飞书）只传通讯录人员，本服务统一按工号匹配
 * pj_people_employee.employee_code，回填 employee_id / user_id 后 upsert，
 * 保证多渠道映射口径一致。匹配不到的账号保留 external 信息、employee_id 为空，
 * 后续可人工补绑（match_type=MANUAL）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImUserMappingService implements ImUserMappingSyncPort {

    /** 匹配方式：工号匹配 */
    private static final String MATCH_CODE = "CODE";
    /** 匹配方式：人工绑定 */
    private static final String MATCH_MANUAL = "MANUAL";
    /** 匹配方式：未匹配 */
    private static final String MATCH_NONE = "NONE";

    private final ImUserMappingMapper mappingMapper;
    private final EmployeeMapper employeeMapper;
    private final SysUserMapper sysUserMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImUserMappingSyncResult sync(String channel, String corpId, List<ImUserSyncDTO> users) {
        if (users == null || users.isEmpty()) {
            return new ImUserMappingSyncResult(0, 0, 0, 0, 0);
        }
        String corp = corpId == null ? "" : corpId.trim();

        // 1. 工号批量查员工，避免逐人查库
        Set<String> codes = users.stream()
            .map(ImUserSyncDTO::getExternalCode)
            .filter(StringUtils::isNotBlank)
            .map(String::trim)
            .collect(java.util.stream.Collectors.toSet());
        Map<String, Employee> employeeByCode = new LinkedHashMap<>();
        if (!codes.isEmpty()) {
            List<Employee> employees = employeeMapper.selectList(
                new LambdaQueryWrapper<Employee>().in(Employee::getEmployeeCode, codes));
            for (Employee employee : employees) {
                employeeByCode.putIfAbsent(employee.getEmployeeCode(), employee);
            }
        }

        // 2. 批量取已存在的映射行，决定新增还是更新
        Set<String> externalIds = users.stream()
            .map(ImUserSyncDTO::getExternalUserId)
            .filter(StringUtils::isNotBlank)
            .collect(java.util.stream.Collectors.toSet());
        List<ImUserMapping> existing = externalIds.isEmpty() ? List.of()
            : mappingMapper.selectList(new LambdaQueryWrapper<ImUserMapping>()
                .eq(ImUserMapping::getChannel, channel)
                .eq(ImUserMapping::getCorpId, corp)
                .in(ImUserMapping::getExternalUserId, externalIds));
        Map<String, ImUserMapping> existingByExternalId = new LinkedHashMap<>();
        for (ImUserMapping mapping : existing) {
            existingByExternalId.putIfAbsent(mapping.getExternalUserId(), mapping);
        }

        // 3. 逐条 upsert
        LocalDateTime now = LocalDateTime.now();
        int total = 0;
        int matched = 0;
        int created = 0;
        int updated = 0;
        for (ImUserSyncDTO user : users) {
            if (StringUtils.isBlank(user.getExternalUserId())) {
                continue;
            }
            total++;
            String externalId = user.getExternalUserId().trim();
            Employee employee = StringUtils.isBlank(user.getExternalCode())
                ? null : employeeByCode.get(user.getExternalCode().trim());

            ImUserMapping row = existingByExternalId.get(externalId);
            ImUserMapping target;
            if (row == null) {
                target = new ImUserMapping();
                target.setChannel(channel);
                target.setCorpId(corp);
                target.setExternalUserId(externalId);
                fillRow(target, user, employee, now);
                target.setCreateTime(now);
                mappingMapper.insert(target);
                created++;
            } else {
                target = row;
                fillRow(target, user, employee, now);
                mappingMapper.updateById(target);
                updated++;
            }
            if (employee != null) {
                matched++;
            }
        }

        int unmatched = total - matched;
        log.info("[IM账号映射] channel={}, corpId={}, 账号={}, 匹配={}, 未匹配={}, 新增={}, 更新={}",
            channel, corp, total, matched, unmatched, created, updated);
        return new ImUserMappingSyncResult(total, matched, unmatched, created, updated);
    }

    /**
     * 用渠道快照覆盖映射行的 external 字段与员工匹配结果。
     */
    private void fillRow(ImUserMapping row, ImUserSyncDTO user, Employee employee, LocalDateTime now) {
        row.setExternalUnionId(StringUtils.isBlank(user.getExternalUnionId()) ? null : user.getExternalUnionId().trim());
        row.setExternalName(user.getExternalName());
        row.setExternalMobile(user.getExternalMobile());
        row.setExternalCode(StringUtils.isBlank(user.getExternalCode()) ? null : user.getExternalCode().trim());
        row.setExternalActive(user.getExternalActive() == null ? Boolean.TRUE : user.getExternalActive());
        row.setEmployeeId(employee == null ? null : employee.getEmployeeId());
        row.setUserId(employee == null ? null : employee.getUserId());
        row.setMatchType(employee == null ? MATCH_NONE : MATCH_CODE);
        row.setLastSyncTime(now);
        row.setUpdateTime(now);
    }

    // ==================== 管理界面 ====================

    /**
     * 映射列表分页（按渠道/匹配状态/关键字/在职筛选）。
     */
    public PageResult<ImUserMappingVO> pageList(ImUserMappingQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<ImUserMapping> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getChannel()), ImUserMapping::getChannel, query.getChannel());
        if ("MATCHED".equals(query.getMatchStatus())) {
            wrapper.isNotNull(ImUserMapping::getEmployeeId);
        } else if ("UNMATCHED".equals(query.getMatchStatus())) {
            wrapper.isNull(ImUserMapping::getEmployeeId);
        }
        if (query.getActive() != null) {
            wrapper.eq(ImUserMapping::getExternalActive, query.getActive());
        }
        if (StringUtils.isNotBlank(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(ImUserMapping::getExternalName, keyword)
                .or().like(ImUserMapping::getExternalCode, keyword)
                .or().like(ImUserMapping::getExternalMobile, keyword)
                .or().like(ImUserMapping::getExternalUserId, keyword));
        }
        wrapper.orderByDesc(ImUserMapping::getLastSyncTime);

        Page<ImUserMapping> page = mappingMapper.selectPage(pageQuery.build(), wrapper);
        List<ImUserMappingVO> rows = page.getRecords().stream().map(this::toVO).toList();
        enrich(rows);
        return PageResult.build(rows, page.getTotal());
    }

    /**
     * 人工绑定：把渠道账号绑定到指定员工（覆盖自动匹配结果）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void bind(Long mappingId, Long employeeId) {
        ImUserMapping mapping = mappingMapper.selectById(mappingId);
        if (mapping == null) {
            throw new ServiceException("映射记录不存在或已被删除");
        }
        Employee employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            throw new ServiceException("员工不存在");
        }
        // 同一渠道下员工只允许绑定一个账号，避免一个人挂两个钉钉号
        ImUserMapping conflict = mappingMapper.selectOne(new LambdaQueryWrapper<ImUserMapping>()
            .eq(ImUserMapping::getChannel, mapping.getChannel())
            .eq(ImUserMapping::getCorpId, mapping.getCorpId())
            .eq(ImUserMapping::getEmployeeId, employeeId)
            .ne(ImUserMapping::getMappingId, mappingId)
            .last("limit 1"));
        if (conflict != null) {
            String conflictAccount = StringUtils.isBlank(conflict.getExternalName())
                ? conflict.getExternalUserId() : conflict.getExternalName();
            throw new ServiceException("员工「" + employee.getEmployeeName() + "」已绑定账号「"
                + conflictAccount + "」，请先解绑后再绑定");
        }
        // 只更新员工/匹配方式字段：实体上 external* 字段为 ALWAYS 策略，
        // 用空实体 updateById 会把钉钉姓名/工号/手机号一并清空，导致列表信息丢失
        mappingMapper.update(null, new LambdaUpdateWrapper<ImUserMapping>()
            .eq(ImUserMapping::getMappingId, mappingId)
            .set(ImUserMapping::getEmployeeId, employee.getEmployeeId())
            .set(ImUserMapping::getUserId, employee.getUserId())
            .set(ImUserMapping::getMatchType, MATCH_MANUAL)
            .set(ImUserMapping::getUpdateTime, LocalDateTime.now()));
        log.info("[IM账号映射] 人工绑定 mappingId={}, channel={}, externalUserId={}, employeeId={}, operator={}",
            mappingId, mapping.getChannel(), mapping.getExternalUserId(), employeeId, currentOperator());
    }

    /**
     * 解绑：清空员工/账号关联，回到未匹配状态（下次同步仍会按工号自动匹配）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void unbind(Long mappingId) {
        ImUserMapping mapping = mappingMapper.selectById(mappingId);
        if (mapping == null) {
            throw new ServiceException("映射记录不存在或已被删除");
        }
        // 显式 set null：只清空员工/账号关联，保留钉钉姓名、工号、手机号等账号信息
        mappingMapper.update(null, new LambdaUpdateWrapper<ImUserMapping>()
            .eq(ImUserMapping::getMappingId, mappingId)
            .set(ImUserMapping::getEmployeeId, null)
            .set(ImUserMapping::getUserId, null)
            .set(ImUserMapping::getMatchType, MATCH_NONE)
            .set(ImUserMapping::getUpdateTime, LocalDateTime.now()));
        log.info("[IM账号映射] 解绑 mappingId={}, channel={}, externalUserId={}, operator={}",
            mappingId, mapping.getChannel(), mapping.getExternalUserId(), currentOperator());
    }

    /** 批量补齐员工姓名与登录账号名，避免逐行查库 */
    private void enrich(List<ImUserMappingVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Set<Long> employeeIds = rows.stream().map(ImUserMappingVO::getEmployeeId)
            .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> employeeNameMap = new LinkedHashMap<>();
        if (!employeeIds.isEmpty()) {
            for (Employee employee : employeeMapper.selectBatchIds(employeeIds)) {
                employeeNameMap.put(employee.getEmployeeId(), employee.getEmployeeName());
            }
        }
        Set<Long> userIds = rows.stream().map(ImUserMappingVO::getUserId)
            .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> userNameMap = new LinkedHashMap<>();
        if (!userIds.isEmpty()) {
            for (SysUser user : sysUserMapper.selectBatchIds(userIds)) {
                userNameMap.put(user.getUserId(), user.getUserName());
            }
        }
        for (ImUserMappingVO row : rows) {
            if (row.getEmployeeId() != null) {
                row.setEmployeeName(employeeNameMap.get(row.getEmployeeId()));
            }
            if (row.getUserId() != null) {
                row.setUserName(userNameMap.get(row.getUserId()));
            }
        }
    }

    private ImUserMappingVO toVO(ImUserMapping mapping) {
        ImUserMappingVO vo = new ImUserMappingVO();
        vo.setMappingId(mapping.getMappingId());
        vo.setChannel(mapping.getChannel());
        vo.setCorpId(mapping.getCorpId());
        vo.setExternalUserId(mapping.getExternalUserId());
        vo.setExternalName(mapping.getExternalName());
        vo.setExternalMobile(mapping.getExternalMobile());
        vo.setExternalCode(mapping.getExternalCode());
        vo.setExternalActive(mapping.getExternalActive());
        vo.setEmployeeId(mapping.getEmployeeId());
        vo.setUserId(mapping.getUserId());
        vo.setMatchType(mapping.getMatchType());
        vo.setLastSyncTime(mapping.getLastSyncTime());
        return vo;
    }

    /** 当前操作人 ID（用于日志留痕） */
    private Long currentOperator() {
        try {
            return org.dromara.common.satoken.utils.LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
