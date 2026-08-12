package com.obdeadsoup.devpilot.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 防止跨模块直接使用 Mapper、Persistence Entity 或 Repository 实现。 */
@DisplayName("DevPilot 跨模块 Persistence 边界")
class CrossModulePersistenceBoundaryTest {

    private static final String BASE = "com.obdeadsoup.devpilot.";
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.obdeadsoup.devpilot");

    @Test
    @DisplayName("跨模块调用不得绕过公开应用服务或 Port 直连 Persistence")
    void modulesMustNotAccessAnotherModulesPersistenceImplementation() {
        for (String owner : new String[]{"identity", "project", "outbox", "task", "github", "notification", "audit"}) {
            noClasses().that().resideOutsideOfPackage(BASE + owner + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            BASE + owner + ".persistence.mapper..",
                            BASE + owner + ".persistence.entity..",
                            BASE + owner + ".persistence.repository..")
                    .because("其他模块必须通过 " + owner + " 的 application/api/port 边界协作")
                    .check(CLASSES);
        }
    }
}
