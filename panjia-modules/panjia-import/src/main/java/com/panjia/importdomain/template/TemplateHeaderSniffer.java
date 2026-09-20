package com.panjia.importdomain.template;

import com.panjia.importutil.exception.ImportUtilException;
import lombok.extern.slf4j.Slf4j;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import org.apache.fesod.sheet.exception.ExcelAnalysisStopException;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上传文件表头嗅探器：解析前先读取前几行原始文本，供多模板按表头自动匹配。
 * <p>
 * 嗅探到探测行数上限（或首个数据行）即中断读取，不消费整个文件。
 * 返回 1-based 行文本列表：get(0) = 第 1 行各单元格文本。
 */
@Slf4j
@Component
public class TemplateHeaderSniffer {

    /** 最多探测前 5 行（覆盖单行/多行表头模板的 header_row 取值范围） */
    private static final int PROBE_ROWS = 5;

    /**
     * 嗅探文件前若干行的单元格文本。
     *
     * @param in             文件流（调用方负责重新开启，本方法可能提前中断流）
     * @param originalFilename 文件名（仅日志用）
     * @return 1-based 行文本列表；无表头的空文件返回空列表
     */
    public List<List<String>> sniff(InputStream in, String originalFilename) {
        Map<Integer, List<String>> rows = new LinkedHashMap<>();
        try {
            FesodSheet.read(in, new AnalysisEventListener<Map<Integer, String>>() {
                @Override
                public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                    int rowIdx = context.readRowHolder().getRowIndex();
                    if (rowIdx >= PROBE_ROWS) {
                        return;
                    }
                    rows.put(rowIdx, toCells(headMap));
                }

                @Override
                public void invoke(Map<Integer, String> data, AnalysisContext context) {
                    // 表头区读取完毕，遇到数据行即中断嗅探
                    throw new ExcelAnalysisStopException();
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // 文件行数不足 PROBE_ROWS 时自然读完
                }
            }).sheet().headRowNumber(PROBE_ROWS).doRead();
        } catch (ExcelAnalysisStopException expected) {
            // 正常中断
        } catch (Exception e) {
            throw new ImportUtilException("无法读取上传文件（文件损坏或格式不支持）: " + originalFilename, e);
        }
        if (log.isDebugEnabled()) {
            log.debug("表头嗅探: file={}, rows={}", originalFilename, rows.size());
        }
        // 转 1-based：结果.get(0) 即文件第 1 行
        List<List<String>> result = new ArrayList<>();
        for (int i = 0; i < PROBE_ROWS; i++) {
            if (rows.containsKey(i)) {
                result.add(rows.get(i));
            }
        }
        return result;
    }

    private List<String> toCells(Map<Integer, String> headMap) {
        int maxCol = headMap.keySet().stream().max(Integer::compareTo).orElse(-1);
        List<String> cells = new ArrayList<>(maxCol + 1);
        for (int i = 0; i <= maxCol; i++) {
            String v = headMap.get(i);
            // 归一化：trim + 去除必填后缀 *（用户直接用下载模板填写后回传）
            cells.add(com.panjia.importutil.template.HeaderNames.normalize(v));
        }
        return cells;
    }
}
