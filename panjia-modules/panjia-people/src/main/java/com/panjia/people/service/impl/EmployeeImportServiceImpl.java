package com.panjia.people.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.databind.json.JsonMapper;
import com.panjia.importutil.archive.ArchiveResult;
import com.panjia.importutil.archive.FileArchiver;
import com.panjia.importutil.dto.ParsedRow;
import com.panjia.importutil.dto.ParsedSheet;
import com.panjia.importutil.parser.ParserFactory;
import com.panjia.importutil.template.model.ColumnDef;
import com.panjia.importutil.template.model.ImportTemplate;
import com.panjia.importutil.validate.BasicValidator;
import com.panjia.importutil.validate.FieldError;
import com.panjia.people.domain.EmployeeStatus;
import com.panjia.people.domain.PeopleImportBatch;
import com.panjia.people.domain.PeopleImportBatchStatus;
import com.panjia.people.domain.PeopleImportIssue;
import com.panjia.people.domain.PeopleImportIssueStatus;
import com.panjia.people.domain.PeopleImportIssueType;
import com.panjia.people.domain.PeopleImportRaw;
import com.panjia.people.dto.EmployeeCreateDTO;
import com.panjia.people.mapper.PeopleImportBatchMapper;
import com.panjia.people.mapper.PeopleImportIssueMapper;
import com.panjia.people.mapper.PeopleImportRawMapper;
import com.panjia.people.port.DeptPort;
import com.panjia.people.service.EmployeeImportService;
import com.panjia.people.service.EmployeeService;
import com.panjia.people.service.PeopleImportTemplateBridge;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 员工导入服务实现（V6.0）。
 * <p>
 * 文件解析/归档/基础格式校验由 common-import-util 提供；本服务只做：
 * <ul>
 *   <li>阶段 A（独立小事务）：落批次 PARSING + 原始行 raw_json（insert-only）+
 *       基础格式 issue + 业务校验 issue（工号唯一/部门路径/师傅存在）；
 *       存在任一阻断 issue → 批次 FAILED（issue 保留供排查）；</li>
 *   <li>阶段 B（★ 单一大原子事务）：逐行 deptPort.ensureDept 自动建树 →
 *       复用 {@link EmployeeService#createEmployee}（员工 + sys_user + 岗位/角色 +
 *       8 条 fact + change_log + salary_record）；任一行失败整批回滚 → FAILED。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeImportServiceImpl implements EmployeeImportService {

    /** 员工导入模板编码 */
    private static final String TEMPLATE_CODE_EMPLOYEE = "EMPLOYEE";

    /** 文件归档业务目录 */
    private static final String BIZ_DIR = "people";

    /** 岗位名分隔符（模板约定：多岗位以 "/" 分隔） */
    private static final String POST_SEPARATOR = "/";

    /** 部门路径分隔符（模板约定：门店-组别） */
    private static final String DEPT_PATH_SEPARATOR = "-";

    /** 系统操作人（无人值守兜底） */
    private static final Long SYSTEM_OPERATOR_ID = 0L;

    private static final JsonMapper OBJECT_MAPPER = new JsonMapper();

    private final PeopleImportTemplateBridge templateBridge;
    private final FileArchiver fileArchiver;
    private final ParserFactory parserFactory;
    private final BasicValidator basicValidator;
    private final PeopleImportBatchMapper batchMapper;
    private final PeopleImportRawMapper rawMapper;
    private final PeopleImportIssueMapper issueMapper;
    private final EmployeeService employeeService;
    private final DeptPort deptPort;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @PostConstruct
    void init() {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Long importEmployees(byte[] content, String fileName, Long operatorId) {
        // 1. 解析启用模板（工具层内存模型）
        ImportTemplate template = templateBridge.resolve(TEMPLATE_CODE_EMPLOYEE);

        // 2. 原始文件归档（审计锚点）
        ArchiveResult archived = fileArchiver.archive(content, fileName, BIZ_DIR);

        // 3. 文件解析（纯内存）
        ParsedSheet sheet;
        try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
            sheet = parserFactory.parse(in, template, fileName);
        } catch (Exception e) {
            log.error("员工导入文件解析失败: {}", fileName, e);
            throw new ServiceException("文件解析失败: " + e.getMessage());
        }

        // 4. 基础格式校验（必填/类型/枚举/正则/长度，含解析阶段类型错误）
        List<FieldError> fieldErrors = basicValidator.validate(sheet, template);

        // 提取部门层级列（按 deptLevel 升序），阶段 A/B 共用
        List<ColumnDef> deptCols = deptColumns(template);

        // 5. 阶段 A：诊断（独立小事务：批次 + raw + issue）
        Long batchId = txTemplate.execute(status ->
            doDiagnose(template, archived, sheet, fieldErrors, fileName, operatorId, deptCols));

        PeopleImportBatch batch = batchMapper.selectById(batchId);
        if (batch != null && batch.getStatus() == PeopleImportBatchStatus.FAILED) {
            log.warn("员工导入批次诊断未通过 batchId={} failedRows={}", batchId, batch.getFailedRows());
            return batchId;
        }

        // 6. 阶段 B：单一大原子事务落地
        try {
            txTemplate.executeWithoutResult(status -> doImport(batchId, sheet, operatorId, deptCols));
        } catch (Exception e) {
            log.error("员工导入落地失败，整批回滚 batchId={}", batchId, e);
            markFailed(batchId, "落地失败整批回滚: " + e.getMessage());
            throw new ServiceException("员工导入失败: " + e.getMessage());
        }
        return batchId;
    }

    @Override
    public List<PeopleImportBatch> listBatches() {
        return batchMapper.selectList(new LambdaQueryWrapper<PeopleImportBatch>()
            .orderByDesc(PeopleImportBatch::getCreateTime));
    }

    @Override
    public PeopleImportBatch getBatch(Long batchId) {
        return batchMapper.selectById(batchId);
    }

    @Override
    public List<PeopleImportIssue> listIssues(Long batchId) {
        return issueMapper.selectList(new LambdaQueryWrapper<PeopleImportIssue>()
            .eq(PeopleImportIssue::getBatchId, batchId)
            .orderByAsc(PeopleImportIssue::getRowNo));
    }

    // ==================== 阶段 A：诊断 ====================

    /**
     * 诊断阶段（在独立事务内执行）：落批次 + 原始行 + 问题清单。
     *
     * @return 批次 ID
     */
    private Long doDiagnose(ImportTemplate template, ArchiveResult archived, ParsedSheet sheet,
                            List<FieldError> fieldErrors, String fileName, Long operatorId,
                            List<ColumnDef> deptCols) {
        PeopleImportBatch batch = new PeopleImportBatch();
        batch.setBatchNo(generateBatchNo());
        batch.setTemplateCode(template.getTemplateCode());
        batch.setTemplateVersion(template.getTemplateVersion());
        batch.setFileName(fileName);
        batch.setStoragePath(archived.getStoragePath());
        batch.setFileHash(archived.getFileHash());
        batch.setTotalRows(sheet.getTotalRows());
        batch.setStatus(PeopleImportBatchStatus.PARSING);
        batch.setOperatorId(operatorId);
        batchMapper.insert(batch);

        // 逐行落原始归档（raw_json = 全量原始字符串）
        for (ParsedRow row : sheet.getRows()) {
            PeopleImportRaw raw = new PeopleImportRaw();
            raw.setBatchId(batch.getId());
            raw.setRowNo(row.getRowNo());
            raw.setRawJson(toJson(row.getRawValues()));
            rawMapper.insert(raw);
        }

        // 问题清单：基础格式 issue + 业务校验 issue
        List<PeopleImportIssue> issues = new ArrayList<>();
        for (FieldError fe : fieldErrors) {
            issues.add(toIssue(batch.getId(), fe));
        }
        issues.addAll(businessValidate(batch.getId(), sheet, deptCols));
        for (PeopleImportIssue issue : issues) {
            issueMapper.insert(issue);
        }

        boolean blocked = issues.stream().anyMatch(i -> i.getIssueType().isBlocking());
        batch.setFailedRows(issues.size());
        batch.setSuccessRows(blocked ? 0 : sheet.getTotalRows());
        batch.setStatus(blocked ? PeopleImportBatchStatus.FAILED : PeopleImportBatchStatus.VALIDATING);
        if (blocked) {
            batch.setRemark("诊断未通过：存在 " + issues.size() + " 个阻断问题，请下载问题清单修正后重新导入");
        }
        batchMapper.updateById(batch);
        return batch.getId();
    }

    /**
     * 业务校验（工号唯一 / 部门层级路径 / 师傅存在）。
     */
    private List<PeopleImportIssue> businessValidate(Long batchId, ParsedSheet sheet, List<ColumnDef> deptCols) {
        List<PeopleImportIssue> issues = new ArrayList<>();

        // 工号：文件内首次出现行号 + 全量收集
        Map<String, Integer> codeFirstRow = new LinkedHashMap<>();
        Set<String> fileCodes = new HashSet<>();
        List<String> allCodes = new ArrayList<>();
        for (ParsedRow row : sheet.getRows()) {
            String code = str(row, "employee_code");
            if (StringUtils.isBlank(code)) {
                continue;
            }
            codeFirstRow.putIfAbsent(code, row.getRowNo());
            fileCodes.add(code);
            allCodes.add(code);

            // 部门层级路径：按 deptLevel 升序拼接；任一必填级缺失或跳级则记录 issue
            String deptPath = tryAssembleDeptPath(row, deptCols);
            if (deptPath == null) {
                issues.add(buildIssue(batchId, row.getRowNo(), PeopleImportIssueType.DEPT_PATH_INVALID,
                    "dept_path", joinDeptValues(row, deptCols),
                    "部门层级路径非法: " + describeDeptColumns(deptCols)));
            }
        }

        // 工号重复：文件内（非首次出现行）
        Set<String> seen = new HashSet<>();
        for (ParsedRow row : sheet.getRows()) {
            String code = str(row, "employee_code");
            if (StringUtils.isBlank(code)) {
                continue;
            }
            if (!seen.add(code)) {
                issues.add(buildIssue(batchId, row.getRowNo(), PeopleImportIssueType.DUPLICATE_CODE,
                    "employee_code", code, "工号在文件内重复（首次出现于第 " + codeFirstRow.get(code) + " 行）: " + code));
            }
        }

        // 工号重复：库内已存在
        Map<String, Long> existingCodes = employeeService.findEmployeeIdsByCodes(allCodes);
        for (ParsedRow row : sheet.getRows()) {
            String code = str(row, "employee_code");
            if (StringUtils.isNotBlank(code) && existingCodes.containsKey(code)) {
                issues.add(buildIssue(batchId, row.getRowNo(), PeopleImportIssueType.DUPLICATE_CODE,
                    "employee_code", code, "工号在系统中已存在: " + code));
            }
        }

        // 师傅工号：库内与本批次均不存在 → 阻断
        List<String> mentorCodes = sheet.getRows().stream()
            .map(r -> str(r, "mentor_code"))
            .filter(StringUtils::isNotBlank)
            .distinct()
            .toList();
        Map<String, Long> existingMentors = employeeService.findEmployeeIdsByCodes(mentorCodes);
        for (ParsedRow row : sheet.getRows()) {
            String mentorCode = str(row, "mentor_code");
            if (StringUtils.isBlank(mentorCode)) {
                continue;
            }
            if (!existingMentors.containsKey(mentorCode) && !fileCodes.contains(mentorCode)) {
                issues.add(buildIssue(batchId, row.getRowNo(), PeopleImportIssueType.MENTOR_NOT_FOUND,
                    "mentor_code", mentorCode, "师傅工号不存在（系统与本批次均无）: " + mentorCode));
            }
        }

        return issues;
    }

    // ==================== 阶段 B：单一大原子事务落地 ====================

    /**
     * 落地阶段（在单一大原子事务内执行）：逐行建树 + 复用员工新增全流程。
     */
    private void doImport(Long batchId, ParsedSheet sheet, Long operatorId, List<ColumnDef> deptCols) {
        PeopleImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalStateException("导入批次不存在: " + batchId);
        }
        batch.setStatus(PeopleImportBatchStatus.IMPORTING);
        batchMapper.updateById(batch);

        Long operator = operatorId != null ? operatorId : SYSTEM_OPERATOR_ID;
        for (ParsedRow row : sheet.getRows()) {
            EmployeeCreateDTO dto = toCreateDTO(row, deptCols);
            employeeService.createEmployee(dto, operator);
        }

        batch.setStatus(PeopleImportBatchStatus.SUCCESS);
        batch.setSuccessRows(sheet.getTotalRows());
        batch.setFailedRows(0);
        batch.setRemark(null);
        batchMapper.updateById(batch);
    }

    /**
     * 解析行 → 新增员工 DTO（映射规则与旧 EmployeeImportSink 一致）。
     */
    private EmployeeCreateDTO toCreateDTO(ParsedRow row, List<ColumnDef> deptCols) {
        String deptFull = assembleDeptPath(row, deptCols);
        Long deptId = deptPort.ensureDept(deptFull);

        EmployeeCreateDTO dto = new EmployeeCreateDTO();
        dto.setDeptId(deptId);
        dto.setEmployeeCode(str(row, "employee_code"));
        dto.setEmployeeName(str(row, "employee_name"));
        dto.setPostNames(splitPosts(str(row, "post_names")));
        dto.setLevelCode(str(row, "level"));
        dto.setPhone(str(row, "phone"));
        dto.setIdCard(str(row, "id_card"));
        dto.setReportDate(date(row, "report_date"));
        dto.setHireDate(date(row, "hire_date"));
        dto.setSocialInsured(bool(row, "social"));
        dto.setHousingInsured(bool(row, "housing"));
        dto.setCommercialInsured(bool(row, "commercial"));
        dto.setDormitory(bool(row, "dormitory"));
        dto.setParttime(bool(row, "parttime"));
        dto.setMentorCode(str(row, "mentor_code"));
        // 兼职员工初始状态 = PARTTIME，其余 ACTIVE
        dto.setStatus(Boolean.TRUE.equals(bool(row, "parttime"))
            ? EmployeeStatus.PARTTIME.getCode()
            : EmployeeStatus.ACTIVE.getCode());
        return dto;
    }

    // ==================== 辅助方法 ====================

    /**
     * 阶段 B 失败后在新事务内标记批次 FAILED（落地事务已回滚，批次/raw/issue 保留）。
     */
    private void markFailed(Long batchId, String reason) {
        try {
            txTemplate.executeWithoutResult(status -> {
                PeopleImportBatch batch = batchMapper.selectById(batchId);
                if (batch != null) {
                    batch.setStatus(PeopleImportBatchStatus.FAILED);
                    batch.setRemark(truncate(reason, 500));
                    batchMapper.updateById(batch);
                }
            });
        } catch (Exception e) {
            log.warn("标记批次 FAILED 异常 batchId={}", batchId, e);
        }
    }

    private PeopleImportIssue toIssue(Long batchId, FieldError fe) {
        return buildIssue(batchId, fe.getRowNo(), mapIssueType(fe),
            fe.getField(), fe.getRawValue(), truncate(fe.getMessage(), 1000));
    }

    /**
     * 工具层基础校验原因 → people 域问题类型。
     */
    private PeopleImportIssueType mapIssueType(FieldError fe) {
        String reason = fe.getReason() == null ? "" : fe.getReason();
        return switch (reason) {
            case "REQUIRED_MISSING" -> PeopleImportIssueType.REQUIRED_MISSING;
            case "ENUM_INVALID" -> "level".equals(fe.getField())
                ? PeopleImportIssueType.LEVEL_INVALID
                : PeopleImportIssueType.COLUMN_TYPE_ERR;
            default -> PeopleImportIssueType.COLUMN_TYPE_ERR;
        };
    }

    private PeopleImportIssue buildIssue(Long batchId, Integer rowNo, PeopleImportIssueType type,
                                         String field, String rawValue, String message) {
        PeopleImportIssue issue = new PeopleImportIssue();
        issue.setBatchId(batchId);
        issue.setRowNo(rowNo);
        issue.setIssueType(type);
        issue.setFieldName(field);
        issue.setRawValue(truncate(rawValue, 500));
        issue.setMessage(truncate(message, 1000));
        issue.setStatus(PeopleImportIssueStatus.OPEN);
        return issue;
    }

    /**
     * 提取模板中所有 deptLevel 列（部门层级），按层级升序排序。
     * <p>
     * 模板里以 deptLevel 字段标识该列对应部门的第几级，未配置则该批次无部门字段。
     */
    private List<ColumnDef> deptColumns(ImportTemplate template) {
        if (template == null || template.getColumns() == null) {
            return List.of();
        }
        return template.getColumns().stream()
            .filter(c -> c.getDeptLevel() != null)
            .sorted(Comparator.comparingInt(ColumnDef::getDeptLevel))
            .toList();
    }

    /**
     * 诊断阶段用：拼接部门层级路径；失败返回 null（由调用方落 issue）。
     * 规则：
     * <ul>
     *   <li>任一 required=true 的 deptLevel 列为空 → 失败；</li>
     *   <li>存在跳级（低级为空但更高级填了） → 失败；</li>
     *   <li>未配置任何 deptLevel 列 → 失败（按业务约定部门必填）。</li>
     * </ul>
     */
    private String tryAssembleDeptPath(ParsedRow row, List<ColumnDef> deptCols) {
        if (deptCols.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>(deptCols.size());
        boolean seenEmpty = false;
        for (ColumnDef col : deptCols) {
            String v = str(row, col.getField());
            boolean blank = StringUtils.isBlank(v);
            if (blank) {
                if (col.isRequired()) {
                    return null;
                }
                seenEmpty = true;
                continue;
            }
            if (seenEmpty) {
                return null;
            }
            parts.add(v);
        }
        return parts.isEmpty() ? null : String.join(DEPT_PATH_SEPARATOR, parts);
    }

    /**
     * 阶段 B 用：拼接部门层级路径；失败抛 ServiceException（整批回滚）。
     */
    private String assembleDeptPath(ParsedRow row, List<ColumnDef> deptCols) {
        String path = tryAssembleDeptPath(row, deptCols);
        if (path == null) {
            throw new ServiceException("部门层级路径非法（行 " + row.getRowNo() + "）: "
                + describeDeptColumns(deptCols));
        }
        return path;
    }

    /**
     * 把行内所有部门层级字段值用 "/" 拼接，给 issue.rawValue 用（便于前端定位）。
     */
    private String joinDeptValues(ParsedRow row, List<ColumnDef> deptCols) {
        return deptCols.stream()
            .map(c -> str(row, c.getField()))
            .map(s -> s == null ? "" : s)
            .collect(Collectors.joining("/"));
    }

    /**
     * 描述部门层级列（如 "大区/门店/小组"），给 issue.message 用。
     */
    private String describeDeptColumns(List<ColumnDef> deptCols) {
        return deptCols.stream().map(ColumnDef::getColName).collect(Collectors.joining("/"));
    }

    /**
     * 岗位名字符串 → 集合（"/" 分隔，去空白去重）。
     */
    private List<String> splitPosts(String raw) {
        if (StringUtils.isBlank(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(POST_SEPARATOR))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
    }

    private String str(ParsedRow row, String field) {
        String v = row.getRawValues().get(field);
        return v == null ? null : v.trim();
    }

    private Boolean bool(ParsedRow row, String field) {
        Object v = row.getValues().get(field);
        return v instanceof Boolean b ? b : null;
    }

    private LocalDate date(ParsedRow row, String field) {
        Object v = row.getValues().get(field);
        return v instanceof LocalDate d ? d : null;
    }

    private String generateBatchNo() {
        return "PEIMP" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("raw_json 序列化失败", e);
            return "{}";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
