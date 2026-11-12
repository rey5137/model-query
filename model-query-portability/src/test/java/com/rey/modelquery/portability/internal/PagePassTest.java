package com.rey.modelquery.portability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.vendor.InlineCountFallback;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PagePassTest {

    private static final TableField<Object, Object> ROOT = TableField.root(Object.class);
    private static final ColumnField<Long, Object, Long> ID = ColumnField.of(Long.class, ROOT, "id", Long.class);
    private static final ModelQuery<Object, Long, Long> QUERY = ModelQuery.builder(ROOT, row -> row.get(ID))
            .select(SelectSet.of(ID)).primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc()).build();
    private static final ModelQuery<Object, Long, Long> KEYS = ModelQuery.builder(ROOT, row -> row.get(ID))
            .select(SelectSet.of(ID)).primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc())
            .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();

    @Test
    void ac_ins_13_rewrites_only_the_selected_page_and_preserves_the_delegates_throwable() {
        RuntimeException stop = new RuntimeException("stop");
        List<CountMode> modes = new ArrayList<>();
        try (JdbcRecording recording = JdbcRecording.call()) {
            Object delegate = Proxy.newProxyInstance(ModelQueryExecutor.class.getClassLoader(),
                    new Class<?>[] {ModelQueryExecutor.class}, (proxy, method, args) -> {
                        modes.add((CountMode) args[2]);
                        throw stop;
                    });
            PagePass pass = new PagePass(delegate, recording, 2);
            ModelQueryExecutor<Object> executor = pass.executor();
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> executor.page(QUERY, PageSpec.of(0, 2), CountMode.COUNT)).isSameAs(stop);
            }
            assertThat(modes).containsExactly(CountMode.COUNT, CountMode.NO_COUNT, CountMode.COUNT);
            assertThat(pass.countedPage()).isZero();
        }
    }

    @Test
    void ac_ins_13_proxy_object_methods_use_identity_without_recording_or_delegating() {
        try (JdbcRecording recording = JdbcRecording.call()) {
            Object delegate = Proxy.newProxyInstance(ModelQueryExecutor.class.getClassLoader(),
                    new Class<?>[] {ModelQueryExecutor.class}, (proxy, method, args) -> {
                        throw new AssertionError("Object method delegated: " + method.getName());
                    });
            PagePass pass = new PagePass(delegate, recording, 0);
            ModelQueryExecutor<Object> executor = pass.executor();
            ModelQueryExecutor<Object> other = pass.executor();
            assertThat(executor.equals(executor)).isTrue();
            assertThat(executor.equals(other)).isFalse();
            assertThat(other.equals(executor)).isFalse();
            assertThat(executor.equals(delegate)).isFalse();
            assertThat(executor.equals(null)).isFalse();
            assertThat(executor.hashCode()).isEqualTo(System.identityHashCode(executor));
            assertThat(executor.toString()).isEqualTo(executor.getClass().getName() + "@"
                    + Integer.toHexString(System.identityHashCode(executor)));
            assertThat(recording.statements()).isEmpty();
            assertThat(pass.countedPage()).isZero();
        }
    }

    public interface WithDefault extends ModelQueryExecutor<Object> {
        default Object defaultPage() {
            return page(QUERY, PageSpec.of(1, 2), CountMode.COUNT);
        }
    }

    @Test
    void ac_ins_13_default_methods_route_their_inner_page_through_the_proxy() {
        try (JdbcRecording recording = JdbcRecording.call()) {
            Object delegate = Proxy.newProxyInstance(ModelQueryExecutor.class.getClassLoader(),
                    new Class<?>[] {WithDefault.class}, (proxy, method, args) -> {
                        assertThat(method.getName()).isEqualTo("page");
                        recording.statement("prepare", "select count(*) from rows");
                        return null;
                    });
            PagePass pass = new PagePass(delegate, recording, 0);
            WithDefault executor = (WithDefault) Proxy.newProxyInstance(WithDefault.class.getClassLoader(),
                    new Class<?>[] {WithDefault.class}, pass);
            executor.defaultPage();
            assertThat(pass.countedPage()).isEqualTo(1);
            assertThat(pass.statement()).isEqualTo(CallResult.Statement.COUNT);
        }
    }

    @Test
    void ac_ins_13_inline_fallbacks_keys_and_earlier_statements_are_distinguished() {
        for (boolean fallback : new boolean[] {false, true}) {
            try (JdbcRecording recording = JdbcRecording.call()) {
                Object delegate = Proxy.newProxyInstance(ModelQueryExecutor.class.getClassLoader(),
                        new Class<?>[] {ModelQueryExecutor.class}, (proxy, method, args) -> {
                            InlineCountFallback.decided(fallback);
                            recording.statement("prepare", fallback ? "select distinct count(id) from rows"
                                    : "select id,count(*) over() from rows");
                            return null;
                        });
                PagePass pass = new PagePass(delegate, recording, 0);
                pass.executor().page(KEYS, PageSpec.of(1, 2), CountMode.INLINE_COUNT);
                assertThat(pass.statement()).isEqualTo(fallback ? CallResult.Statement.COUNT
                        : CallResult.Statement.CONTENT_AND_COUNT);
                assertThat(pass.content()).isEqualTo(CallResult.Statement.KEYS);
                assertThat(pass.countedPage()).isEqualTo(fallback ? 1 : 0);
            }
        }
        try (JdbcRecording recording = JdbcRecording.call()) {
            recording.statement("earlier", "select id from earlier");
            Object delegate = Proxy.newProxyInstance(ModelQueryExecutor.class.getClassLoader(),
                    new Class<?>[] {ModelQueryExecutor.class}, (proxy, method, args) -> {
                        recording.statement("prepare", "select count(*) from rows");
                        return null;
                    });
            PagePass pass = new PagePass(delegate, recording, 0);
            pass.executor().page(QUERY, PageSpec.of(0, 2), CountMode.COUNT);
            assertThat(pass.statement()).isEqualTo(CallResult.Statement.NONE);
            assertThat(pass.countedPage()).isZero();
        }
    }

    @Test
    void ac_ins_13_a_cte_count_is_recognized_by_the_page_mode_and_inline_fallback() {
        for (CountMode mode : new CountMode[] {CountMode.COUNT, CountMode.INLINE_COUNT}) {
            try (JdbcRecording recording = JdbcRecording.call()) {
                Object delegate = Proxy.newProxyInstance(ModelQueryExecutor.class.getClassLoader(),
                        new Class<?>[] {ModelQueryExecutor.class}, (proxy, method, args) -> {
                            if (mode == CountMode.INLINE_COUNT) {
                                InlineCountFallback.decided(true);
                            }
                            recording.statement("prepare", "with x as (select id from rows) select count(*) from x");
                            return null;
                        });
                PagePass pass = new PagePass(delegate, recording, 0);
                pass.executor().page(QUERY, PageSpec.of(0, 2), mode);
                assertThat(pass.statement()).isEqualTo(CallResult.Statement.COUNT);
                assertThat(pass.countedPage()).isEqualTo(1);
                assertThat(pass.content()).isEqualTo(CallResult.Statement.CONTENT);
            }
        }
    }

}
