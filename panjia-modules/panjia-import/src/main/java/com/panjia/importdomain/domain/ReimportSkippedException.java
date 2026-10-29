package com.panjia.importdomain.domain;

/**
 * 重复导入跳过信号（非错误）。
 * <p>
 * 归一化完成后若发现内容与现有生效批次完全一致，批次转 SKIPPED 并抛出本异常，
 * 由 {@code doNormalizePhase} 的 {@code noRollbackFor} 保证批次状态提交、
 * 由调用方识别后不再走失败标记，Controller 返回「已跳过」提示。
 */
public class ReimportSkippedException extends RuntimeException {

    public ReimportSkippedException(String message) {
        super(message);
    }
}
