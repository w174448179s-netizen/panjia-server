package com.panjia.payroll.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.panjia.contracts.port.ConversionFactorPort;
import com.panjia.payroll.domain.BatchStatus;
import com.panjia.payroll.domain.EmployeeRole;
import com.panjia.payroll.domain.PayrollBatch;
import com.panjia.payroll.domain.PayrollDetail;
import com.panjia.payroll.mapper.PayrollBatchMapper;
import com.panjia.payroll.mapper.PayrollDetailMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 历史工资 Excel 导入脚本。
 * <p>
 * 通过 Spring Boot 启动参数 {@code --import-history-payroll=2026-07} 触发（period 取该参数值）。
 * 读取天街工资表（7 个 sheet），回写所有业务表：
 * <ul>
 *   <li>工资表 → pj_payroll_batch（一条）+ pj_payroll_detail（逐行，经纪人/店长）</li>
 *   <li>店长工资 / 总监工资 / 人事数据 / 绩效和扣款 → 按 姓名+period 追加到既有 PayrollDetail</li>
 *   <li>新签业绩 / 结佣业绩 → pj_normalized_record + pj_perf_fact（PERF_EXPECT / PERF_REAL）</li>
 * </ul>
 * <p>
 * 关键决策：员工/部门匹配失败跳过该行并记日志，不中断；不创建 CommissionApplication，只写 perf_fact；
 * 幂等：开始前检查 period 是否已存在工资批次，存在则跳过。
 * <p>
 * 跨模块表（pj_perf_fact / pj_normalized_record / pj_import_batch / pj_people_employee / sys_dept）
 * 在本模块无实体类依赖，统一用 {@link JdbcTemplate} 直接执行 SQL。
 *
 * @author panjia
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryPayrollImporter {

    private static final DataFormatter DF = new DataFormatter();
    private static final Pattern DATE_RE = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");
    /** 未匹配员工缓存哨兵（避免对同一姓名重复查库）。 */
    private static final EmpMatch NULL_MATCH = new EmpMatch(-1L, null);
    /** sys_dept 未匹配哨兵。 */
    private static final Long DEPT_NOT_FOUND = -999999L;

    private final PayrollBatchMapper batchMapper;
    private final PayrollDetailMapper detailMapper;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ConversionFactorPort conversionFactorPort;

    private final Map<String, EmpMatch> empCache = new HashMap<>();
    private final Map<String, Long> deptCache = new HashMap<>();

    /** 工资表 sheet 处理后回填，供总监 sheet 新建明细时使用。 */
    private Long currentBatchId;

    /** 本次运行的告警收集（员工未匹配/日期解析失败等跳过行），入口处重置。 */
    private List<String> warnings = new ArrayList<>();

    // ==================== 入口 ====================

    /** 导入结果（摘要 + 告警清单 + 是否成功），供导入域批次记录问题清单与状态。 */
    public record ImportOutcome(String summary, List<String> warnings, boolean success) {
    }

    /**
     * 兼容旧调用（CLI 兜底入口）：自带批次、告警仅落日志。
     */
    public String importFromStream(String period, InputStream excelStream) {
        List<String> warnings = new ArrayList<>();
        ImportOutcome outcome = importFromStream(period, excelStream, null, warnings);
        for (String w : warnings) {
            log.warn("[历史工资导入][告警] {}", w);
        }
        return outcome.summary();
    }

    /**
     * 从上传的 Excel 文件流导入历史工资数据。
     *
     * @param period          工资归属月（如 2026-07）
     * @param excelStream     xlsx 文件输入流
     * @param externalBatchId 导入域批次 ID（经 /import/upload 上传时传入，业绩事实/归一化记录/实收单挂该批次；
     *                        null 时走 CLI 兜底自建 HIST-PAYROLL 批次）
     * @param warningSink     告警收集器（员工未匹配/日期解析失败等跳过行），由调用方传入
     */
    public ImportOutcome importFromStream(String period, InputStream excelStream,
                                          Long externalBatchId, List<String> warningSink) {
        this.warnings = warningSink != null ? warningSink : new ArrayList<>();
        this.warnings.clear();
        if (period == null || period.isBlank()) {
            return new ImportOutcome("失败：period 不能为空", this.warnings, false);
        }
        log.info("[历史工资导入] 开始：period={}, importBatchId={}", period, externalBatchId);
        try (XSSFWorkbook wb = new XSSFWorkbook(excelStream)) {
            // 幂等（分段）：工资段按 period 是否已有批次判断，业绩段按是否已有 source='IMPORT' 事实判断。
            // 场景：首次导入在业绩段失败（如 SQL 异常）后，重试只需补写业绩段，不重复写工资段。
            Long exist = batchMapper.selectCount(new LambdaQueryWrapper<PayrollBatch>()
                .eq(PayrollBatch::getPeriod, period));
            boolean payrollDone = exist != null && exist > 0;
            long payrollBatchId;
            if (payrollDone) {
                PayrollBatch prev = batchMapper.selectList(new LambdaQueryWrapper<PayrollBatch>()
                    .eq(PayrollBatch::getPeriod, period)
                    .last("LIMIT 1")).get(0);
                payrollBatchId = prev.getId();
                this.currentBatchId = prev.getId();
                log.warn("[历史工资导入] period={} 已存在工资批次 {}，跳过工资段", period, payrollBatchId);
            } else {
                // 1. 工资表：建批次 + 经纪人/店长明细
                PayrollBatch batch = processPayrollSheet(period, wb.getSheet("工资表"));
                if (batch == null) {
                    log.error("[历史工资导入] 工资表处理失败或无有效数据，终止");
                    return new ImportOutcome("失败：工资表处理失败或无有效数据", this.warnings, false);
                }
                payrollBatchId = batch.getId();
                this.currentBatchId = batch.getId();

                // 2. 总监工资：总监不在工资表中，此处按需新建/补写明细
                processDirectorSheet(period, wb.getSheet("总监工资"));
                // 3. 店长工资：补写店长团队计薪字段
                processManagerSheet(period, wb.getSheet("店长工资"));
                // 4. 人事数据：补写社保/公积金/宿舍/新人绩效等
                processHrSheet(period, wb.getSheet("人事数据"));
                // 5. 绩效和扣款：补写积分扣款/绩效等级/扣点合计
                processPerfDeductSheet(period, wb.getSheet("绩效和扣款"));
            }

            // 4b. 考勤汇总 → pj_people_attendance（独立幂等：已写入则跳过）
            Integer attCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pj_people_attendance WHERE attend_month = ? AND data_source = 'IMPORT'",
                Integer.class, LocalDate.parse(period + "-01"));
            if (attCount != null && attCount > 0) {
                log.warn("[历史工资导入] period={} 已存在考勤汇总 {} 条，跳过考勤段", period, attCount);
            } else {
                processHrAttendanceOnly(period, wb.getSheet("人事数据"));
            }

            // 5b. 绩效积分 → pj_people_performance_score（独立幂等：已写入则跳过）
            Integer scoreCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pj_people_performance_score WHERE score_month = ? AND data_source = 'IMPORT'",
                Integer.class, LocalDate.parse(period + "-01"));
            if (scoreCount != null && scoreCount > 0) {
                log.warn("[历史工资导入] period={} 已存在积分汇总 {} 条，跳过分段", period, scoreCount);
            } else {
                processPerfDeductScoreOnly(period, wb.getSheet("绩效和扣款"), wb.getSheet("工资表"));
            }

            // 5c. 员工算薪事实 → pj_people_salary_fact（独立幂等：有差异补当月事实）
            processSalaryFactSegment(period, wb.getSheet("工资表"), wb.getSheet("人事数据"));

            // 5d. 审批单补齐：考勤/积分审批单置 APPROVED（算薪卡点放行 + 期间锁定不可修改）
            ensureApprovalBackfill(period);

            // 6/7. 新签业绩 + 结佣业绩 → pj_normalized_record + pj_perf_fact（业绩段幂等：已写入则跳过）
            Integer factCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pj_perf_fact WHERE period = ? AND source = 'IMPORT'",
                Integer.class, period);
            int newsign = 0;
            int real = 0;
            // 导入域批次：直接挂引擎批次，并把此前 CLI 批次（HIST-PAYROLL-*）写入的数据迁绑过来，
            // 保证导入域撤销按新批次级联生效；CLI 兜底路径保持自建批次
            long importBatchId;
            if (externalBatchId != null) {
                importBatchId = externalBatchId;
                List<Long> histBatchIds = jdbc.queryForList(
                    "SELECT id FROM pj_import_batch WHERE batch_no = ?", Long.class, "HIST-PAYROLL-" + period);
                if (!histBatchIds.isEmpty() && histBatchIds.get(0) != externalBatchId) {
                    Long oldBatchId = histBatchIds.get(0);
                    jdbc.update("UPDATE pj_perf_fact SET batch_id = ? WHERE batch_id = ?", externalBatchId, oldBatchId);
                    jdbc.update("UPDATE pj_normalized_record SET batch_id = ? WHERE batch_id = ?", externalBatchId, oldBatchId);
                    jdbc.update("UPDATE pj_perf_received_apply SET batch_id = ? WHERE batch_id = ?", externalBatchId, oldBatchId);
                }
            } else {
                importBatchId = ensureImportBatch(period);
            }
            if (factCount != null && factCount > 0) {
                log.warn("[历史工资导入] period={} 已存在导入业绩事实 {} 条，跳过业绩段", period, factCount);
            } else {
                newsign = processPerfSheet(period, wb.getSheet("新签业绩"), "PERF_EXPECT", importBatchId);
                real = processPerfSheet(period, wb.getSheet("结佣业绩"), "PERF_REAL", importBatchId);
            }

            // 8. 实收审批单：IMPORT 实收事实按订单号建 APPROVED 单并回填 received_apply_id（幂等：已有单则合并绑定）
            int receivedApplies = ensureReceivedApplies(period, importBatchId);

            // 9. 结佣申请单：IMPORT 实收事实按合同建 LOCKED 申请单 + APPROVED 明细（幂等：已有明细的事实跳过）
            int commissionApplies = ensureCommissionApplications(period, importBatchId);

            String summary = String.format(
                "完成：period=%s, 工资批次=%d（%s）, 新签业绩=%d, 结佣业绩=%d, 实收审批单=%d, 结佣申请单=%d",
                period, payrollBatchId, payrollDone ? "已存在跳过" : "本次新建", newsign, real,
                receivedApplies, commissionApplies);
            log.info("[历史工资导入] {}", summary);
            return new ImportOutcome(summary, this.warnings, true);
        } catch (Exception e) {
            log.error("[历史工资导入] 异常：period={}", period, e);
            return new ImportOutcome("异常：" + e.getMessage(), this.warnings, false);
        }
    }

    // ==================== 1. 工资表 ====================

    /**
     * 工资表（28 列）：表头在第 1 行，数据从第 2 行起。
     * 创建工资批次 + 逐行 PayrollDetail（经纪人/店长；总监不在本表）。
     */
    private PayrollBatch processPayrollSheet(String period, Sheet sheet) {
        if (sheet == null) {
            log.warn("[工资表] sheet 不存在");
            return null;
        }
        List<PayrollDetail> built = new ArrayList<>();
        int skip = 0;
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String name = str(cell(row, 1));
            if (isBlank(name)) {
                continue;
            }
            EmpMatch emp = findEmployee(name);
            if (emp == null) {
                skip++;
                continue;
            }
            PayrollDetail d = new PayrollDetail();
            d.setEmployeeId(emp.employeeId());
            d.setDeptId(emp.deptId() != null ? emp.deptId() : resolveDeptId(str(cell(row, 0))));
            d.setLevelCode(str(cell(row, 2)));                       // 职级
            d.setEmployeeRole(roleFromPosition(str(cell(row, 3))));   // 职位
            d.setNewSignPerformance(num(cell(row, 4)));
            d.setNewSignRate(num(cell(row, 5)));
            d.setPerfDeduct(num(cell(row, 6)));
            d.setMentorBonus(num(cell(row, 12)));                    // 招聘奖励
            d.setFinalRate(num(cell(row, 8)));
            d.setCommissionPerformance(num(cell(row, 9)));
            d.setCommissionIncome(num(cell(row, 11)));
            d.setBaseSalary(num(cell(row, 13)));
            d.setBonus(num(cell(row, 14)));
            d.setAttendanceFee(num(cell(row, 15)));
            d.setPointsFee(num(cell(row, 16)));
            d.setSocialFee(num(cell(row, 18)));
            d.setHousingFund(num(cell(row, 19)));
            d.setNegativeCarryover(num(cell(row, 20)));
            d.setCommercialInsurance(num(cell(row, 21)));
            d.setDormitoryFee(num(cell(row, 22)));
            BigDecimal g23 = num(cell(row, 23));                     // 工资合计
            d.setGross(g23 != null ? g23 : num(cell(row, 17)));
            BigDecimal n26 = num(cell(row, 26));                     // 最终发放
            d.setNet(n26 != null ? n26 : num(cell(row, 24)));
            d.setTax(num(cell(row, 25)));
            built.add(d);
        }

        if (built.isEmpty()) {
            log.warn("[工资表] 无有效数据行（匹配失败 {} 行）", skip);
            return null;
        }

        PayrollBatch batch = new PayrollBatch();
        batch.setPeriod(period);
        batch.setDeptScope("ALL");
        batch.setStatus(BatchStatus.PAID);
        batch.setEmployeeCount(built.size());
        batch.setGrossTotal(BigDecimal.ZERO);
        batch.setDeductTotal(BigDecimal.ZERO);
        batch.setTaxTotal(BigDecimal.ZERO);
        batch.setNetTotal(BigDecimal.ZERO);
        batch.setAttempt(0);
        batchMapper.insert(batch);

        int inserted = 0;
        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal netTotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        for (PayrollDetail d : built) {
            d.setBatchId(batch.getId());
            d.setPeriod(period);
            try {
                detailMapper.insert(d);
                inserted++;
                if (d.getGross() != null) {
                    grossTotal = grossTotal.add(d.getGross());
                }
                if (d.getNet() != null) {
                    netTotal = netTotal.add(d.getNet());
                }
                if (d.getTax() != null) {
                    taxTotal = taxTotal.add(d.getTax());
                }
            } catch (Exception e) {
                log.warn("[工资表] 明细插入失败：employeeId={}, name={}", d.getEmployeeId(), e.getMessage());
            }
        }
        batch.setEmployeeCount(inserted);
        batch.setGrossTotal(round2(grossTotal));
        batch.setDeductTotal(BigDecimal.ZERO);
        batch.setTaxTotal(round2(taxTotal));
        batch.setNetTotal(round2(netTotal));
        batchMapper.updateById(batch);

        log.info("[工资表] 批次={} 人数={}（匹配失败跳过 {} 行）", batch.getId(), inserted, skip);
        return batch;
    }

    // ==================== 2. 总监工资 ====================

    /**
     * 总监工资（19 列）：表头在第 1 行，数据从第 2 行起。
     * 总监按门店分行，第一行有姓名+汇总，后续行只有门店。仅取有姓名的行。
     * 总监不在工资表中，此处按需新建明细（employeeRole=DIRECTOR）。
     */
    private void processDirectorSheet(String period, Sheet sheet) {
        if (sheet == null) {
            log.warn("[总监工资] sheet 不存在");
            return;
        }
        int ok = 0;
        int skip = 0;
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String name = str(cell(row, 0)); // 姓名
            if (isBlank(name)) {
                continue; // 后续门店分行，跳过
            }
            EmpMatch emp = findEmployee(name);
            if (emp == null) {
                skip++;
                continue;
            }
            Long deptId = emp.deptId() != null ? emp.deptId() : resolveDeptId(str(cell(row, 1)));
            upsertDirector(period, emp, deptId, d -> {
                setIf(num(cell(row, 2)), d::setDeptNewSignTotal);
                setIf(num(cell(row, 3)), d::setDeptEmployerSocialTotal);
                setIf(num(cell(row, 5)), d::setStoreRate);
                setIf(num(cell(row, 6)), d::setStoreIncome);
                setIf(num(cell(row, 7)), d::setBaseSalary);
                setIf(num(cell(row, 8)), d::setFullAttendance);
                setIf(num(cell(row, 9)), d::setBonus);
                setIf(num(cell(row, 10)), d::setCommissionPerformance);
                setIf(num(cell(row, 11)), d::setCommissionIncome);
                setIf(num(cell(row, 12)), d::setMentorBonus);
                setIf(num(cell(row, 13)), d::setSocialFee);
                setIf(num(cell(row, 14)), d::setHousingFund);
                setIf(num(cell(row, 15)), d::setCommercialInsurance);
                setIf(num(cell(row, 16)), d::setGross);
                setIf(num(cell(row, 17)), d::setTax);
                setIf(num(cell(row, 18)), d::setNet);
            });
            ok++;
        }
        log.info("[总监工资] 处理 {} 行（匹配失败跳过 {} 行）", ok, skip);
    }

    // ==================== 3. 店长工资 ====================

    /**
     * 店长工资（15 列）：第 1 行标题，第 2 行表头，数据从第 3 行起。
     * 按姓名匹配既有 PayrollDetail 追加团队计薪字段。
     */
    private void processManagerSheet(String period, Sheet sheet) {
        if (sheet == null) {
            log.warn("[店长工资] sheet 不存在");
            return;
        }
        int ok = 0;
        int skip = 0;
        for (int r = 2; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String name = str(cell(row, 1));
            if (isBlank(name)) {
                continue;
            }
            EmpMatch emp = findEmployee(name);
            if (emp == null) {
                skip++;
                continue;
            }
            boolean updated = updateExisting(period, emp, name, d -> {
                setIf(num(cell(row, 3)), d::setDeptNewSignTotal);
                setIf(num(cell(row, 4)), d::setDeptEmployerSocialTotal);
                setIf(num(cell(row, 7)), d::setTeamRate);
                setIf(num(cell(row, 8)), d::setTeamIncome);
                setIf(num(cell(row, 9)), d::setPersonalNewsignIncome);
                setIf(num(cell(row, 11)), d::setMinSalary);
                setIf(num(cell(row, 12)), d::setGuaranteeFill);
                setIf(num(cell(row, 13)), d::setOtherDeduct);
            });
            if (updated) {
                ok++;
            } else {
                skip++;
            }
        }
        log.info("[店长工资] 处理 {} 行（匹配失败跳过 {} 行）", ok, skip);
    }

    // ==================== 4. 人事数据 ====================

    /**
     * 人事数据（18 列）：表头在第 1 行，数据从第 2 行起。
     * 按姓名匹配既有 PayrollDetail 追加社保/公积金/宿舍/新人绩效等。
     * 考勤汇总由 {@link #processHrAttendanceOnly} 独立执行（幂等）。
     */
    private void processHrSheet(String period, Sheet sheet) {
        if (sheet == null) {
            log.warn("[人事数据] sheet 不存在");
            return;
        }
        int ok = 0;
        int skip = 0;
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String name = str(cell(row, 1));
            if (isBlank(name)) {
                continue;
            }
            EmpMatch emp = findEmployee(name);
            if (emp == null) {
                skip++;
                continue;
            }
            boolean updated = updateExisting(period, emp, name, d -> {
                setIf(num(cell(row, 10)), d::setSocialFee);
                setIf(num(cell(row, 11)), d::setHousingFund);
                setIf(num(cell(row, 12)), d::setDormitoryFee);
                setIf(num(cell(row, 13)), d::setPointsFee);
                setIf(num(cell(row, 14)), d::setBonus);            // 新人绩效
                setIf(num(cell(row, 15)), d::setNegativeCarryover);
                setIf(num(cell(row, 16)), d::setMentorBonus);       // 新人带教
                setIf(num(cell(row, 17)), d::setOtherDeduct);
            });
            if (updated) {
                ok++;
            } else {
                skip++;
            }
        }
        log.info("[人事数据] 处理 {} 行（匹配失败跳过 {} 行）", ok, skip);
    }

    /**
     * 考勤汇总独立段：从人事数据 sheet 取出勤天数（col5）+ 考勤详情（col9），
     * upsert 到 pj_people_attendance。与 {@link #processHrSheet} 解耦，
     * 允许工资段已写但考勤未写时单独补执行。
     */
    private void processHrAttendanceOnly(String period, Sheet sheet) {
        if (sheet == null) {
            return;
        }
        int attOk = 0;
        int attSkip = 0;
        LocalDate month = LocalDate.parse(period + "-01");
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String name = str(cell(row, 1));
            if (isBlank(name)) continue;
            EmpMatch emp = findEmployee(name);
            if (emp == null) { attSkip++; continue; }
            BigDecimal attendDays = num(cell(row, 5));
            String attendDetail = str(cell(row, 9));
            ParsedAttendance parsed = parseAttendanceDetail(attendDetail);
            try {
                jdbc.update(
                    "INSERT INTO pj_people_attendance (id, employee_id, attend_month, leave_days, absent_days, "
                        + "late_count, late_minutes, missing_card_count, attend_days, rest_days, "
                        + "data_source, remark, version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0) "
                        + "ON CONFLICT (employee_id, attend_month) DO UPDATE SET "
                        + "attend_days = EXCLUDED.attend_days, "
                        + "leave_days = EXCLUDED.leave_days, "
                        + "absent_days = EXCLUDED.absent_days, "
                        + "late_count = EXCLUDED.late_count, "
                        + "late_minutes = EXCLUDED.late_minutes, "
                        + "missing_card_count = EXCLUDED.missing_card_count, "
                        + "remark = EXCLUDED.remark, "
                        + "data_source = 'IMPORT', "
                        + "version = pj_people_attendance.version + 1",
                    IdWorker.getId(), emp.employeeId(), month,
                    parsed.leaveDays(), parsed.absentDays(), parsed.lateCount(),
                    parsed.lateMinutes(), parsed.missingCard(),
                    attendDays != null ? attendDays : BigDecimal.ZERO, null,
                    "IMPORT", attendDetail);
                attOk++;
            } catch (Exception e) {
                log.warn("[考勤] upsert 跳过：employeeId={}，{}", emp.employeeId(), e.getMessage());
                attSkip++;
            }
        }
        log.info("[考勤汇总] upsert {} 条（失败 {} 条）", attOk, attSkip);
    }

    /** 考勤详情文字解析结果（迟到分钟/缺卡/旷工在文字中通常缺失，默认 0）。 */
    private record ParsedAttendance(long lateCount, long lateMinutes, long missingCard,
                                    BigDecimal absentDays, BigDecimal leaveDays) {
    }

    /**
     * 从考勤详情文字还原结构化考勤字段。
     * <p>
     * 文字格式为「汇总：逐日明细」（如 {@code 迟到2次，事假1天：26/29号各迟到1次，30号事假1天}），
     * 只解析冒号前的汇总段，避免与逐日明细重复计数；无冒号则整串视为汇总
     * （如 {@code 出勤26天}/{@code 0}/{@code 兼职}，均无异常项，结果全 0）。
     * 可还原：迟到次数、请假天数（事假/病假/年假/调休/婚假/产假/丧假/陪产假/护理假）；
     * 文字中未记载的迟到分钟/缺卡次数/旷工天数恒为 0。
     */
    private ParsedAttendance parseAttendanceDetail(String detail) {
        long lateCount = 0;
        long lateMinutes = 0;
        long missingCard = 0;
        BigDecimal absentDays = BigDecimal.ZERO;
        BigDecimal leaveDays = BigDecimal.ZERO;
        if (detail != null && !detail.isBlank()) {
            String text = detail.trim();
            int idx = text.indexOf('：');
            if (idx < 0) {
                idx = text.indexOf(':');
            }
            String summary = idx >= 0 ? text.substring(0, idx) : text;
            lateCount = sumLong(LATE_COUNT_RE, summary);
            lateMinutes = sumLong(LATE_MINUTE_RE, summary);
            missingCard = sumLong(MISSING_CARD_RE, summary);
            absentDays = sumDecimal(ABSENT_RE, summary);
            leaveDays = sumDecimal(LEAVE_RE, summary);
        }
        return new ParsedAttendance(lateCount, lateMinutes, missingCard, absentDays, leaveDays);
    }

    /** 汇总正则全部匹配组为 long（无匹配返回 0）。 */
    private long sumLong(Pattern pattern, String text) {
        long sum = 0;
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            sum += Long.parseLong(m.group(1));
        }
        return sum;
    }

    /** 汇总正则全部匹配组为 BigDecimal（无匹配返回 0）。 */
    private BigDecimal sumDecimal(Pattern pattern, String text) {
        BigDecimal sum = BigDecimal.ZERO;
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            sum = sum.add(new BigDecimal(m.group(1)));
        }
        return sum;
    }

    // ==================== 5. 绩效和扣款 ====================

    /**
     * 绩效和扣款（18 列）：表头在第 1 行，数据从第 2 行起。左右两半各自一人：
     * 左半（姓名 col1）→ 积分考核=pointsFee、其他扣款=otherDeduct；
     * 右半（姓名 col9）→ 绩效等级=perfGrade、绩效提成点=perfDeduct、合计=totalDeduct。
     * 积分汇总由 {@link #processPerfDeductScoreOnly} 独立执行（幂等）。
     */
    private void processPerfDeductSheet(String period, Sheet sheet) {
        if (sheet == null) {
            log.warn("[绩效和扣款] sheet 不存在");
            return;
        }
        int ok = 0;
        int skip = 0;
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            // 左半
            String lname = str(cell(row, 1));
            if (!isBlank(lname)) {
                EmpMatch emp = findEmployee(lname);
                BigDecimal pointsFee = num(cell(row, 3));   // 积分考核
                BigDecimal otherDeduct = num(cell(row, 4)); // 其他扣款
                if (emp != null) {
                    if (updateExisting(period, emp, lname, d -> {
                            if (pointsFee != null) {
                                d.setPointsFee(pointsFee);
                            }
                            if (otherDeduct != null) {
                                d.setOtherDeduct(otherDeduct);
                            }
                        })) {
                        ok++;
                    } else {
                        skip++;
                    }
                } else {
                    skip++;
                }
            }
            // 右半
            String rname = str(cell(row, 9));
            if (!isBlank(rname)) {
                EmpMatch emp = findEmployee(rname);
                String grade = str(cell(row, 13));          // 绩效等级
                BigDecimal perfDeduct = num(cell(row, 14));  // 绩效提成点
                BigDecimal totalDeduct = num(cell(row, 17)); // 合计
                if (emp != null) {
                    if (updateExisting(period, emp, rname, d -> {
                            if (grade != null) {
                                d.setPerfGrade(grade);
                            }
                            if (perfDeduct != null) {
                                d.setPerfDeduct(perfDeduct);
                            }
                            if (totalDeduct != null) {
                                d.setTotalDeduct(totalDeduct);
                            }
                        })) {
                        ok++;
                    } else {
                        skip++;
                    }
                } else {
                    skip++;
                }
            }
        }
        log.info("[绩效和扣款] 处理 {} 半行（匹配失败跳过 {} 半行）", ok, skip);
    }

    /**
     * 绩效积分独立段：从绩效和扣款 sheet 右半取出（总积分 col10 + 出勤天数 col11），
     * upsert 到 pj_people_performance_score。与 {@link #processPerfDeductSheet} 解耦，
     * 允许工资段已写但积分未写时单独补执行。
     * <p>晚提交次数从工资表「积分扣款」列（col16）反推：扣减 = 次数 × 单价 5 元，
     * 故 次数 = 积分扣款金额 ÷ 5（HALF_UP 取整），使积分页面扣减项与工资表对上。
     */
    private void processPerfDeductScoreOnly(String period, Sheet sheet, Sheet payrollSheet) {
        if (sheet == null) return;
        // 工资表「积分扣款」（col16）按姓名建立映射（一人一行）
        Map<String, BigDecimal> pointsFeeByName = new LinkedHashMap<>();
        if (payrollSheet != null) {
            for (int r = 1; r <= payrollSheet.getLastRowNum(); r++) {
                Row row = payrollSheet.getRow(r);
                if (row == null) continue;
                String name = str(cell(row, 1));
                BigDecimal fee = num(cell(row, 16));
                if (!isBlank(name) && fee != null) {
                    pointsFeeByName.put(name.trim(), fee);
                }
            }
        }
        int scoreOk = 0;
        int scoreSkip = 0;
        LocalDate month = LocalDate.parse(period + "-01");
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String rname = str(cell(row, 9));
            if (isBlank(rname)) continue;
            EmpMatch emp = findEmployee(rname);
            if (emp == null) { scoreSkip++; continue; }
            BigDecimal totalPoints = num(cell(row, 10));
            Integer attendDays = intVal(cell(row, 11));
            if (totalPoints == null) continue; // 「不考核」行跳过
            // 晚提交次数 = 工资表积分扣款 ÷ 5 元/次（无该列数据为 0）
            BigDecimal pointsFee = pointsFeeByName.get(rname.trim());
            int lateCount = pointsFee == null ? 0
                : pointsFee.divide(new BigDecimal("5"), 0, RoundingMode.HALF_UP).intValue();
            try {
                jdbc.update(
                    "INSERT INTO pj_people_performance_score (id, employee_id, score_month, "
                        + "total_points, attend_days, late_submit_count, data_source, version) "
                        + "VALUES (?,?,?,?,?,?,?,0) "
                        + "ON CONFLICT (employee_id, score_month) DO UPDATE SET "
                        + "total_points = EXCLUDED.total_points, "
                        + "attend_days = EXCLUDED.attend_days, "
                        + "late_submit_count = EXCLUDED.late_submit_count, "
                        + "data_source = 'IMPORT', "
                        + "version = pj_people_performance_score.version + 1",
                    IdWorker.getId(), emp.employeeId(), month,
                    totalPoints, attendDays != null ? attendDays : 0, lateCount, "IMPORT");
                scoreOk++;
            } catch (Exception e) {
                log.warn("[积分] upsert 跳过：employeeId={}，{}", emp.employeeId(), e.getMessage());
                scoreSkip++;
            }
        }
        log.info("[绩效积分] upsert {} 条（失败 {} 条）", scoreOk, scoreSkip);
    }

    // ==================== 5c. 员工算薪事实 ====================

    /** 历史导入写入的算薪事实标识（pj_people_salary_fact.change_field），幂等段判断依据 */
    private static final String FACT_CHANGE_FIELD = "HIST_IMPORT";
    /** 职级编码格式（A0~A5/S1/S2），「总监」等非编码值不写 LEVEL 事实 */
    private static final Pattern LEVEL_CODE_RE = Pattern.compile("^[AS]\\d$");
    // ---- 考勤详情文字解析（冒号前为汇总段，冒号后为逐日明细，只解析汇总避免重复计数） ----
    private static final Pattern LATE_COUNT_RE = Pattern.compile("迟到(\\d+)次");
    private static final Pattern LATE_MINUTE_RE = Pattern.compile("迟到(\\d+)分钟");
    private static final Pattern MISSING_CARD_RE = Pattern.compile("缺卡(\\d+)次");
    private static final Pattern ABSENT_RE = Pattern.compile("旷工(\\d+(?:\\.\\d+)?)天");
    private static final Pattern LEAVE_RE =
        Pattern.compile("(?:事假|病假|年假|调休|婚假|产假|丧假|陪产假|护理假|请假)(\\d+(?:\\.\\d+)?)天");

    /**
     * 员工算薪事实独立段：从工资表/人事数据推导 LEVEL、社保/公积金/商保/宿舍的
     * 开关（SOCIAL/HOUSING/COMMERCIAL/DORMITORY）与金额（SOCIAL_FEE/HOUSING_FUND/
     * COMMERCIAL_FEE/DORMITORY_FEE，取 Excel 金额绝对值），与该月月初时点的既有事实链比对，
     * <b>有差异才插入</b>当月区间 [月初, 次月初) 的新事实（不闭合/不改既有事实链，
     * 不影响其他月份取数）。原则：按该月重算算薪时，上述取数与导入的工资表能对上。
     * <p>
     * 幂等：按 change_field='HIST_IMPORT' AND effective_date=月初 判断，已写入则整段跳过。
     * <p>
     * 差异口径：布尔事实缺省=false（引擎 parseBoolFact null→null→不扣），故既有为空且推导为
     * false 不算差异；金额事实按数值比较（忽略精度格式）；LEVEL 缺失或不同均算差异。
     */
    private void processSalaryFactSegment(String period, Sheet payrollSheet, Sheet hrSheet) {
        if (payrollSheet == null && hrSheet == null) {
            return;
        }
        LocalDate monthStart = LocalDate.parse(period + "-01");
        LocalDate monthEnd = monthStart.plusMonths(1);
        Integer done = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pj_people_salary_fact WHERE change_field = ? AND effective_date = ?",
            Integer.class, FACT_CHANGE_FIELD, monthStart);
        if (done != null && done > 0) {
            log.warn("[算薪事实] period={} 已存在导入事实 {} 条，跳过", period, done);
            return;
        }
        // 汇总每个员工的推导事实：工资表在前（含商保），人事数据在后覆盖（列口径同源）
        Map<Long, Map<String, String>> derived = new LinkedHashMap<>();
        collectSalaryFacts(payrollSheet, derived, true);
        collectSalaryFacts(hrSheet, derived, false);
        int ins = 0;
        int same = 0;
        int fail = 0;
        for (Map.Entry<Long, Map<String, String>> e : derived.entrySet()) {
            long employeeId = e.getKey();
            for (Map.Entry<String, String> f : e.getValue().entrySet()) {
                String type = f.getKey();
                String value = f.getValue();
                String current = queryFactValueAt(employeeId, type, monthStart);
                if (isFactSame(value, current)) {
                    same++;
                    continue;
                }
                try {
                    jdbc.update(
                        "INSERT INTO pj_people_salary_fact (fact_id, employee_id, fact_type, value, "
                            + "effective_date, expire_date, change_field) VALUES (?,?,?,?,?,?,?)",
                        IdWorker.getId(), employeeId, type, value, monthStart, monthEnd, FACT_CHANGE_FIELD);
                    ins++;
                } catch (Exception ex) {
                    fail++;
                    log.warn("[算薪事实] 插入失败跳过：employeeId={}, type={}，{}", employeeId, type, ex.getMessage());
                }
            }
        }
        log.info("[算薪事实] 插入 {} 条（与既有一致跳过 {}，失败 {}）", ins, same, fail);
    }

    /**
     * 从单个 sheet 收集推导事实。isPayroll=true 走工资表列位（社保18/公积金19/商保21/宿舍22），
     * 否则走人事数据列位（社保10/公积金11/宿舍12，无商保）。职级列两表同为 col2。
     */
    private void collectSalaryFacts(Sheet sheet, Map<Long, Map<String, String>> out, boolean isPayroll) {
        if (sheet == null) {
            return;
        }
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String name = str(cell(row, 1));
            if (isBlank(name)) {
                continue;
            }
            EmpMatch emp = findEmployee(name);
            if (emp == null) {
                continue;
            }
            Map<String, String> facts = out.computeIfAbsent(emp.employeeId(), k -> new LinkedHashMap<>());
            String level = str(cell(row, 2));
            if (level != null && LEVEL_CODE_RE.matcher(level.trim()).matches()) {
                facts.put("LEVEL", level.trim());
            }
            putBoolFee(facts, "SOCIAL", "SOCIAL_FEE", num(cell(row, isPayroll ? 18 : 10)));
            putBoolFee(facts, "HOUSING", "HOUSING_FUND", num(cell(row, isPayroll ? 19 : 11)));
            putBoolFee(facts, "DORMITORY", "DORMITORY_FEE", num(cell(row, isPayroll ? 22 : 12)));
            if (isPayroll) {
                putBoolFee(facts, "COMMERCIAL", "COMMERCIAL_FEE", num(cell(row, 21)));
            }
        }
    }

    /** 开关 + 金额成对推导：金额非空且非 0 → true 并写金额（绝对值，引擎按正数扣款）；否则仅 false。 */
    private void putBoolFee(Map<String, String> facts, String boolType, String feeType, BigDecimal amount) {
        boolean on = amount != null && amount.signum() != 0;
        facts.put(boolType, Boolean.toString(on));
        if (on) {
            facts.put(feeType, amount.abs().stripTrailingZeros().toPlainString());
        }
    }

    /** 查员工某类事实在指定时点的最新值（闭开区间，与 SalaryFactMapper.selectValueAt 同口径）。 */
    private String queryFactValueAt(long employeeId, String type, LocalDate point) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT value FROM pj_people_salary_fact WHERE employee_id = ? AND fact_type = ? "
                + "AND effective_date <= ? AND (expire_date IS NULL OR ? < expire_date) "
                + "ORDER BY effective_date DESC, create_time DESC LIMIT 1",
            employeeId, type, point, point);
        Object v = rows.isEmpty() ? null : rows.get(0).get("value");
        return v == null ? null : v.toString();
    }

    /** 差异判断：布尔与引擎缺省对齐（既有为空 + 推导 false = 无差异）；金额按数值比较。 */
    private boolean isFactSame(String value, String current) {
        if (current == null || current.isBlank()) {
            return "false".equals(value);
        }
        String cur = current.trim();
        if ("true".equals(value) || "false".equals(value)) {
            return value.equals(cur);
        }
        try {
            return new BigDecimal(cur).compareTo(new BigDecimal(value)) == 0;
        } catch (NumberFormatException e) {
            return value.equals(cur);
        }
    }

    // ==================== 5d. 考勤/积分审批单补齐 ====================

    /**
     * 审批单补齐段：为该期间补 APPROVED 状态的考勤审批单 + 积分审批单。
     * <p>
     * 目的：① 算薪卡点放行——创建薪酬批次要求两单均 APPROVED（无数据期间不卡）；
     * ② 期间锁定——APPROVED 后考勤/积分禁止手工改数，保证历史回填数据不被篡改。
     * 快照 JSON 与正常提交（人事提交→总监审批）同构，详情页可正常展示审阅内容。
     * <p>
     * 幂等/收敛：一期一审一行。已存在 APPROVED 跳过；已存在其他状态（DRAFT/SUBMITTED/REJECTED，
     * 历史期间不应出现）更新为 APPROVED 并重写快照；不存在则插入。
     * processInstanceId/submitBy/approveBy 置空——历史补录无流程实例与操作人。
     */
    private void ensureApprovalBackfill(String period) {
        ensureAttendanceApproval(period);
        ensureScoreApproval(period);
    }

    /** 补齐/收敛考勤审批单为 APPROVED（一期一审；不存在插入，存在非 APPROVED 更新）。 */
    private void ensureAttendanceApproval(String period) {
        String snapshot = buildAttendanceSnapshotJson(period);
        List<Map<String, Object>> exist = jdbc.queryForList(
            "SELECT id, status FROM pj_people_attendance_approval WHERE period = ? LIMIT 1", period);
        if (exist.isEmpty()) {
            jdbc.update(
                "INSERT INTO pj_people_attendance_approval (id, period, status, submit_time, approve_time, "
                    + "reject_reason, process_instance_id, snapshot, version, create_time, update_time) "
                    + "VALUES (?,?,?,?,?,NULL,NULL,?,0,now(),now())",
                IdWorker.getId(), period, "APPROVED", LocalDateTime.now(), LocalDateTime.now(), snapshot);
            log.info("[考勤审批补齐] period={} 新增 APPROVED 审批单", period);
            return;
        }
        long id = asLong(exist.get(0).get("id"));
        String status = exist.get(0).get("status") == null ? null : exist.get(0).get("status").toString();
        if ("APPROVED".equals(status)) {
            log.info("[考勤审批补齐] period={} 已为 APPROVED，跳过", period);
            return;
        }
        jdbc.update(
            "UPDATE pj_people_attendance_approval SET status = 'APPROVED', approve_time = now(), "
                + "snapshot = ?, version = version + 1, update_time = now() WHERE id = ?",
            snapshot, id);
        log.info("[考勤审批补齐] period={} 审批单 id={} 由 {} 收敛为 APPROVED", period, id, status);
    }

    /** 补齐/收敛积分审批单为 APPROVED（一期一审；不存在插入，存在非 APPROVED 更新）。 */
    private void ensureScoreApproval(String period) {
        String snapshot = buildScoreSnapshotJson(period);
        List<Map<String, Object>> exist = jdbc.queryForList(
            "SELECT id, status FROM pj_people_score_approval WHERE period = ? LIMIT 1", period);
        if (exist.isEmpty()) {
            jdbc.update(
                "INSERT INTO pj_people_score_approval (id, period, status, submit_time, approve_time, "
                    + "reject_reason, process_instance_id, snapshot, version, create_time, update_time) "
                    + "VALUES (?,?,?,?,?,NULL,NULL,?,0,now(),now())",
                IdWorker.getId(), period, "APPROVED", LocalDateTime.now(), LocalDateTime.now(), snapshot);
            log.info("[积分审批补齐] period={} 新增 APPROVED 审批单", period);
            return;
        }
        long id = asLong(exist.get(0).get("id"));
        String status = exist.get(0).get("status") == null ? null : exist.get(0).get("status").toString();
        if ("APPROVED".equals(status)) {
            log.info("[积分审批补齐] period={} 已为 APPROVED，跳过", period);
            return;
        }
        jdbc.update(
            "UPDATE pj_people_score_approval SET status = 'APPROVED', approve_time = now(), "
                + "snapshot = ?, version = version + 1, update_time = now() WHERE id = ?",
            snapshot, id);
        log.info("[积分审批补齐] period={} 审批单 id={} 由 {} 收敛为 APPROVED", period, id, status);
    }

    /**
     * 构建考勤审批快照 JSON（口径对齐 AttendanceApprovalServiceImpl.buildSnapshotJson）：
     * 全量行数 + 异常行（迟到次数/迟到分钟/缺卡次数/旷工天数/请假天数 任一 &gt; 0）。
     */
    private String buildAttendanceSnapshotJson(String period) {
        LocalDate monthStart = LocalDate.parse(period + "-01");
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT a.employee_id, a.late_count, a.late_minutes, a.missing_card_count, a.absent_days, "
                + "a.leave_days, a.remark, e.employee_code, e.employee_name "
                + "FROM pj_people_attendance a LEFT JOIN pj_people_employee e ON e.employee_id = a.employee_id "
                + "WHERE a.attend_month = ?", monthStart);
        List<Map<String, Object>> abnormal = new ArrayList<>();
        BigDecimal abnormalLeaveDays = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            boolean hit = dec(r.get("late_count")).signum() > 0 || dec(r.get("late_minutes")).signum() > 0
                || dec(r.get("missing_card_count")).signum() > 0
                || dec(r.get("absent_days")).signum() > 0 || dec(r.get("leave_days")).signum() > 0;
            if (!hit) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employeeId", asLong(r.get("employee_id")));
            row.put("employeeCode", r.get("employee_code"));
            row.put("employeeName", r.get("employee_name"));
            row.put("attendMonth", monthStart.toString());
            row.put("lateCount", dec(r.get("late_count")));
            row.put("lateMinutes", dec(r.get("late_minutes")));
            row.put("missingCardCount", dec(r.get("missing_card_count")));
            row.put("absentDays", dec(r.get("absent_days")));
            row.put("leaveDays", dec(r.get("leave_days")));
            row.put("remark", r.get("remark"));
            abnormal.add(row);
            abnormalLeaveDays = abnormalLeaveDays.add(dec(r.get("leave_days")));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("totalCount", rows.size());
        snapshot.put("abnormalCount", abnormal.size());
        snapshot.put("abnormalLeaveDays", abnormalLeaveDays);
        snapshot.put("rows", abnormal);
        return writeJson(snapshot);
    }

    /**
     * 构建积分审批快照 JSON（口径对齐 ScoreApprovalServiceImpl.buildSnapshotJson）：
     * 等级计数（平均分 = 总积分/出勤天数，≥A门槛 A / ≥B门槛 B / 否则 C，出勤 0 天免审），
     * B/C 扣点行 + 晚提交扣款行。等级阈值/扣点取 pj_payroll_policy_rule GLOBAL 的
     * rule_content.points，缺失时兜底默认口径（与 ScoreGradePolicy 一致）。
     */
    private String buildScoreSnapshotJson(String period) {
        LocalDate monthStart = LocalDate.parse(period + "-01");
        ScoreRule rule = loadScoreRule();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.employee_id, s.total_points, s.attend_days, s.late_submit_count, "
                + "e.employee_code, e.employee_name "
                + "FROM pj_people_performance_score s LEFT JOIN pj_people_employee e ON e.employee_id = s.employee_id "
                + "WHERE s.score_month = ?", monthStart);
        long gradeA = 0;
        long gradeB = 0;
        long gradeC = 0;
        List<Map<String, Object>> deductRows = new ArrayList<>();
        List<Map<String, Object>> lateRows = new ArrayList<>();
        int lateTotalCount = 0;
        BigDecimal lateTotalFee = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            BigDecimal totalPoints = dec(r.get("total_points"));
            int attendDays = r.get("attend_days") == null ? 0 : dec(r.get("attend_days")).intValue();
            BigDecimal avg = (totalPoints == null || attendDays <= 0) ? null
                : totalPoints.divide(BigDecimal.valueOf(attendDays), 4, RoundingMode.HALF_UP);
            String grade = avg == null ? null : resolveGrade(rule, avg);
            switch (grade == null ? "" : grade) {
                case "A" -> gradeA++;
                case "B" -> gradeB++;
                case "C" -> gradeC++;
                default -> { }
            }
            if ("B".equals(grade) || "C".equals(grade)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("employeeId", asLong(r.get("employee_id")));
                row.put("employeeCode", r.get("employee_code"));
                row.put("employeeName", r.get("employee_name"));
                row.put("scoreMonth", monthStart.toString());
                row.put("totalPoints", totalPoints);
                row.put("attendDays", attendDays);
                row.put("avgPoints", avg);
                row.put("grade", grade);
                row.put("deductRate", deductOf(rule, grade));
                int lateCount = r.get("late_submit_count") == null ? 0 : dec(r.get("late_submit_count")).intValue();
                row.put("lateSubmitCount", lateCount);
                row.put("pointsFee", lateCount > 0 ? lateFeeOf(rule, lateCount) : null);
                deductRows.add(row);
            }
            if (lateCount(r) > 0) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("employeeId", asLong(r.get("employee_id")));
                row.put("employeeCode", r.get("employee_code"));
                row.put("employeeName", r.get("employee_name"));
                row.put("scoreMonth", monthStart.toString());
                row.put("lateSubmitCount", lateCount(r));
                BigDecimal fee = lateFeeOf(rule, lateCount(r));
                row.put("pointsFee", fee);
                lateRows.add(row);
                lateTotalCount += lateCount(r);
                lateTotalFee = lateTotalFee.add(fee);
            }
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("totalCount", rows.size());
        snapshot.put("gradeACount", gradeA);
        snapshot.put("gradeBCount", gradeB);
        snapshot.put("gradeCCount", gradeC);
        snapshot.put("rows", deductRows);
        snapshot.put("lateSubmitTotalCount", lateTotalCount);
        snapshot.put("lateSubmitTotalFee", round2(lateTotalFee));
        snapshot.put("lateSubmitRows", lateRows);
        return writeJson(snapshot);
    }

    /** 读取积分等级规则（pj_payroll_policy_rule GLOBAL 的 rule_content.points），缺失走兜底。 */
    private ScoreRule loadScoreRule() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT rule_content FROM pj_payroll_policy_rule WHERE scope_type = 'GLOBAL' LIMIT 1");
        if (rows.isEmpty() || rows.get(0).get("rule_content") == null) {
            return new ScoreRule(null, null, null, null, null, null);
        }
        try {
            JsonNode points = objectMapper
                .readTree(rows.get(0).get("rule_content").toString()).path("points");
            if (points.isMissingNode()) {
                return new ScoreRule(null, null, null, null, null, null);
            }
            return new ScoreRule(nodeDecimal(points, "gradeA"), nodeDecimal(points, "gradeB"),
                nodeDecimal(points, "deductA"), nodeDecimal(points, "deductB"),
                nodeDecimal(points, "deductC"), nodeDecimal(points, "penaltyFee"));
        } catch (Exception e) {
            log.warn("[审批补齐] 积分规则解析失败，使用兜底口径：{}", e.getMessage());
            return new ScoreRule(null, null, null, null, null, null);
        }
    }

    private static BigDecimal nodeDecimal(JsonNode node, String key) {
        JsonNode v = node.path(key);
        return v.isMissingNode() || v.isNull() ? null : new BigDecimal(v.asText());
    }

    /** 平均分 → 等级（对齐 ScoreGradePolicy.resolveGrade，含兜底门槛 8/6）。 */
    private static String resolveGrade(ScoreRule rule, BigDecimal avg) {
        BigDecimal aMin = rule.gradeAMin() != null ? rule.gradeAMin() : new BigDecimal("8");
        BigDecimal bMin = rule.gradeBMin() != null ? rule.gradeBMin() : new BigDecimal("6");
        if (avg.compareTo(aMin) >= 0) {
            return "A";
        }
        return avg.compareTo(bMin) >= 0 ? "B" : "C";
    }

    /** 等级 → 扣点小数（对齐 ScoreGradePolicy.deductOf，兜底 0/-0.02/-0.04）。 */
    private static BigDecimal deductOf(ScoreRule rule, String grade) {
        return switch (grade) {
            case "B" -> rule.deductB() != null ? rule.deductB() : new BigDecimal("-0.02");
            case "C" -> rule.deductC() != null ? rule.deductC() : new BigDecimal("-0.04");
            default -> BigDecimal.ZERO;
        };
    }

    /** 积分扣款 = 晚提交次数 × 单价（对齐 ScoreGradePolicy.lateFeeOf，兜底 5 元/次）。 */
    private static BigDecimal lateFeeOf(ScoreRule rule, int count) {
        BigDecimal unit = rule.lateFeePerTime() != null ? rule.lateFeePerTime() : new BigDecimal("5");
        return unit.multiply(BigDecimal.valueOf(count)).setScale(2, RoundingMode.HALF_UP);
    }

    private static int lateCount(Map<String, Object> r) {
        return r.get("late_submit_count") == null ? 0 : dec(r.get("late_submit_count")).intValue();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("快照 JSON 序列化失败", e);
        }
    }

    /** Object → BigDecimal（JdbcTemplate 返回 Integer/Long/BigDecimal/BigInteger 等）。 */
    private static BigDecimal dec(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        return o instanceof BigDecimal bd ? bd : new BigDecimal(o.toString());
    }

    /** 积分等级规则（gradeAMin/gradeBMin/deductA/deductB/deductC/晚提交罚款单价，均可空=兜底）。 */
    private record ScoreRule(BigDecimal gradeAMin, BigDecimal gradeBMin, BigDecimal deductA,
                             BigDecimal deductB, BigDecimal deductC, BigDecimal lateFeePerTime) {
    }

    // ==================== 6/7. 新签业绩 / 结佣业绩 ====================

    /**
     * 新签/结佣业绩（12-13 列）：表头在第 1 行，数据从第 2 行起。
     * 按"签约人"列姓名匹配员工，回写 pj_normalized_record + pj_perf_fact。
     *
     * @param factType PERF_EXPECT（新签）/ PERF_REAL（结佣）
     * @return 成功插入的 perf_fact 条数
     */
    private int processPerfSheet(String period, Sheet sheet, String factType, long importBatchId) {
        if (sheet == null) {
            log.warn("[{}] sheet 不存在", factType);
            return 0;
        }
        int facts = 0;
        int skip = 0;
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String signer = str(cell(row, 4)); // 签约人
            if (isBlank(signer)) {
                continue;
            }
            BigDecimal amount = num(cell(row, 9)); // 85后
            if (amount == null) {
                continue; // 无业绩金额，跳过
            }
            EmpMatch emp = findEmployee(signer);
            if (emp == null) {
                skip++;
                warnings.add("[业绩] 员工未匹配，整行跳过：" + signer + "（" + factType + " 第" + (r + 1) + "行）");
                continue;
            }
            LocalDate businessDate = parseDate(cell(row, 0));
            if (businessDate == null) {
                log.warn("[{}] 第 {} 行 日期解析失败跳过：{}", factType, r + 1, signer);
                skip++;
                warnings.add("[业绩] 日期解析失败，整行跳过：" + signer + "（" + factType + " 第" + (r + 1) + "行）");
                continue;
            }
            String contract = str(cell(row, 1));          // 合同号
            String bizType = str(cell(row, 2));           // 类型
            // Excel 金额是折算后，pj_perf_fact 需存原始金额：amount ÷ 折算因子
            BigDecimal factor = conversionFactorPort.factorOf(bizType);
            if (factor != null && factor.compareTo(BigDecimal.ONE) != 0) {
                amount = amount.divide(factor, 2, RoundingMode.HALF_UP);
            }
            String address = str(cell(row, 3));           // 房源地址
            String roleType = str(cell(row, 7));          // 所属角色
            BigDecimal ratio = num(cell(row, 8));         // 角色占比
            if (ratio == null) {
                ratio = BigDecimal.ONE;
            }
            long employeeId = emp.employeeId();
            Long deptId = emp.deptId();
            String sourceKey = (isBlank(contract) ? ("ROW" + r) : contract) + "_" + employeeId;

            // 幂等：上次运行可能已插入归一化记录但业绩事实插入失败（孤儿记录），按 batch+source_key 复用
            List<Long> normIds = jdbc.query(
                "SELECT id FROM pj_normalized_record WHERE batch_id = ? AND source_key = ? LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), importBatchId, sourceKey);
            long normId;
            if (!normIds.isEmpty()) {
                normId = normIds.get(0);
            } else {
                normId = IdWorker.getId();
                try {
                    jdbc.update(
                        "INSERT INTO pj_normalized_record (id, batch_id, record_type, period, employee_id, "
                            + "source_key, biz_type, order_no, contract_no, property_address, sign_date, "
                            + "role_type, role_name, share_ratio, validation_status) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        normId, importBatchId, "SIGNED", period, employeeId, sourceKey, bizType,
                        contract, contract, address, businessDate.toString(), roleType, signer, ratio, 1);
                } catch (Exception e) {
                    log.warn("[{}] 归一化记录插入失败跳过：sourceKey={}，{}", factType, sourceKey, e.getMessage());
                    skip++;
                    continue;
                }
            }
            try {
                jdbc.update(
                    "INSERT INTO pj_perf_fact (id, fact_type, period, business_date, normalized_record_id, "
                        + "batch_id, source_key, biz_type, employee_id, dept_id, role_type, share_ratio, "
                        + "performance_amount, effective_date, fact_status, source, version, "
                        + "contract_no, order_no, property_address, role_name) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE','IMPORT',0,?,?,?,?)",
                    IdWorker.getId(), factType, period, businessDate, normId, importBatchId, sourceKey,
                    bizType, employeeId, deptId, roleType, ratio, amount, businessDate,
                    contract, contract, address, signer);
                facts++;
            } catch (Exception e) {
                log.warn("[{}] 业绩事实插入失败跳过：sourceKey={}，{}", factType, sourceKey, e.getMessage());
                skip++;
            }
        }
        log.info("[{}] 完成：写入业绩事实 {} 条（跳过 {} 行）", factType, facts, skip);
        return facts;
    }

    // ==================== 8/9. 实收审批单 + 结佣申请单（历史终态补建） ====================

    /**
     * 实收审批单补建（对齐 ReceivedApplyServiceImpl.autoCreateForBatch 口径）：
     * 本批次 IMPORT 非零 ACTIVE 实收事实按订单号（空订单回退合同号/sourceKey）分组，
     * 每组一张 APPROVED 终态单并回填 pj_perf_fact.received_apply_id，使实收明细页面可查。
     * <p>幂等：组内已有未完结单（DRAFT/SUBMITTED/APPROVED）则合并绑定事实并收敛为 APPROVED，不重复建单。
     *
     * @return 本次新建的实收审批单条数
     */
    private int ensureReceivedApplies(String period, long importBatchId) {
        List<Map<String, Object>> facts = jdbc.queryForList(
            "SELECT id, order_no, contract_no, source_key, property_address, business_date, "
                + "biz_type, dept_id, performance_amount "
                + "FROM pj_perf_fact WHERE period = ? AND source = 'IMPORT' AND batch_id = ? "
                + "AND fact_type = 'PERF_REAL' AND fact_status = 'ACTIVE' AND received_apply_id IS NULL",
            period, importBatchId);
        // 非零事实按业务键分组（对齐原生：零金额事实不参与建单）
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> f : facts) {
            BigDecimal amount = dec(f.get("performance_amount"));
            if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            groups.computeIfAbsent(bizKeyOf(f), k -> new ArrayList<>()).add(f);
        }
        if (groups.isEmpty()) {
            log.info("[实收审批单] 无待建单实收事实：period={}", period);
            return 0;
        }
        // 应收快照：该期间全部 ACTIVE PERF_EXPECT 按业务键聚合（对齐原生 selectExpectSumsByBizKeys）
        Map<String, BigDecimal> expectedMap = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
            "SELECT COALESCE(NULLIF(order_no,''), NULLIF(contract_no,''), source_key) AS biz_key, "
                + "SUM(performance_amount) AS amt FROM pj_perf_fact "
                + "WHERE period = ? AND fact_type = 'PERF_EXPECT' AND fact_status = 'ACTIVE' "
                + "GROUP BY 1", period)) {
            expectedMap.put(String.valueOf(row.get("biz_key")), dec(row.get("amt")));
        }

        LocalDateTime now = LocalDateTime.now();
        String stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int created = 0;
        int seq = 1;
        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            String bizKey = entry.getKey();
            List<Map<String, Object>> rows = entry.getValue();
            BigDecimal realSum = rows.stream()
                .map(r -> dec(r.get("performance_amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (realSum.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            // 门店：组内部门唯一才取值（对齐原生 uniqueDeptId），跨门店合作单为空
            Set<Object> deptIds = rows.stream().map(r -> r.get("dept_id"))
                .filter(Objects::nonNull).collect(Collectors.toSet());
            Long deptId = deptIds.size() == 1
                ? ((Number) deptIds.iterator().next()).longValue() : null;
            BigDecimal expected = expectedMap.getOrDefault(bizKey, BigDecimal.ZERO);
            Map<String, Object> head = rows.get(0);
            LocalDateTime bizDate = maxBusinessDate(rows);

            List<Long> factIds = rows.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
            // 已有未完结单则合并绑定并收敛为 APPROVED（历史终态修复），否则新建
            List<Long> existing = jdbc.queryForList(
                "SELECT id FROM pj_perf_received_apply WHERE period = ? AND contract_no = ? "
                    + "AND status IN ('DRAFT','SUBMITTED','APPROVED') LIMIT 1",
                Long.class, period, bizKey);
            long applyId;
            if (!existing.isEmpty()) {
                applyId = existing.get(0);
                jdbc.update("UPDATE pj_perf_fact SET received_apply_id = ? WHERE id IN ("
                    + placeholders(factIds.size()) + ")", prepend(applyId, factIds.toArray()));
                jdbc.update("UPDATE pj_perf_received_apply SET received_amount = received_amount + ?, "
                    + "item_count = item_count + ?, expected_amount = ?, status = 'APPROVED', "
                    + "current_node = NULL, approve_time = COALESCE(approve_time, ?), update_time = ? WHERE id = ?",
                    realSum, rows.size(), expected, now, now, applyId);
                log.info("[实收审批单] 合并绑定既有单：applyId={}, contract={}", applyId, bizKey);
            } else {
                applyId = IdWorker.getId();
                String applyNo = "RCV" + stamp + String.format("%03d", seq++);
                jdbc.update("INSERT INTO pj_perf_received_apply (id, apply_no, period, contract_no, "
                    + "order_no, property_address, business_date, dept_id, batch_id, received_amount, "
                    + "expected_amount, item_count, biz_type, status, current_node, process_instance_id, "
                    + "applicant_id, approver_id, approve_time, version) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, 'APPROVED', NULL, NULL, NULL, NULL, ?, 0)",
                    applyId, applyNo, period, bizKey, bizKey, strOrNull(head.get("property_address")),
                    bizDate, deptId, importBatchId, realSum, expected, rows.size(),
                    strOrNull(head.get("biz_type")), now);
                jdbc.update("UPDATE pj_perf_fact SET received_apply_id = ? WHERE id IN ("
                    + placeholders(factIds.size()) + ")", prepend(applyId, factIds.toArray()));
                created++;
                log.info("[实收审批单] 新建 APPROVED 单：applyNo={}, contract={}, received={}, facts={}",
                    applyNo, bizKey, realSum, rows.size());
            }
        }
        log.info("[实收审批单] 完成：新建 {} 张（period={}）", created, period);
        return created;
    }

    /**
     * 结佣申请单补建（对齐 CommissionApplicationService 提交+审批终态口径）：
     * 本批次 IMPORT 非零 ACTIVE 实收事实按合同号分组，每组一张 LOCKED 申请单 +
     * 每条事实一行 APPROVED 结佣明细（approved_month=期间，计入口径与算薪 findLocked 一致）。
     * <p>幂等：已有非冲销明细的事实跳过；合同已有未完结申请单则挂靠补明细。
     *
     * @return 本次新建的结佣申请单条数
     */
    private int ensureCommissionApplications(String period, long importBatchId) {
        List<Map<String, Object>> facts = jdbc.queryForList(
            "SELECT f.id, f.order_no, f.contract_no, f.source_key, f.property_address, f.business_date, "
                + "f.biz_type, f.dept_id, f.employee_id, f.role_type, f.fee_item, f.performance_amount, "
                + "f.received_apply_id FROM pj_perf_fact f "
                + "WHERE f.period = ? AND f.source = 'IMPORT' AND f.batch_id = ? "
                + "AND f.fact_type = 'PERF_REAL' AND f.fact_status = 'ACTIVE' "
                + "AND NOT EXISTS (SELECT 1 FROM pj_commission_item ci "
                + "WHERE ci.performance_fact_id = f.id AND ci.status <> 'REVERSED')",
            period, importBatchId);
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> f : facts) {
            BigDecimal amount = dec(f.get("performance_amount"));
            if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            groups.computeIfAbsent(bizKeyOf(f), k -> new ArrayList<>()).add(f);
        }
        if (groups.isEmpty()) {
            log.info("[结佣申请单] 无待建明细的实收事实：period={}", period);
            return 0;
        }
        Map<String, BigDecimal> expectedMap = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
            "SELECT COALESCE(NULLIF(order_no,''), NULLIF(contract_no,''), source_key) AS biz_key, "
                + "SUM(performance_amount) AS amt FROM pj_perf_fact "
                + "WHERE period = ? AND fact_type = 'PERF_EXPECT' AND fact_status = 'ACTIVE' "
                + "GROUP BY 1", period)) {
            expectedMap.put(String.valueOf(row.get("biz_key")), dec(row.get("amt")));
        }

        LocalDateTime now = LocalDateTime.now();
        String stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int created = 0;
        int seq = 1;
        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            String contractNo = entry.getKey();
            List<Map<String, Object>> rows = entry.getValue();
            BigDecimal totalAmount = rows.stream()
                .map(r -> dec(r.get("performance_amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            Set<Object> deptIds = rows.stream().map(r -> r.get("dept_id"))
                .filter(Objects::nonNull).collect(Collectors.toSet());
            Long deptId = deptIds.size() == 1
                ? ((Number) deptIds.iterator().next()).longValue() : null;
            BigDecimal expected = expectedMap.getOrDefault(contractNo, BigDecimal.ZERO);
            Map<String, Object> head = rows.get(0);

            // 已有未完结申请单则挂靠补明细，否则新建 LOCKED 单
            List<Long> existing = jdbc.queryForList(
                "SELECT id FROM pj_commission_application WHERE period = ? AND contract_no = ? "
                    + "AND status IN ('DRAFT','SUBMITTED','APPROVED','LOCKED') LIMIT 1",
                Long.class, period, contractNo);
            long applicationId;
            if (!existing.isEmpty()) {
                applicationId = existing.get(0);
                jdbc.update("UPDATE pj_commission_application SET status = 'LOCKED', current_node = NULL, "
                    + "approved_month = COALESCE(approved_month, ?), lock_time = COALESCE(lock_time, ?), "
                    + "approve_time = COALESCE(approve_time, ?), total_amount = total_amount + ?, "
                    + "item_count = item_count + ?, update_time = ? WHERE id = ?",
                    period, now, now, totalAmount, rows.size(), now, applicationId);
                log.info("[结佣申请单] 挂靠既有单补明细：applicationId={}, contract={}", applicationId, contractNo);
            } else {
                applicationId = IdWorker.getId();
                String applyNo = "CAPP" + stamp + String.format("%03d", seq++);
                jdbc.update("INSERT INTO pj_commission_application (id, apply_no, period, contract_no, "
                    + "order_no, property_address, business_date, dept_id, item_count, total_amount, "
                    + "expected_amount, aligned, status, current_node, approved_month, process_instance_id, "
                    + "applicant_id, approver_id, approve_time, lock_time, version) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?, FALSE, 'LOCKED', NULL, ?, NULL, NULL, NULL, ?, ?, 0)",
                    applicationId, applyNo, period, contractNo, contractNo,
                    strOrNull(head.get("property_address")), maxBusinessDate(rows), deptId,
                    rows.size(), totalAmount, expected, period, now, now);
                created++;
                log.info("[结佣申请单] 新建 LOCKED 单：applyNo={}, contract={}, total={}, items={}",
                    applyNo, contractNo, totalAmount, rows.size());
            }
            // 每条事实一行 APPROVED 明细（对齐审批回调：PENDING→APPROVED + approved_month）
            for (Map<String, Object> f : rows) {
                jdbc.update("INSERT INTO pj_commission_item (id, application_id, performance_fact_id, "
                    + "period, contract_no, approved_month, employee_id, dept_id, biz_type, role_type, "
                    + "fee_item, amount, status, origin_reversed, version) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'APPROVED', FALSE, 0)",
                    IdWorker.getId(), applicationId, ((Number) f.get("id")).longValue(), period,
                    contractNo, period, ((Number) f.get("employee_id")).longValue(),
                    f.get("dept_id") == null ? null : ((Number) f.get("dept_id")).longValue(),
                    strOrNull(f.get("biz_type")), strOrNull(f.get("role_type")),
                    strOrNull(f.get("fee_item")), dec(f.get("performance_amount")));
            }
        }
        log.info("[结佣申请单] 完成：新建 {} 张（period={}）", created, period);
        return created;
    }

    /** 业务键：订单号优先，空回退合同号，再回退 sourceKey（保证合同号 NOT NULL 约束可满足） */
    private String bizKeyOf(Map<String, Object> fact) {
        String orderNo = strOrNull(fact.get("order_no"));
        if (orderNo != null && !orderNo.isBlank()) {
            return orderNo;
        }
        String contractNo = strOrNull(fact.get("contract_no"));
        if (contractNo != null && !contractNo.isBlank()) {
            return contractNo;
        }
        return String.valueOf(fact.get("source_key"));
    }

    /** 组内最大业务时间（快照取合同内最大，对齐原生口径） */
    private LocalDateTime maxBusinessDate(List<Map<String, Object>> rows) {
        return rows.stream()
            .map(r -> r.get("business_date"))
            .filter(Objects::nonNull)
            .map(v -> v instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : null)
            .filter(Objects::nonNull)
            .max(LocalDateTime::compareTo)
            .orElse(null);
    }

    private Object[] prepend(Object first, Object[] rest) {
        Object[] all = new Object[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    private String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private String strOrNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    // ==================== 明细 upsert 辅助 ====================

    /**
     * 按姓名+period 找既有 PayrollDetail 并更新（仅写非空字段，不覆盖既有值）。
     * 找不到则记日志跳过（append sheet 不负责新建明细）。
     */
    private boolean updateExisting(String period, EmpMatch emp, String name, Consumer<PayrollDetail> updater) {
        PayrollDetail d = findDetail(period, emp.employeeId());
        if (d == null) {
            log.warn("匹配工资明细失败跳过：name={}, employeeId={}", name, emp.employeeId());
            return false;
        }
        updater.accept(d);
        detailMapper.updateById(d);
        return true;
    }

    /**
     * 总监明细 upsert：找到则更新，找不到则新建（employeeRole=DIRECTOR）。
     */
    private void upsertDirector(String period, EmpMatch emp, Long deptId, Consumer<PayrollDetail> updater) {
        PayrollDetail d = findDetail(period, emp.employeeId());
        if (d == null) {
            d = new PayrollDetail();
            d.setEmployeeId(emp.employeeId());
            d.setDeptId(deptId);
            d.setEmployeeRole(EmployeeRole.DIRECTOR);
            d.setBatchId(currentBatchId);
            d.setPeriod(period);
            updater.accept(d);
            detailMapper.insert(d);
            log.info("[总监工资] 新建明细：employeeId={}, deptId={}", emp.employeeId(), emp.deptId());
        } else {
            updater.accept(d);
            detailMapper.updateById(d);
        }
    }

    private PayrollDetail findDetail(String period, long employeeId) {
        List<PayrollDetail> list = detailMapper.selectList(
            new LambdaQueryWrapper<PayrollDetail>()
                .eq(PayrollDetail::getPeriod, period)
                .eq(PayrollDetail::getEmployeeId, employeeId));
        return list.isEmpty() ? null : list.get(0);
    }

    // ==================== 跨模块查询 ====================

    private long ensureImportBatch(String period) {
        String batchNo = "HIST-PAYROLL-" + period;
        List<Map<String, Object>> existRows = jdbc.queryForList(
            "SELECT id FROM pj_import_batch WHERE batch_no = ?", batchNo);
        if (!existRows.isEmpty()) {
            long id = asLong(existRows.get(0).get("id"));
            log.info("[历史工资导入] 复用既有导入批次：id={}, batchNo={}", id, batchNo);
            return id;
        }
        long id = IdWorker.getId();
        jdbc.update(
            "INSERT INTO pj_import_batch (id, batch_no, source_type, template_version, file_name, "
                + "period, total_rows, success_rows, failed_rows, status, version) "
                + "VALUES (?,?,?,?,?,?,?,?,?,3,0)",
            id, batchNo, "OTHERS", "HISTORY-1.0",
            "天街工资表" + period + ".xlsx", period, 0, 0, 0);
        log.info("[历史工资导入] 创建导入批次：id={}, period={}", id, period);
        return id;
    }

    /**
     * 按姓名匹配员工：先精确，再 LIKE；唯一匹配才取，多匹配/无匹配记日志跳过。结果缓存。
     */
    private EmpMatch findEmployee(String name) {
        if (isBlank(name)) {
            return null;
        }
        EmpMatch cached = empCache.get(name);
        if (cached != null) {
            return cached == NULL_MATCH ? null : cached;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT employee_id, dept_id FROM pj_people_employee WHERE employee_name = ?", name);
        if (rows.isEmpty()) {
            rows = jdbc.queryForList(
                "SELECT employee_id, dept_id FROM pj_people_employee WHERE employee_name LIKE ?",
                "%" + name + "%");
        }
        EmpMatch m;
        if (rows.size() == 1) {
            Map<String, Object> rm = rows.get(0);
            m = new EmpMatch(asLong(rm.get("employee_id")), asLong(rm.get("dept_id")));
        } else {
            if (rows.size() > 1) {
                log.warn("员工匹配多条跳过：{}", name);
            } else {
                log.warn("员工未匹配跳过：{}", name);
            }
            m = null;
        }
        empCache.put(name, m == null ? NULL_MATCH : m);
        return m;
    }

    /**
     * 按"门店名"模糊匹配 sys_dept，取第一个。结果缓存。
     */
    private Long resolveDeptId(String storeName) {
        if (isBlank(storeName)) {
            return null;
        }
        Long cached = deptCache.get(storeName);
        if (cached != null) {
            return cached == DEPT_NOT_FOUND ? null : cached;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT dept_id FROM sys_dept WHERE dept_name LIKE ? ORDER BY dept_id LIMIT 1",
            "%" + storeName + "%");
        Long id = rows.isEmpty() ? null : asLong(rows.get(0).get("dept_id"));
        deptCache.put(storeName, id == null ? DEPT_NOT_FOUND : id);
        if (id == null) {
            log.warn("部门未匹配：{}", storeName);
        }
        return id;
    }

    // ==================== POI 单元格解析 ====================

    private Cell cell(Row row, int col) {
        return row == null ? null : row.getCell(col);
    }

    private BigDecimal num(Cell c) {
        if (c == null) {
            return null;
        }
        switch (c.getCellType()) {
            case NUMERIC:
                return BigDecimal.valueOf(c.getNumericCellValue());
            case STRING:
                String s = c.getStringCellValue().trim();
                if (s.isEmpty()) {
                    return null;
                }
                try {
                    return new BigDecimal(s);
                } catch (NumberFormatException e) {
                    log.warn("金额解析失败：{}", s);
                    return null;
                }
            case FORMULA:
                try {
                    return BigDecimal.valueOf(c.getNumericCellValue());
                } catch (Exception e) {
                    String fs = DF.formatCellValue(c).trim();
                    if (fs.isEmpty()) {
                        return null;
                    }
                    try {
                        return new BigDecimal(fs);
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                }
            default:
                return null;
        }
    }

    /** 从 POI Cell 取整数（出勤天数/迟到次数等），空或非数字返回 null。 */
    private Integer intVal(Cell c) {
        BigDecimal b = num(c);
        if (b == null) {
            return null;
        }
        return b.intValue();
    }

    private String str(Cell c) {
        if (c == null) {
            return null;
        }
        switch (c.getCellType()) {
            case STRING:
                return c.getStringCellValue().trim();
            case NUMERIC:
                double d = c.getNumericCellValue();
                if (!Double.isInfinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15) {
                    return Long.toString((long) d);
                }
                return BigDecimal.valueOf(d).toPlainString();
            case FORMULA:
                return DF.formatCellValue(c).trim();
            case BOOLEAN:
                return Boolean.toString(c.getBooleanCellValue());
            default:
                return null;
        }
    }

    private LocalDate parseDate(Cell c) {
        if (c == null) {
            return null;
        }
        switch (c.getCellType()) {
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(c)) {
                    return toLocalDate(c.getDateCellValue());
                }
                return null;
            case STRING:
                return parseDateStr(c.getStringCellValue());
            case FORMULA:
                if (DateUtil.isCellDateFormatted(c)) {
                    try {
                        return toLocalDate(c.getDateCellValue());
                    } catch (Exception ignored) {
                        // 落到字符串解析
                    }
                }
                return parseDateStr(DF.formatCellValue(c));
            default:
                return null;
        }
    }

    private LocalDate parseDateStr(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = DATE_RE.matcher(s);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    // ==================== 通用辅助 ====================

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

    private void setIf(BigDecimal v, Consumer<BigDecimal> setter) {
        if (v != null) {
            setter.accept(v);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Long asLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static BigDecimal round2(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    /** 员工匹配结果（employeeId + deptId，deptId 可空）。 */
    private record EmpMatch(long employeeId, Long deptId) {
    }
}
