package com.panjia.common.config;

import com.panjia.common.util.ScoreSubmitWindow;
import lombok.RequiredArgsConstructor;
import org.dromara.system.api.ConfigService;
import org.springframework.stereotype.Component;

/**
 * 积分提交窗口口径提供者：读取参数管理 {@code panjia.score.submit_window}。
 * <p>
 * 走 RuoYi 参数缓存，参数改动即时生效；解析失败回退默认口径。
 */
@Component
@RequiredArgsConstructor
public class ScoreSubmitWindowProvider {

    private final ConfigService configService;

    /** 当前生效口径（每次调用读参数缓存，避免进程内缓存导致改参数不生效） */
    public ScoreSubmitWindow.Spec currentSpec() {
        return ScoreSubmitWindow.parse(configService.getConfigValue(ScoreSubmitWindow.CONFIG_KEY));
    }
}
