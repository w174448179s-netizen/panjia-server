package com.panjia.importutil.template;

/**
 * 表头文本归一化工具。
 * <p>
 * 用户「下载模板 → 直接填写 → 上传」时，模板必填列表头带 {@code TemplateExporter}
 * 追加的视觉后缀 " *"（如「工号 *」），与模板配置中的 source_header「工号」不相等，
 * 会导致嗅探匹配/解析全部失败。所有按表头匹配的环节统一走本工具归一化：
 * <ol>
 *   <li>去除首尾空白</li>
 *   <li>去除结尾必填标记 *（可重复，兼容手工多打）</li>
 * </ol>
 * 真实业务表头（如「✨今日总积分」）不以 * 结尾，归一化不影响匹配。
 */
public final class HeaderNames {

    private HeaderNames() {
    }

    /**
     * 归一化表头文本；输入为 null 返回 null。
     */
    public static String normalize(String header) {
        if (header == null) {
            return null;
        }
        String h = header.trim();
        // 去除必填后缀 *（模板导出为 " *"，兼容用户手工修改成 "*"）
        while (h.endsWith("*")) {
            h = h.substring(0, h.length() - 1).trim();
        }
        return h;
    }

    /**
     * 剥离 occurrence 后缀：「姓名@2」→「姓名」，无后缀原样返回。
     * <p>
     * occurrence 语法用于同文件重复列表头（如天街工资表「绩效和扣款」sheet 左右
     * 双表各有「姓名」列）：模板 source_header 写「姓名@1」取第 1 次出现、
     * 「姓名@2」取第 2 次。嗅探/防呆比对表头时须先剥后缀再比对。
     */
    public static String baseName(String colName) {
        int idx = occurrenceIndex(colName);
        return idx > 0 ? colName.substring(0, idx) : colName;
    }

    /**
     * occurrence 序号：「姓名@2」→ 2；无后缀（或 @ 后非纯数字）返回 null。
     */
    public static Integer occurrence(String colName) {
        int idx = occurrenceIndex(colName);
        return idx > 0 ? Integer.valueOf(colName.substring(idx + 1)) : null;
    }

    /** occurrence 分隔符位置；无 occurrence 返回 -1 */
    private static int occurrenceIndex(String colName) {
        if (colName == null) {
            return -1;
        }
        int idx = colName.lastIndexOf('@');
        if (idx > 0 && idx < colName.length() - 1
            && colName.substring(idx + 1).chars().allMatch(Character::isDigit)) {
            return idx;
        }
        return -1;
    }
}
