package com.obdeadsoup.devpilot.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 守住 HTTP、Domain 与 Persistence 的关键边界，不限制 application 使用本模块持久化能力。 */
@DisplayName("DevPilot 分层边界架构")
class LayerBoundaryArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.obdeadsoup.devpilot");

    @Test
    @DisplayName("Controller/API 层不得直接访问 Mapper")
    void apiMustNotAccessMapperDirectly() {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..persistence.mapper..")
                .because("Controller 必须把业务与数据访问委托给应用服务")
                .check(CLASSES);
    }

    @Test
    @DisplayName("Domain 层不得依赖 Web 或 Persistence 技术实现")
    void domainMustRemainTechnologyIndependent() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..api..",
                        "..persistence..",
                        "org.springframework.web..",
                        "org.springframework.http..",
                        "jakarta.servlet..",
                        "com.baomidou..")
                .because("Domain 规则应可脱离 Controller、Servlet、Spring MVC 与 MyBatis 独立演进")
                .check(CLASSES);
    }

    @Test
    @DisplayName("Persistence 层不得反向依赖 API")
    void persistenceMustNotDependOnApi() {
        noClasses().that().resideInAPackage("..persistence..")
                .should().dependOnClassesThat().resideInAPackage("..api..")
                .because("持久化实现不能反向绑定 HTTP Controller 或 Response DTO")
                .check(CLASSES);
    }
}
