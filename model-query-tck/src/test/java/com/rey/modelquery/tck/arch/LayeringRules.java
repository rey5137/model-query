package com.rey.modelquery.tck.arch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.Arrays;

/**
 * The INV-7 dependency rules (delivery/61 R-REL-03). Every rule is parameterised by the root package, so the same
 * rules run on the real code ({@code com.rey.modelquery}) and on a deliberately bad fixture tree.
 */
final class LayeringRules {

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

    /** {@code core} imports only {@code jakarta.persistence.*} and the JDK ({@code java.*}; no {@code javax.*}). */
    ArchRule coreImportsOnlyJakartaPersistenceAndJdk() {
        return classes()
                .that()
                .resideInAPackage(pkg("core"))
                .should()
                .onlyDependOnClassesThat(resideInAnyPackage(pkg("core"), "java..", "jakarta.persistence.."))
                .as("core imports only jakarta.persistence and the JDK")
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

    /** {@code jakarta.validation} is used by {@code jpa} only. */
    ArchRule onlyJpaImportsJakartaValidation() {
        return noClasses()
                .that()
                .resideOutsideOfPackage(pkg("jpa"))
                .and()
                .resideInAPackage(root + "..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("jakarta.validation..")
                .as("only jpa imports jakarta.validation")
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
     * only on {@code annotations}. No module may depend on a module that is later in the order or beside it.
     */
    ArchRule dependenciesFlowOneWay() {
        return CompositeArchRule.of(forbid("annotations", "core", "jpa", "hibernate", "spring", "processor"))
                .and(forbid("core", "jpa", "hibernate", "spring", "processor"))
                .and(forbid("jpa", "hibernate", "spring", "processor"))
                .and(forbid("hibernate", "spring", "processor"))
                .and(forbid("spring", "hibernate", "processor"))
                .and(forbid("processor", "core", "jpa", "hibernate", "spring"))
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
