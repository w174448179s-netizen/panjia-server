package com.panjia.license.enums;

/**
 * /check 接口返回结果码。
 * 区分不同失败原因，便于前端提示与日志排查。
 */
public enum CheckResultEnum {

    /** 允许操作 */
    ALLOWED("ALLOWED", "允许操作"),

    /** 授权校验失败 */
    AUTH_FAILED("AUTH_FAILED", "授权校验失败"),

    /** 指纹不匹配 */
    FP_MISMATCH("FP_MISMATCH", "指纹不匹配"),

    /** 版本越界 */
    VERSION_OUT_OF_RANGE("VERSION_OUT_OF_RANGE", "版本越界"),

    /** Token 签名无效 */
    SIGNATURE_INVALID("SIGNATURE_INVALID", "Token 签名无效"),

    /** Token 已被吊销 */
    TOKEN_REVOKED("TOKEN_REVOKED", "Token 已被吊销"),

    /** 操作不被允许（离线锁死/受限模式） */
    OPERATION_DENIED("OPERATION_DENIED", "操作不被允许"),

    /** 服务端暂时不可达，使用缓存降级 */
    CACHE_FALLBACK("CACHE_FALLBACK", "使用缓存降级"),

    /** 网络层不可达，离线模式禁用缓存 */
    NETWORK_OFFLINE("NETWORK_OFFLINE", "网络层不可达"),

    /** 版本不匹配 */
    VERSION_MISMATCH("VERSION_MISMATCH", "版本不匹配");

    private final String code;
    private final String message;

    CheckResultEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
