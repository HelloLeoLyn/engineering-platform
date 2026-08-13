package com.engineeringplatform.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

class PlatformCoreArchitectureTest {
    private final JavaClasses classes = new ClassFileImporter().importPackages("com.engineeringplatform");

    @Test
    void platformCoreMustNotDependOnHigherLayers() {
        noClasses().that().resideInAPackage("com.engineeringplatform.core..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.engineeringplatform.web..",
                        "com.engineeringplatform.data..",
                        "com.engineeringplatform.app..")
                .check(classes);
    }
}
