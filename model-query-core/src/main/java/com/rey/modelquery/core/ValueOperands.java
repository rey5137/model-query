package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.ParameterExpression;
import java.util.Optional;

/**
 * The value rule every path that binds an input value as an explicit parameter shares: a filter operator's value
 * always, and under inline value handling every filter, key, cursor and assignment value (R-FLT-08, R-FLT-21).
 *
 * @implSpec api/12 R-FLT-08, R-FLT-21, vendor/40 R-VND-24, R-VND-28, D-168, D-170, D-177
 */
@EngineFacing
@Incubating
public final class ValueOperands {

    private ValueOperands() {
    }

    /**
     * {@code value} as a statement parameter recorded in {@code sink}: the provider binder's parameter typed by
     * {@code typedBy}'s mapping where the binder types by it, else a parameter typed by {@code type}, which only a
     * mapping-independent value takes; anything else is {@code MQ1502}, raised before any statement, naming
     * {@code label}, the path or operator and its column. A {@code null} {@code typedBy} skips the binder, for a
     * value whose type is not the column's (R-FLT-21, R-VND-24, D-170).
     */
    public static <T> Expression<T> valueOperand(RenderOptions options, CriteriaBuilder cb, Expression<?> typedBy,
            Class<T> type, Object value, String label, ParameterSink sink) {
        Optional<OperandBinder> binder = typedBy == null ? Optional.empty() : options.operandBinder();
        if (binder.isEmpty() || !binder.get().typesBy(typedBy)) {
            requireMappingIndependent(label, value, binder.isPresent());
            ParameterExpression<T> parameter = cb.parameter(type);
            sink.add(parameter, value);
            return parameter;
        }
        return typedParameter(binder.get(), label, cb, typedBy, type, value, sink);
    }

    /**
     * {@code binder}'s parameter of {@code type} typed by {@code typedBy}'s mapping, recorded in {@code sink} to bind
     * {@code value}; {@code MQ1502} naming {@code label}, the column and its operation, when the binder throws, returns
     * no parameter or one this statement already binds (R-VND-24).
     */
    static <T> ParameterExpression<T> typedParameter(OperandBinder binder, String label, CriteriaBuilder cb,
            Expression<?> typedBy, Class<T> type, Object value, ParameterSink sink) {
        ParameterExpression<T> parameter;
        try {
            parameter = binder.parameter(cb, typedBy, type);
        } catch (ModelQueryException e) {
            throw e; // an engine error keeps its own code
        } catch (RuntimeException e) {
            throw new ModelQueryDefinitionException(MqCode.MQ1502, label
                    + " cannot bind its value: the provider's operand binder threw " + e, e);
        }
        if (parameter == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1502, label
                    + " cannot bind its value: the provider's operand binder returned no parameter");
        }
        if (sink.binds(parameter)) {
            // A parameter binds one value per statement: reused, the later value would replace the earlier (INV-5).
            throw new ModelQueryDefinitionException(MqCode.MQ1502, label
                    + " cannot bind its value: the provider's operand binder returned a parameter this statement "
                    + "already binds; it must make a new parameter on every call");
        }
        sink.add(parameter, value);
        return parameter;
    }

    /**
     * {@code MQ1502} for a value whose binding depends on the column's mapping, which a parameter typed by a Java
     * class gets wrong: a {@code STRING} enum binds by ordinal, a {@code @Convert} value not at all, a
     * {@code Boolean} mapped to {@code 'Y'/'N'} or a {@code UUID} stored as {@code char(36)} matches nothing (R-FLT-21,
     * D-168). {@code declined}: the provider has a binder, but it can't type a value by this column's expression.
     */
    private static void requireMappingIndependent(String label, Object value, boolean declined) {
        // An array's package is its element's, so a LocalDate[] would pass the package check alone.
        if (!(value instanceof String
                || value instanceof Number && plainNumber(value.getClass())
                || !value.getClass().isArray() && value.getClass().getPackageName().equals("java.time"))) {
            throw new ModelQueryDefinitionException(MqCode.MQ1502, label
                    + " cannot bind a value of type " + value.getClass().getSimpleName()
                    + (declined ? ": the provider's operand binder can't type it by this column's expression"
                            : " without the provider's operand binder")
                    + "; only a String, a java.lang or java.math number or a java.time value binds by its class");
        }
    }

    /** A {@code java.lang} or {@code java.math} number, which a parameter typed by its class binds as is (D-168). */
    private static boolean plainNumber(Class<?> type) {
        String pkg = type.getPackageName();
        return pkg.equals("java.lang") || pkg.equals("java.math");
    }
}
