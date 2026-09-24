package com.panjia.importdomain.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.event.ImportBatchArchivedEvent;
import com.panjia.contracts.port.ConversionFactorPort;
import com.panjia.contracts.port.PeopleQueryPort;
import com.panjia.importdomain.domain.ImportBatch;
import com.panjia.importdomain.domain.ImportBatchStatus;
import com.panjia.importdomain.domain.ImportIssue;
import com.panjia.importdomain.domain.ImportIssuePhase;
import com.panjia.importdomain.domain.ImportIssueStatus;
import com.panjia.importdomain.domain.ImportIssueType;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.NormalizedRecord;
import com.panjia.importdomain.domain.NormalizedRecordType;
import com.panjia.importdomain.domain.raw.RawAttendance;
import com.panjia.importdomain.domain.raw.RawData;
import com.panjia.importdomain.domain.raw.RawManual;
import com.panjia.importdomain.domain.raw.RawPayroll;
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
import com.panjia.importdomain.mapper.RawPayrollMapper;
import com.panjia.importdomain.mapper.RawPointsMapper;
import com.panjia.importdomain.mapper.RawSignedMapper;
import com.panjia.importdomain.template.ImportTemplateBridge;
import com.panjia.importdomain.template.TemplateHeaderSniffer;
import com.panjia.importutil.archive.ArchiveResult;
import com.panjia.importutil.archive.FileArchiver;
import com.panjia.importutil.convert.TypeConverter;
import com.panjia.importutil.dto.ParsedSheet;
import com.panjia.importutil.parser.ParserFactory;
import com.panjia.importutil.template.model.ColumnDef;
import com.panjia.importutil.template.model.ImportTemplate;
import com.panjia.importutil.validate.BasicValidator;
import com.panjia.importutil.validate.FieldError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final TemplateHeaderSniffer headerSniffer;
    private final FileArchiver fileArchiver;
    private final ParserFactory parserFactory;
    private final BasicValidator basicValidator;
    private final List<DataSource> dataSources;
    private final List<SourceKeyGenerator> sourceKeyGenerators;
    private final ImportBatchMapper batchMapper;
    private final ImportIssueMapper issueMapper;
    private final NormalizedRecordMapper normalizedRecordMapper;
    private final RawSignedMapper rawSignedMapper;
    private final RawAttendanceMapper rawAttendanceMapper;
    private final RawPointsMapper rawPointsMapper;
    private final RawManualMapper rawManualMapper;
    private final RawPayrollMapper rawPayrollMapper;
    private final PeopleQueryPort peopleQueryPort;
    private final ConversionFactorPort conversionFactorPort;
    private final EventPort eventPort;
    private final AttendanceSummaryAggregator attendanceSummaryAggregator;
    private final ScoreSummaryAggregator scoreSummaryAggregator;
    private final BatchSupersedeService batchSupersedeService;

    /** 历史工资多模板批次的模板版本快照（一文件 10 套模板，批次级统一标识） */
    private static final String HIST_TEMPLATE_VERSION = "HIST_MULTI_V1";

    /**
     * 自身代理引用（绕过同类内部方法调用的代理拦截问题）。
     * <p>
     * Spring AOP / CGLIB 代理只拦截外部 bean 进入本对象的入口；本类内部
     * {@code this.doParsePhase(...)} / {@code this.doNormalizePhase(...)}
     * 会绕过代理，导致方法上的 {@code @Transactional} 完全失效。
     * 注入自身代理引用（{@code self}），让内部调用走代理，使事务注解生效。
     * <p>
     * 字段标 {@code @Autowired @Lazy} 且非 final：
     * <ul>
     *   <li>{@code @Lazy} 解决「构造器注入自我引用」的循环依赖（提前暴露半成品代理）</li>
     *   <li>非 final 字段不进 Lombok {@code @RequiredArgsConstructor} 生成的构造器</li>
     * </ul>
     * <p>
     * 历史教训：本字段是 L1 修复「{@code No existing transaction found for transaction marked with
     * propagation 'mandatory'}」运行时异常时新增的——事件发布前置要求 MANDATORY 事务上下文，
     * 而 {@code @Transactional} 失效会导致所有 mapper 操作走 auto-commit（数据能落库），
     * 但 {@code eventPort.emit()} 直接抛 IllegalTransactionStateException。
     */
    @Autowired
    @Lazy
    private ImportEngine self;

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
        // 历史工资：多模板管线（同 source_type 10 套激活模板逐 sheet 解析，建一个批次）
        if (sourceType == ImportSourceType.HISTORY_PAYROLL) {
            return self.importHistoryPayroll(sourceType, content, fileName, period, operatorId, deptId);
        }
        // 1. 嗅探文件表头 → 多激活模板时按表头自动匹配（原始文件/简版模板共存）
        List<List<String>> headerRows;
        try (ByteArrayInputStream probe = new ByteArrayInputStream(content)) {
            headerRows = headerSniffer.sniff(probe, fileName);
        } catch (Exception e) {
            log.error("表头嗅探失败: {}", fileName, e);
            throw new IllegalStateException("无法读取上传文件，请确认文件未损坏: " + e.getMessage(), e);
        }
        ImportTemplate template = templateBridge.resolveByHeaders(sourceType.getCode(), headerRows);

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

        // 3.5 文件形态防呆：表头与模板完全不匹配 / 没有数据行 → 直接拦截，不创建批次。
        // 此前随机文件会解析出 0 行（或表头全对不上的空行）静默走完归一化并 ARCHIVED，
        // 前端显示"上传成功"，用户误以为导入成功。
        validateSheetShape(sheet, template);

        // 4. 基础格式校验（必填/类型/枚举/正则/长度）
        List<FieldError> fieldErrors = basicValidator.validate(sheet, template);

        // 5. 事务 A：落批次 + RawData + Issue
        // 走 self 代理：this.doParsePhase 会绕过 Spring AOP 代理 → @Transactional 失效 → 无事务上下文
        // → 后续 eventPort.emit() (MANDATORY) 会抛 IllegalTransactionStateException。
        ImportBatch batch = self.doParsePhase(sourceType, sheet, fieldErrors, fileName, period,
            operatorId, deptId, template, archived);

        // 6. 事务 B：归一化
        try {
            self.doNormalizePhase(batch.getId(), sourceType, period);
        } catch (Exception e) {
            log.error("归一化失败 batchId={}", batch.getId(), e);
            markFailed(batch.getId());
            throw e;
        }
        return batch.getId();
    }

    // ==================== 历史工资多模板管线 ====================

    /** 单 sheet 解析产物（模板 code + 基础校验错误随行，事务 A 一并落库） */
    private record HistorySheetParse(ParsedSheet sheet, String templateCode, List<FieldError> errors) {
    }

    /**
     * 历史工资导入（HISTORY_PAYROLL 标准管线）：同文件全部激活模板逐 sheet 解析
     * （一模板一 sheet：工资族/考勤口径/积分口径/新签/结佣各自成模板；绩效和扣款
     * 左右双表拆两套模板各取一个「姓名」列），建 <b>一个</b> 批次
     * （templateVersion=HIST_MULTI_V1），RawData 按运行时类型分流 4 张 raw 表
     * （RawSigned/RawAttendance/RawPoints/RawPayroll）。
     * <p>
     * 事务语义与标准管线一致：事务 A（批次+RawData+PARSE issue）→ 事务 B
     * （归一化+归档事件）。员工按姓名批量匹配（天街历史表无工号列），未匹配
     * → NORMALIZE issue → PENDING_CONFIRM 人工处理后重归一化。
     * <p>
     * 与标准管线的差异点（模板层已配置）：
     * <ul>
     *   <li>历史数据脏（标题行/透视残留/「不考核」文本）：模板全列 STRING+非必填，
     *       宽容解析放消费端；空姓名行视为合并单元格残留静默跳过（与老导入器
     *       「仅取有姓名行」一致）；</li>
     *   <li>缺失 sheet（无总监的门店）容忍跳过；</li>
     *   <li>业绩「85后」列为折算后金额：归一化期 ÷ bizType 折算因子还原原始金额。</li>
     * </ul>
     */
    public Long importHistoryPayroll(ImportSourceType sourceType, byte[] content,
                                     String fileName, String period, Long operatorId, Long deptId) {
        List<com.panjia.importdomain.domain.ImportTemplate> templates =
            templateBridge.listActive(sourceType.getCode());
        if (templates.isEmpty()) {
            throw new IllegalStateException("未找到激活模板: sourceType=" + sourceType.getCode());
        }
        // 多模板解析（每模板独立流：fesod 读完即关）
        List<HistorySheetParse> parsed = new ArrayList<>();
        for (com.panjia.importdomain.domain.ImportTemplate entity : templates) {
            ImportTemplate tool = templateBridge.toToolModel(entity);
            ParsedSheet sheet;
            try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
                sheet = parserFactory.parse(in, tool, fileName);
            } catch (Exception e) {
                // sheet 缺失（无总监/无店长的门店文件）容忍跳过；模板结构性错误
                // （表头不匹配/行数超限）由 validateSheetShape 与解析器自行拦截
                log.warn("[历史工资] sheet 解析失败跳过: template={}, file={}",
                    entity.getTemplateCode(), fileName, e);
                continue;
            }
            if (sheet.getRows().isEmpty()) {
                log.info("[历史工资] sheet 无数据行跳过: template={}", entity.getTemplateCode());
                continue;
            }
            validateSheetShape(sheet, tool);
            parsed.add(new HistorySheetParse(sheet, entity.getTemplateCode(),
                basicValidator.validate(sheet, tool)));
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("文件中没有可导入的数据行，请确认上传的是天街工资表");
        }

        // 原始文件归档（审计锚点）
        ArchiveResult archived = fileArchiver.archive(content, fileName, "import");

        // 事务 A：建 1 个批次 + 全部 RawData + PARSE issue
        ImportBatch batch = self.doHistoryParsePhase(sourceType, parsed, fileName, period,
            operatorId, deptId, archived);

        // 事务 B：归一化（姓名匹配 + 口径分流 + 反折算）
        try {
            self.doNormalizePhase(batch.getId(), sourceType, period);
        } catch (Exception e) {
            log.error("归一化失败 batchId={}", batch.getId(), e);
            markFailed(batch.getId());
            throw e;
        }
        return batch.getId();
    }

    /**
     * 历史工资事务 A：建一个批次，循环各 sheet 产物转 RawData 分流落库，
     * PARSE issue 一并入库，行数/问题数汇总后进入 NORMALIZING。
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportBatch doHistoryParsePhase(ImportSourceType sourceType, List<HistorySheetParse> sheets,
                                           String fileName, String period, Long operatorId, Long deptId,
                                           ArchiveResult archived) {
        ImportBatch batch = new ImportBatch();
        batch.setSourceType(sourceType);
        batch.setTemplateVersion(HIST_TEMPLATE_VERSION);
        batch.setFileName(fileName);
        batch.setOriginalFileName(fileName);
        batch.setStoragePath(archived.getStoragePath());
        batch.setPeriod(period);
        batch.setStatus(ImportBatchStatus.PARSING);
        batch.setOperatorId(operatorId);
        batch.setDeptId(deptId);
        batch.setBatchNo(generateBatchNo(sourceType, period));
        batchMapper.insert(batch);

        DataSource ds = findDataSource(sourceType);
        int totalRows = 0;
        int issueCount = 0;
        for (HistorySheetParse sp : sheets) {
            ImportContext ctx = new ImportContext(batch.getId(), batch.getBatchNo(), period,
                HIST_TEMPLATE_VERSION, sp.templateCode());
            ParseResult parseResult = ds.parse(sp.sheet(), ctx);
            batchInsertRawData(sourceType, parseResult.getRows());
            for (FieldError fe : sp.errors()) {
                issueMapper.insert(toIssue(batch.getId(), fe));
            }
            for (ImportIssue issue : parseResult.getIssues()) {
                issue.setBatchId(batch.getId());
                issueMapper.insert(issue);
            }
            totalRows += sp.sheet().getTotalRows();
            issueCount += sp.errors().size() + parseResult.getIssues().size();
        }

        batch.setTotalRows(totalRows);
        batch.setFailedRows(issueCount);
        batch.setSuccessRows(Math.max(0, totalRows - issueCount));
        batch.setStatus(ImportBatchStatus.NORMALIZING);
        batchMapper.updateById(batch);
        return batch;
    }

    /**
     * 历史工资归一化：按姓名批量匹配员工（重名视为歧义不匹配 → issue），
     * 按 RawData 运行时类型 + raw_json 口径标记定 recordType 并填充口径字段。
     * 空姓名行为合并单元格/透视残留，静默跳过（与老导入器「仅取有姓名行」一致）。
     */
    private void normalizeHistory(Long batchId, String period, List<ImportIssue> issues) {
        List<RawData> rawRows = new ArrayList<>();
        rawRows.addAll(rawSignedMapper.selectList(byBatch(RawSigned::getBatchId, batchId)));
        rawRows.addAll(rawAttendanceMapper.selectList(byBatch(RawAttendance::getBatchId, batchId)));
        rawRows.addAll(rawPointsMapper.selectList(byBatch(RawPoints::getBatchId, batchId)));
        rawRows.addAll(rawPayrollMapper.selectList(byBatch(RawPayroll::getBatchId, batchId)));

        // 姓名批量富化（一次查询防 N+1；findEmployeeRefsByNames 重名不返回）
        Set<String> names = rawRows.stream()
            .map(r -> str(parseRawJson(r.getRawJson()), "employeeName"))
            .filter(n -> n != null && !n.isEmpty())
            .collect(Collectors.toSet());
        Map<String, com.panjia.contracts.dto.EmployeeRef> refs = names.isEmpty()
            ? Map.of()
            : peopleQueryPort.findEmployeeRefsByNames(names);

        for (RawData raw : rawRows) {
            Map<String, Object> json = parseRawJson(raw.getRawJson());
            String name = str(json, "employeeName");
            if (name == null || name.isEmpty()) {
                continue;
            }
            com.panjia.contracts.dto.EmployeeRef ref = refs.get(name);
            if (ref == null) {
                issues.add(buildIssue(raw.getRowNo(), ImportIssueType.EMPLOYEE_NOT_MATCH,
                    "employeeName", truncate(name, 500), "员工未匹配(重名或不存在): " + name));
                continue;
            }

            NormalizedRecord nr = new NormalizedRecord();
            nr.setBatchId(batchId);
            nr.setRecordType(historyRecordType(raw, json));
            nr.setPeriod(period);
            nr.setRawDataId(raw.getId());
            nr.setEmployeeId(ref.employeeId());
            nr.setEmployeeExternalCode(ref.employeeCode());
            nr.setSourceKey(historySourceKey(raw, nr.getRecordType(), json, ref.employeeCode()));
            fillHistoryFields(nr, json);
            nr.setValidationStatus(1);
            normalizedRecordMapper.insert(nr);
        }
    }

    /** RawData 运行时类型 + raw_json 口径标记 → 归一化记录类型 */
    private NormalizedRecordType historyRecordType(RawData raw, Map<String, Object> json) {
        if (raw instanceof RawSigned) {
            return "HIST_REAL".equals(str(json, "recordType"))
                ? NormalizedRecordType.HIST_REAL
                : NormalizedRecordType.HIST_EXPECT;
        }
        if (raw instanceof RawAttendance) {
            return NormalizedRecordType.ATTENDANCE;
        }
        if (raw instanceof RawPoints) {
            return NormalizedRecordType.POINTS;
        }
        return NormalizedRecordType.PAYROLL_WAGE;
    }

    /**
     * 历史行 sourceKey（批次内唯一，uk_norm_source_key）：
     * 业绩行=前缀|合同号|工号|行号（同合同同人可能多行，行号兜底；
     * 合同号空回退 ROW行号，老导入器同语义）；考勤/积分行=前缀|工号（月度一人一行）；
     * 工资族行=sheetKind|工号|行号。
     */
    private String historySourceKey(RawData raw, NormalizedRecordType type,
                                    Map<String, Object> json, String code) {
        String contractNo = str(json, "contractNo");
        String contractKey = contractNo == null || contractNo.isEmpty()
            ? "ROW" + raw.getRowNo() : contractNo;
        return switch (type) {
            case HIST_EXPECT -> "EXP|" + contractKey + "|" + code + "|" + raw.getRowNo();
            case HIST_REAL -> "REAL|" + contractKey + "|" + code + "|" + raw.getRowNo();
            case ATTENDANCE -> "ATT|" + code;
            case POINTS -> "PTS|" + code;
            default -> str(json, "sheetKind") + "|" + code + "|" + raw.getRowNo();
        };
    }

    /**
     * 历史行口径填充。
     * <ul>
     *   <li>业绩行（HIST_EXPECT/HIST_REAL）：「85后」为折算后金额，÷ bizType 折算因子
     *       还原原始金额（pj_perf_fact.performance_amount 存原始口径，与贝壳行一致）；
     *       orderNo 以合同号充当（业绩汇总仅按订单号聚合，历史行无订单号）；</li>
     *   <li>考勤/积分行：月度总量口径（与日报行区分，聚合器走 HISTORY_PAYROLL 分支）；</li>
     *   <li>工资族行（PAYROLL_WAGE）：全字段进 extraJson，payroll 域按员工合并。</li>
     * </ul>
     */
    private void fillHistoryFields(NormalizedRecord nr, Map<String, Object> json) {
        switch (nr.getRecordType()) {
            case HIST_EXPECT, HIST_REAL -> {
                String contractNo = str(json, "contractNo");
                nr.setContractNo(contractNo);
                nr.setOrderNo(contractNo);
                String bizType = str(json, "bizType");
                nr.setBizType(bizType);
                nr.setPropertyAddress(str(json, "propertyAddress"));
                nr.setSignDate(str(json, "signDate"));
                nr.setShareRatio(decimal(json, "shareRatio"));
                nr.setRoleType(truncate(str(json, "roleType"), 30, "roleType", "HIST_" + nr.getRecordType()));
                nr.setRoleName(str(json, "employeeName"));
                BigDecimal converted = reverseConvert(bizType, decimal(json, "amount85"));
                if (nr.getRecordType() == NormalizedRecordType.HIST_EXPECT) {
                    nr.setReceivableAmount(converted);
                } else {
                    nr.setReceivedAmount(converted);
                }
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("storeGroup", str(json, "storeGroup"));
                extra.put("storeName", str(json, "storeName"));
                extra.put("settledFlag", str(json, "settledFlag"));
                extra.put("settleDate", str(json, "settleDate"));
                extra.put("amount85", str(json, "amount85"));
                nr.setExtraJson(toJson(extra));
            }
            case ATTENDANCE -> {
                nr.setReceivableAmount(decimal(json, "leaveAmount"));
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("lateCount", intVal(json, "lateCount"));
                extra.put("attendDays", decimal(json, "attendDays"));
                extra.put("attendanceDetail", str(json, "attendanceDetail"));
                nr.setExtraJson(toJson(extra));
            }
            case POINTS -> {
                nr.setReceivableAmount(decimal(json, "score"));
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("attendDays", intVal(json, "attendDays"));
                nr.setExtraJson(toJson(extra));
            }
            default -> nr.setExtraJson(toJson(json));
        }
    }

    /** 折算后金额 → 原始金额（÷ bizType 折算因子；因子缺省 1 时原样返回） */
    private BigDecimal reverseConvert(String bizType, BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal factor = conversionFactorPort.factorOf(bizType);
        if (factor == null || factor.compareTo(BigDecimal.ONE) == 0) {
            return amount;
        }
        return amount.divide(factor, 2, RoundingMode.HALF_UP);
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
        ImportContext ctx = new ImportContext(batch.getId(), batch.getBatchNo(), period,
            template.getTemplateVersion(), template.getTemplateCode());
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

        // 清旧归一化结果（重归一化场景）。
        // ★ 只清 NORMALIZE 阶段 issue：PARSE 阶段的基础校验 issue（REQUIRED_MISSING 等）
        //   是模板 required / validation_rules 的校验产物，此前全量 deleteByBatchId
        //   把它们删掉，导致模板校验"看起来没生效"。
        normalizedRecordMapper.deleteByBatchId(batchId);
        issueMapper.deleteNormalizePhaseByBatchId(batchId);

        // 格式类硬错误（必填缺失/类型错误等行级校验不过）：批次直接 FAILED 终态。
        // 「待确认」只保留给可修复的归一化问题（员工未匹配 → 重归一化）；
        // 格式错误无法在系统内修复，停在 PENDING_CONFIRM 只会让用户误以为可以"确认"。
        long parseIssueCount = issueMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ImportIssue>()
                .eq(ImportIssue::getBatchId, batchId)
                .eq(ImportIssue::getPhase, ImportIssuePhase.PARSE));
        if (parseIssueCount > 0) {
            batch.fail();
            batch.setFailedRows((int) parseIssueCount);
            batch.setSuccessRows(0);
            batch.setRemark("格式校验未通过（" + parseIssueCount + " 处），请查看问题清单，修正后重新导入");
            batchMapper.updateById(batch);
            return;
        }

        List<ImportIssue> issues = new ArrayList<>();
        normalizePerformance(batchId, sourceType, period, issues);

        // 落新 issue
        for (ImportIssue issue : issues) {
            issue.setBatchId(batchId);
            issueMapper.insert(issue);
        }

        // 更新批次状态：仅 blocking issue（格式/结构错误）→ PENDING_CONFIRM 人工处理；
        // 非 blocking issue（员工未匹配等富化失败）不阻塞归档，问题清单照常可见，下游跳过该行；
        // 无任何 issue → ARCHIVED 自动对外可见。
        boolean hasBlocking = issues.stream().anyMatch(i -> i.getIssueType() != null && i.getIssueType().isBlocking());
        batch.finishNormalize(hasBlocking);
        // failedRows 仍按 issue 总数计（含非阻塞），问题清单可见；successRows 按总 issue 扣减
        // （非阻塞 issue 的行虽然归一化了，但下游因缺 employeeCode 跳过，与"失败"语义一致）
        batch.setFailedRows(issues.size());
        batch.setSuccessRows(Math.max(0, batch.getTotalRows() - issues.size()));

        // ★ V2.0 §5.5 重复导入（SUPERSEDED）：同 (sourceType, period, deptId) 若已存在
        //   ARCHIVED 且未被废弃的旧批次，须先回填 superseded_by_batch_id，才不撞
        //   uk_import_batch_type_period_dept 唯一索引。新批次进入 ARCHIVED 在本事务末尾
        //   执行（status=3 触发唯一索引），所以 marker 必须抢在此之前 commit。
        //   整个 doNormalizePhase 是单个事务，与 batch.updateById 一起 all-or-nothing。
        if (ImportBatchStatus.ARCHIVED.equals(batch.getStatus())) {
            supersedeIfDuplicate(batch);
            // ★ 自动归档路径发 ImportBatchArchivedEvent（同一事务内 Outbox INSERT 与业务表
            //   UPDATE 原子提交，EventPort 强制要求 MANDATORY 事务上下文）。
            //   supersedeIfDuplicate 已在本事务内完成 markSuperseded 回填，
            //   selectSupersededBatchIds 在同一事务可读到被本批 supersede 的旧批次。
            emitArchivedEvent(batch);
        }

        batchMapper.updateById(batch);
    }

    /**
     * 构造并发布 ImportBatchArchivedEvent（自动归档路径，doNormalizePhase 内调用）。
     * <p>
     * supersededBatchIds 取本事务内 markSuperseded 回填的旧批次（同维度唯一索引约束下
     * 一般 0~1 个）；与 ImportBatchServiceImpl.archive 手动归档路径共用同一 emit 语义。
     *
     * @param batch 当前进入 ARCHIVED 的批次
     */
    private void emitArchivedEvent(ImportBatch batch) {
        List<Long> supersededIds = batchMapper.selectSupersededBatchIds(batch.getId());
        List<String> supersededStrIds = (supersededIds == null || supersededIds.isEmpty())
            ? Collections.emptyList()
            : supersededIds.stream().map(String::valueOf).toList();

        Long operatorId = null;
        try {
            operatorId = org.dromara.common.satoken.utils.LoginHelper.getUserId();
        } catch (Exception ignored) {
        }

        ImportBatchArchivedEvent event = new ImportBatchArchivedEvent();
        event.setBatchId(batch.getId());
        event.setSourceType(batch.getSourceType() == null ? null : batch.getSourceType().getCode());
        event.setPeriod(batch.getPeriod());
        event.setOperatorId(operatorId);
        event.setSupersededBatchIds(supersededStrIds);
        // 考勤批次：聚合月度汇总随事件 payload 投递，员工域 AttendanceArchiveHandler 消费
        event.setAttendanceSummaries(attendanceSummaryAggregator.aggregateIfAttendance(
            batch.getId(), event.getSourceType(), batch.getPeriod()));
        // 积分批次：日报按工号聚合月度汇总随事件 payload 投递，员工域 ScoreArchiveHandler 消费
        event.setScoreSummaries(scoreSummaryAggregator.aggregateIfPoints(
            batch.getId(), event.getSourceType(), batch.getPeriod()));
        eventPort.emit(event);

        log.info("[导入归档事件] 发布 ImportBatchArchivedEvent(自动归档): batchId={}, sourceType={}, period={}, operatorId={}, supersededBatchIds={}",
            batch.getId(), event.getSourceType(), event.getPeriod(), operatorId, supersededStrIds);
    }

    /**
     * 同 (sourceType, period, deptId) 唯一索引范围内，把已存在的 ARCHIVED
     * 未被废弃的旧批次标记为被本批次废弃，避免新批次转入 ARCHIVED 时撞唯一索引。
     * <p>
     * 设计依据：V2.0 §5.5 / §6.1 / ADR-IMP-004（单据逻辑失效）。
     */
    private void supersedeIfDuplicate(ImportBatch newBatch) {
        batchSupersedeService.supersedeOldArchivedBatch(newBatch);
    }

    private void normalizePerformance(Long batchId, ImportSourceType sourceType,
                                     String period, List<ImportIssue> issues) {
        // 历史工资：多模板混装（4 张 raw 表、按姓名匹配、口径分流），独立分支
        if (sourceType == ImportSourceType.HISTORY_PAYROLL) {
            normalizeHistory(batchId, period, issues);
            return;
        }
        // 读 RawData
        List<? extends RawData> rawRows = selectRawData(sourceType, batchId);

        // 批量匹配员工（未匹配 → issue，不写归一化记录；该行不进 fact 表）
        Map<String, Long> codeToId = matchEmployees(rawRows);

        // sourceKey 生成器
        SourceKeyGenerator keyGen = findSourceKeyGenerator(sourceType);
        NormalizedRecordType recordType = toRecordType(sourceType);

        // POINTS 口径：同人同日多次填报取文件最后一条（钉钉「修改重提」= 最新生效），
        // 被覆盖行记 DUPLICATE_KEY issue。不做去重会导致两点问题：
        //   1) 同日不同填报时间的行 source_key 不同，全部入库 → 聚合 SUM 总积分重复计；
        //   2) 同日同填报时间的行撞 uk_norm_source_key → 整批导入失败。
        boolean dedupDaily = sourceType == ImportSourceType.POINTS;
        Map<String, NormalizedRecord> pendingRecords = dedupDaily ? new java.util.LinkedHashMap<>() : null;
        Map<String, Integer> pendingRowNo = dedupDaily ? new java.util.HashMap<>() : null;

        for (RawData raw : rawRows) {
            Map<String, Object> jsonMap = parseRawJson(raw.getRawJson());
            String externalCode = extractExternalCode(jsonMap);

            Long matchedEmployeeId = null;
            if (externalCode == null || externalCode.isEmpty()) {
                // 员工号为空：理论上被模板层必填卡住（required + DataValidation +
                // 服务端 BasicValidator 三道防线）；落到这里说明用户绕过了模板
                // （如自行拼装 Excel）。记 REQUIRED_MISSING issue，不写归一化记录。
                issues.add(buildIssue(raw.getRowNo(), ImportIssueType.REQUIRED_MISSING,
                    "employeeCode", null, "员工号为空"));
            } else if (!codeToId.containsKey(externalCode)) {
                // 员工号未在主数据中匹配上：EMPLOYEE_NOT_MATCH issue
                // 主数据不在导入域管辖，由 issue 提示用户去人事系统补录或清理后重归一化
                issues.add(buildIssue(raw.getRowNo(), ImportIssueType.EMPLOYEE_NOT_MATCH,
                    "employeeCode", externalCode, "员工未匹配: " + externalCode));
            } else {
                matchedEmployeeId = codeToId.get(externalCode);
            }

            // 未匹配行不写归一化记录：该行不参与 fact 表下沉；批次进入
            // PENDING_CONFIRM 由人工决定后续动作（修复主数据、重归一化、整批关闭）
            if (matchedEmployeeId == null) {
                continue;
            }

            NormalizedRecord nr = new NormalizedRecord();
            nr.setBatchId(batchId);
            nr.setRecordType(recordType);
            nr.setPeriod(period);
            nr.setRawDataId(raw.getId());
            nr.setEmployeeId(matchedEmployeeId);
            nr.setEmployeeExternalCode(externalCode);

            // sourceKey
            if (keyGen != null) {
                nr.setSourceKey(keyGen.generate(jsonMap));
            }

            // 金额/业务字段
            fillPerformanceFields(nr, sourceType, jsonMap);
            nr.setValidationStatus(1);  // 走到此处必已匹配成功

            if (!dedupDaily) {
                normalizedRecordMapper.insert(nr);
                continue;
            }
            // POINTS：同人同日去重（同工号 + 同自然日；日期缺失行按行号天然唯一不参与覆盖）
            java.time.LocalDate pointDay = raw instanceof com.panjia.importdomain.domain.raw.RawPoints rp
                ? rp.getPointDate() : null;
            String dayKey = pointDay != null
                ? externalCode + "|" + pointDay
                : "ROW|" + raw.getRowNo();
            NormalizedRecord prev = pendingRecords.put(dayKey, nr);
            if (prev == null) {
                pendingRowNo.put(dayKey, raw.getRowNo());
            } else {
                // 文件中靠后的行覆盖靠前的行，被覆盖行记问题提示（不阻断导入）
                issues.add(buildIssue(pendingRowNo.get(dayKey), ImportIssueType.DUPLICATE_KEY,
                    "employeeCode", externalCode, "同人同日重复填报，仅保留最后一条，本行积分忽略"));
                pendingRowNo.put(dayKey, raw.getRowNo());
            }
        }

        // POINTS：去重完成后统一入库
        if (dedupDaily) {
            for (NormalizedRecord nr : pendingRecords.values()) {
                normalizedRecordMapper.insert(nr);
            }
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
        issue.setPhase(ImportIssuePhase.PARSE);
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
        // 毫秒 + 4 位随机：秒级时间戳在同一秒内连续导入会撞 uk_import_batch_no
        String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        int salt = java.util.concurrent.ThreadLocalRandom.current().nextInt(10000);
        return type.name() + "_" + (period == null ? "NOPERIOD" : period.replace("-", "")) + "_" + ts + String.format("%04d", salt);
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
            case HISTORY_PAYROLL -> {
                // 多模板混装：同一批 RawData 按 runtime 类型分流 4 张 raw 表
                for (RawData r : rows) {
                    if (r instanceof RawSigned s) {
                        rawSignedMapper.insert(s);
                    } else if (r instanceof RawAttendance a) {
                        rawAttendanceMapper.insert(a);
                    } else if (r instanceof RawPoints p) {
                        rawPointsMapper.insert(p);
                    } else if (r instanceof RawPayroll w) {
                        rawPayrollMapper.insert(w);
                    } else {
                        throw new IllegalStateException("历史工资行类型未知: " + r.getClass().getName());
                    }
                }
            }
        }
    }

    private List<? extends RawData> selectRawData(ImportSourceType type, Long batchId) {
        return switch (type) {
            case KE_SIGNED -> rawSignedMapper.selectList(byBatch(RawSigned::getBatchId, batchId));
            case ATTENDANCE -> rawAttendanceMapper.selectList(byBatch(RawAttendance::getBatchId, batchId));
            case POINTS -> rawPointsMapper.selectList(byBatch(RawPoints::getBatchId, batchId));
            case OTHERS -> rawManualMapper.selectList(byBatch(RawManual::getBatchId, batchId));
            case HISTORY_PAYROLL -> {
                List<RawData> all = new ArrayList<>();
                all.addAll(rawSignedMapper.selectList(byBatch(RawSigned::getBatchId, batchId)));
                all.addAll(rawAttendanceMapper.selectList(byBatch(RawAttendance::getBatchId, batchId)));
                all.addAll(rawPointsMapper.selectList(byBatch(RawPoints::getBatchId, batchId)));
                all.addAll(rawPayrollMapper.selectList(byBatch(RawPayroll::getBatchId, batchId)));
                yield all;
            }
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
            case KE_SIGNED -> {
                nr.setBizType(str(json, "bizType"));
                nr.setReceivableAmount(decimal(json, "currentReceivable"));
                nr.setReceivedAmount(decimal(json, "currentReceived"));
                nr.setTotalReceivableAmount(decimal(json, "totalReceivable"));
                nr.setTotalReceivedAmount(decimal(json, "totalReceived"));
                nr.setShareRatio(decimal(json, "shareRatio"));
                nr.setRoleType(truncate(str(json, "roleType"), 30, "roleType", "KE_SIGNED"));
                nr.setRoleName(str(json, "roleName"));
                nr.setOrderNo(str(json, "orderNo"));
                nr.setContractNo(str(json, "contractNo"));
                nr.setPropertyAddress(str(json, "propertyAddress"));
                nr.setSignDate(str(json, "signDate"));
                nr.setFeeItem(str(json, "feeItem"));
            }
            case ATTENDANCE -> {
                nr.setReceivableAmount(decimal(json, "leaveAmount"));
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("lateCount", intVal(json, "lateCount"));
                extra.put("absentDays", decimal(json, "absentDays"));
                // 请假天数（事假+病假合计）参与算薪扣款；事/病假分项留痕
                extra.put("leaveDays", decimal(json, "leaveDays"));
                extra.put("personalLeaveDays", decimal(json, "personalLeaveDays"));
                extra.put("sickLeaveDays", decimal(json, "sickLeaveDays"));
                extra.put("attendDays", decimal(json, "attendDays"));
                extra.put("restDays", decimal(json, "restDays"));
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
            case ATTENDANCE -> NormalizedRecordType.ATTENDANCE;
            case POINTS -> NormalizedRecordType.POINTS;
            case OTHERS -> NormalizedRecordType.MANUAL;
            case HISTORY_PAYROLL -> NormalizedRecordType.SIGNED;
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
        issue.setPhase(ImportIssuePhase.NORMALIZE);
        return issue;
    }

    /**
     * 文件形态防呆：表头与模板列完全无匹配、或没有可读数据行时直接拦截。
     * <p>
     * 随机文件此前会静默解析出「表头全对不上」的空行甚至 0 行，一路走完
     * 归一化后批次 ARCHIVED，前端显示"上传成功"。表头一个都对不上基本
     * 可以断定用错了文件/模板，直接报错比落一个 0 行批次更直观。
     *
     * @param sheet    解析结果
     * @param template 激活模板
     */
    private void validateSheetShape(ParsedSheet sheet, ImportTemplate template) {
        // occurrence 列（「姓名@2」）比对时剥后缀——文件表头是裸「姓名」
        Set<String> templateHeaders = template.getColumns().stream()
            .map(ColumnDef::getColName)
            .filter(h -> h != null && !h.isBlank())
            .map(h -> com.panjia.importutil.template.HeaderNames.baseName(h.trim()))
            .collect(Collectors.toSet());
        long matchedHeaders = sheet.getHeaders().stream()
            .filter(h -> h != null && templateHeaders.contains(h.trim()))
            .count();
        if (sheet.getHeaders().isEmpty() || matchedHeaders == 0) {
            throw new IllegalArgumentException("表头与导入模板不匹配，请下载模板并按模板格式填写后再上传");
        }
        if (sheet.getRows().isEmpty()) {
            throw new IllegalArgumentException("文件中没有可导入的数据行，请检查文件内容");
        }
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

    /**
     * 截断字符串到指定长度，超长时打 WARN 日志（帮助定位模板列映射/原始数据脏值问题）。
     * 归一化层兜底：DB 字段有长度约束（如 role_type VARCHAR(30)），透传原始值会 INSERT 炸。
     *
     * @param value 原始值（null 返回 null）
     * @param max   最大长度
     * @param field 字段名（日志用）
     * @param ctx   上下文（sourceType / recordType / sheetKind 等，日志用）
     * @return 截断后的值；null 入参 → null；原长 ≤ max → 原值
     */
    private String truncate(String value, int max, String field, String ctx) {
        if (value == null) {
            return null;
        }
        if (value.length() > max) {
            log.warn("[归一化截断] 字段超长已截断：ctx={}, field={}, originalLen={}, max={}, head={}",
                ctx, field, value.length(), max, value.substring(0, Math.min(max, 100)));
            return value.substring(0, max);
        }
        return value;
    }

    private BigDecimal decimal(Map<String, Object> map, String key) {
        String s = str(map, key);
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            // 复用导入基础转换器：裸数字量纲不变，并兼容「5.00%」→0.05、千分位逗号
            // （归一化从 rawJson 原文重读，业绩比例等字段可能带百分号）
            return TypeConverter.parseDecimal(s);
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
