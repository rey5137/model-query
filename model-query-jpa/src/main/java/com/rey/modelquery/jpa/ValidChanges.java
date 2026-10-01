package com.rey.modelquery.jpa;

import com.rey.modelquery.annotations.Incubating;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates a change set field by field against the Bean Validation constraints of the model {@link #value()} names:
 * a set field is checked against its model field's constraints, an unset one is not, so {@code @NotNull} means "may
 * not be cleared". Each violation is reported on the field's own property. The processor puts it on a generated change
 * set when Bean Validation is on the model module's classpath, so {@code @Valid} on a change set needs no
 * configuration.
 *
 * @implSpec R-WRT-21, R-WRT-22, D-15, D-71
 */
@Incubating
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidChangesValidator.class)
public @interface ValidChanges {

    /** The model whose field constraints apply, the {@code M} of the change set's {@code Changes<M>}. */
    Class<?> value();

    /** Never reported: each violation is reported on its field, with the field constraint's own message. */
    String message() default "invalid change set";

    /** The groups this constraint belongs to, and the groups the model's field constraints are checked in. */
    Class<?>[] groups() default {};

    /** The payload of this constraint. */
    Class<? extends Payload>[] payload() default {};
}
