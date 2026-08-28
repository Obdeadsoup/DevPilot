package com.obdeadsoup.devpilot.gateway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 固化 Gateway 的纯边缘边界，阻止业务规则、Servlet MVC 或持久化能力被逐步搬入。 */
@DisplayName("Gateway 模块边界")
class GatewayBoundaryArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.obdeadsoup.devpilot.gateway");

    @Test
    @DisplayName("Gateway 不得依赖业务模块或持久化实现")
    void gatewayMustRemainBusinessNeutral() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "com.obdeadsoup.devpilot.identity..",
                        "com.obdeadsoup.devpilot.project..",
                        "com.obdeadsoup.devpilot.task..",
                        "com.obdeadsoup.devpilot.github..",
                        "com.obdeadsoup.devpilot.notification..",
                        "com.obdeadsoup.devpilot.audit..",
                        "com.obdeadsoup.devpilot.agent..",
                        "..persistence..",
                        "com.baomidou..")
                .because("Gateway 只能路由和处理边缘协议，RBAC 与业务查询必须留在 Core")
                .check(CLASSES);
    }

    @Test
    @DisplayName("Gateway 必须保持 WebFlux 技术栈")
    void gatewayMustNotUseServletOrSpringMvc() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.servlet..",
                        "org.springframework.web.servlet..")
                .because("Gateway 使用响应式 WebFlux，不能混入 Servlet MVC")
                .check(CLASSES);
    }
}
