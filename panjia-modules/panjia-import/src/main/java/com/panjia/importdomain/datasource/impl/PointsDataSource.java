package com.panjia.importdomain.datasource.impl;

import com.panjia.common.config.ScoreSubmitWindowProvider;
import com.panjia.common.util.ScoreSubmitWindow;
import com.panjia.importdomain.datasource.AbstractDataSource;
import com.panjia.importdomain.datasource.ImportContext;
import com.panjia.importdomain.datasource.ParseResult;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawPoints;
import com.panjia.importutil.dto.ParsedRow;
import com.panjia.importutil.dto.ParsedSheet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 积分数据源（POINTS）。
 * <p>
 * 必填/类型校验由 common-import-util 基础校验器完成，本类只做结构转换。
 */
@Component
@RequiredArgsConstructor
public class PointsDataSource extends AbstractDataSource {

    private final ScoreSubmitWindowProvider scoreSubmitWindowProvider;

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.POINTS;
    }

    @Override
    public ParseResult parse(ParsedSheet sheet, ImportContext ctx) {
        ParseResult result = new ParseResult();
        // 提交窗口口径由参数管理配置，单次解析读一次
        ScoreSubmitWindow.Spec window = scoreSubmitWindowProvider.currentSpec();
        for (ParsedRow row : sheet.getRows()) {
            RawPoints raw = new RawPoints();
            raw.setBatchId(ctx.getBatchId());
            raw.setRowNo(row.getRowNo());
            raw.setRawJson(toRawJson(row));

            raw.setEmployeeCode(str(row, "employeeCode"));
            java.time.LocalDateTime submitTime = dateTime(row, "submitTime");
            raw.setSubmitTime(submitTime);
            // pointDate 取填报时间对应的积分归属日：窗口结束点前提交（跨零点补报）归前一天，
            // 与 ScoreSubmitWindow 窗口口径一致，否则次日凌晨的补报会被误判为窗口外
            raw.setPointDate(submitTime != null
                ? ScoreSubmitWindow.resolvePointDate(window, submitTime)
                : date(row, "pointDate"));
            raw.setScore(decimal(row, "score"));
            raw.setViolationCount(integer(row, "violationCount"));

            result.addRow(raw);
        }
        return result;
    }
}
