package com.rey.modelquery.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.rey.modelquery.processor.ProcessorHarness.classes;
import static com.rey.modelquery.processor.ProcessorHarness.compile;
import static com.rey.modelquery.processor.ProcessorHarness.compileWithout;
import static com.rey.modelquery.processor.ProcessorHarness.generated;
import static com.rey.modelquery.processor.ProcessorHarness.generatedFlat;
import static com.rey.modelquery.processor.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.google.testing.compile.Compilation;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.jpa.ValidChanges;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.lang.reflect.Method;
import java.util.Set;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** {@code @ValidChanges} on generated change sets ({@code processor/31} R-GEN-23, {@code api/14} §7). */
class ValidChangesTest {

    /** The test classpath entry that holds {@code jakarta.validation.Constraint}. */
    private static final String VALIDATION_API = "jakarta.validation-api";
    /** The test classpath entry that holds {@code @ValidChanges}: the reactor's classes or the installed jar. */
    private static final String JPA = "model-query-jpa";

    /** An update model whose fields carry Bean Validation constraints. */
    private static final JavaFileObject NOTE_PATCH = source("patch.NotePatch", """
            package patch;

            import com.rey.modelquery.annotations.Column;
            import com.rey.modelquery.annotations.PrimaryKey;
            import com.rey.modelquery.annotations.UpdateModel;
            import jakarta.validation.constraints.NotNull;
            import jakarta.validation.constraints.Size;

            @UpdateModel(root = OrderEntity.class)
            public record NotePatch(
                    @PrimaryKey Long id,
                    @NotNull @Size(max = 5) String note,
                    @NotNull @Column(attribute = "customer") Long customerId) {}
            """);

    private static final JavaFileObject[] NOTE_PATCH_SOURCES = {
        UpdateModelSources.ADDRESS, UpdateModelSources.CUSTOMER_ENTITY, UpdateModelSources.ORDER_ENTITY, NOTE_PATCH
    };

    @Test
    void ac_gen_12_a_change_set_carries_valid_changes_naming_its_model_when_both_resolve() {
        Compilation compilation = compile(UpdateModelSources.ORDER_EDIT_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generatedFlat(compilation, "patch.OrderEditChanges"))
                .contains("import com.rey.modelquery.jpa.ValidChanges;")
                .contains("@ValidChanges(OrderEdit.class) public final class OrderEditChanges");
    }

    @Test
    void ac_gen_12_a_change_set_never_carries_its_models_field_constraints() {
        Compilation compilation = compile(NOTE_PATCH_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "patch.NotePatchChanges"))
                .contains("@ValidChanges(NotePatch.class)")
                .doesNotContain("jakarta.validation")
                .doesNotContain("NotNull")
                .doesNotContain("Size");
    }

    @Test
    void ac_gen_12_without_model_query_jpa_a_change_set_carries_no_valid_changes() {
        Compilation compilation = compileWithout(JPA, UpdateModelSources.ORDER_PATCH_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "patch.OrderPatchChanges")).doesNotContain("ValidChanges");
    }

    @Test
    void ac_wrt_17_without_jakarta_validation_change_sets_compile_with_no_valid_changes() throws Exception {
        Compilation compilation = compileWithout(VALIDATION_API, UpdateModelSources.ORDER_PATCH_SOURCES);

        assertThat(compilation).succeededWithoutWarnings();
        assertThat(generated(compilation, "patch.OrderPatchChanges")).doesNotContain("ValidChanges");
        Class<?> changes = classes(compilation).loadClass("patch.OrderPatchChanges");
        assertThat(changes.getAnnotations()).isEmpty();
    }

    @Test
    void ac_wrt_16_a_generated_change_set_checks_set_fields_only_on_their_own_property() throws Exception {
        ClassLoader loader = classes(compile(NOTE_PATCH_SOURCES));
        Class<?> model = loader.loadClass("patch.NotePatch");
        Class<?> changesClass = loader.loadClass("patch.NotePatchChanges");
        ColumnField<?, ?, ?> customerId = (ColumnField<?, ?, ?>) loader.loadClass("patch.QNotePatch")
                .getField("CUSTOMER_ID")
                .get(null);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        Object unset = changesClass.getConstructor().newInstance();
        Object cleared = set(set(changesClass.getConstructor().newInstance(), "note", null), "customerId", null);
        Object tooLong = set(changesClass.getConstructor().newInstance(), "note", "far too long");

        assertThat(changesClass.getAnnotation(ValidChanges.class).value()).isEqualTo(model);
        // The column reads the entity's "customer"; its constraints are the model field "customerId"'s.
        assertThat(customerId.name()).isEqualTo("customer");
        assertThat(customerId.property()).contains("customerId");
        assertThat(validator.validate(unset)).isEmpty();
        assertThat(validator.validate(cleared))
                .extracting(violation -> violation.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .containsExactlyInAnyOrder(
                        tuple("note", direct(validator, model, "note", null)),
                        tuple("customerId", direct(validator, model, "customerId", null)));
        Set<ConstraintViolation<Object>> size = validator.validate(tooLong);
        assertThat(size).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("note");
            assertThat(violation.getMessage()).isEqualTo(direct(validator, model, "note", "far too long"));
        });
    }

    /** Calls the change set's fluent setter {@code field} with {@code value}; returns the change set. */
    private static Object set(Object changes, String field, Object value) throws Exception {
        for (Method method : changes.getClass().getMethods()) {
            if (method.getName().equals(field) && method.getParameterCount() == 1) {
                return method.invoke(changes, value);
            }
        }
        throw new AssertionError("no setter " + field);
    }

    /** The message the model's own constraint on {@code property} gives {@code value}. */
    private static String direct(Validator validator, Class<?> model, String property, Object value) {
        return validator.validateValue(model, property, value).iterator().next().getMessage();
    }
}
