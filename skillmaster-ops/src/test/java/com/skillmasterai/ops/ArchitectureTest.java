package com.skillmasterai.ops;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The import's domain stays free of the framework and of SQL.
 *
 * <p><strong>What this proves and what it does not.</strong> It proves the source dependencies
 * point the way the design says: a rule change is visible in an import line, and adding a
 * {@code JdbcClient} to a policy class fails here. It cannot prove the runtime path stays out of
 * Spring, and reflection or string lookups would walk around it.
 */
@AnalyzeClasses(packages = "com.skillmasterai.ops", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule importsDoesNotDependOnSpring =
            noClasses().that().resideInAPackage("..ops.imports..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..");

    @ArchTest
    static final ArchRule importsDoesNotTouchSql =
            noClasses().that().resideInAPackage("..ops.imports..")
                    .should().dependOnClassesThat().resideInAnyPackage("javax.sql..",
                            "org.springframework.jdbc..");

    @ArchTest
    static final ArchRule theJobLayerDoesNotSpeakHttp =
            noClasses().that().resideInAPackage("..ops.jobs..")
                    .should().dependOnClassesThat().resideInAnyPackage("java.net.http..");

    @ArchTest
    static final ArchRule theRulesArePure =
            noClasses().that().resideInAPackage("..ops.labels..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..",
                            "javax.sql..", "java.net.http..");
}
