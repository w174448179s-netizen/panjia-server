package com.panjia.performance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.PerformancePeriodClose;
import com.panjia.performance.domain.PerformanceSource;
import com.panjia.performance.domain.PeriodCloseStatus;
import com.panjia.performance.dto.FactQuery;
import com.panjia.performance.dto.PerformanceFactDTO;
import com.panjia.performance.dto.PerformanceManageContractVO;
import com.panjia.performance.dto.PerformanceManageDTO;
import com.panjia.performance.dto.PerformanceManageEmployeeVO;
import com.panjia.performance.dto.PerformanceManagePageVO;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.PerformancePeriodCloseMapper;
import com.panjia.performance.service.PerformanceQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业绩查询服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceQueryServiceImpl implements PerformanceQueryService {

    /** 经纪人角色 ID（仅本人业绩数据权限） */
    private static final Long ROLE_AGENT = 1761300000000000014L;

    private final PerformanceFactMapper factMapper;
    private final PerformancePeriodCloseMapper periodCloseMapper;
    private final EmployeeMainDataQueryPort employeeMainDataQueryPort;

    @Override
    public PageResult<PerformanceFactDTO> listFacts(FactQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<PerformanceFact> wrapper = buildQueryWrapper(query);
        wrapper.orderByDesc(PerformanceFact::getCreateTime);

        Page<PerformanceFact> page = factMapper.selectPage(pageQuery.build(), wrapper);
        List<PerformanceFactDTO> dtoList = page.getRecords().stream()
            .map(this::toDTO)
            .toList();
        fillEmployeeInfo(dtoList);
        return PageResult.build(dtoList, page.getTotal());
    }

    /**
     * 批量补齐员工姓名/部门名（列表页展示）。
     * <p>
     * 统一按 employee_code 关联（历史事实行 employee_id/dept_id 为空也能补上），
     * 一次 IN 查询 + 一次部门名批量查询，无 N+1。
     */
    private void fillEmployeeInfo(List<PerformanceFactDTO> dtoList) {
        if (dtoList == null || dtoList.isEmpty()) {
            return;
        }
        Set<String> codes = dtoList.stream()
            .map(PerformanceFactDTO::getEmployeeCode)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());
        if (codes.isEmpty()) {
            return;
        }
        Map<String, EmployeeMainDataDTO> mainMap = employeeMainDataQueryPort.listByCodes(codes);
        for (PerformanceFactDTO dto : dtoList) {
            EmployeeMainDataDTO main = mainMap.get(dto.getEmployeeCode());
            if (main == null) {
                continue;
            }
            dto.setEmployeeName(main.getEmployeeName());
            if (dto.getDeptName() == null) {
                dto.setDeptName(main.getDeptName());
            }
            if (dto.getEmployeeId() == null) {
                dto.setEmployeeId(main.getEmployeeId());
            }
            if (dto.getDeptId() == null) {
                dto.setDeptId(main.getDeptId());
            }
        }
    }

    @Override
    public PerformanceFactDTO getFact(Long id) {
        PerformanceFact fact = factMapper.selectById(id);
        if (fact == null) {
            return null;
        }
        PerformanceFactDTO dto = toDTO(fact);
        fillEmployeeInfo(List.of(dto));
        return dto;
    }

    @Override
    public BigDecimal sumPerformance(String period, String factType, Long employeeId, Long deptId) {
        // 聚合查询用 QueryWrapper（Lambda 不支持函数字符串 select）
        QueryWrapper<PerformanceFact> wrapper = new QueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(period), "period", period)
            .eq(StringUtils.isNotBlank(factType), "fact_type", factType)
            .eq(employeeId != null, "employee_id", employeeId)
            .eq(deptId != null, "dept_id", deptId)
            .eq("fact_status", FactStatus.ACTIVE.getCode())
            .select("COALESCE(SUM(performance_amount), 0) as performance_amount");

        List<Map<String, Object>> result = factMapper.selectMaps(wrapper);
        if (result == null || result.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Object val = result.get(0).get("performance_amount");
        if (val == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(val.toString());
    }

    @Override
    public List<PerformanceFactDTO> listByEmployeeAndPeriod(Long employeeId, String period, String factType) {
        LambdaQueryWrapper<PerformanceFact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PerformanceFact::getEmployeeId, employeeId)
            .eq(PerformanceFact::getPeriod, period)
            .eq(StringUtils.isNotBlank(factType), PerformanceFact::getFactType, FactType.fromCode(factType))
            .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE)
            .orderByDesc(PerformanceFact::getCreateTime);

        List<PerformanceFact> facts = factMapper.selectList(wrapper);
        return facts.stream().map(this::toDTO).toList();
    }

    @Override
    public boolean isPeriodClosed(String period) {
        if (StringUtils.isBlank(period)) {
            return false;
        }
        PerformancePeriodClose record = periodCloseMapper.selectOne(
            new LambdaQueryWrapper<PerformancePeriodClose>()
                .eq(PerformancePeriodClose::getPeriod, period));
        return record != null && record.getStatus() == PeriodCloseStatus.CLOSED;
    }

    @Override
    public PerformanceManagePageVO<PerformanceManageEmployeeVO> pageManage(String period, String factType, Long deptId,
                                              String bizType, Boolean settled, String keyword,
                                              Integer pageNum, Integer pageSize) {
        PerformanceManagePageVO<PerformanceManageEmployeeVO> vo = new PerformanceManagePageVO<>();
        if (StringUtils.isBlank(period) || StringUtils.isBlank(factType)) {
            vo.setTotal(0);
            vo.setRows(List.of());
            PerformanceManagePageVO.Summary empty = new PerformanceManagePageVO.Summary();
            empty.setTotalAmount(BigDecimal.ZERO);
            vo.setSummary(empty);
            return vo;
        }
        String kw = StringUtils.trimToNull(keyword);
        int page = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 200);
        Long selfEmployeeId = resolveSelfEmployeeId();

        long total = factMapper.countManageEmployees(period, factType, deptId, bizType, settled, kw, selfEmployeeId);
        vo.setTotal(total);

        List<PerformanceManageEmployeeVO> employees = List.of();
        if (total > 0) {
            long offset = (long) (page - 1) * size;
            employees = factMapper.selectManagePageEmployees(
                period, factType, deptId, bizType, settled, kw, selfEmployeeId, offset, size);
        }
        vo.setRows(employees);
        vo.setBizTypes(factMapper.selectManageBizTypes(period, factType));

        Map<String, Object> stat = factMapper.selectManageSummary(
            period, factType, deptId, bizType, settled, kw, selfEmployeeId);
        PerformanceManagePageVO.Summary summary = new PerformanceManagePageVO.Summary();
        summary.setEmployeeCount(toLong(stat.get("employeeCount")));
        summary.setContractCount(toLong(stat.get("contractCount")));
        summary.setDetailCount(toLong(stat.get("detailCount")));
        summary.setUnsettledCount(toLong(stat.get("unsettledCount")));
        Object sum = stat.get("totalAmount");
        summary.setTotalAmount(sum == null ? BigDecimal.ZERO : new BigDecimal(sum.toString()));
        vo.setSummary(summary);
        return vo;
    }

    @Override
    public List<PerformanceManageDTO> listManageDetails(String period, String factType, Long deptId,
                                                        String bizType, Boolean settled, String keyword,
                                                        List<Long> employeeIds) {
        if (StringUtils.isBlank(period) || StringUtils.isBlank(factType)
            || employeeIds == null || employeeIds.isEmpty()) {
            return List.of();
        }
        return factMapper.selectManageListByIds(
            period, factType, deptId, bizType, settled, StringUtils.trimToNull(keyword),
            resolveSelfEmployeeId(), employeeIds);
    }

    @Override
    public PerformanceManagePageVO<PerformanceManageContractVO> pageManageByContract(String period, String factType,
                                              Long deptId, String bizType, Boolean settled, String keyword,
                                              Integer pageNum, Integer pageSize) {
        PerformanceManagePageVO<PerformanceManageContractVO> vo = new PerformanceManagePageVO<>();
        if (StringUtils.isBlank(period) || StringUtils.isBlank(factType)) {
            vo.setTotal(0);
            vo.setRows(List.of());
            PerformanceManagePageVO.Summary empty = new PerformanceManagePageVO.Summary();
            empty.setTotalAmount(BigDecimal.ZERO);
            vo.setSummary(empty);
            return vo;
        }
        String kw = StringUtils.trimToNull(keyword);
        int page = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 200);
        Long selfEmployeeId = resolveSelfEmployeeId();

        long total = factMapper.countManageContracts(period, factType, deptId, bizType, settled, kw, selfEmployeeId);
        vo.setTotal(total);

        List<PerformanceManageContractVO> contracts = List.of();
        if (total > 0) {
            long offset = (long) (page - 1) * size;
            contracts = factMapper.selectManagePageContracts(
                period, factType, deptId, bizType, settled, kw, selfEmployeeId, offset, size);
        }
        vo.setRows(contracts);
        vo.setBizTypes(factMapper.selectManageBizTypes(period, factType));

        Map<String, Object> stat = factMapper.selectManageSummary(
            period, factType, deptId, bizType, settled, kw, selfEmployeeId);
        PerformanceManagePageVO.Summary summary = new PerformanceManagePageVO.Summary();
        summary.setEmployeeCount(toLong(stat.get("employeeCount")));
        summary.setContractCount(toLong(stat.get("contractCount")));
        summary.setDetailCount(toLong(stat.get("detailCount")));
        summary.setUnsettledCount(toLong(stat.get("unsettledCount")));
        Object sum = stat.get("totalAmount");
        summary.setTotalAmount(sum == null ? BigDecimal.ZERO : new BigDecimal(sum.toString()));
        vo.setSummary(summary);
        return vo;
    }

    @Override
    public List<PerformanceManageDTO> listManageDetailsByContractNos(String period, String factType, Long deptId,
                                                        String bizType, Boolean settled, String keyword,
                                                        List<String> contractNos) {
        if (StringUtils.isBlank(period) || StringUtils.isBlank(factType)
            || contractNos == null || contractNos.isEmpty()) {
            return List.of();
        }
        return factMapper.selectManageListByContractNos(
            period, factType, deptId, bizType, settled, StringUtils.trimToNull(keyword), contractNos);
    }

    private long toLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    /**
     * 数据权限：经纪人角色只能查看本人业绩。
     * <p>
     * 返回当前登录用户对应的员工 ID；非经纪人角色返回 null（不限制）。
     * 经纪人在合同列表中只看到自己参与的合同，但展开合同后可见该合同下所有人的分成
     * （{@link #listManageDetailsByContractNos} 不传 selfEmployeeId）。
     */
    private Long resolveSelfEmployeeId() {
        try {
            var loginUser = LoginHelper.getLoginUser();
            if (loginUser == null || loginUser.getRoleId() == null) {
                return null;
            }
            if (!ROLE_AGENT.equals(loginUser.getRoleId())) {
                return null;
            }
            EmployeeMainDataDTO emp = employeeMainDataQueryPort.getByUserId(loginUser.getUserId());
            return emp == null ? null : emp.getEmployeeId();
        } catch (Exception e) {
            log.warn("[performance] 解析经纪人数据权限失败，默认不限制", e);
            return null;
        }
    }

    @Override
    public List<String> listManagePeriods() {
        return factMapper.selectManagePeriods();
    }

    // ==================== 内部方法 ====================

    /**
     * 构建查询条件。
     */
    private LambdaQueryWrapper<PerformanceFact> buildQueryWrapper(FactQuery query) {
        LambdaQueryWrapper<PerformanceFact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()),
            PerformanceFact::getPeriod, query.getPeriod());
        wrapper.eq(StringUtils.isNotBlank(query.getFactType()),
            PerformanceFact::getFactType, FactType.fromCode(query.getFactType()));
        wrapper.eq(query.getEmployeeId() != null,
            PerformanceFact::getEmployeeId, query.getEmployeeId());
        wrapper.eq(query.getDeptId() != null,
            PerformanceFact::getDeptId, query.getDeptId());
        wrapper.eq(StringUtils.isNotBlank(query.getBizType()),
            PerformanceFact::getBizType, query.getBizType());
        wrapper.eq(StringUtils.isNotBlank(query.getFactStatus()),
            PerformanceFact::getFactStatus, FactStatus.fromCode(query.getFactStatus()));
        wrapper.eq(StringUtils.isNotBlank(query.getSource()),
            PerformanceFact::getSource, PerformanceSource.fromCode(query.getSource()));
        return wrapper;
    }

    /**
     * 将 Entity 转换为 DTO。
     * <p>
     * 员工姓名/部门名等关联字段后续补充（先留空或直接从 fact 中能取到的字段）。
     *
     * @param fact 业绩事实实体
     * @return 业绩事实 DTO
     */
    private PerformanceFactDTO toDTO(PerformanceFact fact) {
        PerformanceFactDTO dto = new PerformanceFactDTO();
        dto.setId(fact.getId());
        dto.setFactType(fact.getFactType() != null ? fact.getFactType().getCode() : null);
        dto.setPeriod(fact.getPeriod());
        dto.setBusinessDate(fact.getBusinessDate());
        dto.setEmployeeId(fact.getEmployeeId());
        dto.setEmployeeCode(fact.getEmployeeExternalCode());
        // employeeName 后续补充
        dto.setDeptId(fact.getDeptId());
        // deptName 后续补充
        dto.setBizType(fact.getBizType());
        dto.setSourceKey(fact.getSourceKey());
        dto.setShareRatio(fact.getShareRatio());
        dto.setOriginAmount(fact.getOriginAmount());
        dto.setConversionRate(fact.getConversionRate());
        dto.setPerformanceAmount(fact.getPerformanceAmount());
        dto.setFactStatus(fact.getFactStatus() != null ? fact.getFactStatus().getCode() : null);
        dto.setSource(fact.getSource() != null ? fact.getSource().getCode() : null);
        dto.setCreateTime(fact.getCreateTime());
        return dto;
    }
}
