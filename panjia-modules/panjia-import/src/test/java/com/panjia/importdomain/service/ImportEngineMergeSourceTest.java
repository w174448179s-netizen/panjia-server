package com.panjia.importdomain.service;

import com.panjia.importdomain.domain.ImportSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入来源合并语义边界测试（2026-11-09）。
 * <p>
 * 合并来源（同月允许多批次、不做整批替换）：{@code POINTS}、{@code KE_RECEIVED}、{@code KE_SIGNED}；
 * 其余来源仍按整批替换（{@code supersedeIfDuplicate}）。
 */
@Tag("dev")
class ImportEngineMergeSourceTest {

    /**
     * isMergeSource 为私有纯函数，Mockito 不拦截 private 方法，
     * 用 CALLS_REAL_METHODS 的实例承载反射调用即可，无需构造真实依赖。
     */
    private ImportEngine engine;
    private Method isMergeSource;

    @BeforeEach
    void setUp() throws Exception {
        engine = Mockito.mock(ImportEngine.class, Mockito.CALLS_REAL_METHODS);
        isMergeSource = ImportEngine.class.getDeclaredMethod("isMergeSource", ImportSourceType.class);
        isMergeSource.setAccessible(true);
    }

    @Test
    void mergeSources_true() throws Exception {
        assertTrue(invoke(ImportSourceType.POINTS));
        assertTrue(invoke(ImportSourceType.KE_RECEIVED));
        assertTrue(invoke(ImportSourceType.KE_SIGNED));
    }

    @Test
    void replaceSources_false() throws Exception {
        assertFalse(invoke(ImportSourceType.ATTENDANCE));
        assertFalse(invoke(ImportSourceType.OTHERS));
        assertFalse(invoke(ImportSourceType.HISTORY_PAYROLL));
    }

    private boolean invoke(ImportSourceType sourceType) throws Exception {
        return (Boolean) isMergeSource.invoke(engine, sourceType);
    }
}
