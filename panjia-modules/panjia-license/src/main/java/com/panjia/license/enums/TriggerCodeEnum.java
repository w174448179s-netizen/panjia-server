package com.panjia.license.enums;

/**
 * 受限模式触发原因码。
 * 写告警日志时必须包含，便于我方后台排查客户报障。
 */
public enum TriggerCodeEnum {

    /** T1：授权校验失败（指纹/版本/token） */
    T1_AUTH_FAIL("T1_AUTH_FAIL"),

    /** T2：服务端 REVOKED 指令 */
    T2_SERVER_REVOKED("T2_SERVER_REVOKED"),

    /** T3：完整性自检失败（class 被静态替换） */
    T3_INTEGRITY("T3_INTEGRITY"),

    /** 服务端决策下发受限 */
    SERVER_DECISION("SERVER_DECISION");

    private final String code;

    TriggerCodeEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
