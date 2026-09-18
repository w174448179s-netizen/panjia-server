package com.panjia.performance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.common.util.DeptScopeUtils;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.port.ConversionFactorPort;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.PerformancePeriodClose;
import com.panjia.performance.domain.PerformanceSource;
import com.panjia.performance.domain.PeriodCloseStatus;
import com.panjia.performance.dto.FactQuery;
import com.panjia.performance.dto.PerformanceFactDTO;
import com.panjia.performance.dto.PerformanceFactSearchDTO;
import com.panjia.performance.dto.PerformanceManageContractVO;
import com.panjia.performance.dto.PerformanceManageDTO;
import com.panjia.performance.dto.PerformanceManageEmployeeVO;
import com.panjia.performance.dto.PerformanceManagePageVO;
import com.panjia.performance.dto.PerformanceSearchDetailDTO;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.PerformancePeriodCloseMapper;
import com.panjia.performance.service.FactConversionResolver;
import com.panjia.performance.service.PerformanceQueryService;
import com.panjia.performance.service.PerformanceViewLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.DeptService;
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
    private final PerformanceViewLogService viewLogService;
    /** 折算因子公共方法（取比例 / 金额乘算的唯一入口，规则表读取在薪酬域实现） */
    private final ConversionFactorPort conversionFactorPort;
    /** 业绩域自有标识 → bizType 的解析（factId / 合同号反查） */
    private final FactConversionResolver factConversionResolver;
    /** 部门子树查询（部门数据权限：校验所选部门是否在本人部门范围内） */
    private final DeptService deptService;

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
        // §3.6 数据级行级权限：店长/总监仅本部门（含下级）。未传 deptId 时强制设为登录用户的 dept_id
        if (selfEmployeeId == null) {
            deptId = DeptScopeUtils.enforceSelfDeptScope(deptId, deptService::selectDeptAndChildById, "业绩");
        }

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
            period, factType, deptId, bizType, settled, kw, null, selfEmployeeId);
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
                                              String factStatus, Integer pageNum, Integer pageSize) {
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
        // §3.6 数据级行级权限：店长/总监仅本部门（含下级）。未传 deptId 时强制设为登录用户的 dept_id
        if (selfEmployeeId == null) {
            deptId = DeptScopeUtils.enforceSelfDeptScope(deptId, deptService::selectDeptAndChildById, "业绩");
        }

        long total = factMapper.countManageContracts(period, factType, deptId, bizType, settled, kw, factStatus, selfEmployeeId);
        vo.setTotal(total);

        List<PerformanceManageContractVO> contracts = List.of();
        if (total > 0) {
            long offset = (long) (page - 1) * size;
            contracts = factMapper.selectManagePageContracts(
                period, factType, deptId, bizType, settled, kw, factStatus, selfEmployeeId, offset, size);
            fillContractConversion(contracts);
        }
        vo.setRows(contracts);
        vo.setBizTypes(factMapper.selectManageBizTypes(period, factType));

        Map<String, Object> stat = factMapper.selectManageSummary(
            period, factType, deptId, bizType, settled, kw, factStatus, selfEmployeeId);
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
        // §3.6 数据级行级权限：所有登录用户仅能钻取本部门（含下级）明细，越权传他部门 deptId 直接拒绝
        Long scopedDeptId = DeptScopeUtils.enforceSelfDeptScope(deptId, deptService::selectDeptAndChildById, "业绩");
        List<PerformanceManageDTO> rows = factMapper.selectManageListByContractNos(
            period, factType, scopedDeptId, bizType, settled, StringUtils.trimToNull(keyword), contractNos);
        fillManageDetailConversion(rows);

        // §3.6 查看留痕：经纪人打开含他人业绩的合同 → 异步写 view_log
        // 触发条件：当前登录用户是经纪人 + 单合同展开（contractNos.size()==1）
        // 店长/总监/算薪属职权查看不记（resolveSelfEmployeeId 返回 null 即非经纪人）
        if (contractNos.size() == 1) {
            Long viewerId = resolveSelfEmployeeId();
            if (viewerId != null) {
                List<Long> viewedEmployeeIds = rows.stream()
                    .map(PerformanceManageDTO::getEmployeeId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
                viewLogService.recordViewAsync(
                    null,  // contractId 在事实表无显式合同实体，留 null；按需可后续接入合同域 ID
                    contractNos.get(0),
                    viewerId,
                    viewedEmployeeIds,
                    "MY_PERF_DRILLDOWN");
            }
        }
        return rows;
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
        dto.setPerformanceAmount(fact.getPerformanceAmount());
        dto.setFactStatus(fact.getFactStatus() != null ? fact.getFactStatus().getCode() : null);
        dto.setSource(fact.getSource() != null ? fact.getSource().getCode() : null);
        dto.setCreateTime(fact.getCreateTime());
        return dto;
    }

    @Override
    public PageResult<PerformanceFactSearchDTO> searchByContract(String period, Long deptId,
                                                                  String keyword, Integer pageNum, Integer pageSize) {
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
        long offset = (long) (page - 1) * size;

        // 数据权限：经纪人仅本人参与的合同；店长/总监强制本部门（含下级），越权指定他部门直接拒绝
        Long selfEmployeeId = resolveSelfEmployeeId();
        Long effectiveDeptId = deptId;
        if (selfEmployeeId == null) {
            effectiveDeptId = DeptScopeUtils.enforceSelfDeptScope(deptId, deptService::selectDeptAndChildById, "业绩");
        }

        long total = factMapper.countFactSearchByContract(period, effectiveDeptId, keyword, selfEmployeeId);
        List<PerformanceFactSearchDTO> rows = total == 0
            ? List.of()
            : factMapper.selectFactSearchByContract(period, effectiveDeptId, keyword, selfEmployeeId, offset, size);
        fillSearchConversion(rows);

        return new PageResult<>(rows, total);
    }

    /**
     * 完整业绩查询·按业务键查询合同下明细。
     * <p>
     * 业务键口径见 {@link PerformanceQueryService#searchDetails}：一手房、房产金融、
     * 家装荐客为订单号，其余为合同号（空回退订单号）。查询覆盖该业务键全部期间，
     * 与列表行的合同全周期聚合口径一致。
     */
    @Override
    public List<PerformanceSearchDetailDTO> searchDetails(String bizNo) {
        if (StringUtils.isBlank(bizNo)) {
            return List.of();
        }
        List<PerformanceSearchDetailDTO> rows = factMapper.selectSearchDetailRows(bizNo.trim());
        // 钻取防越权：经纪人仅能打开本人参与的合同；店长/总监仅能打开本部门（含下级）的合同。
        // 命中后仍返回该合同下全部分成行（与合同业绩页「展开可见同合同他人分成」口径一致）。
        assertSearchDetailInScope(rows);
        fillSearchDetailConversion(rows);
        return rows;
    }

    /**
     * 业绩查询钻取明细的数据权限校验：
     * <ul>
     *   <li>经纪人：明细中须存在本人员工行，否则拒绝；</li>
     *   <li>店长/总监：明细中须存在本部门（含下级）行，否则拒绝；</li>
     *   <li>财务/超管及其它角色：不限制。</li>
     * </ul>
     */
    private void assertSearchDetailInScope(List<PerformanceSearchDetailDTO> rows) {
        try {
            Long selfEmployeeId = resolveSelfEmployeeId();
            if (selfEmployeeId != null) {
                boolean hit = rows.stream().anyMatch(r -> selfEmployeeId.equals(r.getEmployeeId()));
                if (!hit) {
                    throw new ServiceException("无权查看该合同业绩明细");
                }
                return;
            }
            var loginUser = LoginHelper.getLoginUser();
            if (loginUser == null) {
                return;
            }
            // 所有登录用户（超管除外）：明细行 deptId 须落在本部门（含下级）子树内
            List<Long> scopeDeptIds = DeptScopeUtils.selfDeptSubtree(deptService::selectDeptAndChildById);
            if (scopeDeptIds == null) {
                return;
            }
            boolean hit = rows.stream()
                .map(PerformanceSearchDetailDTO::getDeptId)
                .filter(Objects::nonNull)
                .anyMatch(scopeDeptIds::contains);
            if (!hit) {
                throw new ServiceException("无权查看该合同业绩明细");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[performance] 业绩查询钻取权限校验失败，默认拒绝", e);
            throw new ServiceException("数据权限校验失败");
        }
    }

    // ==================== 折算填充（统一入口） ====================

    /**
     * 合同管理列表：按 bizType 批量取因子，填充 convertedAmount / originalConvertedAmount。
     */
    private void fillContractConversion(List<PerformanceManageContractVO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(rows.stream()
            .map(PerformanceManageContractVO::getBizType)
            .collect(Collectors.toSet()));
        for (PerformanceManageContractVO row : rows) {
            BigDecimal factor = conversionFactorPort.factorOf(factorMap, row.getBizType());
            row.setConvertedAmount(conversionFactorPort.convert(row.getAmount(), factor));
            row.setOriginalConvertedAmount(conversionFactorPort.convert(row.getOriginalAmount(), factor));
        }
    }

    /**
     * 合同管理明细：按 bizType 批量取因子，填充 convertedAmount / originalConvertedAmount。
     */
    private void fillManageDetailConversion(List<PerformanceManageDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(rows.stream()
            .map(PerformanceManageDTO::getBizType)
            .collect(Collectors.toSet()));
        for (PerformanceManageDTO row : rows) {
            BigDecimal factor = conversionFactorPort.factorOf(factorMap, row.getBizType());
            row.setConvertedAmount(conversionFactorPort.convert(row.getAmount(), factor));
            row.setOriginalConvertedAmount(conversionFactorPort.convert(row.getOriginalAmount(), factor));
        }
    }

    /**
     * 业绩查询列表：按 bizType 批量取因子，填充所有折算后金额。
     */
    private void fillSearchConversion(List<PerformanceFactSearchDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(rows.stream()
            .map(PerformanceFactSearchDTO::getBizType)
            .collect(Collectors.toSet()));
        for (PerformanceFactSearchDTO row : rows) {
            BigDecimal factor = conversionFactorPort.factorOf(factorMap, row.getBizType());
            // 新签业绩两列：expectConvertedAmount = 当前值折算，originalExpectConvertedAmount = 调整前折算；
            // 前端按 expectAmount 与 expectOriginalAmount 是否相等决定单值展示还是「原值 → 调整后值」
            row.setExpectConvertedAmount(conversionFactorPort.convert(row.getExpectAmount(), factor));
            row.setOriginalExpectConvertedAmount(
                conversionFactorPort.convert(row.getExpectOriginalAmount(), factor));
            row.setRealConvertedAmount(conversionFactorPort.convert(row.getRealAmount(), factor));
            row.setCommissionConvertedAmount(conversionFactorPort.convert(row.getCommissionAmount(), factor));
        }
    }

    /**
     * 业绩查明细：按 factId 批量解析因子，填充所有折算后金额。
     * <p>
     * 同一行的新签业绩（应收）与实收业绩（实收）共用本行因子，取值与乘算都走公共方法。
     */
    private void fillSearchDetailConversion(List<PerformanceSearchDetailDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> factIds = rows.stream()
            .map(PerformanceSearchDetailDTO::getFactId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, BigDecimal> factorMap = factConversionResolver.factorByFactIds(factIds);
        for (PerformanceSearchDetailDTO row : rows) {
            BigDecimal factor = conversionFactorPort.factorOf(factorMap, row.getFactId());
            row.setOriginalExpectConvertedAmount(conversionFactorPort.convert(row.getOriginalExpectAmount(), factor));
            row.setExpectConvertedAmount(conversionFactorPort.convert(row.getExpectAmount(), factor));
            row.setRealConvertedAmount(conversionFactorPort.convert(row.getRealAmount(), factor));
        }
    }
}
