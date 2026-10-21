package com.rey.modelquery.tck.flt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FilterOperator;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OperandBinder;
import com.rey.modelquery.core.OperatorFeature;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.QueryCustomizer;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.ConstantRowEntity;
import com.rey.modelquery.tck.col.ConstantStatus;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.ParameterExpression;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * The engine's operator gate and operand binder seam through the executor, with a test provider
 * ({@link OperatorProviderSupport}) and a test profile answering each check: the vendor's first ({@code MQ4015}),
 * then the provider's ({@code MQ4014}), both before any statement; {@code checkPhases}' stand-in gate; and a value
 * typed by the provider's binder on every statement that renders it (spec api/12 R-FLT-20, R-FLT-21, vendor/40
 * R-VND-23, R-VND-24, D-169; TCK AC-FLT-24, AC-FLT-22's binder seam).
 */
class OperatorGateExecutionTest {

    record R(Long id, String label) {}

    static final TableField<ConstantRowEntity, ConstantRowEntity> ROWS = TableField.root(ConstantRowEntity.class);
    static final ColumnField<R, ConstantRowEntity, Long> ID = ColumnField.of(R.class, ROWS, "id", Long.class);
    static final ColumnField<R, ConstantRowEntity, String> LABEL =
            ColumnField.of(R.class, ROWS, "label", String.class);
    static final ColumnField<R, ConstantRowEntity, ConstantStatus> STATUS =
            ColumnField.of(R.class, ROWS, "status", ConstantStatus.class);
    static final ModelQuery.Builder<ConstantRowEntity, Object, R> ROWS_QUERY =
            ModelQuery.builder(ROWS, row -> new R(row.get(ID), row.get(LABEL)))
                    .select(SelectSet.of(ID, LABEL)).orderBy(ID.asc());

    /** A user operator opting into a catalogue feature's gate (R-FLT-20): its own SQL runs on every vendor. */
    static final FilterOperator<String, String> GATED_EQUALS =
            FilterOperator.<String>of("gatedEquals", (ctx, left, right) -> ctx.cb().equal(left, right))
                    .requires(OperatorFeature.REGEX_MATCH);
    static final FilterOperator<ConstantStatus, ConstantStatus> STATUS_IS =
            FilterOperator.of("statusIs", (ctx, left, right) -> ctx.cb().equal(left, right));

    // ---- AC-FLT-24: the two checks, in order, before any statement

    @TckTest
    void ac_flt_24_a_vendor_answering_false_is_mq4015_even_where_the_provider_serves_the_feature(TckDatabase db) {
        List<String> sql = SqlSnapshots.capture(db, ds -> run(ds, feature -> false, feature -> true, null,
                executor -> assertThatThrownBy(() -> executor.list(
                        ROWS_QUERY.where(f -> f.op(GATED_EQUALS, LABEL, "plain")).build(), Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ4015))
                        .hasMessageContaining("op(gatedEquals)").hasMessageContaining("REGEX_MATCH")
                        .hasMessageContaining("VendorProfile#operators")));
        assertThat(sql).noneMatch(s -> s.contains("constant_rows"));
    }

    @TckTest
    void ac_flt_24_a_vendor_answering_true_and_a_provider_answering_false_is_mq4014(TckDatabase db) {
        List<String> sql = SqlSnapshots.capture(db, ds -> run(ds, feature -> true, feature -> false, null,
                executor -> {
                    ModelQuery<ConstantRowEntity, Object, R> query =
                            ROWS_QUERY.where(f -> f.op(GATED_EQUALS, LABEL, "plain")).build();
                    assertThatThrownBy(() -> executor.list(query, Limit.unlimited()))
                            .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014))
                            .hasMessageContaining("op(gatedEquals)").hasMessageContaining("REGEX_MATCH")
                            .hasMessageContaining("ProviderSupport#operators");
                    assertThatThrownBy(() -> executor.count(query))
                            .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014));
                }));
        assertThat(sql).noneMatch(s -> s.contains("constant_rows"));
    }

    @TckTest
    void ac_flt_24_both_checks_passing_runs_the_operator_on_its_content_and_count_statements(TckDatabase db) {
        List<Object> results = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> run(ds, feature -> true, feature -> true, null, executor -> {
            ModelQuery<ConstantRowEntity, Object, R> query =
                    ROWS_QUERY.where(f -> f.op(GATED_EQUALS, LABEL, "plain")).build();
            results.add(executor.list(query, Limit.unlimited()));
            results.add(executor.count(query));
        }));
        assertThat(results).containsExactly(List.of(new R(1L, "plain")), 1L);
    }

    @TckTest
    void ac_flt_24_a_query_with_an_op_and_a_customizer_passes_check_phases(TckDatabase db) {
        QueryCustomizer none = (spec, joins, query, cb, phase) -> { };
        ModelQuery<ConstantRowEntity, Object, R> query =
                ROWS_QUERY.where(f -> f.op(GATED_EQUALS, LABEL, "plain")).customize(none).build();
        List<List<R>> results = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    // The scratch build's stand-in gate serves the feature, where the real build has no gate (D-169).
                    query.checkPhases(cb);
                    query.checkPhases(cb, RenderOptions.portable());
                    assertThatThrownBy(() -> query.buildQuery(cb, Phase.MODEL, RenderOptions.portable()))
                            .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014));
                });
            }
            // Through the executor, which checks the phases on first execution, the query runs.
            run(ds, feature -> true, feature -> true, null,
                    executor -> results.add(executor.list(query, Limit.unlimited())));
        });
        assertThat(results).containsExactly(List.of(new R(1L, "plain")));
    }

    // ---- the binder seam (R-VND-24): an enum value typed by the provider's binder

    @TckTest
    void ac_flt_22_with_the_providers_binder_an_enum_value_binds_through_it_on_every_statement(TckDatabase db) {
        RecordingBinder binder = new RecordingBinder();
        List<Object> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> run(ds, null, feature -> false, binder, executor -> {
            ModelQuery<ConstantRowEntity, Object, R> query =
                    ROWS_QUERY.where(f -> f.op(STATUS_IS, STATUS, ConstantStatus.PENDING)).build();
            results.add(executor.list(query, Limit.unlimited()));
            results.add(executor.count(query));
        }));
        // With no binder this value is MQ1502 (UserOperatorTest); with one, rows 1 and 5 are PENDING.
        assertThat(results).containsExactly(List.of(new R(1L, "plain"), new R(5L, "padded")), 2L);
        assertThat(binder.types).isNotEmpty().containsOnly(ConstantStatus.class);
        assertThat(sql).filteredOn(s -> s.contains("from constant_rows"))
                .isNotEmpty().allSatisfy(s -> assertThat(s).contains("status=?").doesNotContain("PENDING"));
    }

    // ---- helpers

    /** A binder typing the parameter by its class alone, enough where the renderer compares with {@code =}. */
    static final class RecordingBinder implements OperandBinder {
        final List<Class<?>> types = new ArrayList<>();

        @Override
        public <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy, Class<T> type) {
            types.add(type);
            return cb.parameter(type);
        }
    }

    /**
     * Runs {@code work} on an executor whose provider answers {@code provider} and supplies {@code binder}, and whose
     * profile is the built-in one answering {@code vendor}, or the built-in one itself when {@code vendor} is null.
     */
    private static void run(DataSource ds, Predicate<OperatorFeature> vendor, Predicate<OperatorFeature> provider,
            OperandBinder binder, Consumer<ModelQueryExecutor<ConstantRowEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            // Resolved inside serving: the factory's resolution is cached, and must name the test provider.
            OperatorProviderSupport.serving(provider, binder, () -> {
                ModelQueryConfig config = vendor == null ? ModelQueryConfig.defaults()
                        : ModelQueryConfig.defaults().vendorProfiles(List.of(OperatorProviderSupport.answering(sf, vendor)));
                sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, ConstantRowEntity.class, config)));
            });
        }
    }
}
