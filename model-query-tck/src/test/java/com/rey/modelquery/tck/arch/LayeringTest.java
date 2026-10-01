package com.rey.modelquery.tck.arch;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * INV-7 layering (delivery/61 R-REL-03). Each rule passes on the real code and fails on a deliberately bad fixture
 * tree ({@code com.rey.modelquery.tck.arch.fixture}, compiled in test scope only), so a rule that silently stops
 * matching anything turns this test red.
 */
class LayeringTest {

    private static final String REAL_ROOT = "com.rey.modelquery";
    private static final String FIXTURE_ROOT = "com.rey.modelquery.tck.arch.fixture";

    private static final JavaClasses REAL = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(REAL_ROOT);

    private static final JavaClasses BAD = new ClassFileImporter().importPackages(FIXTURE_ROOT);

    // The real code fails on an empty match; the fixture may leave some sub-rules empty, so its tests assert the
    // intended violation by message instead.
    private static final LayeringRules REAL_RULES = new LayeringRules(REAL_ROOT, false);
    private static final LayeringRules BAD_RULES = new LayeringRules(FIXTURE_ROOT, true);

    private static final String F = FIXTURE_ROOT + ".";
    private static final String HARNESS = "com.rey.modelquery.tck.harness.";

    /** The rule passes on real code and fails on the fixture, naming each expected bad class and forbidden target. */
    private static void proves(Function<LayeringRules, ArchRule> rule, String... expectedInMessage) {
        assertThatCode(() -> rule.apply(REAL_RULES).check(REAL)).doesNotThrowAnyException();
        assertThatCode(() -> rule.apply(BAD_RULES).check(BAD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContainingAll(expectedInMessage);
    }

    @Test
    void realCodeIsImported() {
        // Guards against vacuous passes: every module's classes must be on the TCK classpath.
        org.assertj.core.api.Assertions.assertThat(REAL.stream().map(c -> c.getPackageName()))
                .anyMatch(p -> p.startsWith(REAL_ROOT + ".annotations"))
                .anyMatch(p -> p.startsWith(REAL_ROOT + ".core"))
                .anyMatch(p -> p.startsWith(REAL_ROOT + ".jpa"))
                .anyMatch(p -> p.startsWith(REAL_ROOT + ".hibernate"))
                .anyMatch(p -> p.startsWith(REAL_ROOT + ".processor"))
                .anyMatch(p -> p.startsWith(REAL_ROOT + ".spring.data"))
                .anyMatch(p -> p.startsWith(REAL_ROOT + ".spring.boot"));
    }

    @Test
    void ac_rel_02_coreImportsOnlyJakartaPersistenceAndJdk() {
        proves(
                LayeringRules::coreImportsOnlyJakartaPersistenceAndJdk,
                F + "core.BadCoreHibernate",
                "org.hibernate.Session",
                F + "core.BadCoreThirdPartyJavax",
                "javax.inject.fixturestub.Stub");
    }

    @Test
    void ac_rel_02_jpaDoesNotImportHibernate() {
        proves(LayeringRules::jpaDoesNotImportHibernate, F + "jpa.BadJpaHibernate", "org.hibernate.SessionFactory");
    }

    @Test
    void ac_rel_02_onlySpringModulesImportSpring() {
        proves(
                LayeringRules::onlySpringModulesImportSpring,
                F + "hibernate.BadSpringImport",
                "org.springframework.fixturestub.Stub");
    }

    @Test
    void ac_rel_02_onlyValidChangesImportsJakartaValidation() {
        proves(
                LayeringRules::onlyValidChangesImportsJakartaValidation,
                F + "hibernate.BadValidationImport",
                F + "jpa.BadJpaValidationImport",
                "jakarta.validation.fixturestub.Stub");
        assertThatCode(() -> BAD_RULES.onlyValidChangesImportsJakartaValidation().check(BAD))
                .hasMessageNotContaining(F + "jpa.ValidChangesValidator");
    }

    @Test
    void ac_proc_01_annotationsDependOnlyOnTheJdk() {
        proves(
                LayeringRules::annotationsDependOnlyOnJdk,
                F + "annotations.BadAnnotations",
                F + "core.GoodCore",
                F + "annotations.BadAnnotationsThirdParty",
                "javax.inject.fixturestub.Stub");
    }

    @Test
    void ac_rel_02_processorDependsOnlyOnAnnotationsAndJavaPoet() {
        proves(
                LayeringRules::processorDependsOnlyOnAnnotationsAndJavaPoet,
                F + "processor.BadProcessorModuleEdge",
                F + "jpa.GoodJpa",
                F + "processor.BadProcessorThirdPartyJavax",
                "javax.inject.fixturestub.Stub");
    }

    @Test
    void ac_rel_02_noLombok() {
        proves(LayeringRules::noLombok, F + "hibernate.BadLombokImport", "lombok.fixturestub.Stub");
    }

    @Test
    void ac_rel_02_dependenciesFlowOneWay() {
        proves(
                LayeringRules::dependenciesFlowOneWay,
                F + "annotations.BadAnnotations",
                F + "core.GoodCore",
                F + "processor.BadProcessorModuleEdge",
                F + "jpa.GoodJpa");
    }

    @Test
    void ac_qa_05_coreAndJpaHibernateEdgesFail() {
        proves(
                LayeringRules::coreImportsOnlyJakartaPersistenceAndJdk,
                F + "core.BadCoreHibernate",
                "org.hibernate.Session");
        proves(LayeringRules::jpaDoesNotImportHibernate, F + "jpa.BadJpaHibernate", "org.hibernate.SessionFactory");
    }

    @Test
    void ac_vnd_01_onlyProfilesAndDetectionNameADatabaseVendor() {
        proves(LayeringRules::databaseVendorOnlyInProfilesAndDetection, F + "jpa.BadVendorCheck",
                F + "jpa.spi.DatabaseVendor");
    }

    /**
     * No test class outside the harness knows which vendor it runs on (R-QA-03, AC-QA-02): the TCK sources are the
     * same for every vendor, and only the snapshot directory chosen by {@code SqlSnapshots} differs.
     */
    private static ArchRule noVendorOutside(String... exemptPackages) {
        String[] outside = new String[exemptPackages.length + 1];
        outside[0] = "..tck.harness..";
        System.arraycopy(exemptPackages, 0, outside, 1, exemptPackages.length);
        return ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage("com.rey.modelquery.tck..")
                .and()
                .resideOutsideOfPackages(outside)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(HARNESS + "TckVendor")
                .orShould()
                .callMethod(HARNESS + "TckDatabase", "vendor")
                .orShould()
                .callMethod(HARNESS + "TckTarget", "vendor");
    }

    @Test
    void ac_qa_02_onlyTheHarnessAndSnapshotDirectoryChoiceKnowTheVendor() {
        JavaClasses tck = new ClassFileImporter().importPackages("com.rey.modelquery.tck");
        // SqlSnapshots picks src/test/resources/sql/<vendor>/, the one difference R-QA-03 allows; the vendor tests
        // expect each database's own profile, the difference R-VND-04 and vendor/41 §2 allow.
        assertThatCode(() -> noVendorOutside("..tck.sql..", "..tck.vnd..").check(tck)).doesNotThrowAnyException();
        // Not vacuous: without that exemption the rule finds SqlSnapshots.
        assertThatCode(() -> noVendorOutside().check(tck))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("com.rey.modelquery.tck.sql.SqlSnapshots");
    }
}
