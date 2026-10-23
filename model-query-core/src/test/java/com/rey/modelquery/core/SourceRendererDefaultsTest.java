package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.Tuple;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A {@link SourceRenderer} that overrides nothing refuses every method with {@code MQ4014} (R-SRC-12, D-151). */
class SourceRendererDefaultsTest {

    private static final SourceRenderer NOTHING_OVERRIDDEN = new SourceRenderer() {};

    @Test
    void ac_src_16_window_combine_and_recursive_default_to_mq4014() {
        assertMq4014(() -> NOTHING_OVERRIDDEN.window(null, null), "window functions");
        assertMq4014(() -> NOTHING_OVERRIDDEN.combine(null, SetOperation.UNION, null, null), "set operations");
        assertMq4014(() -> NOTHING_OVERRIDDEN.cte(null, null), "renders no CTE sources");
        assertMq4014(() -> NOTHING_OVERRIDDEN.recursive(null, null), "not recursive CTEs");
        assertMq4014(() -> NOTHING_OVERRIDDEN.register(null, null), "chained CTEs");
        assertMq4014(() -> NOTHING_OVERRIDDEN.registerRecursive(null, null), "chained CTEs");
        assertMq4014(() -> NOTHING_OVERRIDDEN.from(null, null), "chained CTEs");
    }

    @Test
    void ac_src_43_join_defaults_to_mq4014() {
        assertMq4014(() -> NOTHING_OVERRIDDEN.join(null, null, null, null), "not joined sources");
    }

    @Test
    void ac_src_16_cte_defaults_over_register_then_from() {
        List<String> calls = new ArrayList<>();
        SourceRenderer.CteHandle handle = new SourceRenderer.CteHandle() {};
        SourceRenderer renderer = new SourceRenderer() {
            @Override
            public CteHandle register(CriteriaQuery<?> top, CriteriaQuery<Tuple> body) {
                calls.add("register");
                return handle;
            }

            @Override
            public Root<Tuple> from(AbstractQuery<?> query, CteHandle inner) {
                calls.add(inner == handle ? "from(handle)" : "from(other)");
                return null;
            }
        };
        assertThat(renderer.cte(null, null)).isNull();
        assertThat(calls).containsExactly("register", "from(handle)");
    }

    @Test
    void ac_src_16_recursive_defaults_over_register_recursive_then_from() {
        List<String> calls = new ArrayList<>();
        SourceRenderer.CteHandle handle = new SourceRenderer.CteHandle() {};
        Root<Tuple> root = root();
        Expression<?> depth = expression();
        Expression<?> cycle = expression();
        SourceRenderer renderer = new SourceRenderer() {
            @Override
            public RecursiveHandle registerRecursive(CriteriaQuery<?> top, RecursiveSpec spec) {
                calls.add("registerRecursive");
                return new RecursiveHandle(handle, depth, cycle, null);
            }

            @Override
            public Root<Tuple> from(AbstractQuery<?> query, CteHandle inner) {
                calls.add(inner == handle ? "from(handle)" : "from(other)");
                return root;
            }
        };
        RecursiveCte cte = renderer.recursive(null, null);
        assertThat(calls).containsExactly("registerRecursive", "from(handle)");
        assertThat(cte.depthKey()).isSameAs(depth);
        assertThat(cte.cycleKey()).isSameAs(cycle);
        assertThat(cte.duplicateKey()).isNull();
    }

    @Test
    void ac_src_16_recursive_cte_requires_root_and_both_keys_but_not_the_duplicate_key() {
        Root<Tuple> root = root();
        Expression<?> key = expression();
        assertThatNullPointerException().isThrownBy(() -> new RecursiveCte(null, key, key, key));
        assertThatNullPointerException().isThrownBy(() -> new RecursiveCte(root, null, key, key));
        assertThatNullPointerException().isThrownBy(() -> new RecursiveCte(root, key, null, key));
        assertThat(new RecursiveCte(root, key, key, null).duplicateKey()).isNull();
    }

    @Test
    void ac_src_16_recursive_handle_requires_handle_and_both_keys_but_not_the_duplicate_key() {
        SourceRenderer.CteHandle handle = new SourceRenderer.CteHandle() {};
        Expression<?> key = expression();
        assertThatNullPointerException()
                .isThrownBy(() -> new SourceRenderer.RecursiveHandle(null, key, key, key));
        assertThatNullPointerException()
                .isThrownBy(() -> new SourceRenderer.RecursiveHandle(handle, null, key, key));
        assertThatNullPointerException()
                .isThrownBy(() -> new SourceRenderer.RecursiveHandle(handle, key, null, key));
        assertThat(new SourceRenderer.RecursiveHandle(handle, key, key, null).duplicateKey()).isNull();
    }

    @SuppressWarnings("unchecked")
    private static Root<Tuple> root() {
        return (Root<Tuple>) java.lang.reflect.Proxy.newProxyInstance(
                Root.class.getClassLoader(), new Class<?>[] {Root.class}, (p, m, a) -> null);
    }

    private static Expression<?> expression() {
        return (Expression<?>) java.lang.reflect.Proxy.newProxyInstance(
                Expression.class.getClassLoader(), new Class<?>[] {Expression.class}, (p, m, a) -> null);
    }

    private static void assertMq4014(Runnable call, String feature) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ModelQueryConfigurationException.class)
                .hasMessageContaining(feature);
        assertThat(((ModelQueryConfigurationException) thrown).code()).isEqualTo(MqCode.MQ4014);
    }
}
