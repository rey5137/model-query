package com.rey.modelquery.tck.flt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.FilterOperator;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OperandBinder;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/**
 * Hibernate's operand binder through the executor on every Tier-1 vendor: an operator's value typed by its column's
 * mapping for {@code STRING} and {@code ORDINAL} enums, a {@code @Convert} attribute, an array and a converted
 * column, under the default value handling and {@code hibernate.criteria.value_handling_mode=inline}, an aggregate of
 * a path typed by its column, a function of a column declined to the no-binder rule, and a node type the binder
 * doesn't expect refused (spec api/12 R-FLT-21, vendor/40 R-VND-24, D-170; TCK AC-FLT-22). The table is
 * Hibernate's own DDL, created and dropped per factory, so MySQL's {@code STRING} enum column is its native
 * {@code enum(...)} and an array column is the line's own mapping.
 */
public class HibernateOperandBinderTest {

    enum Phase {
        ACTIVE,
        INACTIVE,
        PENDING
    }

    /** A value stored through a JPA converter as {@code SKU:<code>}, so a value bound by class would match nothing. */
    record Sku(String code) {}

    static final class SkuConverter implements AttributeConverter<Sku, String> {
        @Override
        public String convertToDatabaseColumn(Sku sku) {
            return sku == null ? null : "SKU:" + sku.code();
        }

        @Override
        public Sku convertToEntityAttribute(String column) {
            return column == null ? null : new Sku(column.substring(4));
        }
    }

    /** A model {@code String} code over the {@code Sku} attribute (R-COL-14), on top of the JPA converter. */
    static final class SkuCode implements ColumnConverter<String, Sku> {
        @Override
        public String toModel(Sku attribute) {
            return attribute.code();
        }

        @Override
        public Sku toAttribute(String model) {
            return new Sku(model);
        }
    }

    @Entity
    @Table(name = "operand_rows")
    static class OperandRow {
        @Id
        Long id;

        /** Hibernate's default {@code STRING} DDL: MySQL's native {@code enum(...)}. */
        @Enumerated(EnumType.STRING)
        @Column(name = "phase")
        Phase phase;

        @Enumerated(EnumType.STRING)
        @Column(name = "phase_text", columnDefinition = "varchar(20)")
        Phase phaseText;

        @Enumerated(EnumType.ORDINAL)
        @Column(name = "phase_no")
        Phase phaseNo;

        @Convert(converter = SkuConverter.class)
        @Column(name = "sku")
        Sku sku;

        @Column(name = "tags")
        String[] tags;

        @Column(name = "label")
        String label;

        OperandRow() {}

        OperandRow(long id, Phase phase, String label, String sku, String... tags) {
            this.id = id;
            this.label = label;
            this.phase = phase;
            this.phaseText = phase;
            this.phaseNo = phase;
            this.sku = new Sku(sku);
            this.tags = tags;
        }
    }

    record R(Long id) {}

    static final TableField<OperandRow, OperandRow> ROWS = TableField.root(OperandRow.class);
    static final ColumnField<R, OperandRow, Long> ID = ColumnField.of(R.class, ROWS, "id", Long.class);
    static final ColumnField<R, OperandRow, Phase> PHASE = ColumnField.of(R.class, ROWS, "phase", Phase.class);
    static final ColumnField<R, OperandRow, Phase> PHASE_TEXT =
            ColumnField.of(R.class, ROWS, "phaseText", Phase.class);
    static final ColumnField<R, OperandRow, Phase> PHASE_NO = ColumnField.of(R.class, ROWS, "phaseNo", Phase.class);
    static final ColumnField<R, OperandRow, Sku> SKU = ColumnField.of(R.class, ROWS, "sku", Sku.class);
    static final ColumnField<R, OperandRow, String> SKU_CODE =
            ColumnField.of(R.class, ROWS, "sku", String.class, Sku.class, new SkuCode());
    static final ColumnField<R, OperandRow, String[]> TAGS = ColumnField.of(R.class, ROWS, "tags", String[].class);
    static final ColumnField<R, OperandRow, String> LABEL = ColumnField.of(R.class, ROWS, "label", String.class);
    /** A function of a column: Hibernate types it by its Java class alone, so the binder declines it (D-170). */
    static final ExpressionField<R, String> LABEL_UPPER = Expr.function("upper", String.class, LABEL);
    static final ExpressionField<R, Phase> PHASE_TEXT_OR_PHASE = Expr.coalesce(PHASE_TEXT, PHASE);
    static final ModelQuery.Builder<OperandRow, Object, R> QUERY =
            ModelQuery.builder(ROWS, row -> new R(row.get(ID))).select(SelectSet.of(ID)).orderBy(ID.asc());

    record P(Phase phase, Phase latest) {}

    static final ColumnField<P, OperandRow, Phase> P_PHASE = ColumnField.of(P.class, ROWS, "phaseNo", Phase.class);
    static final AggregateField<P, Phase> P_LATEST =
            Agg.max(ColumnField.of(P.class, ROWS, "phaseText", Phase.class));

    static final FilterOperator<Phase, Phase> PHASE_IS =
            FilterOperator.of("phaseIs", (ctx, left, right) -> ctx.cb().equal(left, right));
    static final FilterOperator<Sku, Sku> SKU_IS =
            FilterOperator.of("skuIs", (ctx, left, right) -> ctx.cb().equal(left, right));
    static final FilterOperator<String, String> CODE_IS =
            FilterOperator.of("codeIs", (ctx, left, right) -> ctx.cb().equal(left, right));
    static final FilterOperator<String, String> TEXT_IS =
            FilterOperator.of("textIs", (ctx, left, right) -> ctx.cb().equal(left, right));
    static final FilterOperator<String[], String[]> TAGS_ARE =
            FilterOperator.of("tagsAre", (ctx, left, right) -> ctx.cb().equal(left, right));

    @TckTest
    void ac_flt_22_with_hibernates_binder_enums_a_convert_attribute_an_array_and_a_converted_column_bind_by_the_column(
            TckDatabase db) {
        for (String handling : List.of("bind", "inline")) {
            List<List<R>> results = new ArrayList<>();
            List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, handling, executor -> {
                results.add(ids(executor, f -> f.op(PHASE_IS, PHASE, Phase.ACTIVE)));
                results.add(ids(executor, f -> f.op(PHASE_IS, PHASE_TEXT, Phase.ACTIVE)));
                results.add(ids(executor, f -> f.op(PHASE_IS, PHASE_NO, Phase.INACTIVE)));
                results.add(ids(executor, f -> f.op(SKU_IS, SKU, new Sku("C3"))));
                results.add(ids(executor, f -> f.op(CODE_IS, SKU_CODE, "B2")));
                results.add(ids(executor, f -> f.op(TAGS_ARE, TAGS, new String[] {"red", "blue"})));
            }));
            // Exactly the matching rows: by ordinal, MySQL's varchar column would match every row and its native
            // enum column none (rfc/0017-spike-results.md consequence 2).
            assertThat(results).as(handling).containsExactly(rows(1L, 4L), rows(1L, 4L), rows(2L), rows(3L), rows(2L),
                    rows(2L));
            // The value is a statement parameter under either value handling, never a literal (R-FLT-08).
            assertThat(sql).as(handling).filteredOn(s -> s.contains("from operand_rows")).hasSize(6)
                    .allSatisfy(s -> assertThat(where(s)).contains("?").doesNotContain("'")
                            .doesNotContainPattern("=\\s*\\d"));
        }
    }

    @TckTest
    void ac_flt_22_the_binder_refuses_a_node_type_its_line_does_not_expect_with_mq1502(TckDatabase db) {
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = factory(ds, "bind")) {
                OperandBinder hibernate = new HibernateProviderSupport().operands(sf).orElseThrow();
                // The double: the column's entity path in place of the column, a node type no line binds by.
                OperandBinder entityTyped = new OperandBinder() {
                    @Override
                    public <T> ParameterExpression<T> parameter(CriteriaBuilder cb, Expression<?> typedBy,
                            Class<T> type) {
                        return hibernate.parameter(cb, ((Path<?>) typedBy).getParentPath(), type);
                    }
                };
                OperatorProviderSupport.serving(feature -> false, entityTyped, () -> sf.inSession(em -> {
                    var executor = ModelQueryExecutor.create(em, OperandRow.class, ModelQueryConfig.defaults());
                    assertThatThrownBy(() -> ids(executor, f -> f.op(PHASE_IS, PHASE, Phase.ACTIVE)))
                            .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                            .hasMessageContaining("op(phaseIs)").hasMessageContaining("R.phase")
                            .hasRootCauseInstanceOf(IllegalStateException.class);
                }));
            }
        });
        assertThat(sql).noneMatch(s -> s.contains("from operand_rows"));
    }

    @TckTest
    void ac_flt_22_over_a_function_of_a_column_a_string_binds_by_class_and_an_enum_is_mq1502_before_any_statement(
            TckDatabase db) {
        List<List<R>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, "bind", executor -> {
            results.add(ids(executor, f -> f.op(TEXT_IS, LABEL_UPPER, "BETA")));
            // Typed by the function's Java class an enum would bind by ordinal and match nothing (R-FLT-21).
            for (var column : List.of(Expr.function("upper", Phase.class, PHASE_TEXT), PHASE_TEXT_OR_PHASE)) {
                assertThatThrownBy(() -> ids(executor, f -> f.op(PHASE_IS, column, Phase.ACTIVE)))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                        .hasMessageContaining("op(phaseIs)").hasMessageContaining("Phase")
                        .hasMessageContaining("can't type it by this column's expression");
            }
        }));
        assertThat(results).containsExactly(rows(2L, 4L));
        assertThat(sql).filteredOn(s -> s.contains("from operand_rows")).hasSize(1)
                .allSatisfy(s -> assertThat(where(s)).contains("?").doesNotContain("'"));
    }

    @TckTest
    void ac_flt_22_having_op_over_an_aggregate_of_a_path_binds_typed_by_the_column(TckDatabase db) {
        List<P> groups = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, "bind", executor -> groups.addAll(executor.list(
                ModelQuery.builder(ROWS, row -> new P(row.get(P_PHASE), row.get(P_LATEST)))
                        .select(SelectSet.of(P_PHASE, P_LATEST))
                        .groupBy(P_PHASE)
                        .having(h -> h.op(PHASE_IS, P_LATEST, Phase.INACTIVE))
                        .orderBy(P_PHASE.asc())
                        .build(), Limit.unlimited()))));
        // max(phase_text) compared with 'INACTIVE', its STRING mapping; by ordinal it would be 1, matching nothing.
        assertThat(groups).containsExactly(new P(Phase.INACTIVE, Phase.INACTIVE));
    }

    private static List<R> rows(Long... ids) {
        return Arrays.stream(ids).map(R::new).toList();
    }

    /** The statement's {@code where} clause, up to its {@code order by}. */
    private static String where(String sql) {
        int start = sql.indexOf(" where ");
        int end = sql.indexOf(" order by ");
        return start < 0 ? "" : sql.substring(start, end < 0 ? sql.length() : end);
    }

    private static List<R> ids(ModelQueryExecutor<OperandRow> executor, UnaryOperator<Filters<R>> where) {
        return executor.list(QUERY.where(where).build(), Limit.unlimited());
    }

    /** Runs {@code work} on an executor over a seeded factory with Hibernate's provider, binder included. */
    private static void withExecutor(DataSource ds, String handling,
            Consumer<ModelQueryExecutor<OperandRow>> work) {
        try (SessionFactory sf = factory(ds, handling)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OperandRow.class,
                    ModelQueryConfig.defaults())));
        }
    }

    /**
     * A factory of {@link OperandRow} alone over {@code ds}, its table created, seeded and dropped with it, and
     * {@code hibernate.criteria.value_handling_mode} set to {@code handling}.
     */
    public static SessionFactory factory(DataSource ds, String handling) {
        SessionFactory sf = new Configuration().addAnnotatedClass(OperandRow.class).buildSessionFactory(
                new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds)
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .applySetting(AvailableSettings.CRITERIA_VALUE_HANDLING_MODE, handling)
                        .build());
        sf.inTransaction(session -> {
            session.persist(new OperandRow(1, Phase.ACTIVE, "alpha", "A1", "red"));
            session.persist(new OperandRow(2, Phase.INACTIVE, "beta", "B2", "red", "blue"));
            session.persist(new OperandRow(3, Phase.PENDING, "gamma", "C3", "blue"));
            session.persist(new OperandRow(4, Phase.ACTIVE, "Beta", "D4", "green"));
        });
        return sf;
    }
}
