package com.panjia.performance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.common.util.DeptScopeUtils;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.port.ConversionFactorPort;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.PerformancePeriodClose;
import com.panjia.performance.domain.PerformanceSource;
import com.panjia.performance.domain.PeriodCloseStatus;
import com.panjia.performance.domain.bo.AddMemberPayload;
import com.panjia.performance.domain.bo.PerformanceFactBo;
import com.panjia.performance.domain.bo.PerformanceManageContractDetailBo;
import com.panjia.performance.domain.bo.PerformanceManageContractBo;
import com.panjia.performance.domain.vo.PerformanceFactVo;
import com.panjia.performance.domain.vo.PerformanceFactSearchVo;
import com.panjia.performance.domain.vo.PerformanceManageContractVo;
import com.panjia.performance.domain.vo.PerformanceManageVo;
import com.panjia.performance.domain.vo.PerformanceManagePageVo;
import com.panjia.performance.domain.vo.PerformanceSearchDetailVo;
import com.panjia.performance.domain.bo.PerformanceSearchBo;
import com.panjia.performance.domain.bo.PerformanceSearchBizTypesBo;
import com.panjia.performance.domain.bo.PerformanceSearchEmployeeOptionsBo;
import com.panjia.performance.mapper.PerformanceAdjustMapper;
import com.panjia.performance.util.MoneyUtil;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.PerformancePeriodCloseMapper;
import com.panjia.performance.service.FactConversionResolver;
import com.panjia.performance.service.IPerformanceQueryService;
import com.panjia.performance.service.PerformanceViewLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.DeptService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
public class PerformanceQueryServiceImpl implements IPerformanceQueryService {

    /** 经纪人角色 ID（仅本人业绩数据权限） */
    private static final Long ROLE_AGENT = 1761300000000000014L;

    /** 员工下拉选项单次最大返回条数 */
    private static final int EMPLOYEE_OPTION_LIMIT = 20;

    private final PerformanceFactMapper factMapper;
    private final PerformanceAdjustMapper adjustMapper;
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
    public PageResult<PerformanceFactVo> listFacts(PerformanceFactBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<PerformanceFact> wrapper = buildQueryWrapper(query);
        wrapper.orderByDesc(PerformanceFact::getCreateTime);

        Page<PerformanceFact> page = factMapper.selectPage(pageQuery.build(), wrapper);
        List<PerformanceFactVo> dtoList = page.getRecords().stream()
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
    private void fillEmployeeInfo(List<PerformanceFactVo> dtoList) {
        if (dtoList == null || dtoList.isEmpty()) {
            return;
        }
        Set<String> codes = dtoList.stream()
            .map(PerformanceFactVo::getEmployeeCode)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());
        if (codes.isEmpty()) {
            return;
        }
        Map<String, EmployeeMainDataDTO> mainMap = employeeMainDataQueryPort.listByCodes(codes);
        for (PerformanceFactVo dto : dtoList) {
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
    public List<PerformanceFactVo> listByEmployeeAndPeriod(Long employeeId, String period, String factType) {
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
    public PerformanceManagePageVo<PerformanceManageContractVo> pageManageByContract(PerformanceManageContractBo query, PageQuery pageQuery) {
        String period = query.getPeriod();
        String factType = query.getFactType();
        Long deptId = query.getDeptId();
        Long employeeId = query.getEmployeeId();
        String bizType = query.getBizType();
        String keyword = query.getKeyword();
        String factStatus = query.getFactStatus();
        int pageNum = pageQuery.getPageNum() == null ? 1 : pageQuery.getPageNum();
        int pageSize = pageQuery.getPageSize() == null ? 20 : pageQuery.getPageSize();
        PerformanceManagePageVo<PerformanceManageContractVo> vo = new PerformanceManagePageVo<>();
        if (StringUtils.isBlank(period) || StringUtils.isBlank(factType)) {
            vo.setTotal(0);
            vo.setRows(List.of());
            PerformanceManagePageVo.Summary empty = new PerformanceManagePageVo.Summary();
            empty.setTotalAmount(BigDecimal.ZERO);
            vo.setSummary(empty);
            return vo;
        }
        String kw = StringUtils.trimToNull(keyword);
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 200);
        Long selfEmployeeId = resolveSelfEmployeeId();
        // §3.6 数据级行级权限：店长/总监仅本部门（含下级）。未传 deptId 时强制设为登录用户的 dept_id
        if (selfEmployeeId == null) {
            deptId = DeptScopeUtils.enforceSelfDeptScope(deptId, deptService::selectDeptAndChildById, "业绩");
        }

        long total = factMapper.countManageContracts(period, factType, deptId, employeeId, bizType, kw, factStatus, selfEmployeeId);
        vo.setTotal(total);

        List<PerformanceManageContractVo> contracts = List.of();
        if (total > 0) {
            long offset = (long) (page - 1) * size;
            contracts = factMapper.selectManagePageContracts(
                period, factType, deptId, employeeId, bizType, kw, factStatus, selfEmployeeId, offset, size);
            fillContractOriginalAmount(contracts, period, factType);
            fillContractPendingAdjust(contracts, period, factType);
            fillContractConversion(contracts);
        }
        vo.setRows(contracts);
        vo.setBizTypes(factMapper.selectManageBizTypes(period, factType));

        Map<String, Object> stat = factMapper.selectManageSummary(
            period, factType, deptId, employeeId, bizType, kw, factStatus, selfEmployeeId);
        PerformanceManagePageVo.Summary summary = new PerformanceManagePageVo.Summary();
        summary.setEmployeeCount(toLong(stat.get("employeeCount")));
        summary.setContractCount(toLong(stat.get("contractCount")));
        summary.setDetailCount(toLong(stat.get("detailCount")));
        Object sum = stat.get("totalAmount");
        summary.setTotalAmount(sum == null ? BigDecimal.ZERO : new BigDecimal(sum.toString()));
        vo.setSummary(summary);
        return vo;
    }

    @Override
    public List<PerformanceManageVo> listManageDetailsByContractNos(PerformanceManageContractDetailBo query) {
        String period = query.getPeriod();
        String factType = query.getFactType();
        List<String> contractNos = query.getContractNos();
        if (StringUtils.isBlank(period) || StringUtils.isBlank(factType)
            || contractNos == null || contractNos.isEmpty()) {
            return List.of();
        }
        List<PerformanceManageVo> rows = factMapper.selectManageListByContractNos(
            period, factType, contractNos);
        fillManageDetailEmployeeAndDept(rows);
        fillManageDetailOriginalAmount(rows, period, factType, contractNos);
        fillManageDetailPendingAdjust(rows, period, factType, contractNos);
        fillManageDetailSettled(rows);
        fillManageDetailConversion(rows);

        // §3.6 查看留痕：经纪人打开含他人业绩的合同 → 异步写 view_log
        // 触发条件：当前登录用户是经纪人 + 单合同展开（contractNos.size()==1）
        // 店长/总监/算薪属职权查看不记（resolveSelfEmployeeId 返回 null 即非经纪人）
        if (contractNos.size() == 1) {
            Long viewerId = resolveSelfEmployeeId();
            if (viewerId != null) {
                List<Long> viewedEmployeeIds = rows.stream()
                    .map(PerformanceManageVo::getEmployeeId)
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
    private LambdaQueryWrapper<PerformanceFact> buildQueryWrapper(PerformanceFactBo query) {
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
    private PerformanceFactVo toDTO(PerformanceFact fact) {
        PerformanceFactVo dto = new PerformanceFactVo();
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
    public PageResult<PerformanceFactSearchVo> searchByContract(PerformanceSearchBo query, PageQuery pageQuery) {
        String period = query.getPeriod();
        Long deptId = query.getDeptId();
        String bizType = query.getBizType();
        String keyword = query.getKeyword();
        Long employeeId = query.getEmployeeId();
        int pageNum = pageQuery.getPageNum() == null || pageQuery.getPageNum() < 1 ? 1 : pageQuery.getPageNum();
        int pageSize = pageQuery.getPageSize() == null || pageQuery.getPageSize() < 1 ? 20 : pageQuery.getPageSize();
        long offset = (long) (pageNum - 1) * pageSize;

        // 数据权限：经纪人仅本人参与的合同；店长/总监/人事强制本部门（含下级），越权指定他部门直接拒绝
        Long selfEmployeeId = resolveSelfEmployeeId();
        Long effectiveDeptId = deptId;
        if (selfEmployeeId == null) {
            effectiveDeptId = DeptScopeUtils.enforceSelfDeptScope(deptId, deptService::selectDeptAndChildById, "业绩");
        }
        // 员工筛选：经纪人强制本人；其他角色所传 employeeId 必须落在本人部门数据权限内
        Long filterEmployeeId = resolveSearchEmployeeId(selfEmployeeId, employeeId, effectiveDeptId);

        long total = factMapper.countFactSearchByContract(period, effectiveDeptId,
            StringUtils.trimToNull(bizType), keyword, filterEmployeeId);
        List<PerformanceFactSearchVo> rows = total == 0
            ? List.of()
            : factMapper.selectFactSearchByContract(period, effectiveDeptId,
                StringUtils.trimToNull(bizType), keyword, filterEmployeeId, offset, pageSize);
        fillSearchConversion(rows);

        return new PageResult<>(rows, total);
    }

    @Override
    public List<String> searchBizTypes(PerformanceSearchBizTypesBo query) {
        String period = query.getPeriod();
        Long deptId = query.getDeptId();
        Long employeeId = query.getEmployeeId();
        // 与 searchByContract 完全相同的数据权限口径，保证下拉选项即当前用户可见的类型
        Long selfEmployeeId = resolveSelfEmployeeId();
        Long effectiveDeptId = deptId;
        if (selfEmployeeId == null) {
            effectiveDeptId = DeptScopeUtils.enforceSelfDeptScope(deptId, deptService::selectDeptAndChildById, "业绩");
        }
        Long filterEmployeeId = resolveSearchEmployeeId(selfEmployeeId, employeeId, effectiveDeptId);
        return factMapper.selectSearchBizTypes(StringUtils.trimToNull(period), effectiveDeptId, filterEmployeeId);
    }

    @Override
    public List<EmployeeMainDataDTO> searchEmployeeOptions(PerformanceSearchEmployeeOptionsBo query) {
        String keyword = query.getKeyword();
        Long deptId = query.getDeptId();
        String kw = StringUtils.trimToNull(keyword);
        if (kw == null) {
            return List.of();
        }
        Long selfEmployeeId = resolveSelfEmployeeId();
        if (selfEmployeeId != null) {
            // 经纪人只能选择本人，关键字不匹配本人时返回空（前端同时隐藏选择框，此处为后端兜底）
            EmployeeMainDataDTO self = employeeMainDataQueryPort.getByEmployeeId(selfEmployeeId);
            if (self == null || !matchesEmployeeKeyword(self, kw)) {
                return List.of();
            }
            return List.of(self);
        }
        // 与列表查询同一口径：越权指定他部门直接拒绝；超管/无归属部门系统账号 effectiveDeptId=null 不限制
        Long effectiveDeptId = DeptScopeUtils.enforceSelfDeptScope(deptId, deptService::selectDeptAndChildById, "业绩");
        Collection<Long> deptIds = null;
        if (effectiveDeptId != null) {
            deptIds = deptService.selectDeptAndChildById(effectiveDeptId);
            if (deptIds == null || deptIds.isEmpty()) {
                return List.of();
            }
        }
        return employeeMainDataQueryPort.searchOptions(kw, deptIds, EMPLOYEE_OPTION_LIMIT);
    }

    /**
     * 解析业绩查询实际生效的员工筛选 ID。
     * <ul>
     *   <li>经纪人：强制本人，外部传入的 employeeId 一律忽略；</li>
     *   <li>其他角色：传入 employeeId 时校验该员工当前归属部门在本部门（含下级）子树内，否则拒绝；</li>
     *   <li>超管/无归属部门系统账号（effectiveDeptId=null）：不限制，直接使用传入值。</li>
     * </ul>
     * 传入非法 employeeId（员工不存在）同样拒绝，避免被探测。
     */
    private Long resolveSearchEmployeeId(Long selfEmployeeId, Long requestedEmployeeId, Long effectiveDeptId) {
        if (selfEmployeeId != null) {
            return selfEmployeeId;
        }
        if (requestedEmployeeId == null) {
            return null;
        }
        EmployeeMainDataDTO employee = employeeMainDataQueryPort.getByEmployeeId(requestedEmployeeId);
        if (employee == null) {
            throw new ServiceException("所选员工不存在");
        }
        if (effectiveDeptId != null) {
            List<Long> scopeDeptIds = deptService.selectDeptAndChildById(effectiveDeptId);
            if (employee.getDeptId() == null || scopeDeptIds == null
                || !scopeDeptIds.contains(employee.getDeptId())) {
                throw new ServiceException("无权查询该员工的业绩");
            }
        }
        return requestedEmployeeId;
    }

    /** 员工选项关键字匹配（与 SQL ILIKE 同语义：姓名或工号包含关键字，忽略大小写）。 */
    private boolean matchesEmployeeKeyword(EmployeeMainDataDTO employee, String keyword) {
        String kw = keyword.toLowerCase();
        return (employee.getEmployeeName() != null
                && employee.getEmployeeName().toLowerCase().contains(kw))
            || (employee.getEmployeeCode() != null
                && employee.getEmployeeCode().toLowerCase().contains(kw));
    }

    /**
     * 完整业绩查询·按业务键查询合同下明细。
     * <p>
     * 业务键口径见 {@link IPerformanceQueryService#searchDetails}：一手房、房产金融、
     * 家装荐客为订单号，其余为合同号（空回退订单号）。查询覆盖该业务键全部期间，
     * 与列表行的合同全周期聚合口径一致。
     */
    @Override
    public List<PerformanceSearchDetailVo> searchDetails(String bizNo) {
        if (StringUtils.isBlank(bizNo)) {
            return List.of();
        }
        List<PerformanceSearchDetailVo> rows = factMapper.selectSearchDetailRows(bizNo.trim());
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
    private void assertSearchDetailInScope(List<PerformanceSearchDetailVo> rows) {
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
                .map(PerformanceSearchDetailVo::getDeptId)
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
     * 合同管理列表：批量查调整前金额（originalAmount），直接查调整表的 original_amount 快照。
     * <p>
     * 调整单 contract_no 存的是提交时的展示键（合同号或订单号，随入口而异），
     * 与 {@link #fillContractPendingAdjust} 同理按双键查询与匹配；
     * 无调整的合同 originalAmount 为 null，前端据此只显示单值。
     */
    private void fillContractOriginalAmount(List<PerformanceManageContractVo> rows,
                                            String period, String factType) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (PerformanceManageContractVo row : rows) {
            if (row.getContractNo() != null) {
                keys.add(row.getContractNo());
            }
            if (row.getOrderNo() != null) {
                keys.add(row.getOrderNo());
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> originalMap = new HashMap<>();
        for (Map<String, Object> row : adjustMapper.doSelectOriginalAmounts(period, factType, keys)) {
            Object key = row.get("bizKey");
            Object val = row.get("originalAmount");
            if (key != null && val != null) {
                originalMap.put(key.toString(), new BigDecimal(val.toString()));
            }
        }
        for (PerformanceManageContractVo row : rows) {
            BigDecimal original = originalMap.get(row.getContractNo());
            if (original == null && row.getOrderNo() != null) {
                original = originalMap.get(row.getOrderNo());
            }
            row.setOriginalAmount(original);
        }
    }

    /**
     * 合同管理列表：批量填充审批中的合同级调整单（SUBMITTED/APPROVED，执行前金额未变）。
     * <p>
     * 调整单 contract_no 存的是提交时的展示键（合同号或订单号，随入口而异），
     * 故按两个键都查，命中任一即填充；前端据此显示「调整审批中」标记 + 目标金额。
     */
    private void fillContractPendingAdjust(List<PerformanceManageContractVo> rows,
                                           String period, String factType) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (PerformanceManageContractVo row : rows) {
            if (row.getContractNo() != null) {
                keys.add(row.getContractNo());
            }
            if (row.getOrderNo() != null) {
                keys.add(row.getOrderNo());
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> pendingMap = new HashMap<>();
        for (Map<String, Object> pending : adjustMapper.doSelectPendingByBizKeys(period, factType, keys)) {
            Object key = pending.get("bizKey");
            if (key != null) {
                pendingMap.put(key.toString(), pending);
            }
        }
        if (pendingMap.isEmpty()) {
            return;
        }
        for (PerformanceManageContractVo row : rows) {
            Map<String, Object> pending = pendingMap.get(row.getContractNo());
            if (pending == null && row.getOrderNo() != null) {
                pending = pendingMap.get(row.getOrderNo());
            }
            if (pending != null) {
                row.setAdjustPending(true);
                Object type = pending.get("adjustType");
                row.setAdjustPendingType(type == null ? null : type.toString());
                Object target = pending.get("targetAmount");
                row.setAdjustPendingAmount(target == null ? null : new BigDecimal(target.toString()));
            }
        }
    }

    /**
     * 合同管理明细：批量填充审批中的调整单（SUBMITTED/APPROVED，执行前金额未变）。
     * <p>
     * 两种口径：
     * <ul>
     *   <li>明细级调整（factId 定位）：目标金额直接取调整单 target_amount；</li>
     *   <li>合同级调整（contract_no 定位）：按各明细金额占比分摊总变动额
     *       （{@link MoneyUtil#allocateByAmount}，与执行落库共用同一算法），
     *       审批前即可看到每人分摊的调整金额与调整后业绩。</li>
     * </ul>
     * 明细级优先：已命中明细级调整单的行不再叠加合同级分摊。
     */
    private void fillManageDetailPendingAdjust(List<PerformanceManageVo> rows,
                                               String period, String factType,
                                               List<String> contractNos) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        // 1. 明细级：按 factId 批量查
        Set<Long> factIds = rows.stream()
            .map(PerformanceManageVo::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (!factIds.isEmpty()) {
            Map<Long, Map<String, Object>> pendingMap = new HashMap<>();
            for (Map<String, Object> pending : adjustMapper.doSelectPendingByFactIds(factIds)) {
                Object key = pending.get("factId");
                if (key != null) {
                    pendingMap.put(((Number) key).longValue(), pending);
                }
            }
            for (PerformanceManageVo row : rows) {
                Map<String, Object> pending = pendingMap.get(row.getId());
                if (pending != null) {
                    row.setAdjustPending(true);
                    Object type = pending.get("adjustType");
                    row.setAdjustPendingType(type == null ? null : type.toString());
                    Object target = pending.get("targetAmount");
                    BigDecimal targetAmt = target == null ? null : new BigDecimal(target.toString());
                    row.setAdjustPendingAmount(targetAmt);
                    BigDecimal amt = row.getAmount() != null ? row.getAmount() : BigDecimal.ZERO;
                    row.setAdjustPendingDelta(targetAmt == null ? null : MoneyUtil.round2(targetAmt.subtract(amt)));
                }
            }
        }
        // 2. 合同级：按业务键查，delta 按金额占比分摊到各明细行
        if (contractNos == null || contractNos.isEmpty()) {
            return;
        }
        List<Map<String, Object>> contractPendings =
            adjustMapper.doSelectPendingByBizKeys(period, factType, new HashSet<>(contractNos));
        if (contractPendings.size() != 1) {
            // 明细弹窗单合同场景应恰好命中 1 单；0 单无需分摊，多单口径不明跳过
            return;
        }
        Map<String, Object> pending = contractPendings.get(0);
        Object type = pending.get("adjustType");
        String adjustType = type == null ? null : type.toString();
        // 增加角色人（ADD_MEMBER）：合同总额不变，按发起时 payload 快照逐人预演（含新人 0→X 虚拟行）
        if ("ADD_MEMBER".equals(adjustType)) {
            applyPendingAddMember(rows, period, factType, pending);
            return;
        }
        if (!"AMOUNT".equals(adjustType)) {
            return;
        }
        Object targetObj = pending.get("targetAmount");
        Object originalObj = pending.get("originalAmount");
        if (targetObj == null || originalObj == null) {
            return;
        }
        BigDecimal delta = MoneyUtil.round2(
            new BigDecimal(targetObj.toString()).subtract(new BigDecimal(originalObj.toString())));
        List<BigDecimal> amounts = rows.stream()
            .map(r -> r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO)
            .toList();
        BigDecimal[] parts = MoneyUtil.allocateByAmount(amounts, delta);
        for (int i = 0; i < rows.size(); i++) {
            PerformanceManageVo row = rows.get(i);
            if (Boolean.TRUE.equals(row.getAdjustPending())) {
                continue; // 明细级优先，不叠加
            }
            BigDecimal amt = amounts.get(i);
            row.setAdjustPending(true);
            row.setAdjustPendingType("AMOUNT");
            row.setAdjustPendingDelta(parts[i]);
            row.setAdjustPendingAmount(MoneyUtil.round2(amt.add(parts[i])));
        }
    }

    /**
     * 合同管理明细：在途「增加角色人」单（SUBMITTED/APPROVED）逐行预演。
     * <p>
     * 执行前既有事实未被替代，payload.allocations.factId 可直接命中当前 ACTIVE 行
     * （兜底按员工匹配）：回填每人 adjustPendingDelta / adjustPendingAmount；
     * 新角色人此时尚无事实行，按 payload 合成虚拟行（amount=null，0 → X，审批中标记）。
     */
    private void applyPendingAddMember(List<PerformanceManageVo> rows, String period, String factType,
                                       Map<String, Object> pending) {
        AddMemberPayload payload = parseAddMemberPayload(pending.get("payloadJson"));
        if (payload == null || payload.getAllocations() == null || payload.getNewEmployeeId() == null) {
            return;
        }
        Map<Long, AddMemberPayload.Alloc> allocByFact = new HashMap<>();
        Map<Long, AddMemberPayload.Alloc> allocByEmployee = new HashMap<>();
        for (AddMemberPayload.Alloc a : payload.getAllocations()) {
            if (a.getFactId() != null) {
                allocByFact.put(a.getFactId(), a);
            }
            if (a.getEmployeeId() != null) {
                allocByEmployee.putIfAbsent(a.getEmployeeId(), a);
            }
        }
        for (PerformanceManageVo row : rows) {
            if (Boolean.TRUE.equals(row.getAdjustPending())) {
                continue; // 明细级优先，不叠加
            }
            AddMemberPayload.Alloc alloc = row.getId() != null ? allocByFact.get(row.getId()) : null;
            if (alloc == null && row.getEmployeeId() != null) {
                alloc = allocByEmployee.get(row.getEmployeeId());
            }
            if (alloc == null) {
                continue;
            }
            BigDecimal amt = row.getAmount() != null ? row.getAmount() : BigDecimal.ZERO;
            BigDecimal delta = alloc.getDelta() != null ? alloc.getDelta() : BigDecimal.ZERO;
            row.setAdjustPending(true);
            row.setAdjustPendingType("ADD_MEMBER");
            row.setAdjustPendingDelta(delta);
            row.setAdjustPendingAmount(MoneyUtil.round2(amt.add(delta)));
        }
        // 新角色人虚拟行：合同字段从既有行复制，保证后续折算填充（按 bizType）口径一致
        PerformanceManageVo sample = rows.get(0);
        BigDecimal newAmount = payload.getAmount() != null
            ? payload.getAmount()
            : (pending.get("targetAmount") != null
                ? new BigDecimal(pending.get("targetAmount").toString()) : BigDecimal.ZERO);
        PerformanceManageVo member = new PerformanceManageVo();
        member.setFactType(factType);
        member.setPeriod(period);
        member.setFactStatus("ACTIVE");
        member.setOrderNo(sample.getOrderNo());
        member.setContractNo(sample.getContractNo());
        member.setBizType(sample.getBizType());
        member.setFeeItem(sample.getFeeItem());
        member.setPropertyAddress(sample.getPropertyAddress());
        member.setBusinessDate(sample.getBusinessDate());
        member.setEmployeeId(payload.getNewEmployeeId());
        member.setEmployeeCode(payload.getEmployeeCode());
        member.setEmployeeName(payload.getEmployeeName());
        member.setDeptPath(payload.getDeptName());
        member.setRoleType(payload.getRoleType());
        member.setRoleName(payload.getRoleType());
        member.setShareRatio(payload.getNewShareRatio());
        member.setAmount(null);
        member.setOriginalAmount(null);
        member.setAdjustPending(true);
        member.setAdjustPendingType("ADD_MEMBER");
        member.setAdjustPendingAmount(MoneyUtil.round2(newAmount));
        member.setAdjustPendingDelta(MoneyUtil.round2(newAmount));
        member.setSettled(false);
        rows.add(member);
    }

    /**
     * 解析增加角色人快照（payload_json），解析失败返回 null（跳过逐行预演/还原，不影响主流程）。
     */
    private AddMemberPayload parseAddMemberPayload(Object payloadJson) {
        if (payloadJson == null || StringUtils.isBlank(payloadJson.toString())) {
            return null;
        }
        try {
            return JsonUtils.parseObject(payloadJson.toString(), AddMemberPayload.class);
        } catch (Exception e) {
            log.warn("[业绩查询] 增加角色人快照解析失败，跳过逐行还原：payload={}", payloadJson, e);
            return null;
        }
    }

    /**
     * 合同管理列表：按 bizType 批量取因子，填充 convertedAmount / originalConvertedAmount。
     */
    private void fillContractConversion(List<PerformanceManageContractVo> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(rows.stream()
            .map(PerformanceManageContractVo::getBizType)
            .collect(Collectors.toSet()));
        for (PerformanceManageContractVo row : rows) {
            BigDecimal factor = conversionFactorPort.factorOf(factorMap, row.getBizType());
            row.setConvertedAmount(conversionFactorPort.convert(row.getAmount(), factor));
            row.setOriginalConvertedAmount(conversionFactorPort.convert(row.getOriginalAmount(), factor));
            // 带出折算系数：调整弹窗录入业绩后前端自动算折算金额（折算列不可编辑）
            row.setConversionFactor(factor);
        }
    }

    /**
     * 合同管理明细：批量填充员工姓名/工号/部门路径（一次 IN 查询，避免 N+1）。
     */
    private void fillManageDetailEmployeeAndDept(List<PerformanceManageVo> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> employeeIds = rows.stream()
            .map(PerformanceManageVo::getEmployeeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (employeeIds.isEmpty()) {
            return;
        }
        Map<Long, EmployeeMainDataDTO> empMap = employeeMainDataQueryPort.listByIds(employeeIds);
        for (PerformanceManageVo row : rows) {
            EmployeeMainDataDTO emp = empMap.get(row.getEmployeeId());
            if (emp == null) {
                continue;
            }
            row.setEmployeeName(emp.getEmployeeName());
            row.setEmployeeCode(emp.getEmployeeCode());
            row.setDeptPath(emp.getDeptName());
        }
    }

    /**
     * 合同管理明细：批量查调整前金额（直接查调整表 original_amount 快照，无调整为 null）。
     */
    private void fillManageDetailOriginalAmount(List<PerformanceManageVo> rows,
                                                String period, String factType,
                                                List<String> contractNos) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        // 1. 明细级已执行调整：调整单上有 fact_id，直接回填 originalAmount 与调整金额
        Set<Long> factIds = rows.stream()
            .map(PerformanceManageVo::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (!factIds.isEmpty()) {
            Map<Long, BigDecimal> originalMap = new HashMap<>();
            for (Map<String, Object> row : adjustMapper.selectOriginalAmountsByFactIds(factIds)) {
                Object key = row.get("factId");
                Object val = row.get("originalAmount");
                if (key != null && val != null) {
                    originalMap.put(((Number) key).longValue(), new BigDecimal(val.toString()));
                }
            }
            for (PerformanceManageVo row : rows) {
                BigDecimal original = originalMap.get(row.getId());
                if (original != null) {
                    row.setOriginalAmount(original);
                    BigDecimal amt = row.getAmount() != null ? row.getAmount() : BigDecimal.ZERO;
                    BigDecimal changed = MoneyUtil.round2(amt.subtract(original));
                    if (changed.signum() != 0) {
                        row.setAdjustDelta(changed);
                    }
                }
            }
        }
        // 2. 合同级已执行调整（多单链式调整按 id 倒序逐单逆向回退）：
        //    AMOUNT：无 fact_id 痕迹，按等比不变性用当前金额逆向分摊（与执行时按原始金额分摊结果一致）；
        //    ADD_MEMBER：合同总额不变，按 payload 快照逐人反转——既有行加回扣减额（935→835 还原为 935），
        //    新角色人行归零（0→100 的原口径为 0）。执行后既有事实已被替代（新 id、同 source_key），
        //    故 payload.factId 不能与当前行等值，须经旧事实 source_key / 员工+角色 映射。
        if (contractNos == null || contractNos.size() != 1) {
            return; // 多合同混合场景键无法区分，不做还原（明细弹窗为单合同）
        }
        List<Map<String, Object>> executed =
            adjustMapper.doSelectExecutedContractAdjusts(period, factType, contractNos);
        if (executed.isEmpty()) {
            return;
        }
        // 预取各 ADD_MEMBER 快照中的旧事实（执行后多为 REVERSED）：factId → sourceKey/员工/角色
        Set<Long> payloadFactIds = new HashSet<>();
        for (Map<String, Object> adj : executed) {
            if (!"ADD_MEMBER".equals(String.valueOf(adj.get("adjustType")))) {
                continue;
            }
            AddMemberPayload payload = parseAddMemberPayload(adj.get("payloadJson"));
            if (payload == null || payload.getAllocations() == null) {
                continue;
            }
            for (AddMemberPayload.Alloc a : payload.getAllocations()) {
                if (a.getFactId() != null) {
                    payloadFactIds.add(a.getFactId());
                }
            }
        }
        Map<Long, PerformanceFactSummaryDTO> oldFactById = new HashMap<>();
        if (!payloadFactIds.isEmpty()) {
            for (PerformanceFactSummaryDTO f : factMapper.selectFactSummariesByIds(payloadFactIds)) {
                oldFactById.put(f.getFactId(), f);
            }
        }
        int n = rows.size();
        BigDecimal[] working = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            BigDecimal amt = rows.get(i).getAmount();
            working[i] = amt != null ? amt : BigDecimal.ZERO;
        }
        for (Map<String, Object> adj : executed) {
            String adjustType = String.valueOf(adj.get("adjustType"));
            if ("AMOUNT".equals(adjustType)) {
                Object targetObj = adj.get("targetAmount");
                Object originalObj = adj.get("originalAmount");
                if (targetObj == null || originalObj == null) {
                    continue;
                }
                BigDecimal delta = MoneyUtil.round2(
                    new BigDecimal(targetObj.toString()).subtract(new BigDecimal(originalObj.toString())));
                BigDecimal[] parts = MoneyUtil.allocateByAmount(java.util.Arrays.asList(working), delta);
                for (int i = 0; i < n; i++) {
                    working[i] = MoneyUtil.round2(working[i].subtract(parts[i]));
                }
            } else if ("ADD_MEMBER".equals(adjustType)) {
                reverseAddMemberAdjust(rows, working, parseAddMemberPayload(adj.get("payloadJson")), oldFactById);
            }
        }
        for (int i = 0; i < n; i++) {
            PerformanceManageVo row = rows.get(i);
            if (row.getOriginalAmount() == null) {
                row.setOriginalAmount(working[i]);
            }
            BigDecimal amt = row.getAmount() != null ? row.getAmount() : BigDecimal.ZERO;
            BigDecimal changed = MoneyUtil.round2(amt.subtract(working[i]));
            if (changed.signum() != 0) {
                row.setAdjustDelta(changed);
            }
        }
    }

    /**
     * 逆向回退一单已执行的「增加角色人」调整（工作数组原地修改）：
     * <ul>
     *   <li>新角色人行（员工 + 角色命中 payload）：调整前不存在，工作值置 0；</li>
     *   <li>既有行：按 source_key（首选；执行后事实 id 已变但 source_key 沿用）
     *       或员工+角色 / 员工（兜底）命中快照分摊，工作值回加 −delta（扣减额被加回）。</li>
     * </ul>
     */
    private void reverseAddMemberAdjust(List<PerformanceManageVo> rows, BigDecimal[] working,
                                        AddMemberPayload payload,
                                        Map<Long, PerformanceFactSummaryDTO> oldFactById) {
        if (payload == null || payload.getAllocations() == null) {
            return;
        }
        Map<String, AddMemberPayload.Alloc> allocBySourceKey = new HashMap<>();
        Map<String, AddMemberPayload.Alloc> allocByEmpRole = new HashMap<>();
        Map<Long, AddMemberPayload.Alloc> allocByEmployee = new HashMap<>();
        for (AddMemberPayload.Alloc a : payload.getAllocations()) {
            PerformanceFactSummaryDTO oldFact = a.getFactId() != null ? oldFactById.get(a.getFactId()) : null;
            if (oldFact != null && StringUtils.isNotBlank(oldFact.getSourceKey())) {
                allocBySourceKey.put(oldFact.getSourceKey(), a);
            }
            Long employeeId = oldFact != null ? oldFact.getEmployeeId() : a.getEmployeeId();
            if (employeeId != null) {
                allocByEmployee.putIfAbsent(employeeId, a);
                if (oldFact != null && StringUtils.isNotBlank(oldFact.getRoleType())) {
                    allocByEmpRole.putIfAbsent(employeeId + "|" + oldFact.getRoleType(), a);
                }
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            PerformanceManageVo row = rows.get(i);
            // 新角色人行：还原为 0（0 → X 的调整前口径）
            if (payload.getNewEmployeeId() != null
                && payload.getNewEmployeeId().equals(row.getEmployeeId())
                && (StringUtils.isBlank(payload.getRoleType()) || payload.getRoleType().equals(row.getRoleType()))) {
                working[i] = BigDecimal.ZERO;
                continue;
            }
            AddMemberPayload.Alloc alloc = null;
            if (StringUtils.isNotBlank(row.getSourceKey())) {
                alloc = allocBySourceKey.get(row.getSourceKey());
            }
            if (alloc == null && row.getEmployeeId() != null && StringUtils.isNotBlank(row.getRoleType())) {
                alloc = allocByEmpRole.get(row.getEmployeeId() + "|" + row.getRoleType());
            }
            if (alloc == null && row.getEmployeeId() != null) {
                alloc = allocByEmployee.get(row.getEmployeeId());
            }
            if (alloc != null && alloc.getDelta() != null) {
                working[i] = MoneyUtil.round2(working[i].subtract(alloc.getDelta()));
            }
        }
    }

    /**
     * 合同管理明细：批量查结佣状态（settled / settleDate）。
     */
    private void fillManageDetailSettled(List<PerformanceManageVo> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> factIds = rows.stream()
            .map(PerformanceManageVo::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (factIds.isEmpty()) {
            return;
        }
        Map<Long, java.time.LocalDateTime> settleMap = new HashMap<>();
        for (Map<String, Object> row : factMapper.selectSettledInfoByFactIds(factIds)) {
            Object key = row.get("factId");
            Object val = row.get("settleDate");
            if (key != null) {
                settleMap.put(((Number) key).longValue(),
                    val == null ? null : java.time.LocalDateTime.class.cast(val));
            }
        }
        for (PerformanceManageVo row : rows) {
            if (settleMap.containsKey(row.getId())) {
                row.setSettled(true);
                row.setSettleDate(settleMap.get(row.getId()));
            } else {
                row.setSettled(false);
            }
        }
    }

    /**
     * 合同管理明细：按 bizType 批量取因子，填充 convertedAmount / originalConvertedAmount。
     */
    private void fillManageDetailConversion(List<PerformanceManageVo> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(rows.stream()
            .map(PerformanceManageVo::getBizType)
            .collect(Collectors.toSet()));
        for (PerformanceManageVo row : rows) {
            BigDecimal factor = conversionFactorPort.factorOf(factorMap, row.getBizType());
            row.setConvertedAmount(conversionFactorPort.convert(row.getAmount(), factor));
            row.setOriginalConvertedAmount(conversionFactorPort.convert(row.getOriginalAmount(), factor));
            // 带出折算系数：调整弹窗录入业绩后前端自动算折算金额（折算列不可编辑）
            row.setConversionFactor(factor);
        }
    }

    /**
     * 业绩查询列表：按 bizType 批量取因子，填充所有折算后金额。
     */
    private void fillSearchConversion(List<PerformanceFactSearchVo> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(rows.stream()
            .map(PerformanceFactSearchVo::getBizType)
            .collect(Collectors.toSet()));
        for (PerformanceFactSearchVo row : rows) {
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
    private void fillSearchDetailConversion(List<PerformanceSearchDetailVo> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> factIds = rows.stream()
            .map(PerformanceSearchDetailVo::getFactId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, BigDecimal> factorMap = factConversionResolver.factorByFactIds(factIds);
        for (PerformanceSearchDetailVo row : rows) {
            BigDecimal factor = conversionFactorPort.factorOf(factorMap, row.getFactId());
            row.setOriginalExpectConvertedAmount(conversionFactorPort.convert(row.getOriginalExpectAmount(), factor));
            row.setExpectConvertedAmount(conversionFactorPort.convert(row.getExpectAmount(), factor));
            row.setRealConvertedAmount(conversionFactorPort.convert(row.getRealAmount(), factor));
        }
    }
}
