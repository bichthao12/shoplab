package com.shoplab;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Tự kiểm tra các quy tắc giữa module (README, mục "Quy tắc giữa các module").
 * Vi phạm → test đỏ, kèm danh sách từng chỗ vi phạm.
 */
class ArchitectureTests {

    private static final List<String> BUSINESS_MODULES = List.of("product", "order");

    private static final JavaClasses APP = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.shoplab");

    @Test
    @DisplayName("Không có vòng phụ thuộc giữa các module")
    void modulesAreFreeOfCycles() {
        slices().matching("com.shoplab.(*)..").should().beFreeOfCycles().check(APP);
    }

    @Test
    @DisplayName("common không phụ thuộc module nào")
    void commonDoesNotDependOnModules() {
        noClasses().that().resideInAPackage("com.shoplab.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.shoplab.product..", "com.shoplab.order..", "com.shoplab.idempotency..")
                .check(APP);
    }

    @Test
    @DisplayName("idempotency là module dùng chung, không biết tới module nghiệp vụ")
    void idempotencyDoesNotDependOnBusinessModules() {
        noClasses().that().resideInAPackage("com.shoplab.idempotency..")
                .should().dependOnClassesThat().resideInAnyPackage("com.shoplab.product..", "com.shoplab.order..")
                .check(APP);
    }

    @Test
    @DisplayName("Entity chỉ được dùng trong module của nó (module khác tham chiếu bằng id)")
    void entitiesStayInsideTheirModule() {
        for (String module : BUSINESS_MODULES) {
            String pkg = "com.shoplab." + module + "..";
            noClasses().that().resideOutsideOfPackage(pkg)
                    .should().dependOnClassesThat(resideInAPackage(pkg).and(annotatedWith(Entity.class)))
                    .check(APP);
        }
    }

    @Test
    @DisplayName("DTO web chỉ được dùng trong module của nó")
    void webDtosStayInsideTheirModule() {
        for (String module : BUSINESS_MODULES) {
            noClasses().that().resideOutsideOfPackage("com.shoplab." + module + "..")
                    .should().dependOnClassesThat().resideInAPackage("com.shoplab." + module + ".dto..")
                    .check(APP);
        }
    }

    @Test
    @DisplayName("DTO web chỉ dùng ở controller: service, entity, command không phụ thuộc DTO")
    void onlyControllersUseWebDtos() {
        noClasses().that().resideOutsideOfPackage("com.shoplab..dto..")
                .and().areNotAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().resideInAPackage("com.shoplab..dto..")
                .check(APP);
    }

    @Test
    @DisplayName("Repository không public: chỉ code cùng package mới truy cập được dữ liệu của module")
    void repositoriesAreNotPublic() {
        classes().that().areAssignableTo(Repository.class)
                .or().areAnnotatedWith(org.springframework.stereotype.Repository.class)
                .should().notBePublic()
                .check(APP);
    }
}
