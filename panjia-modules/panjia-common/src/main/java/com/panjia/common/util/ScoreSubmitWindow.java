package com.panjia.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 积分日报提交时间窗口口径（导入与钉钉同步共用，避免两套判定跑偏）。
 * <p>
 * 以积分日期 D（日报所属当天）为基准：
 * <ul>
 *   <li>D 窗口开始 ~ D+1 窗口结束之间提交：计入积分；</li>
 *   <li>其中 D 晚提交起点 ~ D+1 窗口结束提交：计入积分，同时记 1 次晚提交；</li>
 *   <li>其余时间（含 D 白天窗口外、窗口外更早/更晚）：不计入积分，计分按 0 处理，页面标识「不计入」。</li>
 * </ul>
 * 三个时间点由参数管理 {@code panjia.score.submit_window} 配置（格式「开始,晚提交起点,次日结束」），
 * 解析失败时回退默认 19:00,23:00,09:00。提交时间缺失时无法判定，按正常计入处理（不因数据缺失扣分）。
 */
public final class ScoreSubmitWindow {

    /** 参数管理中的配置键（系统管理 → 参数设置） */
    public static final String CONFIG_KEY = "panjia.score.submit_window";

    /** 默认窗口配置：开始,晚提交起点,次日结束 */
    public static final String DEFAULT_SPEC_TEXT = "19:00,23:00,09:00";

    private ScoreSubmitWindow() {
    }

    /** 默认口径（参数为空或非法时兜底） */
    public static Spec defaultSpec() {
        return new Spec(LocalTime.of(19, 0), LocalTime.of(23, 0), LocalTime.of(9, 0));
    }

    /**
     * 解析参数值「开始,晚提交起点,次日结束」，任一段非法则整串回退默认。
     *
     * @param text 参数值（逗号分隔，支持中文逗号；时间支持 19:00 / 19:00:00 / 19）
     * @return 窗口口径
     */
    public static Spec parse(String text) {
        if (text == null || text.isBlank()) {
            return defaultSpec();
        }
        String[] parts = text.split("[,，]");
        if (parts.length != 3) {
            return defaultSpec();
        }
        LocalTime start = parseTime(parts[0]);
        LocalTime lateStart = parseTime(parts[1]);
        LocalTime end = parseTime(parts[2]);
        if (start == null || lateStart == null || end == null) {
            return defaultSpec();
        }
        return new Spec(start, lateStart, end);
    }

    /**
     * 判定某条日报的提交结果。
     *
     * @param spec       窗口口径
     * @param pointDate  积分归属日期
     * @param submitTime 提交时间；null 视为无法判定，按计入且不晚提交处理
     * @return 判定结果
     */
    public static SubmitResult evaluate(Spec spec, LocalDate pointDate, LocalDateTime submitTime) {
        if (pointDate == null || submitTime == null) {
            return SubmitResult.COUNTED;
        }
        Spec window = spec == null ? defaultSpec() : spec;
        LocalDateTime start = pointDate.atTime(window.start());
        LocalDateTime end = pointDate.plusDays(1).atTime(window.end());
        if (submitTime.isBefore(start) || !submitTime.isBefore(end)) {
            return SubmitResult.INVALID;
        }
        return submitTime.isBefore(pointDate.atTime(window.lateStart()))
            ? SubmitResult.COUNTED : SubmitResult.COUNTED_LATE;
    }

    /**
     * 由提交时间推断积分归属日期：窗口结束点之前提交（跨零点补报）归前一天，其余归提交当天。
     *
     * @param spec       窗口口径
     * @param submitTime 提交时间
     * @return 积分归属日期；提交时间为 null 返回 null
     */
    public static LocalDate resolvePointDate(Spec spec, LocalDateTime submitTime) {
        if (submitTime == null) {
            return null;
        }
        Spec window = spec == null ? defaultSpec() : spec;
        return submitTime.toLocalTime().isBefore(window.end())
            ? submitTime.toLocalDate().minusDays(1) : submitTime.toLocalDate();
    }

    /** 解析单个时间点，非法返回 null */
    private static LocalTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(":");
        if (parts.length < 1 || parts.length > 3) {
            return null;
        }
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = parts.length >= 2 ? Integer.parseInt(parts[1].trim()) : 0;
            int second = parts.length == 3 ? Integer.parseInt(parts[2].trim()) : 0;
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59) {
                return null;
            }
            return LocalTime.of(hour, minute, second);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 提交窗口口径：当日开始时间、晚提交起点、次日结束时间 */
    public record Spec(LocalTime start, LocalTime lateStart, LocalTime end) {
    }

    /** 提交结果：是否计入积分、是否晚提交 */
    public enum SubmitResult {

        /** 窗口内提交，正常计入 */
        COUNTED(false),

        /** 窗口内晚提交起点后提交，计入且记晚提交 */
        COUNTED_LATE(true),

        /** 窗口外提交，不计入（积分按 0 计） */
        INVALID(false);

        private final boolean late;

        SubmitResult(boolean late) {
            this.late = late;
        }

        /** 是否计入积分 */
        public boolean counted() {
            return this != INVALID;
        }

        /** 是否记晚提交 */
        public boolean late() {
            return late;
        }
    }
}
