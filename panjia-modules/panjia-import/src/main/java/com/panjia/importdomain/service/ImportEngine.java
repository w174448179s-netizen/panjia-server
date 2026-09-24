package com.panjia.importdomain.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.event.ImportBatchArchivedEvent;
import com.panjia.contracts.port.HistoryPayrollImportPort;
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
    private final PeopleQueryPort peopleQueryPort;
    private final EventPort eventPort;
    private final AttendanceSummaryAggregator attendanceSummaryAggregator;
    private final ScoreSummaryAggregator scoreSummaryAggregator;
    private final BatchSupersedeService batchSupersedeService;
    private final HistoryPayrollImportPort historyPayrollImportPort;

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
        // 历史工资旁路：多 sheet 跨域直写（薪酬域），不走模板解析/归一化管线
        if (sourceType == ImportSourceType.HISTORY_PAYROLL) {
            ArchiveResult archived = fileArchiver.archive(content, fileName, "import");
            return self.doHistoryPayrollPhase(archived, content, fileName, period, operatorId, deptId);
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

    // ==================== 历史工资旁路 ====================

    /**
     * 历史工资导入（HISTORY_PAYROLL 旁路）：归档已完成，本方法建批次 → 委托
     * {@link HistoryPayrollImportPort}（薪酬域）直写各域表 → 告警回填问题清单 →
     * 小事务收口批次状态（有告警 PENDING_CONFIRM / 无告警 ARCHIVED，自动 supersede 同维度旧批次并发归档事件）。
     * <p>
     * <b>刻意不包大事务</b>：导入器逐行 catch-continue 的容错语义只在逐条自动提交下成立
     * （PG 事务内任一语句失败即整个事务 aborted，后续语句全部 25P02）；且导入按分段幂等设计，
     * 失败重跑即为恢复手段，与 CLI 兜底路径语义一致。失败时批次置 FAILED 并抛出原始异常
     * （markFailed 自身失败不掩盖根因）。
     */
    public Long doHistoryPayrollPhase(ArchiveResult archived, byte[] content, String fileName,
                                      String period, Long operatorId, Long deptId) {
        ImportBatch batch = new ImportBatch();
        batch.setSourceType(ImportSourceType.HISTORY_PAYROLL);
        batch.setTemplateVersion("HIST_V1");
        batch.setFileName(fileName);
        batch.setOriginalFileName(fileName);
        batch.setStoragePath(archived.getStoragePath());
        batch.setPeriod(period);
        // 旁路无解析阶段，直接以 NORMALIZING 建批次，finishNormalize 走 NORMALIZING→ARCHIVED/PENDING_CONFIRM
        batch.setStatus(ImportBatchStatus.NORMALIZING);
        batch.setOperatorId(operatorId);
        batch.setDeptId(deptId);
        batch.setBatchNo(generateBatchNo(ImportSourceType.HISTORY_PAYROLL, period));
        batchMapper.insert(batch);
        try {
            HistoryPayrollImportPort.HistoryPayrollImportResult result =
                historyPayrollImportPort.importAll(batch.getId(), period, content, fileName);
            if (!result.success()) {
                throw new IllegalStateException(result.summary());
            }
            // 告警行（员工未匹配等）→ 批次问题清单
            for (String w : result.warnings()) {
                ImportIssue issue = buildIssue(null,
                    w.contains("未匹配") ? ImportIssueType.EMPLOYEE_NOT_MATCH : ImportIssueType.COLUMN_TYPE_ERR,
                    null, null, truncate(w, 1000));
                issue.setBatchId(batch.getId());
                issueMapper.insert(issue);
            }
            // 批次状态收口 + 归档事件（emit 为 MANDATORY，须在小事务内与状态更新原子提交）
            self.finalizeHistoryBatch(batch, result);
            return batch.getId();
        } catch (Exception e) {
            try {
                markFailed(batch.getId());
            } catch (Exception markEx) {
                log.error("[历史工资] 批次置 FAILED 失败（不影响原始异常抛出）：batchId={}", batch.getId(), markEx);
            }
            throw e;
        }
    }

    /**
     * 历史工资批次状态收口（小事务）：finishNormalize → supersede → 归档事件 → 批次更新，
     * 保证 emit 的 Outbox INSERT 与批次终态原子提交。
     */
    @Transactional(rollbackFor = Exception.class)
    public void finalizeHistoryBatch(ImportBatch batch, HistoryPayrollImportPort.HistoryPayrollImportResult result) {
        batch.setRemark(truncate(result.summary(), 500));
        batch.setFailedRows(result.warnings().size());
        batch.setSuccessRows(result.warnings().isEmpty() ? 1 : 0);
        batch.finishNormalize(!result.warnings().isEmpty());
        if (ImportBatchStatus.ARCHIVED.equals(batch.getStatus())) {
            supersedeIfDuplicate(batch);
            emitArchivedEvent(batch);
        }
        batchMapper.updateById(batch);
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

        // 更新批次状态：归一化 issue（员工未匹配等可修复问题）→ PENDING_CONFIRM 人工处理；
        // 无任何 issue → ARCHIVED 自动对外可见。
        batch.finishNormalize(!issues.isEmpty());
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
            case HISTORY_PAYROLL -> throw new IllegalStateException("历史工资批次不走 raw 管线");
        }
    }

    private List<? extends RawData> selectRawData(ImportSourceType type, Long batchId) {
        return switch (type) {
            case KE_SIGNED -> rawSignedMapper.selectList(byBatch(RawSigned::getBatchId, batchId));
            case ATTENDANCE -> rawAttendanceMapper.selectList(byBatch(RawAttendance::getBatchId, batchId));
            case POINTS -> rawPointsMapper.selectList(byBatch(RawPoints::getBatchId, batchId));
            case OTHERS -> rawManualMapper.selectList(byBatch(RawManual::getBatchId, batchId));
            case HISTORY_PAYROLL -> List.of();
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
                nr.setRoleType(str(json, "roleType"));
                nr.setRoleName(str(json, "roleName"));
                // 合同维度冗余字段：业绩域快照到 pj_perf_fact，消除关联查询
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
        Set<String> templateHeaders = template.getColumns().stream()
            .map(ColumnDef::getColName)
            .filter(h -> h != null && !h.isBlank())
            .map(String::trim)
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
