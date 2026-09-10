package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Set;

/**
 * 导入批次状态机（V1.4 §3.5）。
 * <p>
 * 流转：PARSING → NORMALIZING → PENDING_CONFIRM → ARCHIVED；
 * FAILED 为终态，禁止回退；PENDING_CONFIRM 修复后可回到 NORMALIZING。
 */
public enum ImportBatchStatus {

    /** 解析中（读取文件、落 RawData） */
    PARSING(0, "解析中", Set.of("NORMALIZING", "FAILED"), false, true),

    /** 归一化中（列映射 + 校验 → NormalizedRecord / Sink） */
    NORMALIZING(1, "归一化中", Set.of("PENDING_CONFIRM", "ARCHIVED", "FAILED"), false, true),

    /** 待确认（有 ImportIssue，需人工处理） */
    PENDING_CONFIRM(2, "待确认", Set.of("NORMALIZING", "ARCHIVED", "FAILED"), false, false),

    /** 已归档（对外可见/可被下游消费） */
    ARCHIVED(3, "已归档", Set.of(), true, false),

    /** 失败（终态，不可直接修改，需新建批次重试） */
    FAILED(4, "失败", Set.of(), true, false);

    @EnumValue
    private final int code;
    private final String desc;
    private final Set<String> next;
    private final boolean terminal;
    private final boolean running;

    ImportBatchStatus(int code, String desc, Set<String> next, boolean terminal, boolean running) {
        this.code = code;
        this.desc = desc;
        this.next = next;
        this.terminal = terminal;
        this.running = running;
    }

    public int getCode() {
        return code;
    }

    @JsonValue
    public String getDesc() {
        return desc;
    }

    /** 是否可流转到目标状态 */
    public boolean canTransitTo(ImportBatchStatus target) {
        return next.contains(target.name());
    }

    public boolean isTerminal() {
        return terminal;
    }

    public boolean isRunning() {
        return running;
    }

    public static ImportBatchStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (ImportBatchStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }
}
