package com.panjia.architecture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Spring bean 名冲突守卫。
 * <p>
 * <b>为什么需要这个测试：</b>{@code PanjiaAutoConfiguration} 上有
 * {@code @ComponentScan(basePackages = "com.panjia")}，整个 {@code com.panjia.**} 位于
 * <b>同一个 Spring 上下文</b>。而 Spring 对 {@code @Service/@Component/…} 默认取
 * 「简单类名首字母小写」作为 bean 名 → 不同模块里两个<b>同名类</b>会产生同一个 bean 名，
 * 启动时抛 {@code ConflictingBeanDefinitionException}。
 * <p>
 * 这类缺陷<b>编译期完全发现不了</b>：各模块单独编译都通过，只有启动才炸。
 * 已实际发生一次：业绩域的 {@code ConversionService} 与结佣域的同名类撞名
 * {@code 'conversionService'}；处置是把业绩域那个改名为 {@code FactConversionResolver}
 * （它只做 factId→bizType 解析），结佣域那个纯转发壳直接删除、改为注入
 * {@code ConversionFactorPort}。
 * <p>
 * <b>为什么放在 ruoyi-admin：</b>本仓库只有 ruoyi-admin 的测试 classpath 同时包含
 * performance / commission / payroll / people / import 等全部业务模块，
 * 换成任一业务模块都会因看不到兄弟模块而漏检。请勿移动本类。
 * <p>
 * 扫描走 {@link ClassPathBeanDefinitionScanner}，只注册 bean 定义、<b>不实例化</b>，
 * 因此不依赖数据库、不需要 Spring 容器启动，毫秒级完成；
 * 冲突正是在 {@code checkCandidate} 注册阶段抛出的，与实际启动路径一致。
 */
@Tag("dev")
class SpringBeanNameCollisionTest {

    @Test
    void noDuplicatedBeanNameUnderComPanjia() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(beanFactory);

        assertDoesNotThrow(
            () -> scanner.scan("com.panjia"),
            "com.panjia 下存在同简单类名的 Spring 组件，bean 名冲突，应用启动必失败。"
                + " 修复：给其中之一显式命名（如 @Service(\"performanceConversionService\")），"
                + "或移除多余的同名类（若它只是纯转发壳）。"
        );
    }
}
