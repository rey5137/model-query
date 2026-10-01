package com.rey.modelquery.jpa;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.Assignment;
import com.rey.modelquery.core.Changes;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Optional;
import java.util.Set;

/**
 * The validator of {@link ValidChanges}: it calls {@code Validator.validateValue(model, property, value)} for each set
 * column with a {@linkplain com.rey.modelquery.core.ColumnField#property() property}, NULL included, and re-reports
 * each violation on that property with its message escaped, so a rejected value in it is never evaluated. A column
 * with no property, which only a hand-written change set has, and an expression assignment are not checked. The
 * field constraints are checked by a validator of the default {@code ValidatorFactory}, built once on first use.
 *
 * @implSpec R-WRT-21, D-71
 */
@Incubating
public final class ValidChangesValidator implements ConstraintValidator<ValidChanges, Changes<?>> {

    private Class<?> model;
    private Class<?>[] groups;

    /** Called by the Bean Validation provider with the annotation it validates. */
    @Override
    public void initialize(ValidChanges annotation) {
        this.model = annotation.value();
        this.groups = annotation.groups();
    }

    /** Whether every set column's value satisfies its model field's constraints; a {@code null} change set does. */
    @Override
    public boolean isValid(Changes<?> changes, ConstraintValidatorContext context) {
        if (changes == null) {
            return true;
        }
        boolean valid = true;
        for (Assignment<?, ?> assignment : changes.assignments()) {
            Optional<String> property = assignment.column().property();
            if (property.isEmpty() || assignment instanceof Assignment.Expression<?, ?>) {
                continue;
            }
            Object value = assignment instanceof Assignment.Value<?, ?> set ? set.value() : null;
            Set<? extends ConstraintViolation<?>> violations =
                    DefaultValidator.INSTANCE.validateValue(model, property.get(), value, groups);
            for (ConstraintViolation<?> violation : violations) {
                if (valid) {
                    context.disableDefaultConstraintViolation();
                    valid = false;
                }
                context.buildConstraintViolationWithTemplate(escaped(violation.getMessage()))
                        .addPropertyNode(property.get())
                        .addConstraintViolation();
            }
        }
        return valid;
    }

    /** {@code message} as a template that interpolates to itself: {@code \}, braces and {@code $} escaped. */
    static String escaped(String message) {
        var template = new StringBuilder(message.length());
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '\\' || c == '{' || c == '}' || c == '$') {
                template.append('\\');
            }
            template.append(c);
        }
        return template.toString();
    }

    /** Built on first use, so a change set never validated needs no provider. */
    private static final class DefaultValidator {

        static final Validator INSTANCE = Validation.buildDefaultValidatorFactory().getValidator();
    }
}
