package com.panjia.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.importutil.archive.ArchiveResult;
import com.panjia.importutil.archive.FileArchiver;
import com.panjia.ledger.domain.LedgerSubject;
import com.panjia.ledger.domain.LfAccount;
import com.panjia.ledger.domain.LfImportBatch;
import com.panjia.ledger.domain.LfStatementRow;
import com.panjia.ledger.dto.DeptRowDTO;
import com.panjia.ledger.dto.LfImportResultVO;
import com.panjia.ledger.dto.LfStatementExcelRow;
import com.panjia.ledger.mapper.LfAccountMapper;
import com.panjia.ledger.mapper.LfImportBatchMapper;
import com.panjia.ledger.mapper.LfStatementRowMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.excel.utils.ExcelBuilder;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.redis.utils.RedisUtils;
import org.redisson.api.RLock;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 理房通流水异步导入：请求阶段归档并创建批次，后台按账户串行落库和匹配。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LfStatementImportService {

    private static final String ARCHIVE_DIR = "ledger/lf";
    private static final int ROW_BATCH_SIZE = 100;
    private static final int CONTRACT_BATCH_SIZE = 50;

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-M-d H:m:s"),
        DateTimeFormatter.ofPattern("yyyy-M-d H:m")
    );

    private static final String DEPARTMENT_INCOME_REMARK = "佣金分账-居间代理费-品效商圈权益";

    private static final List<String> REVERSAL_KEYWORDS = List.of(
        "解约", "退款", "业绩变更", "冲减", "追回", "调账", "补足手续费", "应付账单", "佣金调账", "退单", "退费"
    );

    private final LfAccountMapper accountMapper;
    private final LfImportBatchMapper batchMapper;
    private final LfStatementRowMapper rowMapper;
    private final LfExpenseService expenseService;
    private final LfMatchService matchService;
    private final FileArchiver fileArchiver;
    private final TaskExecutor taskExecutor;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @PostConstruct
    void init() {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /** 上传请求只校验账户、归档文件并创建批次，不读取 MultipartFile 到异步线程。 */
    public LfImportResultVO submitImport(byte[] content, String fileName,
                                         Long storeDeptId, Long operatorId) {
        if (content == null || content.length == 0) {
            throw new ServiceException("导入文件不能为空");
        }
        LfAccount account = resolveAccount(fileName, storeDeptId);
        ArchiveResult archived = fileArchiver.archive(content, fileName, ARCHIVE_DIR);

        LfImportBatch batch = new LfImportBatch();
        batch.setAccountId(account.getId());
        batch.setStoreDeptId(account.getStoreDeptId());
        batch.setFileName(fileName);
        batch.setStoragePath(archived.getStoragePath());
        batch.setTotalRows(0);
        batch.setSuccessRows(0);
        batch.setFailRows(0);
        batch.setStatus("PROCESSING");
        batch.setOperatorId(operatorId);
        batch.setCreateTime(LocalDateTime.now());
        txTemplate.executeWithoutResult(status -> batchMapper.insert(batch));

        LfImportResultVO result = toResult(batch);
        try {
            taskExecutor.execute(() -> processBatch(batch.getId()));
        } catch (RuntimeException e) {
            log.error("[理房通导入] 异步任务提交失败 batchId={}", batch.getId(), e);
            failBatch(batch.getId(), result, e);
            throw new ServiceException("导入任务提交失败，请稍后重试");
        }
        return result;
    }

    public LfImportResultVO getImportResult(Long batchId) {
        LfImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new ServiceException("导入批次不存在");
        }
        LfImportResultVO result = StringUtils.isBlank(batch.getResultDetail())
            ? new LfImportResultVO()
            : JsonUtils.parseObject(batch.getResultDetail(), LfImportResultVO.class);
        result.setBatchId(batch.getId());
        result.setFileName(batch.getFileName());
        result.setStatus(batch.getStatus());
        result.setTotalRows(intValue(batch.getTotalRows()));
        result.setSuccessRows(intValue(batch.getSuccessRows()));
        result.setFailRows(intValue(batch.getFailRows()));
        if (result.getErrors().isEmpty() && StringUtils.isNotBlank(batch.getErrorMsg())) {
            result.getErrors().add(batch.getErrorMsg());
        }
        return result;
    }

    private void processBatch(Long batchId) {
        LfImportResultVO result = new LfImportResultVO();
        result.setBatchId(batchId);
        result.setStatus("PROCESSING");
        try {
            LfImportBatch batch = batchMapper.selectById(batchId);
            if (batch == null) {
                throw new ServiceException("导入批次不存在");
            }
            result.setFileName(batch.getFileName());

            List<LfStatementExcelRow> rows;
            byte[] content = fileArchiver.load(batch.getStoragePath());
            try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
                rows = ExcelBuilder.read(input, LfStatementExcelRow.class).doReadSync();
            }
            if (rows == null || rows.isEmpty()) {
                throw new ServiceException("文件中没有可导入的流水");
            }
            result.setTotalRows(rows.size());
            saveProgress(batchId, result);

            RLock lock = RedisUtils.getClient().getLock("panjia:lf:import:" + batch.getAccountId());
            lock.lock();
            try {
                executeImport(batch, rows, result);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }

            completeBatch(batchId, result);
            log.info("[理房通导入] 异步导入完成 batchId={} status={} total={} success={} fail={}",
                batchId, result.getStatus(), result.getTotalRows(), result.getSuccessRows(), result.getFailRows());
        } catch (Exception e) {
            log.error("[理房通导入] 异步导入失败 batchId={}", batchId, e);
            failBatch(batchId, result, e);
        }
    }

    private void executeImport(LfImportBatch batch, List<LfStatementExcelRow> rows,
                               LfImportResultVO result) {
        LfAccount account = accountMapper.selectById(batch.getAccountId());
        if (account == null) {
            throw new ServiceException("理房通账户不存在或已删除");
        }
        List<LfStatementRow> validRows = new ArrayList<>();
        int lineNo = 1;
        for (LfStatementExcelRow excel : rows) {
            lineNo++;
            try {
                LfStatementRow row = convert(excel, account, batch.getId(), lineNo);
                if (row != null) {
                    validRows.add(row);
                } else {
                    result.setSkippedRows(result.getSkippedRows() + 1);
                }
            } catch (Exception e) {
                result.getErrors().add("第 " + lineNo + " 行：" + e.getMessage());
            }
        }

        // 同一文件内记账流水号重复时以最后一行为准
        Map<String, LfStatementRow> dedup = new LinkedHashMap<>();
        for (LfStatementRow row : validRows) {
            dedup.put(row.getVoucherNo(), row);
        }
        validRows = new ArrayList<>(dedup.values());
        result.setFailRows(result.getErrors().size());
        saveProgress(batch.getId(), result);

        Map<String, LfStatementRow> existingByVoucher = loadExisting(account.getId(), validRows);
        List<LfStatementRow> affectedContracts = new ArrayList<>();
        List<LedgerSubject> subjects = expenseService.loadExpenseSubjects();
        List<DeptRowDTO> subtree = expenseService.loadSubtree(account.getStoreDeptId());

        for (int i = 0; i < validRows.size(); i += ROW_BATCH_SIZE) {
            List<LfStatementRow> chunk = validRows.subList(i, Math.min(i + ROW_BATCH_SIZE, validRows.size()));
            LfImportResultVO chunkResult = new LfImportResultVO();
            List<LfStatementRow> chunkContracts = new ArrayList<>();
            txTemplate.executeWithoutResult(status -> {
                for (LfStatementRow row : chunk) {
                    LfStatementRow existing = existingByVoucher.get(row.getVoucherNo());
                    if (existing == null) {
                        existing = findByBusinessKey(row, account.getId());
                    }
                    if (existing != null) {
                        preserveManualAllocation(row, existing);
                        row.setId(existing.getId());
                        row.setCreateTime(existing.getCreateTime());
                        row.setUpdateTime(LocalDateTime.now());
                        rowMapper.updateById(row);
                        chunkResult.setUpdateRows(chunkResult.getUpdateRows() + 1);
                    } else {
                        row.setCreateTime(LocalDateTime.now());
                        row.setUpdateTime(LocalDateTime.now());
                        rowMapper.insert(row);
                        chunkResult.setInsertRows(chunkResult.getInsertRows() + 1);
                    }
                    expenseService.applyExpenseMeta(row, subjects, subtree);
                    rowMapper.updateById(row);
                    expenseService.syncLedgerEntry(row, batch.getOperatorId());
                    if (StringUtils.isNotBlank(row.getContractNo())
                        && ("BUSINESS".equals(row.getCategory()) || "REVERSAL".equals(row.getCategory()))) {
                        chunkContracts.add(row);
                    }
                    chunkResult.setSuccessRows(chunkResult.getSuccessRows() + 1);
                }
            });
            result.setInsertRows(result.getInsertRows() + chunkResult.getInsertRows());
            result.setUpdateRows(result.getUpdateRows() + chunkResult.getUpdateRows());
            result.setSuccessRows(result.getSuccessRows() + chunkResult.getSuccessRows());
            affectedContracts.addAll(chunkContracts);
            saveProgress(batch.getId(), result);
        }

        List<String> contractNos = affectedContracts.stream()
            .map(LfStatementRow::getContractNo).filter(StringUtils::isNotBlank).distinct().toList();
        for (int i = 0; i < contractNos.size(); i += CONTRACT_BATCH_SIZE) {
            List<String> chunk = contractNos.subList(i, Math.min(i + CONTRACT_BATCH_SIZE, contractNos.size()));
            LfMatchService.Stats stats = matchService.matchContracts(chunk, batch.getOperatorId());
            result.setMatchContractCount(result.getMatchContractCount() + stats.getContractCount());
            result.setMatchedCount(result.getMatchedCount() + stats.getMatched());
            result.setDiffCount(result.getDiffCount() + stats.getDiff());
            result.setNotFoundCount(result.getNotFoundCount() + stats.getNotFound());
            result.setPendingSignRoleCount(result.getPendingSignRoleCount() + stats.getPendingSignRole());
            saveProgress(batch.getId(), result);
        }

        result.setPendingAllocCount(Math.toIntExact(rowMapper.selectCount(
            new LambdaQueryWrapper<LfStatementRow>()
                .eq(LfStatementRow::getBatchId, batch.getId())
                .eq(LfStatementRow::getAllocStatus, "PENDING"))));
        result.setPendingSubjectCount(Math.toIntExact(rowMapper.selectCount(
            new LambdaQueryWrapper<LfStatementRow>()
                .eq(LfStatementRow::getBatchId, batch.getId())
                .eq(LfStatementRow::getCategory, "FEE")
                .isNull(LfStatementRow::getSubjectId))));
        for (Map<String, Object> item : rowMapper.selectUnmappedSubjects(batch.getId())) {
            Object desc = item.get("desc");
            Object cnt = item.get("cnt");
            result.getUnmappedSubjects().add((desc == null ? "未识别费用" : desc) + "（" + cnt + " 笔）");
        }
    }

    private void saveProgress(Long batchId, LfImportResultVO result) {
        result.setStatus("PROCESSING");
        result.setFailRows(result.getErrors().size());
        LfImportBatch update = new LfImportBatch();
        update.setId(batchId);
        update.setTotalRows(result.getTotalRows());
        update.setSuccessRows(result.getSuccessRows());
        update.setFailRows(result.getFailRows());
        update.setStatus("PROCESSING");
        update.setResultDetail(JsonUtils.toJsonString(result));
        batchMapper.updateById(update);
    }

    private void completeBatch(Long batchId, LfImportResultVO result) {
        result.setStatus(result.getErrors().isEmpty() ? "SUCCESS" : "PARTIAL");
        result.setFailRows(result.getErrors().size());
        LfImportBatch update = new LfImportBatch();
        update.setId(batchId);
        update.setSuccessRows(result.getSuccessRows());
        update.setFailRows(result.getFailRows());
        update.setStatus(result.getStatus());
        update.setErrorMsg(errorSummary(result.getErrors()));
        update.setResultDetail(JsonUtils.toJsonString(result));
        update.setFinishTime(LocalDateTime.now());
        batchMapper.updateById(update);
    }

    private void failBatch(Long batchId, LfImportResultVO result, Exception error) {
        try {
            result.setBatchId(batchId);
            result.setStatus("FAILED");
            result.getErrors().add("导入失败：" + rootMessage(error));
            result.setFailRows(result.getErrors().size());
            LfImportBatch update = new LfImportBatch();
            update.setId(batchId);
            update.setTotalRows(result.getTotalRows());
            update.setSuccessRows(result.getSuccessRows());
            update.setFailRows(result.getFailRows());
            update.setStatus("FAILED");
            update.setErrorMsg(errorSummary(result.getErrors()));
            update.setResultDetail(JsonUtils.toJsonString(result));
            update.setFinishTime(LocalDateTime.now());
            batchMapper.updateById(update);
        } catch (Exception updateError) {
            log.error("[理房通导入] 批次失败状态保存异常 batchId={}", batchId, updateError);
        }
    }

    private LfImportResultVO toResult(LfImportBatch batch) {
        LfImportResultVO result = new LfImportResultVO();
        result.setBatchId(batch.getId());
        result.setFileName(batch.getFileName());
        result.setStatus(batch.getStatus());
        result.setTotalRows(intValue(batch.getTotalRows()));
        result.setSuccessRows(intValue(batch.getSuccessRows()));
        result.setFailRows(intValue(batch.getFailRows()));
        return result;
    }

    private String errorSummary(List<String> errors) {
        return errors.isEmpty() ? null
            : String.join("；", errors.subList(0, Math.min(20, errors.size())));
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return StringUtils.isBlank(current.getMessage()) ? current.getClass().getSimpleName() : current.getMessage();
    }

    private int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    private LfAccount resolveAccount(String fileName, Long storeDeptId) {
        if (StringUtils.isBlank(fileName)) {
            throw new ServiceException("文件名不能为空");
        }
        String lower = fileName.toLowerCase();
        List<LfAccount> matches = accountMapper.selectList(new LambdaQueryWrapper<LfAccount>()
            .eq(LfAccount::getStatus, "0")).stream()
            .filter(a -> StringUtils.isNotBlank(a.getFilePrefix())
                && lower.contains(a.getFilePrefix().toLowerCase()))
            .toList();
        if (matches.isEmpty()) {
            throw new ServiceException("文件名未匹配到理房通账户前缀，请先在「理房通账户配置」中维护");
        }
        if (matches.size() > 1) {
            throw new ServiceException("文件名匹配到多个账户前缀，请检查账户配置");
        }
        LfAccount account = matches.get(0);
        if (storeDeptId == null) {
            throw new ServiceException("请选择导入门店");
        }
        if (!storeDeptId.equals(account.getStoreDeptId())) {
            throw new ServiceException("文件名对应门店与所选门店不一致，请确认后再导入");
        }
        if (account.getStoreDeptId() == null || account.getStoreDeptId() == 0L) {
            throw new ServiceException("账户尚未绑定门店，请先在「理房通账户配置」中维护门店");
        }
        return account;
    }

    private LfStatementRow convert(LfStatementExcelRow excel, LfAccount account, Long batchId, int lineNo) {
        if (excel == null || StringUtils.isBlank(excel.getVoucherNo())
            && StringUtils.isBlank(excel.getTransactionTime())
            && StringUtils.isBlank(excel.getTransactionAmount())) {
            return null;
        }
        LocalDateTime time = parseTime(excel.getTransactionTime());
        BigDecimal amount = parseAmount(excel.getTransactionAmount());
        if (time == null || amount == null) {
            throw new ServiceException("动账时间或交易金额无法解析");
        }
        LfStatementRow row = new LfStatementRow();
        row.setBatchId(batchId);
        row.setAccountId(account.getId());
        row.setStoreDeptId(account.getStoreDeptId());
        row.setStoreName(account.getStoreName());
        row.setPeriod(YearMonth.from(time).toString());
        row.setTransactionTime(time);
        row.setIncomeExpenseType(StringUtils.trim(excel.getIncomeExpenseType()));
        row.setTransactionType(StringUtils.trim(excel.getTransactionType()));
        row.setTransactionAmount(amount);
        row.setAccountBalance(parseAmount(excel.getAccountBalance()));
        row.setBusinessDesc(StringUtils.trim(excel.getBusinessDesc()));
        row.setAddress(StringUtils.trim(excel.getAddress()));
        row.setHouseNo(StringUtils.trim(excel.getHouseNo()));
        row.setContractNo(StringUtils.trim(excel.getContractNo()));
        row.setTransactionRemark(StringUtils.trim(excel.getTransactionRemark()));
        row.setCounterpartyName(StringUtils.trim(excel.getCounterpartyName()));
        row.setBizOrderNo(StringUtils.trim(excel.getBizOrderNo()));
        row.setVoucherNo(StringUtils.isBlank(excel.getVoucherNo())
            ? fallbackVoucher(row, excel) : StringUtils.trim(excel.getVoucherNo()));
        row.setPaymentNo(StringUtils.trim(excel.getPaymentNo()));
        row.setRawRowNo(lineNo);
        classify(row);
        return row;
    }

    private String fallbackVoucher(LfStatementRow row, LfStatementExcelRow excel) {
        String key = row.getTransactionTime() + "|" + row.getTransactionAmount() + "|"
            + StringUtils.defaultString(row.getContractNo()) + "|" + StringUtils.defaultString(excel.getPaymentNo());
        return "GEN-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /** 分类：业务/冲减/费用/部门收入/提现/非业务；售前房屋美化单独标记 */
    private void classify(LfStatementRow row) {
        if (DEPARTMENT_INCOME_REMARK.equals(StringUtils.trim(row.getTransactionRemark()))) {
            row.setCategory("DEPT_INCOME");
            row.setDecorationFlag(false);
            return;
        }
        String text = StringUtils.defaultString(row.getBusinessDesc())
            + "|" + StringUtils.defaultString(row.getTransactionType())
            + "|" + StringUtils.defaultString(row.getTransactionRemark());
        boolean withdraw = text.contains("提现");
        if (withdraw) {
            row.setCategory("WITHDRAW");
            row.setDecorationFlag(false);
            return;
        }
        boolean decoration = text.contains("美化");
        if (decoration && StringUtils.isNotBlank(row.getContractNo())) {
            row.setCategory("BUSINESS");
            row.setDecorationFlag(true);
            return;
        }
        row.setDecorationFlag(false);
        // 二手业务全部通过合同处理，不作为独立支出；支出方向按合同冲减处理
        if (text.contains("二手")) {
            row.setCategory(StringUtils.defaultString(row.getIncomeExpenseType()).contains("支出")
                ? "REVERSAL" : "BUSINESS");
            return;
        }
        boolean reversal = REVERSAL_KEYWORDS.stream().anyMatch(text::contains);
        if (reversal) {
            row.setCategory("REVERSAL");
            return;
        }
        String type = StringUtils.defaultString(row.getIncomeExpenseType());
        if (type.contains("支出")) {
            row.setCategory("FEE");
        } else if (type.contains("收入") && StringUtils.isNotBlank(row.getContractNo())) {
            row.setCategory("BUSINESS");
        } else {
            row.setCategory("NON_BUSINESS");
        }
    }

    private void preserveManualAllocation(LfStatementRow row, LfStatementRow existing) {
        if ("MANUAL".equals(existing.getAllocStatus()) && existing.getExpenseDeptId() != null) {
            row.setAllocStatus("MANUAL");
            row.setExpenseDeptId(existing.getExpenseDeptId());
            row.setAllocBy(existing.getAllocBy());
            row.setAllocTime(existing.getAllocTime());
        }
    }

    private Map<String, LfStatementRow> loadExisting(Long accountId, List<LfStatementRow> rows) {
        Map<String, LfStatementRow> result = new HashMap<>();
        List<String> vouchers = rows.stream().map(LfStatementRow::getVoucherNo)
            .filter(StringUtils::isNotBlank).distinct().toList();
        for (int i = 0; i < vouchers.size(); i += 500) {
            List<String> chunk = vouchers.subList(i, Math.min(i + 500, vouchers.size()));
            for (LfStatementRow row : rowMapper.selectList(new LambdaQueryWrapper<LfStatementRow>()
                .eq(LfStatementRow::getAccountId, accountId)
                .in(LfStatementRow::getVoucherNo, chunk))) {
                result.put(row.getVoucherNo(), row);
            }
        }
        return result;
    }

    /** 兜底：记账流水号变化时按 动账时间 + 金额 + 合同编号 匹配 */
    private LfStatementRow findByBusinessKey(LfStatementRow row, Long accountId) {
        if (row.getTransactionTime() == null || row.getTransactionAmount() == null) {
            return null;
        }
        List<LfStatementRow> matches = rowMapper.selectList(new LambdaQueryWrapper<LfStatementRow>()
            .eq(LfStatementRow::getAccountId, accountId)
            .eq(LfStatementRow::getTransactionTime, row.getTransactionTime())
            .eq(LfStatementRow::getTransactionAmount, row.getTransactionAmount())
            .eq(StringUtils.isNotBlank(row.getContractNo()), LfStatementRow::getContractNo, row.getContractNo())
            .last("LIMIT 2"));
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private LocalDateTime parseTime(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String text = value.trim().replace("/", "-").replace("年", "-").replace("月", "-").replace("日", "");
        for (DateTimeFormatter formatter : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (Exception ignored) {
            }
        }
        try {
            return LocalDate.parse(text).atStartOfDay();
        } catch (Exception ignored) {
        }
        try {
            // Excel 序列日期
            BigDecimal serial = new BigDecimal(text);
            long days = serial.longValue();
            long millis = serial.subtract(BigDecimal.valueOf(days))
                .multiply(BigDecimal.valueOf(24L * 60 * 60 * 1000)).longValue();
            return LocalDate.of(1899, 12, 30).plusDays(days).atStartOfDay().plusNanos(millis * 1_000_000);
        } catch (Exception ignored) {
        }
        return null;
    }

    private BigDecimal parseAmount(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String text = value.trim().replace(",", "").replace("￥", "").replace("¥", "")
            .replace(" ", "");
        boolean negative = text.startsWith("(") && text.endsWith(")");
        if (negative) {
            text = text.substring(1, text.length() - 1);
        }
        try {
            BigDecimal amount = new BigDecimal(text);
            return negative ? amount.negate() : amount;
        } catch (Exception e) {
            return null;
        }
    }
}
