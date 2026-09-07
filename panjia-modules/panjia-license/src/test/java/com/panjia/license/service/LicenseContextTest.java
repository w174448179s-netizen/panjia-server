package com.panjia.license.service;

import com.panjia.license.domain.HardwareFingerprint;
import com.panjia.license.domain.LicenseContent;
import com.panjia.license.enums.ClientModeEnum;
import com.panjia.license.enums.LicenseStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LicenseContext 单元测试。
 * 核心验证：默认状态、状态流转、受控方法的行为。
 */
@Tag("dev")
class LicenseContextTest {

    private LicenseContext context;
    private LicenseContent content;

    @BeforeEach
    void setUp() {
        context = new LicenseContext();
        content = LicenseContent.builder()
                .authCode("TEST-AUTH-001")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @Test
    void defaultStatus_shouldBeNotActivated() {
        // 核心回归测试：未加载 token 时状态必须是 NOT_ACTIVATED，不能是 NORMAL
        assertEquals(LicenseStatusEnum.NOT_ACTIVATED, context.getStatus(),
                "默认状态应为 NOT_ACTIVATED，防止无 token 启动通过校验");
    }

    @Test
    void defaultToken_shouldBeNull() {
        assertNull(context.getToken());
        assertNull(context.getLicenseContent());
    }

    @Test
    void setLicense_shouldSetStatusToNormal() {
        context.setLicense(content, "jwt-token-123");

        assertEquals(LicenseStatusEnum.NORMAL, context.getStatus());
        assertEquals("jwt-token-123", context.getToken());
        assertEquals(content, context.getLicenseContent());
        assertFalse(context.isRestricted());
    }

    @Test
    void setLicense_shouldClearCheckCache() {
        context.cacheCheckResult("CALCULATE", 60_000L);
        assertTrue(context.isCheckCacheValid("CALCULATE"));

        context.setLicense(content, "jwt-token-123");

        assertFalse(context.isCheckCacheValid("CALCULATE"), "setLicense 应清空 check 缓存");
    }

    @Test
    void setLicense_shouldResetHeartbeatFailureCount() {
        context.incrementHeartbeatFailure();
        context.incrementHeartbeatFailure();

        context.setLicense(content, "jwt-token-123");

        assertEquals(0, context.getHeartbeatFailureCount(), "setLicense 应重置心跳失败计数");
    }

    @Test
    void setRestrictedRestrict_shouldSetRestrictedStatus() {
        context.setLicense(content, "jwt-token-123");
        context.setRestricted(ClientModeEnum.RESTRICT);

        assertEquals(LicenseStatusEnum.RESTRICTED, context.getStatus());
        assertTrue(context.isRestricted());
    }

    @Test
    void setRestrictedNormal_shouldRestoreNormalStatus() {
        context.setLicense(content, "jwt-token-123");
        context.setRestricted(ClientModeEnum.RESTRICT);
        context.setRestricted(ClientModeEnum.NORMAL);

        assertEquals(LicenseStatusEnum.NORMAL, context.getStatus());
        assertFalse(context.isRestricted());
    }

    @Test
    void setRestrictedNull_shouldDoNothing() {
        context.setLicense(content, "jwt-token-123");
        LicenseStatusEnum before = context.getStatus();

        context.setRestricted(null);

        assertEquals(before, context.getStatus(), "null 模式不应改变状态");
    }

    @Test
    void setOfflineLock_shouldSetOfflineLockStatus() {
        context.setLicense(content, "jwt-token-123");
        context.setOfflineLock();

        assertEquals(LicenseStatusEnum.OFFLINE_LOCK, context.getStatus());
        assertFalse(context.isRestricted());
    }

    @Test
    void setOfflineGrace_shouldSetOfflineGraceStatus() {
        context.setLicense(content, "jwt-token-123");
        context.setOfflineGrace();

        assertEquals(LicenseStatusEnum.OFFLINE_GRACE, context.getStatus());
    }

    @Test
    void heartbeatFailureCount_shouldIncrementAndReset() {
        assertEquals(0, context.getHeartbeatFailureCount());

        int count = context.incrementHeartbeatFailure();
        assertEquals(1, count);
        assertEquals(1, context.getHeartbeatFailureCount());

        context.resetHeartbeatFailure();
        assertEquals(0, context.getHeartbeatFailureCount());
    }

    @Test
    void serverUnavailable_shouldWorkCorrectly() {
        assertFalse(context.isServerUnavailable(), "默认不应处于服务器不可用短路期");

        context.setServerUnavailableUntil(System.currentTimeMillis() + 60_000L);
        assertTrue(context.isServerUnavailable());

        context.clearServerUnavailable();
        assertFalse(context.isServerUnavailable());
    }

    @Test
    void serverUnavailable_shouldExpire() throws InterruptedException {
        context.setServerUnavailableUntil(System.currentTimeMillis() + 50L);
        assertTrue(context.isServerUnavailable());

        Thread.sleep(100L);
        assertFalse(context.isServerUnavailable(), "短路期过后应自动恢复");
    }

    @Test
    void checkCache_shouldExpire() throws InterruptedException {
        context.cacheCheckResult("CALCULATE", 50L);
        assertTrue(context.isCheckCacheValid("CALCULATE"));

        Thread.sleep(100L);
        assertFalse(context.isCheckCacheValid("CALCULATE"));
    }

    @Test
    void checkCache_shouldBeInvalidForUnknownOperation() {
        assertFalse(context.isCheckCacheValid("UNKNOWN_OP"));
    }

    @Test
    void checkCache_invalidateSpecific_shouldRemoveOnlyThat() {
        context.cacheCheckResult("CALCULATE", 60_000L);
        context.cacheCheckResult("EXPORT", 60_000L);

        context.invalidateCheckCache("CALCULATE");

        assertFalse(context.isCheckCacheValid("CALCULATE"));
        assertTrue(context.isCheckCacheValid("EXPORT"));
    }

    @Test
    void checkCache_invalidateNull_shouldClearAll() {
        context.cacheCheckResult("CALCULATE", 60_000L);
        context.cacheCheckResult("EXPORT", 60_000L);

        context.invalidateCheckCache(null);

        assertFalse(context.isCheckCacheValid("CALCULATE"));
        assertFalse(context.isCheckCacheValid("EXPORT"));
    }

    @Test
    void fingerprint_shouldBeSettable() {
        HardwareFingerprint fp = HardwareFingerprint.builder()
                .hostMachineId("host-001")
                .instanceId("inst-001")
                .build();

        context.setFingerprint(fp);
        assertEquals(fp, context.getFingerprint());
    }
}
