package com.skillmasterai;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.skillmasterai.common.ModuleMap;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes the three dependency rules of technical-design.md §2.5 fail the build instead of
 * living in prose.
 *
 * <p>These rules pass trivially while the codebase is small — they start biting as modules
 * land. That is the point: a rule added after the first violation is a rule that has already
 * been broken.
 *
 * <p>Several rules carry {@code allowEmptyShould(true)}, because the packages they govern do
 * not exist yet and ArchUnit treats "matched nothing" as a failure — a guard against a rule
 * whose package pattern has a typo. The flags are deliberately per-rule rather than global
 * (via {@code archRule.failOnEmptyShould}), so that any rule added later still gets the guard.
 * Every module in §2.5 has landed once P0a is finished, at which point the flags are inert.
 *
 * <p>Rule 1 (a module owns its tables) is <em>not</em> checkable here, because table access
 * is a string inside SQL. {@link TableOwnershipTest} approximates it as a source lint.
 */
class ArchitectureTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ModuleMap.BASE_PACKAGE);
    }

    /** §2.5 rule 3. */
    @Test
    void modulesAreFreeOfCycles() {
        slices()
                .matching(ModuleMap.MODULES_PACKAGE + ".(*)..")
                .should().beFreeOfCycles()
                .because("§2.5 rule 3 forbids cyclic dependencies between modules")
                .allowEmptyShould(true) // no module has code until S1; see the class note
                .check(production);
    }

    /** §2.5, the layering that makes the use-case layer meaningful. */
    @Test
    void layersOnlyDependDownwards() {
        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                // optionalLayer rather than layer: three of these are empty until the first
                // endpoints land, and an empty *required* layer is itself a violation.
                //
                // Config sits at the top, above Api: it is Spring wiring, and wiring by
                // definition has to see the beans it assembles. Nothing may depend on it — a
                // module that needs a configured value gets it from its own wiring, which reads
                // the properties record, rather than reaching into the wiring layer.
                .optionalLayer("Config").definedBy(ModuleMap.BASE_PACKAGE + ".config..")
                .optionalLayer("Api").definedBy(ModuleMap.BASE_PACKAGE + ".api..")
                .optionalLayer("UseCase").definedBy(ModuleMap.BASE_PACKAGE + ".usecase..")
                .optionalLayer("Modules").definedBy(ModuleMap.MODULES_PACKAGE + "..")
                .layer("Common").definedBy(ModuleMap.BASE_PACKAGE + ".common..")
                .whereLayer("Config").mayNotBeAccessedByAnyLayer()
                .whereLayer("Api").mayNotBeAccessedByAnyLayer()
                .whereLayer("UseCase").mayOnlyBeAccessedByLayers("Config", "Api")
                .whereLayer("Modules").mayOnlyBeAccessedByLayers("Config", "Api", "UseCase")
                .whereLayer("Common").mayOnlyBeAccessedByLayers("Config", "Api", "UseCase", "Modules")
                .check(production);
    }

    /**
     * §2.5 rule 2. Modules may hold their own internal invariants in a transaction — M7's blob
     * GC has to share the publish transaction — but a transaction that spans modules belongs
     * to the use case, so the annotation may only appear there.
     */
    @Test
    void transactionsAreDeclaredOnlyInUseCases() {
        String useCase = ModuleMap.BASE_PACKAGE + ".usecase..";
        classes().that().areAnnotatedWith(Transactional.class)
                .should().resideInAPackage(useCase)
                .allowEmptyShould(true)
                .check(production);
        methods().that().areAnnotatedWith(Transactional.class)
                .should().beDeclaredInClassesThat().resideInAPackage(useCase)
                .allowEmptyShould(true)
                .check(production);
    }

    /**
     * Rule 1's enforceable half: other modules must go through a module's public interface, not
     * its repositories, and this is what makes "reach across and read their table" a build
     * failure rather than a review comment.
     *
     * <p>Not marked allowEmptyShould: it always has targets, because the rule runs once per
     * module. If it ever matches nothing, the module list itself has gone wrong.
     *
     * <p>It is also the reason internals are "public but confined" rather than package-private.
     *
     * <p>Package-private was the original plan and it does not work: a module's wiring class sits
     * in the module package, {@code internal} is a subpackage, and package-private visibility does
     * not cross a package boundary — so nothing outside {@code internal} could construct anything
     * inside it. Since a module's public service may legitimately need its own repository, the
     * rule that actually holds is this one: {@code internal} is unreachable from outside the
     * module, enforced here rather than by javac.
     */
    @Test
    void nothingOutsideAModuleMayReferenceAnotherModulesInternals() {
        for (ModuleMap.Module module : ModuleMap.modules()) {
            noClasses()
                    .that().resideOutsideOfPackage(module.packageName() + "..")
                    .should().dependOnClassesThat().resideInAPackage(module.internalPackage() + "..")
                    .because(module.id() + " keeps its repositories in " + module.internalPackage())
                    .check(production);
        }
    }

    /**
     * ADR 0123: a module may depend only on another module's published types, plus the shared
     * kernel. The published list is the baseline generated when the rule landed; it may only
     * shrink. A failure here means either the import moves behind a published interface or the
     * published list is widened deliberately (which is a contract change).
     */
    @Test
    void modulesDependOnlyOnPublishedTypes() {
        for (ModuleMap.Module module : ModuleMap.modules()) {
            noClasses()
                    .that().resideInAPackage(ModuleMap.MODULES_PACKAGE + "..")
                    .and().resideOutsideOfPackage(module.packageName() + "..")
                    .should().dependOnClassesThat(new DescribedPredicate<JavaClass>(
                            "not published by " + module.id()) {
                        @Override
                        public boolean test(JavaClass target) {
                            String targetPackage = target.getPackageName();
                            if (!targetPackage.equals(module.packageName())
                                    && !targetPackage.startsWith(module.packageName() + ".")) {
                                return false;
                            }
                            return !ModuleMap.publishes(module, topLevelSimpleName(target.getName()));
                        }
                    })
                    .because("ADR 0123: only published types and the shared kernel cross a module boundary")
                    .check(production);
        }
    }

    /** {@code com.example.Outer$Inner} → {@code Outer}. */
    private static String topLevelSimpleName(String className) {
        int dollar = className.indexOf('$');
        String topLevel = dollar >= 0 ? className.substring(0, dollar) : className;
        return topLevel.substring(topLevel.lastIndexOf('.') + 1);
    }

    /** Common is the bottom of the stack: it depends on nothing of ours. */
    @Test
    void commonDependsOnNoOtherLayer() {
        noClasses()
                .that().resideInAPackage(ModuleMap.BASE_PACKAGE + ".common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        ModuleMap.MODULES_PACKAGE + "..",
                        ModuleMap.BASE_PACKAGE + ".usecase..",
                        ModuleMap.BASE_PACKAGE + ".api..")
                .check(production);
    }
}
