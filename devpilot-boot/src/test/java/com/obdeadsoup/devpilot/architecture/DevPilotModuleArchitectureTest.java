package com.obdeadsoup.devpilot.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 从当前 Maven 依赖方向提炼稳定模块规则，防止下层模块反向引用上层业务模块。
 * Boot 是组合根，不参与业务模块的禁止方向。
 */
@DisplayName("DevPilot 模块依赖架构")
class DevPilotModuleArchitectureTest {

    private static final String BASE = "com.obdeadsoup.devpilot.";
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.obdeadsoup.devpilot");

    @Test
    @DisplayName("业务模块依赖图不得形成循环")
    void businessModulesMustBeFreeOfCycles() {
        slices().matching(BASE + "(*)..")
                .should().beFreeOfCycles()
                .because("模块化单体必须保持 framework 到末端模块的单向依赖")
                .check(CLASSES);
    }

    @Test
    @DisplayName("framework 必须保持业务中立")
    void frameworkMustNotDependOnBusinessModules() {
        noClasses().that().resideInAPackage(BASE + "framework..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        packages("identity", "project", "outbox", "task", "github", "notification", "audit", "agent"))
                .because("framework 只能提供中立基础设施，不能引用具体业务包")
                .check(CLASSES);
    }

    @Test
    @DisplayName("业务模块只能沿已批准方向依赖")
    void businessModulesMustFollowApprovedDirections() {
        forbid("identity", new String[]{"project", "outbox", "task", "github", "notification", "audit", "agent"});
        forbid("project", new String[]{"outbox", "task", "github", "notification", "audit", "agent"});
        forbid("outbox", new String[]{"identity", "project", "task", "github", "notification", "audit", "agent"});
        forbid("task", new String[]{"github", "notification", "audit", "agent"});
        forbid("github", new String[]{"notification", "audit", "agent"});
        forbid("notification", new String[]{"audit", "agent"});
        forbid("audit", new String[]{"task", "notification", "agent"});
    }

    private void forbid(String sourceModule, String[] forbiddenModules) {
        noClasses().that().resideInAPackage(BASE + sourceModule + "..")
                .should().dependOnClassesThat().resideInAnyPackage(packages(forbiddenModules))
                .because(sourceModule + " 不得反向或越层依赖 " + String.join(", ", forbiddenModules))
                .check(CLASSES);
    }

    private static String[] packages(String... modules) {
        String[] result = new String[modules.length];
        for (int index = 0; index < modules.length; index++) {
            result[index] = BASE + modules[index] + "..";
        }
        return result;
    }
}
