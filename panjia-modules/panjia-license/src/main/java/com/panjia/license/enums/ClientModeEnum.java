package com.panjia.license.enums;

/**
 * 服务端下发的客户端模式指令（心跳响应携带）。
 * NORMAL = 正常；RESTRICT = 服务端回收授权但允许"试用"错误结果。
 */
public enum ClientModeEnum {

    /** 正常模式 */
    NORMAL("正常"),

    /** 受限模式：算薪结果偏移，功能可用 */
    RESTRICT("受限");

    private final String description;

    ClientModeEnum(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
