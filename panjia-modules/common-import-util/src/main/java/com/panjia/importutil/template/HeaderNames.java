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
}
