package com.panjia.payroll.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.dto.NormalizedRecordDTO;
import com.panjia.contracts.dto.SalaryFactSyncDTO;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchArchivedEvent;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.contracts.port.ImportNormalizedRecordQueryPort;
import com.panjia.contracts.port.PeopleSalaryFactSyncPort;
import com.panjia.payroll.domain.BatchStatus;
import com.panjia.payroll.domain.EmployeeRole;
import com.panjia.payroll.domain.PayrollBatch;
import com.panjia.payroll.domain.PayrollDetail;
import com.panjia.payroll.mapper.PayrollBatchMapper;
import com.panjia.payroll.mapper.PayrollDetailMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 历史工资归档事件处理器（薪酬域消费自己的数据）。
 * <p>
 * 消费 HISTORY_PAYROLL 批次归档事件，从归一化记录（recordType=PAYROLL_WAGE，
 * 全字段随 extraJson 留痕 + sheetKind 标记来源 sheet）重建历史工资批次：
 * <ol>
 *   <li>工资表（WAGE）→ 新建 {@link PayrollBatch}(PAID) + 经纪人/店长明细行；</li>
 *   <li>总监工资（DIRECTOR）→ 明细 upsert（employeeRole=DIRECTOR，无则新建）；</li>
 *   <li>店长工资（MANAGER）→ 追加既有明细的团队计薪字段；</li>
 *   <li>人事数据补丁（HR）→ 追加既有明细的社保/公积金/宿舍/积分等扣款字段；</li>
 *   <li>算薪事实推导（LEVEL/社保/公积金/商保/宿舍开关+金额）→ 经
 *       {@link PeopleSalaryFactSyncPort} 写入 people 域 pj_people_salary_fact
 *       （表归 people 域管辖，薪酬域不直写）。</li>
 * </ol>
 * 幂等：按 period 先删后建（历史补录期间不存在正常算薪批次，语义同老导入器撤销清理）。
 * 映射口径对齐老导入器 processPayrollSheet/processDirectorSheet/processManagerSheet/
 * processHrSheet/collectSalaryFacts，列位差异以模板 target_field 为准。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayrollArchiveHandler implements DomainEventHandler {

    /** 职级编码（A1/A2/…/S1/S2/…），工资表职级列推导 LEVEL 事实的过滤正则 */
    private static final Pattern LEVEL_CODE_RE = Pattern.compile("^[AS]\\d$");

    private final ImportNormalizedRecordQueryPort importQueryPort;
    private final EmployeeMainDataQueryPort employeeQueryPort;
    private final PayrollBatchMapper batchMapper;
    private final PayrollDetailMapper detailMapper;
    private final PeopleSalaryFactSyncPort salaryFactSyncPort;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return ImportBatchArchivedEvent.EVENT_TYPE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(String eventId, String payloadJson) {
        ImportBatchArchivedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, ImportBatchArchivedEvent.class);
        } catch (JacksonException e) {
            log.error("[历史工资消费] 归档事件反序列化失败：eventId={}", eventId, e);
            return;
        }
        if (!"HISTORY_PAYROLL".equals(event.getSourceType()) || StringUtils.isBlank(event.getPeriod())) {
            return;
        }
        String period = event.getPeriod().trim();
        log.info("[历史工资消费] 开始重建工资批次：batchId={}, period={}", event.getBatchId(), period);

        // ===== 1. 拉取工资族归一化记录（PAYROLL_WAGE），按 sheetKind 分组 =====
        Map<String, List<NormalizedRecordDTO>> byKind = new LinkedHashMap<>();
        int pageNum = 1;
        PageResult<NormalizedRecordDTO> page;
        do {
            page = importQueryPort.listByBatchId(event.getBatchId(), pageNum,
                ImportNormalizedRecordQueryPort.DEFAULT_PAGE_SIZE);
            if (page == null || page.getRows() == null) {
                break;
            }
            for (NormalizedRecordDTO record : page.getRows()) {
                if (!"PAYROLL_WAGE".equals(record.getRecordType())) {
                    continue;
                }
                String kind = sheetKindOf(record.getExtJson());
                if (kind == null) {
                    continue;
                }
                byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(record);
            }
            pageNum++;
        } while (page.getRows() != null && !page.getRows().isEmpty()
            && (long) pageNum * ImportNormalizedRecordQueryPort.DEFAULT_PAGE_SIZE <= page.getTotal());

        if (byKind.isEmpty()) {
            log.info("[历史工资消费] 批次无工资族记录：batchId={}", event.getBatchId());
            return;
        }

        // ===== 2. 工号 → 员工主数据（批量预取，避免 N+1） =====
        Map<String, EmployeeMainDataDTO> empMap = loadEmployees(byKind);

        // ===== 3. 幂等重建：先删该期间历史批次（期间为历史补录，无正常算薪批次） =====
        clearPeriod(period);

        // ===== 4. 工资表建批次 + 明细 =====
        PayrollBatch batch = buildWageSection(period, byKind.get("WAGE"), empMap, event.getOperatorId());
        Long batchId = batch == null ? null : batch.getId();

        // ===== 5. 总监/店长/人事补丁 upsert =====
        int director = upsertDirectorSection(period, byKind.get("DIRECTOR"), empMap, batchId);
        int manager = patchSection(period, byKind.get("MANAGER"), empMap, this::applyManagerRow);
        int hr = patchSection(period, byKind.get("HR"), empMap, this::applyHrRow);

        // ===== 6. 算薪事实推导（工资表 + 人事补丁） =====
        List<SalaryFactSyncDTO> facts = new ArrayList<>();
        collectFacts(byKind.get("WAGE"), empMap, facts, true);
        collectFacts(byKind.get("HR"), empMap, facts, false);
        int factCount = salaryFactSyncPort.syncHistorySalaryFacts(period, facts);

        log.info("[历史工资消费] 工资批次重建完成：period={}, batchId={}, 工资行={}, 总监行={}, "
                + "店长行={}, 人事行={}, 算薪事实={}",
            period, batchId, batch == null ? 0 : batch.getEmployeeCount(), director, manager, hr, factCount);
    }

    // ==================== 工资表（WAGE） ====================

    /**
     * 工资表行 → 批次 + 明细（经纪人/店长主表）。映射对齐老导入器 processPayrollSheet：
     * gross 优先取「工资合计」回退「应发工资」，net 优先取「最终发放」回退「实发工资」。
     */
    private PayrollBatch buildWageSection(String period, List<NormalizedRecordDTO> rows,
                                          Map<String, EmployeeMainDataDTO> empMap, Long operatorId) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        List<PayrollDetail> built = new ArrayList<>();
        int skipped = 0;
        for (NormalizedRecordDTO record : rows) {
            EmployeeMainDataDTO emp = empMap.get(record.getEmployeeCode());
            if (emp == null || emp.getEmployeeId() == null) {
                skipped++;
                log.warn("[历史工资消费] 工资行工号 {} 未匹配员工，跳过", record.getEmployeeCode());
                continue;
            }
            Map<String, String> j = json(record);
            PayrollDetail d = new PayrollDetail();
            d.setEmployeeId(emp.getEmployeeId());
            d.setDeptId(emp.getDeptId());
            d.setLevelCode(text(j, "positionLevel"));
            d.setEmployeeRole(roleFromPosition(text(j, "position")));
            d.setNewSignPerformance(dec(j, "newSignAmount"));
            d.setNewSignRate(dec(j, "newSignRatio"));
            d.setPerfDeduct(dec(j, "perfDeductPoint"));
            d.setMentorBonus(dec(j, "recruitReward"));
            d.setFinalRate(dec(j, "finalRatio"));
            d.setCommissionPerformance(dec(j, "commissionAmount"));
            d.setCommissionIncome(dec(j, "commissionFee"));
            d.setBaseSalary(dec(j, "baseSalary"));
            d.setBonus(dec(j, "perfAmount"));
            d.setAttendanceFee(dec(j, "attendanceFee"));
            d.setPointsFee(dec(j, "pointsFee"));
            d.setSocialFee(dec(j, "socialFee"));
            d.setHousingFund(dec(j, "housingFundFee"));
            d.setNegativeCarryover(dec(j, "prevNegativeSalary"));
            d.setCommercialInsurance(dec(j, "commercialInsurance"));
            d.setDormitoryFee(dec(j, "dormitoryFee"));
            BigDecimal total = dec(j, "salaryTotal");
            d.setGross(total != null ? total : dec(j, "grossSalary"));
            BigDecimal finalPay = dec(j, "finalPay");
            d.setNet(finalPay != null ? finalPay : dec(j, "actualSalary"));
            d.setTax(dec(j, "taxDeduction"));
            built.add(d);
        }
        if (built.isEmpty()) {
            log.warn("[历史工资消费] 工资表无有效数据行（匹配失败 {} 行）", skipped);
            return null;
        }
        PayrollBatch batch = new PayrollBatch();
        batch.setPeriod(period);
        batch.setDeptScope("ALL");
        batch.setStatus(BatchStatus.PAID);
        batch.setAttempt(0);
        batch.setOperatorId(operatorId == null ? 0L : operatorId);
        batchMapper.insert(batch);

        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal netTotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        int inserted = 0;
        for (PayrollDetail d : built) {
            d.setBatchId(batch.getId());
            d.setPeriod(period);
            try {
                detailMapper.insert(d);
                inserted++;
                grossTotal = grossTotal.add(nvl(d.getGross()));
                netTotal = netTotal.add(nvl(d.getNet()));
                taxTotal = taxTotal.add(nvl(d.getTax()));
            } catch (Exception e) {
                log.warn("[历史工资消费] 工资明细插入失败：employeeId={}, {}",
                    d.getEmployeeId(), e.getMessage());
            }
        }
        batch.setEmployeeCount(inserted);
        batch.setGrossTotal(round2(grossTotal));
        batch.setDeductTotal(BigDecimal.ZERO);
        batch.setTaxTotal(round2(taxTotal));
        batch.setNetTotal(round2(netTotal));
        batchMapper.updateById(batch);
        log.info("[历史工资消费] 工资批次：batchId={}, 人数={}（匹配失败 {} 行）", batch.getId(), inserted, skipped);
        return batch;
    }

    // ==================== 总监工资（DIRECTOR） ====================

    /**
     * 总监工资行 → 明细 upsert（无则新建 employeeRole=DIRECTOR，有则更新）。
     * 映射对齐老导入器 processDirectorSheet。
     */
    private int upsertDirectorSection(String period, List<NormalizedRecordDTO> rows,
                                      Map<String, EmployeeMainDataDTO> empMap, Long batchId) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (NormalizedRecordDTO record : rows) {
            EmployeeMainDataDTO emp = empMap.get(record.getEmployeeCode());
            if (emp == null || emp.getEmployeeId() == null) {
                log.warn("[历史工资消费] 总监行工号 {} 未匹配员工，跳过", record.getEmployeeCode());
                continue;
            }
            Map<String, String> j = json(record);
            PayrollDetail d = findDetail(period, emp.getEmployeeId());
            boolean created = false;
            if (d == null) {
                d = new PayrollDetail();
                d.setEmployeeId(emp.getEmployeeId());
                d.setDeptId(emp.getDeptId());
                d.setEmployeeRole(EmployeeRole.DIRECTOR);
                d.setBatchId(batchId);
                d.setPeriod(period);
                created = true;
            }
            d.setDeptNewSignTotal(dec(j, "newSignAmount"));
            d.setDeptEmployerSocialTotal(dec(j, "socialAmount"));
            d.setStoreRate(dec(j, "ratio"));
            d.setStoreIncome(dec(j, "bonusAmount"));
            d.setBaseSalary(dec(j, "baseSalary"));
            d.setFullAttendance(dec(j, "fullAttendance"));
            d.setBonus(dec(j, "perfAmount"));
            d.setCommissionPerformance(dec(j, "commissionAmount"));
            d.setCommissionIncome(dec(j, "perfBonus"));
            d.setMentorBonus(dec(j, "recruitBonus"));
            d.setSocialFee(dec(j, "socialFee"));
            d.setHousingFund(dec(j, "housingFundFee"));
            d.setCommercialInsurance(dec(j, "commercialInsurance"));
            d.setGross(dec(j, "grossSalary"));
            d.setTax(dec(j, "taxDeduction"));
            d.setNet(dec(j, "actualSalary"));
            if (created) {
                detailMapper.insert(d);
            } else {
                detailMapper.updateById(d);
            }
            ok++;
        }
        return ok;
    }

    // ==================== 店长/人事补丁（MANAGER/HR） ====================

    /** 店长/人事补丁行 → 既有明细字段追加（找不到明细行则跳过，对齐老 updateExisting） */
    private int patchSection(String period, List<NormalizedRecordDTO> rows,
                             Map<String, EmployeeMainDataDTO> empMap,
                             java.util.function.BiConsumer<PayrollDetail, Map<String, String>> patcher) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (NormalizedRecordDTO record : rows) {
            EmployeeMainDataDTO emp = empMap.get(record.getEmployeeCode());
            if (emp == null || emp.getEmployeeId() == null) {
                continue;
            }
            PayrollDetail d = findDetail(period, emp.getEmployeeId());
            if (d == null) {
                log.warn("[历史工资消费] 补丁行未找到工资明细，跳过：工号={}, period={}",
                    record.getEmployeeCode(), period);
                continue;
            }
            patcher.accept(d, json(record));
            detailMapper.updateById(d);
            ok++;
        }
        return ok;
    }

    /** 店长工资补丁字段（团队计薪，对齐老 processManagerSheet） */
    private void applyManagerRow(PayrollDetail d, Map<String, String> j) {
        d.setDeptNewSignTotal(dec(j, "teamNewSignAmount"));
        d.setDeptEmployerSocialTotal(dec(j, "socialDeduction"));
        d.setTeamRate(dec(j, "ratio"));
        d.setTeamIncome(dec(j, "teamBonus"));
        d.setPersonalNewsignIncome(dec(j, "personalBonus"));
        d.setMinSalary(dec(j, "guaranteedSalary"));
        d.setGuaranteeFill(dec(j, "makeup8000"));
        d.setOtherDeduct(dec(j, "otherDeduction"));
    }

    /**
     * 人事数据补丁字段（社保/公积金/宿舍/积分等扣款，对齐老 processHrSheet）。
     * 培训扣款（trainingDeduction）无对应明细列，仅随导入域 extraJson 留痕。
     */
    private void applyHrRow(PayrollDetail d, Map<String, String> j) {
        d.setSocialFee(dec(j, "socialFee"));
        d.setHousingFund(dec(j, "housingFundFee"));
        d.setDormitoryFee(dec(j, "dormitoryFee"));
        d.setPointsFee(dec(j, "pointsFee"));
        d.setBonus(dec(j, "newcomerPerf"));
        d.setMentorBonus(dec(j, "mentorFee"));
        d.setOtherDeduct(dec(j, "otherDeduction"));
    }

    // ==================== 算薪事实推导 ====================

    /**
     * 开关 + 金额成对推导（对齐老 collectSalaryFacts/putBoolFee）：
     * 金额非空且非 0 → true + 金额（绝对值）；否则仅 false。
     * 工资表（isPayroll）多推导商保 + LEVEL（职级列）；人事补丁无职级/商保列。
     */
    private void collectFacts(List<NormalizedRecordDTO> rows, Map<String, EmployeeMainDataDTO> empMap,
                              List<SalaryFactSyncDTO> out, boolean isPayroll) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (NormalizedRecordDTO record : rows) {
            EmployeeMainDataDTO emp = empMap.get(record.getEmployeeCode());
            if (emp == null || emp.getEmployeeId() == null) {
                continue;
            }
            Map<String, String> j = json(record);
            if (isPayroll) {
                String level = text(j, "positionLevel");
                if (level != null && LEVEL_CODE_RE.matcher(level.trim()).matches()) {
                    out.add(new SalaryFactSyncDTO(emp.getEmployeeId(), "LEVEL", level.trim()));
                }
            }
            putBoolFee(out, emp.getEmployeeId(), "SOCIAL", "SOCIAL_FEE", dec(j, "socialFee"));
            putBoolFee(out, emp.getEmployeeId(), "HOUSING", "HOUSING_FUND", dec(j, "housingFundFee"));
            putBoolFee(out, emp.getEmployeeId(), "DORMITORY", "DORMITORY_FEE", dec(j, "dormitoryFee"));
            if (isPayroll) {
                putBoolFee(out, emp.getEmployeeId(), "COMMERCIAL", "COMMERCIAL_FEE",
                    dec(j, "commercialInsurance"));
            }
        }
    }

    /** 开关 + 金额成对推导：金额非空且非 0 → true 并写金额（绝对值）；否则仅 false。 */
    private void putBoolFee(List<SalaryFactSyncDTO> out, Long employeeId,
                            String boolType, String feeType, BigDecimal amount) {
        boolean on = amount != null && amount.signum() != 0;
        out.add(new SalaryFactSyncDTO(employeeId, boolType, Boolean.toString(on)));
        if (on) {
            out.add(new SalaryFactSyncDTO(employeeId, feeType, amount.abs().stripTrailingZeros().toPlainString()));
        }
    }

    // ==================== 工具方法 ====================

    /** 按期间清理历史工资批次（明细 + 批次，语义同老撤销清理第②段） */
    private void clearPeriod(String period) {
        int details = detailMapper.delete(new LambdaQueryWrapper<PayrollDetail>()
            .eq(PayrollDetail::getPeriod, period));
        int batches = batchMapper.delete(new LambdaQueryWrapper<PayrollBatch>()
            .eq(PayrollBatch::getPeriod, period));
        log.info("[历史工资消费] 期间重建清理：period={}, 删明细={}, 删批次={}", period, details, batches);
    }

    private PayrollDetail findDetail(String period, Long employeeId) {
        List<PayrollDetail> list = detailMapper.selectList(new LambdaQueryWrapper<PayrollDetail>()
            .eq(PayrollDetail::getPeriod, period)
            .eq(PayrollDetail::getEmployeeId, employeeId));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 批量解析工资族记录的工号 → 员工主数据 */
    private Map<String, EmployeeMainDataDTO> loadEmployees(Map<String, List<NormalizedRecordDTO>> byKind) {
        var codes = new java.util.HashSet<String>();
        for (List<NormalizedRecordDTO> rows : byKind.values()) {
            for (NormalizedRecordDTO r : rows) {
                if (StringUtils.isNotBlank(r.getEmployeeCode())) {
                    codes.add(r.getEmployeeCode().trim());
                }
            }
        }
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, EmployeeMainDataDTO> map = new HashMap<>();
        for (EmployeeMainDataDTO dto : employeeQueryPort.listByCodes(codes).values()) {
            if (dto.getEmployeeCode() != null) {
                map.put(dto.getEmployeeCode(), dto);
            }
        }
        return map;
    }

    /** 职位文本 → 员工角色（对齐老 roleFromPosition） */
    private EmployeeRole roleFromPosition(String position) {
        if (position == null) {
            return null;
        }
        if (position.contains("经纪人")) {
            return EmployeeRole.AGENT;
        }
        if (position.contains("店长")) {
            return EmployeeRole.MANAGER;
        }
        if (position.contains("总监")) {
            return EmployeeRole.DIRECTOR;
        }
        return null;
    }

    /** extraJson → Map；解析失败返回空 Map */
    private Map<String, String> json(NormalizedRecordDTO record) {
        if (StringUtils.isBlank(record.getExtJson())) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(record.getExtJson(), new TypeReference<>() {
            });
        } catch (JacksonException e) {
            log.warn("[历史工资消费] extraJson 解析失败：recordId={}", record.getId());
            return Map.of();
        }
    }

    /** 从 extraJson 取 sheetKind（WAGE/DIRECTOR/MANAGER/HR…） */
    private String sheetKindOf(String extJson) {
        if (StringUtils.isBlank(extJson)) {
            return null;
        }
        try {
            Map<String, String> j = objectMapper.readValue(extJson, new TypeReference<>() {
            });
            return j.get("sheetKind");
        } catch (JacksonException e) {
            return null;
        }
    }

    /** 宽容数值解析：历史列全 STRING（脏数据容忍），空/非法返回 null */
    private BigDecimal dec(Map<String, String> j, String field) {
        String s = j.get(field);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(Map<String, String> j, String field) {
        String s = j.get(field);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal round2(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
