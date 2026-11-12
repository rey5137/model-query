package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** {@link ForwardingModelQueryExecutor} (R-SPR-18, D-225, delivery/61 R-REL-10). */
class ForwardingModelQueryExecutorTest {

    @Test
    void ac_spr_22_the_forwarding_base_overrides_every_abstract_method_of_the_executor() {
        var missing = Arrays.stream(ModelQueryExecutor.class.getMethods())
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .filter(m -> !declaredBy(m))
                .map(Method::toGenericString)
                .toList();
        assertThat(missing).as("methods added to ModelQueryExecutor without a forwarder").isEmpty();
    }

    private static boolean declaredBy(Method m) {
        try {
            return !Modifier.isAbstract(ForwardingModelQueryExecutor.class
                    .getMethod(m.getName(), m.getParameterTypes()).getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
