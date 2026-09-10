package com.panjia.importutil;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * common-import-util 架构红线：
 * 1. 纯技术工具层，禁止依赖任何 JDBC / MyBatis / Spring Data 持久化 API；
 * 2. 禁止出现任何业务表名（pj_ 前缀）——只输出内存 DTO，不落业务库表。
 */
class ImportUtilArchitectureTest {

    private static final String BASE = "com.panjia.importutil";

    @Test
    void 工具层不得依赖持久化API() {
        ArchRule rule = noClasses()
            .that().resideInAPackage(BASE + "..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "java.sql..",
                "javax.sql..",
                "jakarta.persistence..",
                "org.apache.ibatis..",
                "com.baomidou.mybatisplus..",
                "org.springframework.jdbc..",
                "org.springframework.data.repository..",
                "com.panjia.importdomain..",
                "com.panjia.people.."
            )
            .because("工具层只输出内存 DTO（ParsedSheet/ParsedRow/FieldError），不落任何业务库表");
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
            .because("工具层不允许定义 Mapper/Repository");
        rule.check(new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE));
    }
}
