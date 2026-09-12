package com.panjia.commission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.domain.CommissionConsumeLog;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.domain.ConsumeStatus;
import com.panjia.commission.domain.ItemStatus;
import com.panjia.commission.dto.ApplyQuery;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.commission.mapper.CommissionConsumeLogMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结佣申请服务（发起 / 增量重拉 / 提交 / 审批锁定，结佣域详细设计 §4.1~§4.2）。
 * <p>
 * 核心口径：
 * <ul>
 *   <li>金额为<b>结佣业绩金额</b>（PERF_REAL 实收事实原样透传），一分钱提成不算（CI C6/C7）；</li>
 *   <li>0 值实收不入单：仅 {@code amount <> 0} 的事实生成明细（ADR B14）；</li>
 *   <li>封账窗口：CLOSED 期间拒绝发起与增量重拉（§2.5，经 Port 实时查）；</li>
 *   <li>增量重拉只追加、不删除、不修改，天然幂等（ADR B15）；</li>
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

    private final CommissionApplicationMapper applicationMapper;
    private final CommissionItemMapper itemMapper;
    private final CommissionConsumeLogMapper consumeLogMapper;
    private final CommissionPerformanceQueryPort performanceQueryPort;
    private final PeriodCloseQueryPort periodCloseQueryPort;
    private final EmployeeMainDataQueryPort employeeMainDataQueryPort;
    private final EventPort eventPort;

    // ==================== 发起结佣 ====================

    /**
     * 发起结佣（拉取业绩 → 生成明细，§4.1）。
     * <p>
     * 幂等三情形（ADR B15）：无未完成单 → 新建；已有 DRAFT/SUBMITTED → 拒绝并提示走增量重拉；
     * 已有 APPROVED/LOCKED → 拒绝，变更走调整单。
     *
     * @param period     业绩归属月（结算月 YYYY-MM）
     * @param deptId     门店 ID
     * @param operatorId 发起人 ID
     * @return 申请单（含明细条数 / 金额合计）
     */
    @Transactional(rollbackFor = Exception.class)
    public CommissionApplication apply(String period, Long deptId, Long operatorId) {
        if (StringUtils.isBlank(period) || deptId == null) {
            throw new ServiceException("结算月与门店不能为空");
        }

        // ② 封账校验：结佣必须在封账之前完成（§2.5）
        checkPeriodOpen(period, "发起结佣");

        // ③~⑦ 幂等检查：该 (period, deptId) 的未完结申请单
        CommissionApplication existing = findActiveApplication(period, deptId);
        if (existing != null) {
            if (existing.getStatus() == ApplicationStatus.DRAFT
                || existing.getStatus() == ApplicationStatus.SUBMITTED) {
                throw new ServiceException("该门店 " + period + " 月已存在未审批申请单（"
                    + existing.getApplyNo() + "），请走增量重拉补充事实，不要重复发起");
            }
            throw new ServiceException("该门店 " + period + " 月结佣已审批锁定，新增或变更一律走调整单");
        }

        // ③ 拉取 ACTIVE 实收事实（含 0 值，本域过滤）
        List<PerformanceFactSummaryDTO> facts = performanceQueryPort
            .findActiveByDept(period, deptId, FACT_TYPE_REAL);

        // ④★ 0 值过滤：amount = 0 的「已签约未实收」行不生成结佣明细（ADR B14）
        List<PerformanceFactSummaryDTO> nonZeroFacts = filterNonZero(facts);
        if (nonZeroFacts.isEmpty()) {
            throw new ServiceException("门店 " + period + " 月无可入账的实收业绩（amount>0 的实收事实为 0 条）");
        }

        // 员工归属兜底：明细 employee_id NOT NULL，按工号补齐（历史事实 employee_id 可能为空）
        resolveEmployeeIds(nonZeroFacts);

        // ⑤⑥ 生成申请单 + 明细（amount 原样透传，不折算）
        CommissionApplication application = new CommissionApplication();
        application.setApplyNo("CAPP" + LocalDateTime.now().format(APPLY_NO_FORMATTER));
        application.setPeriod(period);
        application.setDeptId(deptId);
        application.setStatus(ApplicationStatus.DRAFT);
        application.setApplicantId(operatorId);
        application.setItemCount(nonZeroFacts.size());
        application.setTotalAmount(sumAmounts(nonZeroFacts));
        try {
            applicationMapper.insert(application);
        } catch (DuplicateKeyException e) {
            // 并发发起撞 uk_capp_period_dept 部分唯一索引 → 转友好提示
            throw new ServiceException("该门店 " + period + " 月申请单已由他人发起，请刷新后走增量重拉");
        }

        for (PerformanceFactSummaryDTO fact : nonZeroFacts) {
            itemMapper.insert(buildItem(application, fact, null));
        }

        log.info("[结佣-发起] 申请单已创建：applyNo={}, period={}, deptId={}, itemCount={}, totalAmount={}",
            application.getApplyNo(), period, deptId, application.getItemCount(), application.getTotalAmount());
        return application;
    }

    // ==================== 增量重拉 ====================

    /**
     * 增量重拉（§4.1.1）：仅 DRAFT/SUBMITTED 单可重拉，差集 F−S 追加为新明细。
     * <p>
     ★ 只追加、不删除、不修改：已生成明细的金额永不因重拉而变化；重复重拉差集为空 → 零副作用（幂等）。
     *
     * @param applicationId 申请单 ID
     * @param operatorId    操作人 ID
     * @return 申请单（聚合已重算）
     */
    @Transactional(rollbackFor = Exception.class)
    public CommissionApplication refresh(Long applicationId, Long operatorId) {
        CommissionApplication application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.DRAFT
            && application.getStatus() != ApplicationStatus.SUBMITTED) {
            throw new ServiceException("仅草稿/已提交状态的申请单可增量重拉（当前："
                + application.getStatus().getDesc() + "）");
        }

        // 封账校验：校验业绩归属月（§2.5 判定规则）
        checkPeriodOpen(application.getPeriod(), "增量重拉");

        // ① 已有未 REVERSED 明细的事实 ID 集合 S
        Set<Long> existingFactIds = itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
                .eq(CommissionItem::getApplicationId, applicationId)
                .isNotNull(CommissionItem::getPerformanceFactId)
                .ne(CommissionItem::getStatus, ItemStatus.REVERSED))
            .stream()
            .map(CommissionItem::getPerformanceFactId)
            .collect(Collectors.toSet());

        // ② 重新拉取 ACTIVE 且 amount <> 0 的事实集合 F
        List<PerformanceFactSummaryDTO> fetched = filterNonZero(performanceQueryPort
            .findActiveByDept(application.getPeriod(), application.getDeptId(), FACT_TYPE_REAL));

        // ③ 差集 F − S
        List<PerformanceFactSummaryDTO> increment = computeIncrement(existingFactIds, fetched);
        if (increment.isEmpty()) {
            log.info("[结佣-重拉] 无新增事实，零副作用：applicationId={}, applyNo={}",
                applicationId, application.getApplyNo());
            return application;
        }

        // 员工归属兜底
        resolveEmployeeIds(increment);

        // ④ 追加明细（不删除、不修改已有明细）+ 重算聚合
        for (PerformanceFactSummaryDTO fact : increment) {
            itemMapper.insert(buildItem(application, fact, null));
        }
        recalcAggregates(applicationId, application);

        // 写消费日志留痕（event_id 每次重拉唯一，满足 uk_ccl_event）
        insertConsumeLog("REFRESH-" + applicationId + "-" + System.currentTimeMillis(),
            "INCREMENTAL_REFRESH", application.getPeriod(),
            increment.stream().map(f -> String.valueOf(f.getFactId())).toList(),
            increment.size(), increment.size(), ConsumeStatus.SUCCESS,
            "增量重拉追加明细 " + increment.size() + " 条");

        log.info("[结佣-重拉] 追加完成：applicationId={}, 新增={}, 操作人={}",
            applicationId, increment.size(), operatorId);
        return application;
    }

    /**
     * 差集计算（纯函数，供单测）：fetched 中 factId 不在 existingFactIds 内的事实。
     *
     * @param existingFactIds 已入单的事实 ID 集合 S
     * @param fetched         本次拉取的事实列表 F（已过滤 0 值）
     * @return 待追加的增量事实
     */
    public static List<PerformanceFactSummaryDTO> computeIncrement(Set<Long> existingFactIds,
                                                                   List<PerformanceFactSummaryDTO> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> existing = existingFactIds == null ? Set.of() : existingFactIds;
        List<PerformanceFactSummaryDTO> increment = new ArrayList<>();
        for (PerformanceFactSummaryDTO fact : fetched) {
            if (fact.getFactId() != null && !existing.contains(fact.getFactId())) {
                increment.add(fact);
            }
        }
        return increment;
    }

    /**
     * 0 值过滤（纯函数，供单测）：仅保留 amount <> 0 的事实（BigDecimal compareTo 比较）。
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
     * 提交审批：DRAFT → SUBMITTED。
     *
     * @param applicationId 申请单 ID
     * @param operatorId    操作人 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long applicationId, Long operatorId) {
        CommissionApplication application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.DRAFT) {
            throw new ServiceException("仅草稿状态可提交（当前：" + application.getStatus().getDesc() + "）");
        }
        application.setStatus(ApplicationStatus.SUBMITTED);
        int rows = applicationMapper.updateById(application);
        if (rows == 0) {
            throw new ServiceException("申请单状态已变化（并发冲突），请刷新后重试");
        }
        log.info("[结佣-提交] 申请单已提交：applyNo={}, operatorId={}", application.getApplyNo(), operatorId);
    }

    /**
     * 审批回调（简化审批，单事务）：
     * <ul>
     *   <li>通过：SUBMITTED → LOCKED（内部先 APPROVED 落 approved_month = 当前月），
     *       明细 PENDING → APPROVED，发布 CommissionApprovedEvent；</li>
     *   <li>驳回：SUBMITTED → REJECTED，明细保持 PENDING。</li>
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
            log.info("[结佣-审批] 申请单已驳回：applyNo={}, approverId={}", application.getApplyNo(), approverId);
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

        log.info("[结佣-审批] 申请单已锁定：applyNo={}, period={}, approvedMonth={}, itemCount={}",
            application.getApplyNo(), application.getPeriod(), approvedMonth, pendingItems.size());
    }

    /**
     * 作废申请单：仅 DRAFT/SUBMITTED 可作废。
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
        log.info("[结佣-作废] 申请单已作废：applyNo={}, operatorId={}", application.getApplyNo(), operatorId);
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
            .orderByDesc(CommissionApplication::getCreateTime);
        var page = applicationMapper.selectPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
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
     * 按明细 ID 查明细。
     *
     * @param itemId 明细 ID
     * @return 明细列表（最多一条）
     */
    public List<CommissionItem> listItemsByItemId(Long itemId) {
        return itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
            .eq(CommissionItem::getId, itemId));
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
     * 供发起 / 增量重拉 / 调整单执行 / 冲销联动共用；REVERSED 行不计入聚合。
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
     * 查该 (period, deptId) 的未完结申请单（DRAFT/SUBMITTED/APPROVED/LOCKED 任一即视为占用，
     * 与 uk_capp_period_dept 部分唯一索引口径一致）。
     */
    private CommissionApplication findActiveApplication(String period, Long deptId) {
        return applicationMapper.selectOne(new LambdaQueryWrapper<CommissionApplication>()
            .eq(CommissionApplication::getPeriod, period)
            .eq(CommissionApplication::getDeptId, deptId)
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
     * 由事实构建结佣明细（amount 原样透传，不折算；冻结 employee/dept/bizType/roleType）。
     *
     * @param application 所属申请单
     * @param fact        业绩事实摘要
     * @param adjustId    来源调整单 ID（调整单执行时传入，发起/重拉为 null）
     * @return 未持久化的明细实体
     */
    private CommissionItem buildItem(CommissionApplication application, PerformanceFactSummaryDTO fact, Long adjustId) {
        CommissionItem item = new CommissionItem();
        item.setApplicationId(application.getId());
        item.setPerformanceFactId(fact.getFactId());
        item.setPeriod(fact.getPeriod());
        item.setEmployeeId(fact.getEmployeeId());
        item.setDeptId(fact.getDeptId());
        item.setBizType(fact.getBizType());
        item.setRoleType(fact.getRoleType());
        item.setAmount(fact.getAmount());
        item.setStatus(ItemStatus.PENDING);
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

    /**
     * 写消费日志（幂等锚点 uk_ccl_event）。
     */
    private void insertConsumeLog(String eventId, String eventType, String period, List<String> factIds,
                                  int factCount, int affectedItems, ConsumeStatus status, String message) {
        CommissionConsumeLog consumeLog = new CommissionConsumeLog();
        consumeLog.setEventId(eventId);
        consumeLog.setEventType(eventType);
        consumeLog.setPeriod(period);
        consumeLog.setFactIds(String.join(",", factIds));
        consumeLog.setFactCount(factCount);
        consumeLog.setAffectedItems(affectedItems);
        consumeLog.setStatus(status);
        consumeLog.setMessage(message);
        consumeLogMapper.insert(consumeLog);
    }
}
