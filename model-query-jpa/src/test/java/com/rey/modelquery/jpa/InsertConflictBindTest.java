package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.RenderOptions;
import jakarta.persistence.Query;
import jakarta.persistence.criteria.ParameterExpression;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A conflict clause binds its update's {@code where} parameters on one statement only (R-FLT-08). */
class InsertConflictBindTest {

    @Test
    void ac_flt_35_a_statement_is_bound_the_where_values_once_and_the_next_statement_none() throws Exception {
        var clause = new InsertConflict<Object>(null, RenderOptions.portable(), Map.of());
        ParameterExpression<?> parameter = proxy(ParameterExpression.class, new ArrayList<>());
        parameters(clause).put(parameter, "NEW");

        List<Object[]> first = new ArrayList<>();
        clause.bind(proxy(Query.class, first));
        assertThat(first).singleElement().satisfies(args -> assertThat(args).containsExactly(parameter, "NEW"));

        // A later statement whose where was never rendered is not bound the earlier statement's values.
        List<Object[]> second = new ArrayList<>();
        clause.bind(proxy(Query.class, second));
        assertThat(second).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<ParameterExpression<?>, Object> parameters(InsertConflict<?> clause) throws Exception {
        Field field = InsertConflict.class.getDeclaredField("parameters");
        field.setAccessible(true);
        return (Map<ParameterExpression<?>, Object>) field.get(clause);
    }

    /** A proxy of {@code type} recording each {@code setParameter} call's arguments in {@code calls}. */
    private static <T> T proxy(Class<T> type, List<Object[]> calls) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> switch (method.getName()) {
                    case "setParameter" -> {
                        calls.add(args);
                        yield self;
                    }
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> type.getSimpleName();
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }
}
