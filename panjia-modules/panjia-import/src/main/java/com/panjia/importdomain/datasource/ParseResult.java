package com.panjia.importdomain.datasource;

import com.panjia.importdomain.domain.ImportIssue;
import com.panjia.importdomain.domain.raw.RawData;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据源解析结果（V1.4 §3.6）。
 * <p>
 * RawData 为 insert-only，仅新增不修改；ImportIssue 不阻塞落库。
 */
@Getter
public class ParseResult {

    private final List<RawData> rows = new ArrayList<>();
    private final List<ImportIssue> issues = new ArrayList<>();

    public void addRow(RawData row) {
        rows.add(row);
    }

    public void addIssue(ImportIssue issue) {
        issues.add(issue);
    }

    public void addIssues(List<ImportIssue> issueList) {
        issues.addAll(issueList);
    }

    public int rowCount() {
        return rows.size();
    }

    public int issueCount() {
        return issues.size();
    }
}
