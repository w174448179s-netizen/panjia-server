package com.panjia.people.adapter;

import com.panjia.people.port.ConfigPort;
import lombok.RequiredArgsConstructor;
import org.dromara.system.api.ConfigService;
import org.springframework.stereotype.Component;

/**
 * 参数配置端口 RuoYi 实现（读 sys_config，走 RuoYi 参数缓存）。
 */
@Component
@RequiredArgsConstructor
public class RuoYiConfigAdapter implements ConfigPort {

    private final ConfigService configService;

    @Override
    public String findValue(String configKey) {
        return configService.getConfigValue(configKey);
    }
}
