package com.panjia.payroll.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.contracts.port.PeriodCloseQueryPort;
import com.panjia.payroll.domain.BatchStatus;
import com.panjia.payroll.domain.ManualItem;
import com.panjia.payroll.domain.ManualItemType;
import com.panjia.payroll.domain.PayrollBatch;
import com.panjia.payroll.dto.ManualItemImportVo;
import com.panjia.payroll.mapper.ManualItemMapper;
import com.panjia.payroll.mapper.PayrollBatchMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 手工录入项服务（奖金/其他收入/其他支出） */
@Service
@RequiredArgsConstructor
public class ManualItemService {

    /**
     * 冻结手工项增删的批次状态。
     * <p>财务规则：提交审批即定稿——审批中/已通过/已锁定/已发放期间，奖金与收支一律不允许新增或删除；
     * 驳回（退回 CALCULATED）后自动解冻，改数重算再提交。CALCULATING 为算薪取数瞬态，一并挡下。
     */
    private static final Set<BatchStatus> FROZEN_BATCH_STATUS = EnumSet.of(
        BatchStatus.CALCULATING,
        BatchStatus.REVIEWING,
        BatchStatus.APPROVED,
        BatchStatus.LOCKED,
        BatchStatus.PAID
    );

    private final ManualItemMapper mapper;
    private final PayrollBatchMapper batchMapper;
    /** 期间封账跨域查询（performance 域实现）：锁定批次记录缺失等异常场景的兜底冻结信号 */
    private final PeriodCloseQueryPort periodCloseQueryPort;
    /** 员工主数据跨域查询（people 域实现）：导入按员工编码批量解析员工 */
    private final EmployeeMainDataQueryPort employeeMainDataQueryPort;

    public Long create(ManualItem item, Long operatorId) {
        if (item.getItemType() == null) {
            throw new ServiceException("请选择录入类型");
        }
        assertRangeValid(item);
        assertRangeEditable(item.getStartMonth(), item.getEndMonth());
        item.setStatus("APPROVED"); // V1 直接生效，后续可走审批
        item.setApplyBy(operatorId);
        item.setApproveBy(operatorId);
        mapper.insert(item);
        return item.getId();
    }

    /** 查询某月生效的手工项（startMonth ≤ 月 ≤ endMonth，截止月可空=长期），按创建时间倒序 */
    public List<ManualItem> listByPeriod(String period) {
        return mapper.selectList(
            new LambdaQueryWrapper<ManualItem>()
                .le(ManualItem::getStartMonth, period)
                .and(w -> w.isNull(ManualItem::getEndMonth).or().ge(ManualItem::getEndMonth, period))
                .orderByDesc(ManualItem::getCreateTime));
    }

    public ManualItem getById(Long id) {
        return mapper.selectById(id);
    }

    public void delete(Long id, Long operatorId) {
        ManualItem item = mapper.selectById(id);
        if (item == null) {
            throw new ServiceException("记录不存在或已被删除");
        }
        assertRangeEditable(item.getStartMonth(), item.getEndMonth());
        mapper.deleteById(id);
    }

    /**
     * Excel 批量导入其他收支/奖金（员工编码定位员工，截止月留空=长期生效）。
     * <p>整批校验通过才入库（避免半成功难对账）；任一行有错则整体拒绝，
     * 返回的异常消息按行号列出全部问题（首行为表头，数据从第 2 行起）。
     *
     * @return 成功导入条数的统计文案
     */
    public String batchImport(List<ManualItemImportVo> rows, Long operatorId) {
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException("导入文件没有数据行");
        }
        // 1. 批量解析员工（入口一次取数，避免循环查员工）
        Set<String> codes = rows.stream()
            .map(r -> StringUtils.trimToEmpty(r.getEmployeeCode()))
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());
        Map<String, EmployeeMainDataDTO> empMap = codes.isEmpty()
            ? Map.of()
            : employeeMainDataQueryPort.listByCodes(codes);

        // 2. 逐行校验并构建；行记录带行号与生效月份集合，供冻结校验精确定位
        record ParsedRow(int line, ManualItem item, java.time.YearMonth start, java.time.YearMonth end) {}
        List<String> errors = new java.util.ArrayList<>();
        List<ParsedRow> valid = new java.util.ArrayList<>();
        java.util.Set<String> allMonths = new java.util.LinkedHashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            ManualItemImportVo row = rows.get(i);
            int line = i + 2;
            String code = StringUtils.trimToEmpty(row.getEmployeeCode());
            if (StringUtils.isBlank(code)) {
                errors.add("第" + line + "行：员工编码不能为空");
                continue;
            }
            EmployeeMainDataDTO emp = empMap.get(code);
            if (emp == null) {
                errors.add("第" + line + "行：员工编码 " + code + " 不存在");
                continue;
            }
            if (StringUtils.isNotBlank(row.getEmployeeName())
                && !row.getEmployeeName().trim().equals(emp.getEmployeeName())) {
                errors.add("第" + line + "行：姓名与编码不匹配（" + code + " 应为 " + emp.getEmployeeName() + "）");
                continue;
            }
            String label = StringUtils.trimToEmpty(row.getItemTypeLabel());
            String itemType = "收入".equals(label) ? ManualItemType.OTHER_INCOME.name()
                : "支出".equals(label) ? ManualItemType.OTHER_DEDUCT.name() : null;
            if (itemType == null) {
                errors.add("第" + line + "行：类型必须填「收入」或「支出」");
                continue;
            }
            if (row.getAmount() == null || row.getAmount().signum() <= 0) {
                errors.add("第" + line + "行：金额必须大于 0");
                continue;
            }
            String start = StringUtils.trimToEmpty(row.getStartMonth());
            if (!start.matches("\\d{4}-\\d{2}")) {
                errors.add("第" + line + "行：生效起始月格式应为 YYYY-MM");
                continue;
            }
            String end = StringUtils.trimToEmpty(row.getEndMonth());
            if (StringUtils.isNotBlank(end)) {
                if (!end.matches("\\d{4}-\\d{2}")) {
                    errors.add("第" + line + "行：生效截止月格式应为 YYYY-MM（留空=长期生效）");
                    continue;
                }
                if (start.compareTo(end) > 0) {
                    errors.add("第" + line + "行：生效起始月不能晚于截止月");
                    continue;
                }
                long months = java.time.YearMonth.parse(start)
                    .until(java.time.YearMonth.parse(end), java.time.temporal.ChronoUnit.MONTHS) + 1;
                if (months > 36) {
                    errors.add("第" + line + "行：生效区间最长 36 个月，长期生效请截止月留空");
                    continue;
                }
            }
            ManualItem item = new ManualItem();
            item.setItemType(ManualItemType.valueOf(itemType));
            item.setEmployeeId(emp.getEmployeeId());
            item.setAmount(row.getAmount());
            item.setStartMonth(start);
            item.setEndMonth(StringUtils.isBlank(end) ? null : end);
            item.setReason(StringUtils.trimToNull(row.getReason()));
            item.setStatus("APPROVED");
            item.setApplyBy(operatorId);
            item.setApproveBy(operatorId);
            // 区间覆盖月份（长期的校验到当前月，未来月份无批次无需校验）
            java.time.YearMonth s = java.time.YearMonth.parse(start);
            java.time.YearMonth e = StringUtils.isBlank(end) ? java.time.YearMonth.now() : java.time.YearMonth.parse(end);
            valid.add(new ParsedRow(line, item, s.isAfter(e) ? e : s, e));
            for (java.time.YearMonth cur = s.isAfter(e) ? e : s; !cur.isAfter(e); cur = cur.plusMonths(1)) {
                allMonths.add(cur.toString());
            }
        }
        if (valid.isEmpty() && !errors.isEmpty()) {
            throw new ServiceException(String.join("\n", errors));
        }

        // 3. 区间月份冻结校验（所有行月份并集一次性校验）
        java.util.Set<String> frozenMonths = new java.util.LinkedHashSet<>();
        for (String month : allMonths) {
            PayrollBatch frozenBatch = batchMapper.selectOne(
                new LambdaQueryWrapper<PayrollBatch>()
                    .eq(PayrollBatch::getPeriod, month)
                    .in(PayrollBatch::getStatus, FROZEN_BATCH_STATUS)
                    .last("LIMIT 1"));
            if (frozenBatch != null || periodCloseQueryPort.isClosed(month)) {
                frozenMonths.add(month);
            }
        }
        for (ParsedRow r : valid) {
            for (java.time.YearMonth cur = r.start(); !cur.isAfter(r.end()); cur = cur.plusMonths(1)) {
                if (frozenMonths.contains(cur.toString())) {
                    errors.add("第" + r.line() + "行：生效区间包含已冻结/封账期间 " + cur + "，请调整区间避开");
                    break;
                }
            }
        }
        if (!errors.isEmpty()) {
            List<String> shown = errors.size() > 20 ? errors.subList(0, 20) : errors;
            String more = errors.size() > 20 ? "\n……共 " + errors.size() + " 条错误" : "";
            throw new ServiceException(String.join("\n", shown) + more);
        }

        // 4. 全部通过，入库
        valid.forEach(r -> mapper.insert(r.item()));
        return "导入成功 " + valid.size() + " 条";
    }

    /** 校验生效区间字段（同提成点调整 startMonth/endMonth 口径；截止月可空=长期生效） */
    private void assertRangeValid(ManualItem item) {
        if (StringUtils.isBlank(item.getStartMonth())) {
            throw new ServiceException("生效起始月不能为空");
        }
        if (StringUtils.isNotBlank(item.getEndMonth())
            && item.getStartMonth().compareTo(item.getEndMonth()) > 0) {
            throw new ServiceException("生效起始月不能晚于截止月");
        }
        // 跨度保护：有截止月时限长 36 个月，防止误录超长区间导致逐月校验/多月重复生效
        if (StringUtils.isNotBlank(item.getEndMonth())) {
            long months = java.time.YearMonth.parse(item.getStartMonth())
                .until(java.time.YearMonth.parse(item.getEndMonth()), java.time.temporal.ChronoUnit.MONTHS) + 1;
            if (months > 36) {
                throw new ServiceException("生效区间最长 36 个月，如需长期生效请截止月留空");
            }
        }
    }

    /**
     * 校验生效区间覆盖的每个期间均允许新增/删除（任一月冻结即拒绝，请拆分区间避开冻结月）。
     * 双重冻结信号与原单月口径一致：批次冻结状态 + 期间封账。
     * 截止月为空（长期生效）时校验到当前月为止——未来月份批次尚不存在，无需校验。
     */
    private void assertRangeEditable(String startMonth, String endMonth) {
        java.time.YearMonth cur = java.time.YearMonth.parse(startMonth);
        java.time.YearMonth end = StringUtils.isBlank(endMonth)
            ? java.time.YearMonth.now()
            : java.time.YearMonth.parse(endMonth);
        if (cur.isAfter(end)) {
            return; // 纯未来区间，当前无批次可校验
        }
        while (!cur.isAfter(end)) {
            String month = cur.toString();
            PayrollBatch frozenBatch = batchMapper.selectOne(
                new LambdaQueryWrapper<PayrollBatch>()
                    .eq(PayrollBatch::getPeriod, month)
                    .in(PayrollBatch::getStatus, FROZEN_BATCH_STATUS)
                    .last("LIMIT 1"));
            if (frozenBatch != null) {
                throw new ServiceException("生效区间包含 " + month + "，" + frozenMessage(frozenBatch.getStatus()));
            }
            if (periodCloseQueryPort.isClosed(month)) {
                throw new ServiceException("生效区间包含已封账期间 " + month
                    + "，奖金与收支不允许新增或删除；如需纠错请先在「结佣明细」页解封该期间，或拆分区间避开该月");
            }
            cur = cur.plusMonths(1);
        }
    }

    private String frozenMessage(BatchStatus status) {
        return switch (status) {
            case CALCULATING -> "该期间工资正在计算中，请稍后再操作";
            case REVIEWING -> "该期间工资批次审批中，奖金与收支已冻结；如需调整请待审批驳回后修改并重算工资";
            case APPROVED -> "该期间工资已审核通过，奖金与收支已冻结；如需调整请驳回审批后修改并重算工资";
            case LOCKED -> "该期间工资已锁定封账，奖金与收支已冻结；如需纠错请先在「结佣明细」页解封该期间";
            case PAID -> "该期间工资已发放，奖金与收支不允许新增或删除";
            default -> "该期间工资批次已冻结，不允许新增或删除";
        };
    }

    /** 取某月生效的已审批手工项，按员工分组（算薪用；startMonth ≤ 月 ≤ endMonth，截止月可空=长期） */
    public Map<Long, List<ManualItem>> loadApprovedForPeriod(String period) {
        List<ManualItem> items = mapper.selectList(
            new LambdaQueryWrapper<ManualItem>()
                .le(ManualItem::getStartMonth, period)
                .and(w -> w.isNull(ManualItem::getEndMonth).or().ge(ManualItem::getEndMonth, period))
                .eq(ManualItem::getStatus, "APPROVED"));
        return items.stream().collect(Collectors.groupingBy(ManualItem::getEmployeeId));
    }
}
