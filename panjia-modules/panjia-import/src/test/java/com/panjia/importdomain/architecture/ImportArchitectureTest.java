package com.panjia.importdomain.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 导入域架构守护（V2.0 交易单据域）。
 * <ul>
 *   <li>规则1：只有 adapter 包可以依赖 org.dromara.system.*（底座 sys_* 细节）。</li>
 *   <li>规则2：import 域禁止依赖 people 域任何包（员工导入已迁至 people 域，
 *       import 对 people 仅通过 contracts 的 PeopleQueryPort 只读协作）。</li>
 *   <li>规则3：import 禁止依赖其他兄弟业务域实现。</li>
 *   <li>规则4：源码/SQL 中不得出现 EmployeeImportSink / pj_people_ 残留。</li>
 * </ul>
 */
@Tag("dev")
class ImportArchitectureTest {

    private final JavaClasses importClasses =
        new ClassFileImporter().importPackages("com.panjia.importdomain");

    /**
     * 规则1：org.dromara.system.* 仅 adapter 包可依赖。
     */
    @Test
    void only_adapter_may_depend_on_ruoyi_system() {
        ArchRule rule = noClasses()
            .that().resideOutsideOfPackage("com.panjia.importdomain.adapter..")
            .should().dependOnClassesThat().resideInAnyPackage("org.dromara.system..")
            .because("sys_user/sys_dept/sys_post 等底座细节只能由 adapter 包访问");
        assertDoesNotThrow(() -> rule.check(importClasses));
    }

    /**
     * 规则2：import 禁止依赖 people 域（V2.0 起员工导入在 people 域内部完成）。
     */
    @Test
    void import_must_not_depend_on_people() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.panjia.importdomain..")
            .should().dependOnClassesThat().resideInAPackage("com.panjia.people..")
            .because("V2.0 员工导入归 people 域；import 对员工仅通过 contracts 的 PeopleQueryPort 只读匹配");
        assertDoesNotThrow(() -> rule.check(importClasses));
    }

    /**
     * 规则3：import 禁止依赖其他兄弟业务域。
     */
    @Test
    void import_must_not_depend_on_sibling_domains() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.panjia.importdomain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.panjia.salary..",
                "com.panjia.ledger..",
                "com.panjia.backup..")
            .because("import 与兄弟域协作只能走 com.panjia.contracts 端口");
        assertDoesNotThrow(() -> rule.check(importClasses));
    }

    /**
     * 规则4：源码与 SQL 不得残留员工导入受控写痕迹。
     */
    @Test
    void no_employee_import_sink_residue() throws IOException {
        Path src = locateSrcMain();
        List<String> forbidden = List.of("EmployeeImportSink", "pj_people_");
        try (Stream<Path> paths = Files.walk(src)) {
            paths.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".java") || name.endsWith(".sql");
                }).forEach(p -> {
                    String content;
                    try {
                        content = Files.readString(p, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    for (String token : forbidden) {
                        assertFalse(content.contains(token),
                            "文件 " + p + " 残留已废弃标识: " + token);
                    }
                });
        }
    }

    private Path locateSrcMain() {
        Path dir = Paths.get("src/main");
        if (Files.exists(dir)) {
            return dir;
        }
        dir = Paths.get("panjia-modules/panjia-import/src/main");
        if (Files.exists(dir)) {
            return dir;
        }
        throw new IllegalStateException("找不到 src/main 目录");
    }
}
