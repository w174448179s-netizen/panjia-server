package com.panjia.performance.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 业绩调整类型枚举。
 * <p>
 * 合同级新签调整三类型（2026-10-14 重构）：业绩调整(AMOUNT，总额不变)、业绩扣减(DEDUCT，
 * 总额须减少)、业绩增加(INCREASE，总额须增加)；三类型均可同时增加角色人。
 * 增加角色人(ADD_MEMBER) 已并入上述三类型、禁止新建（历史单保留展示）；
 * 业绩冲销(VOID)、部门划转(TRANSFER) 已下线，历史数据不再展示，后续需要时再扩展。
 * <p>
 * 存储约定：DB 字段 adjust_type VARCHAR(16) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum AdjustType {

    /** 业绩调整（原「金额调整」，2026-10-14 改名；合同级要求修改后合同总额不变，联动实收按比例调整） */
    AMOUNT("业绩调整"),

    /** 业绩扣减（合同级，2026-10-14）：修改后合同总额必须小于原总额；联动实收同额扣减 */
    DEDUCT("业绩扣减"),

    /** 业绩增加（合同级，2026-10-14）：修改后合同总额必须大于原总额；不联动实收（提醒另行调整） */
    INCREASE("业绩增加"),

    /** 增加角色人（合同级）：已并入 AMOUNT/DEDUCT/INCREASE，禁止新建；历史单保留展示（2026-10-14） */
    ADD_MEMBER("增加角色人"),

    /** 业绩冲正（合同级）：对合同录入冲正/补录明细，走审批流程（2026-10-10） */
    MANUAL_OFFSET("业绩冲正"),

    /** 实收调整（合同级）：对合同实收业绩（PERF_REAL）做合同级金额调整，走审批流程（2026-10-10） */
    RECEIVED_AMOUNT("实收调整"),

    /** 新签补录（合同级）：为 DRAFT 实收单手工补录 PERF_EXPECT 事实，走总监审批流程（2026-10-28） */
    SUPPLEMENT("新签补录");

    /** 调整类型码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 调整类型描述 */
    private final String desc;

    AdjustType(String desc) {
        this.code = name();
        this.desc = desc;
    }

    /**
     * 获取调整类型码。
     *
     * @return 类型码
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 获取调整类型描述。
     *
     * @return 描述文本
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按 code 解析调整类型。
     *
     * @param code 类型码
     * @return 调整类型；code 为空或无法识别时返回 null
     */
    public static AdjustType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (AdjustType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
