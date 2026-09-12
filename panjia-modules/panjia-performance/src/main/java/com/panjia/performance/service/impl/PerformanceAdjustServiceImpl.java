package com.panjia.performance.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.performance.domain.AdjustStatus;
import com.panjia.performance.domain.AdjustType;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.IllegalStateTransitionException;
import com.panjia.performance.domain.PerformanceAdjust;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReversedReason;
import com.panjia.performance.dto.AdjustCreateDTO;
import com.panjia.performance.dto.AdjustQuery;
import com.panjia.performance.engine.ConversionEngine;
import com.panjia.performance.mapper.PerformanceAdjustMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.service.PerformanceAdjustService;
import com.panjia.performance.service.ReverseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业绩调整单服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceAdjustServiceImpl implements PerformanceAdjustService {

    /** 调整单号前缀 */
    private static final String ADJUST_NO_PREFIX = "ADJ";
    /** 调整单号日期格式 */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PerformanceAdjustMapper adjustMapper;
    private final PerformanceFactMapper factMapper;
    private final ReverseService reverseService;
    private final ConversionEngine conversionEngine;

    @Override
    public PageResult<PerformanceAdjust> listAdjusts(AdjustQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<PerformanceAdjust> wrapper = buildQueryWrapper(query);
        wrapper.orderByDesc(PerformanceAdjust::getCreateTime);

        Page<PerformanceAdjust> page = adjustMapper.selectPage(pageQuery.build(), wrapper);
        List<PerformanceAdjust> records = page.getRecords();
        // 批量回填员工姓名 / 部门名称（含目标部门），避免列表显示裸 ID
        fillDisplayNames(records);
        return PageResult.build(records, page.getTotal());
    }

    /**
     * 批量回填展示名称：员工姓名（pj_people_employee）、原部门名、目标部门名（sys_dept）。
     * 空集合安全，两次 IN 查询无 N+1。
     */
    private void fillDisplayNames(List<PerformanceAdjust> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<Long> employeeIds = records.stream()
            .map(PerformanceAdjust::getEmployeeId).filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Set<Long> deptIds = new HashSet<>();
        for (PerformanceAdjust r : records) {
            if (r.getDeptId() != null) deptIds.add(r.getDeptId());
            if (r.getTargetDeptId() != null) deptIds.add(r.getTargetDeptId());
        }

        Map<Long, String> empNameMap = new HashMap<>();
        for (Map<String, Object> row : adjustMapper.employeeNames(employeeIds.stream().toList())) {
            empNameMap.put(((Number) row.get("employeeId")).longValue(), String.valueOf(row.get("employeeName")));
        }
        Map<Long, String> deptNameMap = new HashMap<>();
        for (Map<String, Object> row : adjustMapper.deptNames(deptIds.stream().toList())) {
            deptNameMap.put(((Number) row.get("deptId")).longValue(), String.valueOf(row.get("deptName")));
        }

        for (PerformanceAdjust r : records) {
            if (r.getEmployeeId() != null) {
                r.setEmployeeName(empNameMap.get(r.getEmployeeId()));
            }
            if (r.getDeptId() != null) {
                r.setDeptName(deptNameMap.get(r.getDeptId()));
            }
            if (r.getTargetDeptId() != null) {
                r.setTargetDeptName(deptNameMap.get(r.getTargetDeptId()));
            }
        }
    }

    @Override
    public PerformanceAdjust getAdjust(Long id) {
        PerformanceAdjust adjust = adjustMapper.selectById(id);
        if (adjust != null) {
            fillDisplayNames(List.of(adjust));
        }
        return adjust;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PerformanceAdjust createAdjust(AdjustCreateDTO dto, Long applicantId) {
        // 1. 校验调整类型
        AdjustType adjustType = AdjustType.fromCode(dto.getAdjustType());
        if (adjustType == null) {
            throw new ServiceException("非法调整类型：{}", dto.getAdjustType());
        }

        // 2. 构建调整单
        PerformanceAdjust adjust = new PerformanceAdjust();
        adjust.setAdjustNo(generateAdjustNo());
        adjust.setFactId(dto.getFactId());
        adjust.setPeriod(dto.getPeriod());
        adjust.setEmployeeId(dto.getEmployeeId());
        adjust.setDeptId(dto.getDeptId());
        adjust.setAdjustType(adjustType);
        adjust.setDeltaAmount(dto.getDeltaAmount());
        adjust.setTargetDeptId(dto.getTargetDeptId());
        adjust.setReason(dto.getReason());
        adjust.setPayloadJson(dto.getPayloadJson());
        adjust.setStatus(AdjustStatus.SUBMITTED);
        adjust.setApplicantId(applicantId);

        adjustMapper.insert(adjust);

        log.info("[调整单] 创建成功：adjustId={}, adjustNo={}, type={}, applicantId={}",
            adjust.getId(), adjust.getAdjustNo(), adjustType.getCode(), applicantId);
        return adjust;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveAdjust(Long id, Long approverId) {
        PerformanceAdjust adjust = getAndCheck(id);
        checkTransition(adjust.getStatus(), AdjustStatus.APPROVED, "调整单");

        adjust.setStatus(AdjustStatus.APPROVED);
        adjust.setApproverId(approverId);
        adjust.setApproveTime(LocalDateTime.now());
        adjustMapper.updateById(adjust);

        log.info("[调整单] 审批通过：adjustId={}, approverId={}", id, approverId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectAdjust(Long id, Long approverId, String reason) {
        PerformanceAdjust adjust = getAndCheck(id);
        checkTransition(adjust.getStatus(), AdjustStatus.REJECTED, "调整单");

        adjust.setStatus(AdjustStatus.REJECTED);
        adjust.setApproverId(approverId);
        adjust.setApproveTime(LocalDateTime.now());
        if (StringUtils.isNotBlank(reason)) {
            adjust.setReason(reason);
        }
        adjustMapper.updateById(adjust);

        log.info("[调整单] 审批拒绝：adjustId={}, approverId={}", id, approverId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelAdjust(Long id, Long operatorId) {
        PerformanceAdjust adjust = getAndCheck(id);
        checkTransition(adjust.getStatus(), AdjustStatus.CANCELLED, "调整单");

        adjust.setStatus(AdjustStatus.CANCELLED);
        adjust.setOperatorId(operatorId);
        adjustMapper.updateById(adjust);

        log.info("[调整单] 取消：adjustId={}, operatorId={}", id, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeAdjust(Long id, Long operatorId) {
        PerformanceAdjust adjust = getAndCheck(id);
        checkTransition(adjust.getStatus(), AdjustStatus.EXECUTED, "调整单");

        // 根据调整类型执行不同逻辑
        switch (adjust.getAdjustType()) {
            case AMOUNT -> executeAmountAdjust(adjust, operatorId);
            case VOID -> executeVoidAdjust(adjust, operatorId);
            case TRANSFER -> executeTransferAdjust(adjust, operatorId);
            default -> throw new ServiceException("不支持的调整类型：{}", adjust.getAdjustType());
        }

        // 更新状态为已执行
        adjust.setStatus(AdjustStatus.EXECUTED);
        adjust.setOperatorId(operatorId);
        adjust.setExecuteTime(LocalDateTime.now());
        adjustMapper.updateById(adjust);

        log.info("[调整单] 执行完成：adjustId={}, type={}, operatorId={}",
            id, adjust.getAdjustType().getCode(), operatorId);
    }

    // ==================== 内部方法 ====================

    /**
     * 构建查询条件。
     */
    private LambdaQueryWrapper<PerformanceAdjust> buildQueryWrapper(AdjustQuery query) {
        LambdaQueryWrapper<PerformanceAdjust> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()),
            PerformanceAdjust::getPeriod, query.getPeriod());
        wrapper.eq(StringUtils.isNotBlank(query.getAdjustType()),
            PerformanceAdjust::getAdjustType, AdjustType.fromCode(query.getAdjustType()));
        wrapper.eq(StringUtils.isNotBlank(query.getStatus()),
            PerformanceAdjust::getStatus, AdjustStatus.fromCode(query.getStatus()));
        wrapper.eq(query.getEmployeeId() != null,
            PerformanceAdjust::getEmployeeId, query.getEmployeeId());
        wrapper.eq(query.getDeptId() != null,
            PerformanceAdjust::getDeptId, query.getDeptId());
        return wrapper;
    }

    /**
     * 查询并校验调整单存在。
     */
    private PerformanceAdjust getAndCheck(Long id) {
        PerformanceAdjust adjust = adjustMapper.selectById(id);
        if (adjust == null) {
            throw new ServiceException("调整单不存在：id={}", id);
        }
        return adjust;
    }

    /**
     * 校验状态是否可流转。
     *
     * @param current 当前状态
     * @param target  目标状态
     * @param entity  实体名称
     * @throws IllegalStateTransitionException 非法状态转换
     */
    private void checkTransition(AdjustStatus current, AdjustStatus target, String entity) {
        if (!current.canTransitTo(target)) {
            throw new IllegalStateTransitionException(current.getCode(), target.getCode(), entity);
        }
    }

    /**
     * 生成调整单号：ADJ + yyyyMMdd + 6位随机数。
     *
     * @return 调整单号
     */
    private String generateAdjustNo() {
        String datePart = LocalDate.now().format(DATE_FORMATTER);
        String randomPart = RandomUtil.randomNumbers(6);
        return ADJUST_NO_PREFIX + datePart + randomPart;
    }

    /**
     * 执行金额调整：旧事实冲销 + 新事实生成（新金额）。
     */
    private void executeAmountAdjust(PerformanceAdjust adjust, Long operatorId) {
        PerformanceFact oldFact = factMapper.selectById(adjust.getFactId());
        if (oldFact == null) {
            throw new ServiceException("关联业绩事实不存在：factId={}", adjust.getFactId());
        }
        if (oldFact.getFactStatus() != FactStatus.ACTIVE) {
            throw new ServiceException("关联业绩事实非有效状态，无法调整：factId={}, status={}",
                adjust.getFactId(), oldFact.getFactStatus().getCode());
        }

        // 构建新事实（金额调整）
        PerformanceFact newFact = new PerformanceFact();
        newFact.setFactType(oldFact.getFactType());
        newFact.setPeriod(oldFact.getPeriod());
        newFact.setBusinessDate(oldFact.getBusinessDate());
        newFact.setSourceKey(oldFact.getSourceKey());
        newFact.setBizType(oldFact.getBizType());
        newFact.setEmployeeId(oldFact.getEmployeeId());
        newFact.setEmployeeExternalCode(oldFact.getEmployeeExternalCode());
        newFact.setDeptId(oldFact.getDeptId());
        newFact.setRoleType(oldFact.getRoleType());
        newFact.setShareRatio(oldFact.getShareRatio());
        newFact.setOriginAmount(oldFact.getOriginAmount().add(adjust.getDeltaAmount()));
        newFact.setConversionRate(oldFact.getConversionRate());
        // 重新计算业绩金额
        newFact.setPerformanceAmount(conversionEngine.calculate(
            newFact.getOriginAmount(), newFact.getShareRatio(), newFact.getConversionRate()));
        newFact.setFactStatus(FactStatus.ACTIVE);
        newFact.setSource(oldFact.getSource());
        newFact.setAdjustId(adjust.getId());
        newFact.setOperatorId(operatorId);

        // 替换冲销：旧事实冲销 + 新事实插入
        reverseService.supersede(oldFact.getId(), newFact, operatorId);
    }

    /**
     * 执行业绩冲销：事实冲销。
     */
    private void executeVoidAdjust(PerformanceAdjust adjust, Long operatorId) {
        reverseService.reverseByAdjust(adjust.getFactId(), adjust.getId(),
            ReversedReason.MANUAL_ADJUST, operatorId);
    }

    /**
     * 执行部门划转：旧事实冲销 + 新事实（新部门）生成。
     */
    private void executeTransferAdjust(PerformanceAdjust adjust, Long operatorId) {
        PerformanceFact oldFact = factMapper.selectById(adjust.getFactId());
        if (oldFact == null) {
            throw new ServiceException("关联业绩事实不存在：factId={}", adjust.getFactId());
        }
        if (oldFact.getFactStatus() != FactStatus.ACTIVE) {
            throw new ServiceException("关联业绩事实非有效状态，无法划转：factId={}, status={}",
                adjust.getFactId(), oldFact.getFactStatus().getCode());
        }
        if (adjust.getTargetDeptId() == null) {
            throw new ServiceException("部门划转调整单缺少目标部门：adjustId={}", adjust.getId());
        }

        // 构建新事实（部门划转）
        PerformanceFact newFact = new PerformanceFact();
        newFact.setFactType(oldFact.getFactType());
        newFact.setPeriod(oldFact.getPeriod());
        newFact.setBusinessDate(oldFact.getBusinessDate());
        newFact.setSourceKey(oldFact.getSourceKey());
        newFact.setBizType(oldFact.getBizType());
        newFact.setEmployeeId(oldFact.getEmployeeId());
        newFact.setEmployeeExternalCode(oldFact.getEmployeeExternalCode());
        newFact.setDeptId(adjust.getTargetDeptId()); // 新部门
        newFact.setRoleType(oldFact.getRoleType());
        newFact.setShareRatio(oldFact.getShareRatio());
        newFact.setOriginAmount(oldFact.getOriginAmount());
        newFact.setConversionRate(oldFact.getConversionRate());
        newFact.setPerformanceAmount(oldFact.getPerformanceAmount());
        newFact.setFactStatus(FactStatus.ACTIVE);
        newFact.setSource(oldFact.getSource());
        newFact.setAdjustId(adjust.getId());
        newFact.setOperatorId(operatorId);

        // 替换冲销：旧事实冲销 + 新事实插入（新部门）
        reverseService.supersede(oldFact.getId(), newFact, operatorId);
    }
}
