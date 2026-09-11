package com.panjia.importutil;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * common-import-util 架构红线（V6.0.1 修订）：
 * <ol>
 *   <li>不得依赖任何业务域（com.panjia.importdomain / com.panjia.people）— 工具层不调业务逻辑</li>
 *   <li>不得出现 Mapper / Repository 注解</li>
 *   <li>不得出现任何业务表名（pj_ 前缀）— 只输出内存 DTO，不落业务库表</li>
 * </ol>
 * <p>
 * V6.0.1 修订说明：移除「禁止依赖 mybatis / mybatis-plus / jdbc / spring-data」红线。
 * common-import-util 内置 {@code DefaultSysDictDataAdapter} 走
 * {@code ISysDictDataService}（位于 ruoyi-system），后者会传递引入 mybatis-plus，
 * 这是字典中心查询公共设计的必要依赖。工具层代码本身只调 service 接口，不直接用 Mapper。
 */
class ImportUtilArchitectureTest {

    private static final String BASE = "com.panjia.importutil";

    @Test
    void 工具层不得依赖业务域() {
        ArchRule rule = noClasses()
            .that().resideInAPackage(BASE + "..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.panjia.importdomain..",
                "com.panjia.people.."
            )
            .because("工具层不调业务逻辑；dictType 校验走 ruoyi-system ISysDictDataService，"
                + "跨业务域字典需求由业务域通过 DictDataPort 自定义实现 SPI 接入");
        rule.check(new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE));
    }

    @Test
    void 工具层不得出现Mapper或Repository注解() {
        ArchRule rule = noClasses()
            .that().resideInAPackage(BASE + "..")
            .should().beAnnotatedWith("org.apache.ibatis.annotations.Mapper")
            .orShould().beAnnotatedWith("org.springframework.stereotype.Repository")
            .because("工具层不允许定义 Mapper/Repository；字典查询走 ISysDictDataService service 接口");
        rule.check(new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE));
    }
}
