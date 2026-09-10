package com.panjia.importdomain.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.panjia.contracts.port.PeopleQueryPort;
import com.panjia.importdomain.domain.ImportBatch;
import com.panjia.importdomain.domain.ImportBatchStatus;
import com.panjia.importdomain.domain.ImportIssue;
import com.panjia.importdomain.domain.ImportIssueStatus;
import com.panjia.importdomain.domain.ImportIssueType;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.NormalizedRecord;
import com.panjia.importdomain.domain.NormalizedRecordType;
import com.panjia.importdomain.domain.raw.RawAttendance;
import com.panjia.importdomain.domain.raw.RawData;
import com.panjia.importdomain.domain.raw.RawManual;
import com.panjia.importdomain.domain.raw.RawNewSign;
import com.panjia.importdomain.domain.raw.RawPoints;
import com.panjia.importdomain.domain.raw.RawSigned;
import com.panjia.importdomain.datasource.DataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.datasource.SourceKeyGenerator;
import com.panjia.importdomain.mapper.ImportBatchMapper;
import com.panjia.importdomain.mapper.ImportIssueMapper;
import com.panjia.importdomain.mapper.NormalizedRecordMapper;
import com.panjia.importdomain.mapper.RawAttendanceMapper;
import com.panjia.importdomain.mapper.RawManualMapper;
import com.panjia.importdomain.mapper.RawNewSignMapper;
import com.panjia.importdomain.mapper.RawPointsMapper;
import com.panjia.importdomain.mapper.RawSignedMapper;
import com.panjia.importdomain.template.ImportTemplateBridge;
import com.panjia.importutil.archive.ArchiveResult;
import com.panjia.importutil.archive.FileArchiver;
import com.panjia.importutil.dto.ParsedSheet;
import com.panjia.importutil.parser.ParserFactory;
import com.panjia.importutil.template.model.ImportTemplate;
import com.panjia.importutil.validate.BasicValidator;
import com.panjia.importutil.validate.FieldError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入引擎（V2.0：交易业务单据域）。
 * <p>
 * 文件解析/归档/基础格式校验由 common-import-util 提供，本引擎只做：
 * <ul>
 *   <li>事务 A：落批次 + RawData（insert-only）+ ImportIssue</li>
 *   <li>事务 B：归一化产 NormalizedRecord（业绩/考勤/积分/费用五类单据）</li>
 * </ul>
 * 员工主数据导入已迁至 people 域，本域不再处理 EMPLOYEE。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportEngine {

    private final ImportTemplateBridge templateBridge;
    private final FileArchiver fileArchiver;
    private final ParserFactory parserFactory;
    private final BasicValidator basicValidator;
    private final List<DataSource> dataSources;
    private final List<SourceKeyGenerator> sourceKeyGenerators;
    private final ImportBatchMapper batchMapper;
    private final ImportIssueMapper issueMapper;
    private final NormalizedRecordMapper normalizedRecordMapper;
    private final RawSignedMapper rawSignedMapper;
    private final RawNewSignMapper rawNewSignMapper;
    private final RawAttendanceMapper rawAttendanceMapper;
    private final RawPointsMapper rawPointsMapper;
    private final RawManualMapper rawManualMapper;
    private final PeopleQueryPort peopleQueryPort;

    private static final JsonMapper OBJECT_MAPPER = new JsonMapper();

    /**
     * 从文件字节执行完整导入流程。
     *
     * @param sourceType 数据源类型（五类交易单据）
     * @param content    文件字节内容
     * @param fileName   原始文件名
     * @param period     归属月（YYYY-MM）
     * @param operatorId 操作人 ID
     * @param deptId      部门 ID
     * @return 批次 ID
     */
    public Long importFromFile(ImportSourceType sourceType, byte[] content,
                               String fileName, String period, Long operatorId, Long deptId) {
        // 1. 解析激活模板（工具层内存模型）
        ImportTemplate template = templateBridge.resolve(sourceType.getCode());

        // 2. 原始文件归档（审计锚点）
        ArchiveResult archived = fileArchiver.archive(content, fileName, "import");

        // 3. 文件解析（纯内存，不落库）
        ParsedSheet sheet;
        try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
            sheet = parserFactory.parse(in, template, fileName);
        } catch (Exception e) {
            log.error("文件解析失败: {}", fileName, e);
            throw new IllegalStateException("文件解析失败: " + e.getMessage(), e);
        }

        // 4. 基础格式校验（必填/类型/枚举/正则/长度）
        List<FieldError> fieldErrors = basicValidator.validate(sheet, template);

        // 5. 事务 A：落批次 + RawData + Issue
        ImportBatch batch = doParsePhase(sourceType, sheet, fieldErrors, fileName, period,
            operatorId, deptId, template, archived);

        // 6. 事务 B：归一化
        try {
            doNormalizePhase(batch.getId(), sourceType, period);
        } catch (Exception e) {
            log.error("归一化失败 batchId={}", batch.getId(), e);
            markFailed(batch.getId());
            throw e;
        }
        return batch.getId();
    }

    // ==================== 事务 A：解析落库 ====================

    @Transactional(rollbackFor = Exception.class)
    public ImportBatch doParsePhase(ImportSourceType sourceType, ParsedSheet sheet,
                                    List<FieldError> fieldErrors, String fileName, String period,
                                    Long operatorId, Long deptId, ImportTemplate template,
                                    ArchiveResult archived) {
        // 创建批次
        ImportBatch batch = new ImportBatch();
        batch.setSourceType(sourceType);
        batch.setTemplateVersion(template.getTemplateVersion());
        batch.setFileName(fileName);
        batch.setOriginalFileName(fileName);
        batch.setStoragePath(archived.getStoragePath());
        batch.setPeriod(period);
        batch.setStatus(ImportBatchStatus.PARSING);
        batch.setOperatorId(operatorId);
        batch.setDeptId(deptId);
        batch.setBatchNo(generateBatchNo(sourceType, period));
        batchMapper.insert(batch);

        // 选 DataSource 转 RawData
        DataSource ds = findDataSource(sourceType);
        ImportContext ctx = new ImportContext(batch.getId(), batch.getBatchNo(), period, template.getTemplateVersion());
        ParseResult parseResult = ds.parse(sheet, ctx);

        // 批量落 RawData
        batchInsertRawData(sourceType, parseResult.getRows());

        // 落 ImportIssue：基础校验错误 + DataSource 结构错误
        for (FieldError fe : fieldErrors) {
            issueMapper.insert(toIssue(batch.getId(), fe));
        }
        for (ImportIssue issue : parseResult.getIssues()) {
            issue.setBatchId(batch.getId());
            issueMapper.insert(issue);
        }

        int issueCount = fieldErrors.size() + parseResult.getIssues().size();
        batch.setTotalRows(sheet.getTotalRows());
        batch.setFailedRows(issueCount);
        batch.setSuccessRows(Math.max(0, sheet.getTotalRows() - sheet.getErrorRows()));
        batch.setStatus(ImportBatchStatus.NORMALIZING);
        batchMapper.updateById(batch);

        return batch;
    }

    // ==================== 事务 B：归一化 ====================

    @Transactional(rollbackFor = Exception.class)
    public void doNormalizePhase(Long batchId, ImportSourceType sourceType, String period) {
        ImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalStateException("批次不存在: " + batchId);
        }

        // 清旧归一化结果（重归一化场景）
        normalizedRecordMapper.deleteByBatchId(batchId);
        issueMapper.deleteByBatchId(batchId);

        List<ImportIssue> issues = new ArrayList<>();
        normalizePerformance(batchId, sourceType, period, issues);

        // 落新 issue
        for (ImportIssue issue : issues) {
            issue.setBatchId(batchId);
            issueMapper.insert(issue);
        }

        // 更新批次状态
        batch.finishNormalize(!issues.isEmpty());
        batch.setFailedRows(issues.size());
        batch.setSuccessRows(batch.getTotalRows() - issues.size());
        batchMapper.updateById(batch);
    }

    private void normalizePerformance(Long batchId, ImportSourceType sourceType,
                                      String period, List<ImportIssue> issues) {
        // 读 RawData
        List<? extends RawData> rawRows = selectRawData(sourceType, batchId);

        // 批量匹配员工
        Map<String, Long> codeToId = matchEmployees(rawRows);

        // sourceKey 生成器
        SourceKeyGenerator keyGen = findSourceKeyGenerator(sourceType);
        NormalizedRecordType recordType = toRecordType(sourceType);

        for (RawData raw : rawRows) {
            NormalizedRecord nr = new NormalizedRecord();
            nr.setBatchId(batchId);
            nr.setRecordType(recordType);
            nr.setPeriod(period);
            nr.setRawDataId(raw.getId());

            // 反序列化 rawJson 取标准化字段
            Map<String, Object> jsonMap = parseRawJson(raw.getRawJson());
            String externalCode = extractExternalCode(jsonMap);

            // 员工匹配
            if (externalCode != null && codeToId.containsKey(externalCode)) {
                nr.setEmployeeId(codeToId.get(externalCode));
            } else if (externalCode != null) {
                issues.add(buildIssue(raw.getRowNo(), ImportIssueType.EMPLOYEE_NOT_MATCH,
                    "employeeCode", externalCode, "员工未匹配: " + externalCode));
            }
            nr.setEmployeeExternalCode(externalCode);

            // sourceKey
            if (keyGen != null) {
                nr.setSourceKey(keyGen.generate(jsonMap));
            }

            // 金额/业务字段
            fillPerformanceFields(nr, sourceType, jsonMap);
            nr.setValidationStatus(nr.getEmployeeId() != null ? 1 : 0);

            normalizedRecordMapper.insert(nr);
        }
    }

    // ==================== 辅助方法 ====================

    private ImportIssue toIssue(Long batchId, FieldError fe) {
        ImportIssue issue = new ImportIssue();
        issue.setBatchId(batchId);
        issue.setRowNo(fe.getRowNo());
        issue.setIssueType(mapIssueType(fe.getReason()));
        issue.setFieldName(fe.getField());
        issue.setRawValue(truncate(fe.getRawValue(), 500));
        issue.setMessage(truncate(fe.getMessage(), 1000));
        issue.setStatus(ImportIssueStatus.OPEN);
        return issue;
    }

    private ImportIssueType mapIssueType(String reason) {
        return switch (reason == null ? "" : reason) {
            case "REQUIRED_MISSING" -> ImportIssueType.REQUIRED_MISSING;
            default -> ImportIssueType.COLUMN_TYPE_ERR;
        };
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String generateBatchNo(ImportSourceType type, String period) {
        String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return type.name() + "_" + (period == null ? "NOPERIOD" : period.replace("-", "")) + "_" + ts;
    }

    private DataSource findDataSource(ImportSourceType type) {
        return dataSources.stream()
            .filter(ds -> ds.sourceType() == type)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("未找到 DataSource: " + type));
    }

    private SourceKeyGenerator findSourceKeyGenerator(ImportSourceType type) {
        return sourceKeyGenerators.stream()
            .filter(g -> g.sourceType() == type)
            .findFirst()
            .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private void batchInsertRawData(ImportSourceType type, List<RawData> rows) {
        switch (type) {
            case KE_SIGNED -> {
                for (RawData r : rows) {
                    rawSignedMapper.insert((RawSigned) r);
                }
            }
            case KE_NEW_SIGN -> {
                for (RawData r : rows) {
                    rawNewSignMapper.insert((RawNewSign) r);
                }
            }
            case ATTENDANCE -> {
                for (RawData r : rows) {
                    rawAttendanceMapper.insert((RawAttendance) r);
                }
            }
            case POINTS -> {
                for (RawData r : rows) {
                    rawPointsMapper.insert((RawPoints) r);
                }
            }
            case OTHERS -> {
                for (RawData r : rows) {
                    rawManualMapper.insert((RawManual) r);
                }
            }
        }
    }

    private List<? extends RawData> selectRawData(ImportSourceType type, Long batchId) {
        return switch (type) {
            case KE_SIGNED -> rawSignedMapper.selectList(byBatch(RawSigned::getBatchId, batchId));
            case KE_NEW_SIGN -> rawNewSignMapper.selectList(byBatch(RawNewSign::getBatchId, batchId));
            case ATTENDANCE -> rawAttendanceMapper.selectList(byBatch(RawAttendance::getBatchId, batchId));
            case POINTS -> rawPointsMapper.selectList(byBatch(RawPoints::getBatchId, batchId));
            case OTHERS -> rawManualMapper.selectList(byBatch(RawManual::getBatchId, batchId));
        };
    }

    private <T> com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<T> byBatch(
        com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, Long> col, Long batchId) {
        return new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<T>()
            .eq(col, batchId);
    }

    private Map<String, Long> matchEmployees(List<? extends RawData> rawRows) {
        List<String> codes = rawRows.stream()
            .map(this::extractExternalCode)
            .filter(c -> c != null && !c.isEmpty())
            .distinct()
            .toList();
        if (codes.isEmpty()) {
            return Map.of();
        }
        return peopleQueryPort.findEmployeeIdsByCodes(codes);
    }

    private String extractExternalCode(RawData raw) {
        return extractExternalCode(parseRawJson(raw.getRawJson()));
    }

    private String extractExternalCode(Map<String, Object> jsonMap) {
        Object code = jsonMap.get("employeeCode");
        if (code != null) {
            return code.toString().trim();
        }
        Object roleSysNo = jsonMap.get("roleSysNo");
        if (roleSysNo != null) {
            return roleSysNo.toString().trim();
        }
        return null;
    }

    private void fillPerformanceFields(NormalizedRecord nr, ImportSourceType type, Map<String, Object> json) {
        switch (type) {
            case KE_SIGNED, KE_NEW_SIGN -> {
                nr.setBizType(str(json, "bizType"));
                nr.setReceivableAmount(decimal(json, "currentReceivable"));
                nr.setReceivedAmount(decimal(json, "currentReceived"));
                nr.setShareRatio(decimal(json, "shareRatio"));
                nr.setRoleType(str(json, "roleType"));
            }
            case ATTENDANCE -> {
                nr.setReceivableAmount(decimal(json, "leaveAmount"));
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("lateCount", intVal(json, "lateCount"));
                extra.put("absentDays", decimal(json, "absentDays"));
                nr.setExtraJson(toJson(extra));
            }
            case POINTS -> {
                nr.setReceivableAmount(decimal(json, "score"));
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("violationCount", intVal(json, "violationCount"));
                nr.setExtraJson(toJson(extra));
            }
            case OTHERS -> {
                nr.setBizType(str(json, "itemType"));
                nr.setReceivableAmount(decimal(json, "amount"));
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("reason", str(json, "reason"));
                nr.setExtraJson(toJson(extra));
            }
            default -> { /* no-op */ }
        }
    }

    private NormalizedRecordType toRecordType(ImportSourceType type) {
        return switch (type) {
            case KE_SIGNED -> NormalizedRecordType.SIGNED;
            case KE_NEW_SIGN -> NormalizedRecordType.NEW_SIGN;
            case ATTENDANCE -> NormalizedRecordType.ATTENDANCE;
            case POINTS -> NormalizedRecordType.POINTS;
            case OTHERS -> NormalizedRecordType.MANUAL;
        };
    }

    private ImportIssue buildIssue(Integer rowNo, ImportIssueType type, String field, String rawValue, String msg) {
        ImportIssue issue = new ImportIssue();
        issue.setRowNo(rowNo);
        issue.setIssueType(type);
        issue.setFieldName(field);
        issue.setRawValue(rawValue);
        issue.setMessage(msg);
        issue.setStatus(ImportIssueStatus.OPEN);
        return issue;
    }

    private Map<String, Object> parseRawJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(rawJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("rawJson 反序列化失败", e);
            return Map.of();
        }
    }

    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString().trim();
    }

    private BigDecimal decimal(Map<String, Object> map, String key) {
        String s = str(map, key);
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer intVal(Map<String, Object> map, String key) {
        String s = str(map, key);
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void markFailed(Long batchId) {
        ImportBatch batch = batchMapper.selectById(batchId);
        if (batch != null) {
            try {
                batch.fail();
                batchMapper.updateById(batch);
            } catch (Exception e) {
                log.warn("标记批次失败状态异常", e);
            }
        }
    }
}
