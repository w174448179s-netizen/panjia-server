package com.panjia.commission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.domain.CommissionConsumeLog;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.domain.ItemStatus;
import com.panjia.commission.domain.ReversedReason;
import com.panjia.commission.dto.ApplyQuery;
import com.panjia.commission.dto.CommissionContractVO;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.commission.mapper.CommissionConsumeLogMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.event.CommissionApprovedEvent;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.contracts.port.PeriodCloseQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结佣申请服务（按合同发起 / 提交 / 审批锁定，结佣域详细设计 §4.1~§4.2）。
 * <p>
 * 申请单粒度 = <b>合同 + 业绩归属月</b>：一个合同当月一张申请单，独立提交、独立审批；
 * 数据可分多次导入，未发起的合同在列表中以「未发起」展示，随到随发起，互不影响。
 * <p>
 * 核心口径：
 * <ul>
 *   <li>金额为<b>结佣业绩金额</b>（PERF_REAL 实收事实原样透传），一分钱提成不算（CI C6/C7）；</li>
 *   <li>0 值实收不入单：仅 {@code amount <> 0} 的事实生成明细（ADR B14）；</li>
 *   <li>封账窗口：CLOSED 期间拒绝发起（§2.5，经 Port 实时查）；</li>
 *   <li>已提交单不支持追加事实：数据有变化走「驳回 / 作废 → 重新发起」；</li>
 *   <li>审批通过月 = 工资归属月 approved_month（V4.2 硬要求 1）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionApplicationService {

    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter APPLY_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 结佣口径：实收业绩（结佣确认对象） */
    private static final String FACT_TYPE_REAL = "PERF_REAL";

    /** 列表行虚拟状态：未发起（业绩存在但无申请单） */
    public static final String ROW_STATUS_NONE = "NONE";

    private final CommissionApplicationMapper applicationMapper;
    private final CommissionItemMapper itemMapper;
    private final CommissionConsumeLogMapper consumeLogMapper;
    private final CommissionPerformanceQueryPort performanceQueryPort;
    private final PeriodCloseQueryPort periodCloseQueryPort;
    private final EmployeeMainDataQueryPort employeeMainDataQueryPort;
    private final EventPort eventPort;

    // ==================== 发起结佣（按合同） ====================

    /**
     * 发起结佣（拉取该合同当月事实 → 生成明细，§4.1）。
     * <p>
     * 幂等：该 (period, contractNo) 已有 DRAFT/SUBMITTED/APPROVED/LOCKED 单 → 拒绝；
     * REJECTED 单请直接修改后重新提交；CANCELLED 单作废后可重新发起。
     *
     * @param period     业绩归属月（结算月 YYYY-MM）
     * @param contractNo 合同号
     * @param operatorId 发起人 ID
     * @return 申请单（含明细条数 / 金额合计）
     */
    @Transactional(rollbackFor = Exception.class)
    public CommissionApplication apply(String period, String contractNo, Long operatorId) {
        if (StringUtils.isBlank(period) || StringUtils.isBlank(contractNo)) {
            throw new ServiceException("结算月与合同号不能为空");
        }
        checkPeriodOpen(period, "发起结佣");
        return doApply(period, contractNo, operatorId);
    }

    /**
     * 批量发起：为期间内所有「未发起且有非零实收」的合同逐张建单。
     * <p>
     * 已存在未完结单（DRAFT/SUBMITTED/APPROVED/LOCKED）的合同自动跳过；
     * 每张单仍为独立草稿，需逐张提交/审批。
     *
     * @param period     业绩归属月
     * @param deptId     门店 ID（null=全部门店；非 null 含下级）
     * @param operatorId 发起人 ID
     * @return 新创建申请单数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int batchApply(String period, Long deptId, Long operatorId) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("结算月不能为空");
        }
        checkPeriodOpen(period, "批量发起结佣");

        List<PerformanceContractSummaryDTO> contracts =
            performanceQueryPort.listContractSummaries(period, deptId, FACT_TYPE_REAL);
        Set<String> activeContractNos = listActiveApplications(period).stream()
            .map(CommissionApplication::getContractNo)
            .collect(Collectors.toSet());

        int created = 0;
        List<String> failed = new ArrayList<>();
        for (PerformanceContractSummaryDTO contract : contracts) {
            if (contract.getAmount() == null || contract.getAmount().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            if (activeContractNos.contains(contract.getContractNo())) {
                continue;
            }
            try {
                doApply(period, contract.getContractNo(), operatorId);
                created++;
            } catch (ServiceException e) {
                // 单合同失败（如员工无法归属）不阻断整批，收集后统一提示
                log.warn("[结佣-批量发起] 合同 {} 发起失败：{}", contract.getContractNo(), e.getMessage());
                failed.add(contract.getContractNo());
            }
        }
        log.info("[结佣-批量发起] period={}, deptId={}, 创建={}, 失败={}", period, deptId, created, failed.size());
        if (!failed.isEmpty()) {
            throw new ServiceException("成功发起 " + created + " 张；以下合同失败（请核对员工归属）：" + failed);
        }
        return created;
    }

    /**
     * 发起核心逻辑（不含封账校验，由公共入口保证）。
     */
    private CommissionApplication doApply(String period, String contractNo, Long operatorId) {
        // 幂等检查：该 (period, contractNo) 的未完结申请单
        CommissionApplication existing = findActiveApplication(period, contractNo);
        if (existing != null) {
            if (existing.getStatus() == ApplicationStatus.APPROVED
                || existing.getStatus() == ApplicationStatus.LOCKED) {
                throw new ServiceException("合同 " + contractNo + " " + period
                    + " 月结佣已审批锁定，新增或变更一律走调整单");
            }
            throw new ServiceException("合同 " + contractNo + " " + period
                + " 月已存在" + existing.getStatus().getDesc() + "申请单（" + existing.getApplyNo() + "），请勿重复发起");
        }

        // 拉取该合同 ACTIVE 实收事实（含 0 值，本域过滤）
        List<PerformanceFactSummaryDTO> facts = performanceQueryPort
            .findActiveByContract(period, contractNo, FACT_TYPE_REAL);
        List<PerformanceFactSummaryDTO> nonZeroFacts = filterNonZero(facts);
        if (nonZeroFacts.isEmpty()) {
            throw new ServiceException("合同 " + contractNo + " " + period + " 月无可入账的实收业绩（amount>0 的实收事实为 0 条）");
        }

        // 员工归属兜底：明细 employee_id NOT NULL，按工号补齐
        resolveEmployeeIds(nonZeroFacts);

        // 合同快照（订单号/房源/签约时间取事实聚合值；跨门店合作单 dept_id 留空）
        PerformanceFactSummaryDTO first = nonZeroFacts.get(0);
        String orderNo = null;
        String propertyAddress = null;
        LocalDate businessDate = null;
        Set<Long> deptIds = new HashSet<>();
        for (PerformanceFactSummaryDTO f : nonZeroFacts) {
            if (orderNo == null) {
                orderNo = f.getOrderNo();
            }
            if (propertyAddress == null) {
                propertyAddress = f.getPropertyAddress();
            }
            if (f.getBusinessDate() != null
                && (businessDate == null || f.getBusinessDate().isAfter(businessDate))) {
                businessDate = f.getBusinessDate();
            }
            if (f.getDeptId() != null) {
                deptIds.add(f.getDeptId());
            }
        }

        CommissionApplication application = new CommissionApplication();
        application.setApplyNo("CAPP" + LocalDateTime.now().format(APPLY_NO_FORMATTER));
        application.setPeriod(period);
        application.setContractNo(contractNo);
        application.setOrderNo(orderNo);
        application.setPropertyAddress(propertyAddress);
        application.setBusinessDate(businessDate != null ? businessDate.atStartOfDay() : null);
        application.setDeptId(deptIds.size() == 1 ? first.getDeptId() : null);
        application.setStatus(ApplicationStatus.DRAFT);
        application.setApplicantId(operatorId);
        application.setItemCount(nonZeroFacts.size());
        application.setTotalAmount(sumAmounts(nonZeroFacts));
        try {
            applicationMapper.insert(application);
        } catch (DuplicateKeyException e) {
            // 并发发起撞 uk_capp_period_contract 部分唯一索引 → 转友好提示
            throw new ServiceException("合同 " + contractNo + " " + period + " 月申请单已由他人发起，请刷新");
        }

        for (PerformanceFactSummaryDTO fact : nonZeroFacts) {
            itemMapper.insert(buildItem(application, fact, null));
        }

        log.info("[结佣-发起] 合同申请单已创建：applyNo={}, period={}, contractNo={}, itemCount={}, totalAmount={}",
            application.getApplyNo(), period, contractNo, application.getItemCount(), application.getTotalAmount());
        return application;
    }

    /**
     * 0 值过滤（纯函数，供单测）：仅保留 amount &lt;&gt; 0 的事实（BigDecimal compareTo 比较）。
     *
     * @param facts 事实列表
     * @return 非零金额事实
     */
    public static List<PerformanceFactSummaryDTO> filterNonZero(List<PerformanceFactSummaryDTO> facts) {
        if (facts == null || facts.isEmpty()) {
            return new ArrayList<>();
        }
        List<PerformanceFactSummaryDTO> result = new ArrayList<>(facts.size());
        for (PerformanceFactSummaryDTO fact : facts) {
            if (fact.getAmount() != null && fact.getAmount().compareTo(BigDecimal.ZERO) != 0) {
                result.add(fact);
            }
        }
        return result;
    }

    // ==================== 提交 / 审批 ====================

    /**
     * 提交审批：DRAFT / REJECTED → SUBMITTED。
     * <p>
     * 驳回后的申请单可直接修改重新提交（明细仍为 PENDING）；草稿单明细随单 DRAFT → PENDING。
     *
     * @param applicationId 申请单 ID
     * @param operatorId    操作人 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long applicationId, Long operatorId) {
        CommissionApplication application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.DRAFT
            && application.getStatus() != ApplicationStatus.REJECTED) {
            throw new ServiceException("仅草稿/已驳回状态可提交（当前：" + application.getStatus().getDesc() + "）");
        }
        application.setStatus(ApplicationStatus.SUBMITTED);
        int rows = applicationMapper.updateById(application);
        if (rows == 0) {
            throw new ServiceException("申请单状态已变化（并发冲突），请刷新后重试");
        }
        // 草稿明细随单流转：DRAFT（待提交）→ PENDING（待审批）；驳回单明细已是 PENDING
        itemMapper.update(null, new LambdaUpdateWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .eq(CommissionItem::getStatus, ItemStatus.DRAFT)
            .set(CommissionItem::getStatus, ItemStatus.PENDING));
        log.info("[结佣-提交] 合同申请单已提交：applyNo={}, contractNo={}, operatorId={}",
            application.getApplyNo(), application.getContractNo(), operatorId);
    }

    /**
     * 审批回调（简化审批，单事务）：
     * <ul>
     *   <li>通过：SUBMITTED → LOCKED（内部先 APPROVED 落 approved_month = 当前月），
     *       明细 PENDING → APPROVED，发布 CommissionApprovedEvent；</li>
     *   <li>驳回：SUBMITTED → REJECTED，明细保持 PENDING（可修改后重新提交）。</li>
     * </ul>
     * 幂等：重复回调（已 LOCKED / REJECTED）直接忽略。
     *
     * @param applicationId 申请单 ID
     * @param approve       是否通过
     * @param approverId    审批人 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void callback(Long applicationId, boolean approve, Long approverId) {
        CommissionApplication application = getApplication(applicationId);
        if (application.getStatus() == ApplicationStatus.LOCKED
            || application.getStatus() == ApplicationStatus.REJECTED
            || application.getStatus() == ApplicationStatus.APPROVED) {
            log.info("[结佣-审批] 重复回调忽略：applyNo={}, status={}",
                application.getApplyNo(), application.getStatus().getCode());
            return;
        }
        if (application.getStatus() != ApplicationStatus.SUBMITTED) {
            throw new ServiceException("仅已提交状态的申请单可审批（当前：" + application.getStatus().getDesc() + "）");
        }

        if (!approve) {
            application.setStatus(ApplicationStatus.REJECTED);
            application.setApproverId(approverId);
            int rows = applicationMapper.updateById(application);
            if (rows == 0) {
                throw new ServiceException("申请单状态已变化（并发冲突），请刷新后重试");
            }
            log.info("[结佣-审批] 合同申请单已驳回：applyNo={}, contractNo={}, approverId={}",
                application.getApplyNo(), application.getContractNo(), approverId);
            return;
        }

        // ★ 审批通过月 = 工资归属月（V4.2 硬要求 1）：8 月业绩 9 月审批 → period=2026-08、approved_month=2026-09
        String approvedMonth = LocalDateTime.now().format(PERIOD_FORMATTER);

        // 明细 PENDING → APPROVED（先查 ID 供事件载荷）
        List<CommissionItem> pendingItems = itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .eq(CommissionItem::getStatus, ItemStatus.PENDING));
        itemMapper.update(null, new LambdaUpdateWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .eq(CommissionItem::getStatus, ItemStatus.PENDING)
            .set(CommissionItem::getStatus, ItemStatus.APPROVED)
            .set(CommissionItem::getApprovedMonth, approvedMonth));

        // SUBMITTED → APPROVED → LOCKED（单事务内连续流转，终态 LOCKED）
        application.setStatus(ApplicationStatus.LOCKED);
        application.setApprovedMonth(approvedMonth);
        application.setApproverId(approverId);
        application.setLockTime(LocalDateTime.now());
        int rows = applicationMapper.updateById(application);
        if (rows == 0) {
            throw new ServiceException("申请单状态已变化（并发冲突），请刷新后重试");
        }

        // 发布审批通过事件（payroll 消费；事件只传 ID，明细由 payroll 走 Port 拉取）
        CommissionApprovedEvent event = new CommissionApprovedEvent();
        event.setApplicationId(applicationId);
        event.setPeriod(application.getPeriod());
        event.setApprovedMonth(approvedMonth);
        event.setDeptId(application.getDeptId());
        event.setItemIds(pendingItems.stream().map(i -> String.valueOf(i.getId())).toList());
        eventPort.emit(event);

        log.info("[结佣-审批] 合同申请单已锁定：applyNo={}, contractNo={}, approvedMonth={}, itemCount={}",
            application.getApplyNo(), application.getContractNo(), approvedMonth, pendingItems.size());
    }

    /**
     * 作废申请单：仅 DRAFT/SUBMITTED 可作废。
     * <p>
     * 未审批明细（DRAFT/PENDING）随单冲销（REVERSED + APPLICATION_CANCELLED），
     * 释放对应业绩事实供重新发起；申请单聚合清零。
     *
     * @param applicationId 申请单 ID
     * @param operatorId    操作人 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long applicationId, Long operatorId) {
        CommissionApplication application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.DRAFT
            && application.getStatus() != ApplicationStatus.SUBMITTED) {
            throw new ServiceException("仅草稿/已提交状态可作废（当前：" + application.getStatus().getDesc() + "）");
        }
        application.setStatus(ApplicationStatus.CANCELLED);
        int rows = applicationMapper.updateById(application);
        if (rows == 0) {
            throw new ServiceException("申请单状态已变化（并发冲突），请刷新后重试");
        }

        // 未审批明细随单冲销，释放事实（uk_citem_fact_active 排除 REVERSED → 可重新发起）
        int reversed = itemMapper.update(null, new LambdaUpdateWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .in(CommissionItem::getStatus, ItemStatus.DRAFT, ItemStatus.PENDING)
            .set(CommissionItem::getStatus, ItemStatus.REVERSED)
            .set(CommissionItem::getReversedReason, ReversedReason.APPLICATION_CANCELLED));
        if (reversed > 0) {
            application.setItemCount(0);
            application.setTotalAmount(BigDecimal.ZERO);
            applicationMapper.updateById(application);
        }
        log.info("[结佣-作废] 合同申请单已作废：applyNo={}, contractNo={}, 冲销明细={}, operatorId={}",
            application.getApplyNo(), application.getContractNo(), reversed, operatorId);
    }

    // ==================== 查询 ====================

    /**
     * 分页查询申请单。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 申请单分页
     */
    public PageResult<CommissionApplication> listApplications(ApplyQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<CommissionApplication> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), CommissionApplication::getPeriod, query.getPeriod())
            .eq(query.getDeptId() != null, CommissionApplication::getDeptId, query.getDeptId())
            .eq(StringUtils.isNotBlank(query.getStatus()), CommissionApplication::getStatus,
                ApplicationStatus.fromCode(query.getStatus()))
            .and(StringUtils.isNotBlank(query.getKeyword()), w -> w
                .like(CommissionApplication::getContractNo, query.getKeyword())
                .or().like(CommissionApplication::getOrderNo, query.getKeyword())
                .or().like(CommissionApplication::getPropertyAddress, query.getKeyword()))
            .orderByDesc(CommissionApplication::getCreateTime);
        var page = applicationMapper.selectPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 按「合同」维度分页查询结佣申请（与业绩明细页合同维度对齐）。
     * <p>
     * 数据源为业绩域当月全部合同（PERF_REAL），左联结佣申请单：
     * 无单的合同状态为「未发起」，有单的展示申请单状态与操作。门店权限过滤由业绩域聚合查询承担。
     *
     * @param query     筛选条件（period 必填；deptId / status / keyword）
     * @param pageQuery 分页参数
     * @return 合同维度分页
     */
    public PageResult<CommissionContractVO> listContracts(ApplyQuery query, PageQuery pageQuery) {
        String period = StringUtils.isNotBlank(query.getPeriod())
            ? query.getPeriod() : LocalDateTime.now().format(PERIOD_FORMATTER);

        // 1. 业绩域合同汇总（含下级部门，与业绩明细页口径一致）
        List<PerformanceContractSummaryDTO> contracts =
            performanceQueryPort.listContractSummaries(period, query.getDeptId(), FACT_TYPE_REAL);

        // 2. 当月全部申请单：同一合同取最新一张（CANCELLED 后重新发起时新单优先）
        List<CommissionApplication> applications = applicationMapper.selectList(new LambdaQueryWrapper<CommissionApplication>()
            .eq(CommissionApplication::getPeriod, period)
            .orderByDesc(CommissionApplication::getId));
        Map<String, CommissionApplication> appMap = new LinkedHashMap<>();
        for (CommissionApplication app : applications) {
            appMap.putIfAbsent(app.getContractNo(), app);
        }

        // 3. 关键字过滤
        String keyword = StringUtils.trimToNull(query.getKeyword());

        // 4. 合并：合同事实 + 申请单
        List<CommissionContractVO> all = new ArrayList<>(contracts.size());
        for (PerformanceContractSummaryDTO c : contracts) {
            CommissionApplication app = appMap.get(c.getContractNo());
            String status = app != null && app.getStatus() != null ? app.getStatus().getCode() : ROW_STATUS_NONE;
            if (StringUtils.isNotBlank(query.getStatus()) && !query.getStatus().equals(status)) {
                continue;
            }
            if (keyword != null && !containsKeyword(c, keyword)) {
                continue;
            }
            all.add(toContractVO(period, c, app, status));
        }

        // 5. 排序（签约时间倒序，空值垫底）+ 内存分页
        all.sort((a, b) -> {
            if (a.getBusinessDate() == null && b.getBusinessDate() == null) {
                return 0;
            }
            if (a.getBusinessDate() == null) {
                return 1;
            }
            if (b.getBusinessDate() == null) {
                return -1;
            }
            return b.getBusinessDate().compareTo(a.getBusinessDate());
        });
        int total = all.size();
        int pageNum = pageQuery.getPageNum() != null ? pageQuery.getPageNum() : 1;
        int pageSize = pageQuery.getPageSize() != null ? pageQuery.getPageSize() : 20;
        int from = Math.min((pageNum - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        return PageResult.build(all.subList(from, to), (long) total);
    }

    /** 合同汇总 + 申请单 → 列表行 VO；有单时金额/条数以申请单聚合为准（0 值事实不入单） */
    private CommissionContractVO toContractVO(String period, PerformanceContractSummaryDTO c,
                                              CommissionApplication app, String status) {
        CommissionContractVO vo = new CommissionContractVO();
        vo.setPeriod(period);
        vo.setContractNo(c.getContractNo());
        vo.setOrderNo(c.getOrderNo());
        vo.setBizType(c.getBizType());
        vo.setPropertyAddress(c.getPropertyAddress());
        vo.setBusinessDate(c.getBusinessDate());
        vo.setEmployeeCount(c.getEmployeeCount());
        vo.setStatus(status);
        if (app != null) {
            vo.setApplicationId(app.getId());
            vo.setApplyNo(app.getApplyNo());
            vo.setApplicantId(app.getApplicantId());
            vo.setCreateTime(app.getCreateTime());
            vo.setDeptId(app.getDeptId());
            vo.setAmount(app.getTotalAmount());
            vo.setDetailCount(app.getItemCount() == null ? 0 : app.getItemCount());
        } else {
            vo.setAmount(c.getAmount());
            vo.setDetailCount(c.getDetailCount());
        }
        return vo;
    }

    private boolean containsKeyword(PerformanceContractSummaryDTO c, String keyword) {
        return (c.getContractNo() != null && c.getContractNo().contains(keyword))
            || (c.getOrderNo() != null && c.getOrderNo().contains(keyword))
            || (c.getPropertyAddress() != null && c.getPropertyAddress().contains(keyword));
    }

    /**
     * 申请单详情（含明细）。
     *
     * @param applicationId 申请单 ID
     * @return 申请单
     */
    public CommissionApplication getApplication(Long applicationId) {
        CommissionApplication application = applicationMapper.selectById(applicationId);
        if (application == null) {
            throw new ServiceException("结佣申请单不存在：" + applicationId);
        }
        return application;
    }

    /**
     * 申请单明细列表（全量，含 REVERSED，供详情/审计）。
     *
     * @param applicationId 申请单 ID
     * @return 明细列表（按 ID 升序）
     */
    public List<CommissionItem> listItems(Long applicationId) {
        return itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .orderByAsc(CommissionItem::getId));
    }

    /**
     * 按明细 ID 查单条明细（溯源用，含 REVERSED）。
     *
     * @param itemId 明细 ID
     * @return 明细；不存在返回 null
     */
    public CommissionItem getItem(Long itemId) {
        return itemMapper.selectById(itemId);
    }

    /**
     * 明细分页查询（列表页）。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 明细分页
     */
    public PageResult<CommissionItem> listItems(com.panjia.commission.dto.ItemQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<CommissionItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), CommissionItem::getPeriod, query.getPeriod())
            .eq(query.getEmployeeId() != null, CommissionItem::getEmployeeId, query.getEmployeeId())
            .eq(query.getDeptId() != null, CommissionItem::getDeptId, query.getDeptId())
            .eq(StringUtils.isNotBlank(query.getStatus()), CommissionItem::getStatus,
                ItemStatus.fromCode(query.getStatus()))
            .eq(query.getApplicationId() != null, CommissionItem::getApplicationId, query.getApplicationId())
            .orderByDesc(CommissionItem::getCreateTime);
        var page = itemMapper.selectPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 消费日志分页查询。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 消费日志分页
     */
    public PageResult<CommissionConsumeLog> listConsumeLogs(com.panjia.commission.dto.ConsumeLogQuery query,
                                                            PageQuery pageQuery) {
        LambdaQueryWrapper<CommissionConsumeLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), CommissionConsumeLog::getPeriod, query.getPeriod())
            .eq(StringUtils.isNotBlank(query.getEventType()), CommissionConsumeLog::getEventType, query.getEventType())
            .eq(StringUtils.isNotBlank(query.getEventId()), CommissionConsumeLog::getEventId, query.getEventId())
            .orderByDesc(CommissionConsumeLog::getCreateTime);
        var page = consumeLogMapper.selectPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    // ==================== 共用内部逻辑 ====================

    /**
     * 重算申请单聚合（item_count / total_amount，按未 REVERSED 明细）。
     * <p>
     * 供调整单执行 / 冲销联动共用；REVERSED 行不计入聚合。
     *
     * @param applicationId 申请单 ID
     * @param application   申请单实体（可为 null，内部重新加载）
     */
    public void recalcAggregates(Long applicationId, CommissionApplication application) {
        CommissionApplication app = application != null ? application : applicationMapper.selectById(applicationId);
        if (app == null) {
            return;
        }
        List<CommissionItem> items = itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .ne(CommissionItem::getStatus, ItemStatus.REVERSED));
        app.setItemCount(items.size());
        app.setTotalAmount(items.stream()
            .map(CommissionItem::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        int rows = applicationMapper.updateById(app);
        if (rows == 0) {
            throw new ServiceException("申请单并发冲突，聚合重算失败，请重试：applicationId=" + applicationId);
        }
    }

    /**
     * 查该期间所有未完结申请单（DRAFT/SUBMITTED/APPROVED/LOCKED）。
     */
    private List<CommissionApplication> listActiveApplications(String period) {
        return applicationMapper.selectList(new LambdaQueryWrapper<CommissionApplication>()
            .eq(CommissionApplication::getPeriod, period)
            .in(CommissionApplication::getStatus, ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED,
                ApplicationStatus.APPROVED, ApplicationStatus.LOCKED));
    }

    /**
     * 查该 (period, contractNo) 的未完结申请单（DRAFT/SUBMITTED/APPROVED/LOCKED 任一即视为占用，
     * 与 uk_capp_period_contract 部分唯一索引口径一致）。
     */
    private CommissionApplication findActiveApplication(String period, String contractNo) {
        return applicationMapper.selectOne(new LambdaQueryWrapper<CommissionApplication>()
            .eq(CommissionApplication::getPeriod, period)
            .eq(CommissionApplication::getContractNo, contractNo)
            .in(CommissionApplication::getStatus, ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED,
                ApplicationStatus.APPROVED, ApplicationStatus.LOCKED)
            .orderByDesc(CommissionApplication::getCreateTime)
            .last("LIMIT 1"));
    }

    /**
     * 封账校验：CLOSED 期间结佣窗口关闭（§2.5），严禁系统自动放行。
     */
    private void checkPeriodOpen(String period, String action) {
        if (periodCloseQueryPort.isClosed(period)) {
            throw new ServiceException("[" + action + "] 期间 " + period + " 已封账，结佣窗口关闭；"
                + "如有实收未结，请以差额调整（DIFF）补发到未封账月份，或由总监解封后重走");
        }
    }

    /**
     * 员工归属兜底（就地修改 fact.employeeId）：事实 employee_id 为空时按工号批量补齐，
     * 仍无法归属的（脏数据 / 员工已删）整体拒绝，fail fast（明细 employee_id NOT NULL）。
     */
    private void resolveEmployeeIds(List<PerformanceFactSummaryDTO> facts) {
        Set<String> missingCodes = new HashSet<>();
        for (PerformanceFactSummaryDTO fact : facts) {
            if (fact.getEmployeeId() == null && StringUtils.isNotBlank(fact.getEmployeeCode())) {
                missingCodes.add(fact.getEmployeeCode());
            }
        }
        if (missingCodes.isEmpty()) {
            return;
        }
        Map<String, EmployeeMainDataDTO> mainMap = employeeMainDataQueryPort.listByCodes(missingCodes);
        List<String> unresolved = new ArrayList<>();
        for (PerformanceFactSummaryDTO fact : facts) {
            if (fact.getEmployeeId() != null) {
                continue;
            }
            EmployeeMainDataDTO main = mainMap.get(fact.getEmployeeCode());
            if (main != null && main.getEmployeeId() != null) {
                fact.setEmployeeId(main.getEmployeeId());
                if (fact.getDeptId() == null) {
                    fact.setDeptId(main.getDeptId());
                }
            } else {
                unresolved.add(fact.getEmployeeCode());
            }
        }
        if (!unresolved.isEmpty()) {
            throw new ServiceException("以下工号无法归属员工（请先在员工域补齐主数据）：" + unresolved);
        }
    }

    /**
     * 由事实构建结佣明细（amount 原样透传，不折算；冻结 contractNo/employee/dept/bizType/roleType）。
     * <p>
     * 新建申请单恒为 DRAFT，明细初始状态 DRAFT（待提交），提交时随单流转为 PENDING。
     *
     * @param application 所属申请单
     * @param fact        业绩事实摘要
     * @param adjustId    来源调整单 ID（发起场景为 null）
     * @return 未持久化的明细实体
     */
    private CommissionItem buildItem(CommissionApplication application, PerformanceFactSummaryDTO fact, Long adjustId) {
        CommissionItem item = new CommissionItem();
        item.setApplicationId(application.getId());
        item.setPerformanceFactId(fact.getFactId());
        item.setContractNo(application.getContractNo());
        item.setPeriod(fact.getPeriod());
        item.setEmployeeId(fact.getEmployeeId());
        item.setDeptId(fact.getDeptId());
        item.setBizType(fact.getBizType());
        item.setRoleType(fact.getRoleType());
        item.setAmount(fact.getAmount());
        item.setStatus(ItemStatus.DRAFT);
        item.setOriginReversed(false);
        item.setAdjustId(adjustId);
        return item;
    }

    /** 事实金额合计 */
    private BigDecimal sumAmounts(List<PerformanceFactSummaryDTO> facts) {
        return facts.stream()
            .map(PerformanceFactSummaryDTO::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
