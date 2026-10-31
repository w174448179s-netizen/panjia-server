package com.panjia.people.port;

/**
 * 参数配置端口（people 域 → sys_config 参数管理）。
 * <p>
 * 业务口径类名单/开关放参数管理（系统管理 → 参数设置），运维可即时调整，
 * 不写死在 yml 或代码里。实现见 adapter 层 {@code RuoYiConfigAdapter}，
 * 读取走 RuoYi 参数缓存，可放心在计算链路调用。
 */
public interface ConfigPort {

    /**
     * 按参数键名取参数值。
     *
     * @param configKey 参数键名（如 {@code panjia.score.deduct_leave_names}）
     * @return 参数值；未配置返回 null
     */
    String findValue(String configKey);
}
