package com.shoplab;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Kiểm tra cấu trúc modular monolith. Vi phạm → test đỏ, kèm danh sách từng chỗ vi phạm.
 *  - Ranh giới giữa các module: Spring Modulith (không vòng phụ thuộc, chỉ dùng API của module khác,
 *    chỉ phụ thuộc những module khai báo trong allowedDependencies ở package-info.java).
 *  - Phân tầng bên trong mỗi module: ArchUnit.
 */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(ShoplabApplication.class);

    private static final JavaClasses APP = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.shoplab");

    @Test
    @DisplayName("Ranh giới module đúng: không vòng phụ thuộc, chỉ dùng API của module khác, đúng allowedDependencies")
    void verifiesModuleBoundaries() {
        MODULES.verify();
    }

    @Test
    @DisplayName("Sinh tài liệu module (sơ đồ PlantUML, canvas từng module) vào target/spring-modulith-docs")
    void writesModuleDocumentation() {
        new Documenter(MODULES).writeDocumentation();
    }

    @Test
    @DisplayName("Chỉ tầng web dùng kiểu của tầng web: API và internal không biết gì về controller, DTO")
    void onlyWebLayerUsesWebTypes() {
        noClasses().that().resideOutsideOfPackage("com.shoplab..web..")
                .should().dependOnClassesThat().resideInAPackage("com.shoplab..web..")
                .check(APP);
    }

    @Test
    @DisplayName("Repository không public: dữ liệu của module chỉ được truy cập từ code cùng package")
    void repositoriesAreNotPublic() {
        classes().that().areAssignableTo(Repository.class)
                .or().areAnnotatedWith(org.springframework.stereotype.Repository.class)
                .should().notBePublic()
                .check(APP);
    }
}
