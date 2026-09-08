package com.panjia.common.port;

/**
 * 备份触发端口（横切能力 Port）。
 * <p>
 * 状态：🟡 骨架——接口定义完成，介质实现（对象存储 / 本地）V1 不落地，进 Backlog。
 * <p>
 * 调用契约（P1）：调用点仅两处——① 算薪启动前 ② 系统升级前；
 * 由应用编排层调用，业务 Domain 层禁止直接调。
 */
public interface BackupPort {

    /**
     * 触发备份。
     *
     * @param reason   备份原因
     * @param operator 操作人（异步 / SnailJob 场景必须显式传，如 "system"）
     */
    default void trigger(BackupReason reason, String operator) {

    }
}
