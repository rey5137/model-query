package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.rey.modelquery.core.Assignment;
import com.rey.modelquery.core.Changes;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.TableField;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ExpressionLanguageFeatureLevel;
import org.junit.jupiter.api.Test;

/** {@code @ValidChanges} on a hand-written change set ({@code api/14} §7). */
class ValidChangesValidatorTest {

    /** The update model whose field constraints a change set is checked against. */
    static final class NotePatch {
        @NotNull
        @Size(max = 5)
        String note;

        @Size(max = 3, message = "${validatedValue} is too long")
        String code;
    }

    /** Never resolved against a persistence unit: the validator reads only the columns' properties. */
    static final class NoteEntity {}

    private static final TableField<NoteEntity, NoteEntity> ROOT = TableField.root(NoteEntity.class);
    private static final ColumnField<NotePatch, NoteEntity, String> NOTE =
            ColumnField.of(NotePatch.class, ROOT, "note", String.class).named("note");
    private static final ColumnField<NotePatch, NoteEntity, String> CODE =
            ColumnField.of(NotePatch.class, ROOT, "codeText", String.class).named("code");
    private static final ColumnField<NotePatch, NoteEntity, String> UNNAMED_NOTE =
            ColumnField.of(NotePatch.class, ROOT, "note", String.class);

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /** A change set written by hand, holding the assignments it is given. */
    @ValidChanges(NotePatch.class)
    static final class NotePatchChanges implements Changes<NotePatch> {
        private final List<Assignment<NotePatch, ?>> assignments = new ArrayList<>();

        NotePatchChanges with(Assignment<NotePatch, ?> assignment) {
            assignments.add(assignment);
            return this;
        }

        @Override
        public boolean isSet(ColumnField<NotePatch, ?, ?> column) {
            return assignments.stream().anyMatch(assignment -> assignment.column().equals(column));
        }

        @Override
        public Changes<NotePatch> unset(ColumnField<NotePatch, ?, ?> column) {
            assignments.removeIf(assignment -> assignment.column().equals(column));
            return this;
        }

        @Override
        public boolean isEmpty() {
            return assignments.isEmpty();
        }

        @Override
        public List<Assignment<NotePatch, ?>> assignments() {
            return List.copyOf(assignments);
        }
    }

    @Test
    void ac_wrt_16_an_unset_field_passes_its_not_null() {
        assertThat(VALIDATOR.validate(new NotePatchChanges())).isEmpty();
        assertThat(VALIDATOR.validate(new NotePatchChanges().with(Assignment.of(CODE, "ab")))).isEmpty();
    }

    @Test
    void ac_wrt_16_a_field_set_to_null_fails_on_its_own_property() {
        Set<ConstraintViolation<NotePatchChanges>> violations =
                VALIDATOR.validate(new NotePatchChanges().with(Assignment.ofNull(NOTE)));

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("note");
            assertThat(violation.getMessage()).isEqualTo(direct("note", null));
        });
    }

    @Test
    void ac_wrt_16_a_set_field_over_its_size_fails_on_its_own_property() {
        Set<ConstraintViolation<NotePatchChanges>> violations = VALIDATOR.validate(new NotePatchChanges()
                .with(Assignment.of(NOTE, "far too long"))
                .with(Assignment.of(CODE, "abcd")));

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .containsExactlyInAnyOrder(
                        tuple("note", direct("note", "far too long")),
                        tuple("code", "abcd is too long"));
    }

    @Test
    void ac_wrt_16_a_value_containing_an_expression_is_not_evaluated() {
        // The validator that reports the change set evaluates any expression a template holds, so only escaping keeps
        // a rejected value that its field constraint's message repeats from being evaluated.
        Validator evaluating = Validation.byProvider(HibernateValidator.class)
                .configure()
                .customViolationExpressionLanguageFeatureLevel(ExpressionLanguageFeatureLevel.BEAN_METHODS)
                .buildValidatorFactory()
                .getValidator();
        String parameter = "{jakarta.validation.constraints.NotNull.message}";

        Set<ConstraintViolation<NotePatchChanges>> expression =
                evaluating.validate(new NotePatchChanges().with(Assignment.of(CODE, "${1+1}")));
        Set<ConstraintViolation<NotePatchChanges>> bundleKey =
                evaluating.validate(new NotePatchChanges().with(Assignment.of(CODE, parameter)));

        assertThat(expression).singleElement()
                .extracting(ConstraintViolation::getMessage)
                .isEqualTo("${1+1} is too long");
        assertThat(bundleKey).singleElement()
                .extracting(ConstraintViolation::getMessage)
                .isEqualTo(parameter + " is too long");
    }

    @Test
    void r_wrt_21_a_column_with_no_property_is_not_checked() {
        assertThat(VALIDATOR.validate(new NotePatchChanges().with(Assignment.ofNull(UNNAMED_NOTE)))).isEmpty();
    }

    /** The message the model's own constraint gives {@code value} for {@code property}, in the default locale. */
    private static String direct(String property, Object value) {
        return VALIDATOR.validateValue(NotePatch.class, property, value).iterator().next().getMessage();
    }
}
