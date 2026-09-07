package com.panjia.license.starter;

import com.panjia.license.config.LicenseProperties;
import com.panjia.license.enums.LicenseStatusEnum;
import com.panjia.license.security.IntegrityChecker;
import com.panjia.license.security.LicenseGuard;
import com.panjia.license.security.RestrictedMode;
import com.panjia.license.service.LicenseService;
import com.panjia.license.service.MultiInstanceDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * LicenseStartupValidator 单元测试。
 * 核心验证：无 token 时拒绝启动（双重保险），有 token 时正常启动。
 */
@ExtendWith(MockitoExtension.class)
@Tag("dev")
class LicenseStartupValidatorTest {

    @Mock
    private LicenseService licenseService;
    @Mock
    private LicenseProperties properties;
    @Mock
    private RestrictedMode restrictedMode;
    @Mock
    private MultiInstanceDetector multiInstanceDetector;
    @Mock
    private LicenseGuard licenseGuard;
    @Mock
    private IntegrityChecker integrityChecker;
    @Mock
    private ConfigurableApplicationContext applicationContext;
    @Mock
    private ApplicationReadyEvent event;

    @InjectMocks
    private LicenseStartupValidator validator;

    @BeforeEach
    void setUp() {
        // 默认：需要强制校验、完整性通过、未拉黑（用 lenient 避免被覆盖时报 UnnecessaryStubbing）
        lenient().when(licenseGuard.shouldEnforce()).thenReturn(true);
        lenient().when(multiInstanceDetector.isBlacklisted()).thenReturn(false);
    }

    @Test
    void noToken_nonTestMode_shouldShutdown() {
        // 无 token + 非 testMode → 双重保险应拒绝启动
        when(licenseService.getToken()).thenReturn(null);
        when(licenseService.getCurrentStatus()).thenReturn(LicenseStatusEnum.NOT_ACTIVATED);
        when(properties.isTestMode()).thenReturn(false);

        validator.onApplicationReady();

        // 验证：触发了受限模式 + 关闭了应用上下文
        verify(restrictedMode).trigger(anyString(), anyString());
        verify(applicationContext).close();
    }

    @Test
    void noToken_statusNormal_shouldStillShutdown() {
        // 回归测试：即使 status 被误设为 NORMAL，只要 token 为空就拒绝启动
        when(licenseService.getToken()).thenReturn(null);
        when(licenseService.getCurrentStatus()).thenReturn(LicenseStatusEnum.NORMAL);
        when(properties.isTestMode()).thenReturn(false);

        validator.onApplicationReady();

        verify(restrictedMode).trigger(anyString(), anyString());
        verify(applicationContext).close();
    }

    @Test
    void noToken_testMode_shouldSoftFail() {
        // testMode + 无 token → 软失败，允许启动后激活
        when(licenseService.getToken()).thenReturn(null);
        when(licenseService.getCurrentStatus()).thenReturn(LicenseStatusEnum.NOT_ACTIVATED);
        when(properties.isTestMode()).thenReturn(true);

        validator.onApplicationReady();

        // 软失败：不关闭应用上下文
        verify(applicationContext, never()).close();
    }

    @Test
    void hasToken_normalStatus_shouldStartSuccessfully() {
        // 有 token + 正常状态 → 启动成功
        when(licenseService.getToken()).thenReturn("valid-jwt-token");
        when(licenseService.getCurrentStatus()).thenReturn(LicenseStatusEnum.NORMAL);
        when(licenseService.isRestricted()).thenReturn(false);

        validator.onApplicationReady();

        // 启动成功：不触发受限、不关闭上下文
        verify(restrictedMode, never()).trigger(anyString(), anyString());
        verify(applicationContext, never()).close();
    }

    @Test
    void hasToken_expiredStatus_shouldShutdown() {
        when(licenseService.getToken()).thenReturn("expired-token");
        when(licenseService.getCurrentStatus()).thenReturn(LicenseStatusEnum.EXPIRED);

        validator.onApplicationReady();

        verify(restrictedMode).trigger(anyString(), anyString());
        verify(applicationContext).close();
    }

    @Test
    void integrityCheckFail_shouldShutdown() {
        doThrow(new RuntimeException("完整性校验失败")).when(integrityChecker).checkStartup();

        validator.onApplicationReady();

        verify(restrictedMode).trigger(anyString(), anyString());
        verify(applicationContext).close();
    }

    @Test
    void blacklisted_shouldShutdown() {
        when(multiInstanceDetector.isBlacklisted()).thenReturn(true);

        validator.onApplicationReady();

        verify(restrictedMode).trigger(anyString(), anyString());
        verify(applicationContext).close();
    }

    @Test
    void guardDisabled_shouldSkipAllChecks() {
        // LicenseGuard 关闭时跳过所有校验
        when(licenseGuard.shouldEnforce()).thenReturn(false);

        validator.onApplicationReady();

        verify(integrityChecker, never()).checkStartup();
        verify(applicationContext, never()).close();
    }
}
