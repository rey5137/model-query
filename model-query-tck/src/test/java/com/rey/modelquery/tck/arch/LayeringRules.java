package com.rey.modelquery.tck.arch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.Arrays;
import java.util.Set;

/**
 * The INV-7 dependency rules (delivery/61 R-REL-03). Every rule is parameterised by the root package, so the same
 * rules run on the real code ({@code com.rey.modelquery}) and on a deliberately bad fixture tree.
 */
final class LayeringRules {

    private static final String ENGINE_FACING = "com.rey.modelquery.core.EngineFacing";
    /** The one {@code @EngineFacing} type {@code test} may hold: a fetch plan's child load (D-104). */
    private static final String CHILD_LOAD = "com.rey.modelquery.core.ChildLoad";
    /** The {@code @EngineFacing} members {@code test} may call, to assert a child query (D-104). */
    private static final Set<String> CHILD_READS = Set.of("com.rey.modelquery.core.FetchPlan.childLoads",
            CHILD_LOAD + ".field", CHILD_LOAD + ".query", CHILD_LOAD + ".maxPerParent", CHILD_LOAD + ".toString");

    private final String root;
    private final boolean allowEmptyShould;

    /**
     * @param allowEmptyShould {@code false} for the real code, so a rule that matches nothing fails instead of passing
     *     vacuously; {@code true} for the bad fixture, whose tests assert the intended violation by message instead
     */
    LayeringRules(String root, boolean allowEmptyShould) {
        this.root = root;
        this.allowEmptyShould = allowEmptyShould;
    }

    private String pkg(String module) {
        return root + "." + module + "..";
    }

    /**
     * {@code core} imports only {@code jakarta.persistence.*}, {@code annotations} and the JDK ({@code java.*}; no
     * {@code javax.*}).
     */
    ArchRule coreImportsOnlyJakartaPersistenceAndJdk() {
        return classes()
                .that()
                .resideInAPackage(pkg("core"))
                .should()
                .onlyDependOnClassesThat(resideInAnyPackage(pkg("core"), pkg("annotations"), "java..", "jakarta.persistence.."))
                .as("core imports only jakarta.persistence, annotations and the JDK")
                .allowEmptyShould(allowEmptyShould);
    }

    /** {@code jpa} does not import {@code org.hibernate.*}. */
    ArchRule jpaDoesNotImportHibernate() {
        return noClasses()
                .that()
                .resideInAPackage(pkg("jpa"))
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.hibernate..")
                .as("jpa does not import org.hibernate")
                .allowEmptyShould(allowEmptyShould);
    }

    /** Only the {@code spring-*} modules import {@code org.springframework.*}. */
    ArchRule onlySpringModulesImportSpring() {
        return noClasses()
                .that()
                .resideOutsideOfPackage(pkg("spring"))
                .and()
                .resideInAPackage(root + "..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.springframework..")
                .as("only spring-* modules import org.springframework")
                .allowEmptyShould(allowEmptyShould);
    }

    /**
     * {@code jakarta.validation} is used only by {@code jpa}'s {@code @ValidChanges} and its validator, so the rest of
     * the engine runs without it (api/14 R-WRT-22).
     */
    ArchRule onlyValidChangesImportsJakartaValidation() {
        String validChanges = root + ".jpa.ValidChanges";
        return noClasses()
                .that()
                .resideInAPackage(root + "..")
                .and(describe("are not @ValidChanges or its validator",
                        c -> !c.getFullName().startsWith(validChanges)))
                .should()
                .dependOnClassesThat()
                .resideInAPackage("jakarta.validation..")
                .as("only @ValidChanges and its validator import jakarta.validation")
                .allowEmptyShould(allowEmptyShould);
    }

    /**
     * {@code annotations} depends only on the JDK ({@code java.*}), so a model can be annotated in a module that has
     * neither JPA nor the engine (processor/30 R-PROC-01).
     */
    ArchRule annotationsDependOnlyOnJdk() {
        return classes()
                .that()
                .resideInAPackage(pkg("annotations"))
                .should()
                .onlyDependOnClassesThat(resideInAnyPackage(pkg("annotations"), "java.."))
                .as("annotations depends only on the JDK")
                .allowEmptyShould(allowEmptyShould);
    }

    /**
     * {@code processor} depends only on {@code annotations}, the JDK ({@code java.*} and the JDK's annotation
     * processing packages) and (shaded) JavaPoet. Other {@code javax.*} packages are third-party and not allowed.
     */
    ArchRule processorDependsOnlyOnAnnotationsAndJavaPoet() {
        return classes()
                .that()
                .resideInAPackage(pkg("processor"))
                .should()
                .onlyDependOnClassesThat(resideInAnyPackage(
                        pkg("processor"),
                        pkg("annotations"),
                        "java..",
                        "javax.annotation.processing..",
                        "javax.lang.model..",
                        "javax.tools..",
                        "com.squareup.javapoet.."))
                .as("processor depends only on annotations and JavaPoet")
                .allowEmptyShould(allowEmptyShould);
    }

    /**
     * {@code test} depends only on {@code core} (and the {@code annotations} it exposes), the JDK and AssertJ, so a
     * unit test needs no provider, Spring or other library to assert on a query (api/16 R-INS-06, D-98).
     */
    ArchRule testDependsOnlyOnCoreAndAssertJ() {
        return classes()
                .that()
                .resideInAPackage(pkg("test"))
                .should()
                .onlyDependOnClassesThat(resideInAnyPackage(
                        pkg("test"), pkg("core"), pkg("annotations"), "java..", "org.assertj.."))
                .as("test depends only on core and AssertJ")
                .allowEmptyShould(allowEmptyShould);
    }

    /**
     * {@code test} calls or accesses no member annotated {@code @EngineFacing} and uses no type annotated with it, so
     * an assertion reads the query's public view and never an engine seam (api/16 R-INS-06). The one exception is a
     * child query's assertion, which reads {@code FetchPlan.childLoads()} and a {@code ChildLoad}'s field, query,
     * bound and name, and nothing else of it (D-104).
     */
    ArchRule testUsesNoEngineFacingMember() {
        return noClasses()
                .that()
                .resideInAPackage(pkg("test"))
                .should(useEngineFacing())
                .as("test uses no @EngineFacing member or type")
                .allowEmptyShould(allowEmptyShould);
    }

    private static ArchCondition<JavaClass> useEngineFacing() {
        return new ArchCondition<>("use an @EngineFacing member or type") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                for (JavaAccess<?> access : origin.getAccessesFromSelf()) {
                    AccessTarget target = access.getTarget();
                    if (CHILD_READS.contains(target.getFullName().replaceFirst("\\(.*", ""))) {
                        continue;
                    }
                    boolean member = target.resolveMember().map(m -> m.isAnnotatedWith(ENGINE_FACING)).orElse(false);
                    if (member || target.getOwner().isAnnotatedWith(ENGINE_FACING)) {
                        events.add(SimpleConditionEvent.satisfied(access, access.getDescription()));
                    }
                }
                origin.getDirectDependenciesFromSelf().stream()
                        .filter(dependency -> dependency.getTargetClass().isAnnotatedWith(ENGINE_FACING))
                        .filter(dependency -> !dependency.getTargetClass().getName().equals(CHILD_LOAD))
                        .forEach(dependency ->
                                events.add(SimpleConditionEvent.satisfied(dependency, dependency.getDescription())));
            }
        };
    }

    /**
     * No Lombok anywhere in the library. Lombok's annotations are source-retained, so this only catches references
     * that survive into bytecode; the enforcer {@code bannedDependencies} rule in the root pom is the primary guard.
     */
    ArchRule noLombok() {
        return noClasses()
                .that()
                .resideInAPackage(root + "..")
                .should()
                .dependOnClassesThat(describe("belong to Lombok", c -> c.getPackageName().startsWith("lombok")))
                .as("no Lombok anywhere in the library")
                .allowEmptyShould(allowEmptyShould);
    }

    /**
     * Only profiles and vendor detection name a {@code DatabaseVendor} (INV-6, R-VND-01): {@code jpa.spi},
     * {@code jpa.vendor}, the configuration that sets one explicitly and the Hibernate provider support that maps a
     * dialect to one.
     */
    ArchRule databaseVendorOnlyInProfilesAndDetection() {
        return noClasses()
                .that()
                .resideOutsideOfPackages(pkg("jpa.spi"), pkg("jpa.vendor"))
                .and()
                .doNotHaveFullyQualifiedName(root + ".jpa.ModelQueryConfig")
                .and()
                .doNotHaveFullyQualifiedName(root + ".hibernate.HibernateProviderSupport")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(root + ".jpa.spi.DatabaseVendor")
                .as("only profiles and vendor detection name a DatabaseVendor")
                .allowEmptyShould(allowEmptyShould);
    }

    /**
     * The one-way order {@code annotations <- core <- jpa <- (hibernate, spring)}, with {@code processor} depending
     * only on {@code annotations} and {@code test} (a leaf beside {@code jpa}) only on {@code core}. No module may
     * depend on a module that is later in the order or beside it.
     */
    ArchRule dependenciesFlowOneWay() {
        return CompositeArchRule
                .of(forbid("annotations", "core", "jpa", "hibernate", "spring", "processor", "test"))
                .and(forbid("core", "jpa", "hibernate", "spring", "processor", "test"))
                .and(forbid("jpa", "hibernate", "spring", "processor", "test"))
                .and(forbid("hibernate", "spring", "processor", "test"))
                .and(forbid("spring", "hibernate", "processor", "test"))
                .and(forbid("processor", "core", "jpa", "hibernate", "spring", "test"))
                .and(forbid("test", "jpa", "hibernate", "spring", "processor"))
                .as("dependencies flow one way: annotations <- core <- jpa <- (hibernate, spring)")
                .allowEmptyShould(allowEmptyShould);
    }

    private ArchRule forbid(String from, String... to) {
        String[] targets = Arrays.stream(to).map(this::pkg).toArray(String[]::new);
        return noClasses()
                .that()
                .resideInAPackage(pkg(from))
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(targets)
                .allowEmptyShould(allowEmptyShould);
    }
}
