package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.criteria.Expression;
import java.lang.reflect.Proxy;
import java.util.List;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * {@code setExpression(column, ExpressionField)} at its four write sites, checked at the call and at {@code build()},
 * before any Criteria query exists (spec api/14 R-WRT-53, AC-WRT-46; D-130).
 */
class WriteExpressionTest {

    static final class Counter {}

    static final class Source {}

    record NewCounter(Long id, String label) {}

    record SourceRow(Long id) {}

    static final class FlagConverter implements ColumnConverter<Boolean, String> {
        @Override
        public Boolean toModel(String attribute) {
            return "Y".equals(attribute);
        }

        @Override
        public String toAttribute(Boolean model) {
            return model ? "Y" : "N";
        }
    }

    private static final TableField<Counter, Counter> ROOT = TableField.root(Counter.class);
    private static final TableField<Source, Source> SOURCE = TableField.root(Source.class);

    private static final ColumnField<NewCounter, Counter, Long> ID =
            ColumnField.of(NewCounter.class, ROOT, "id", Long.class);
    private static final ColumnField<NewCounter, Counter, String> LABEL =
            ColumnField.of(NewCounter.class, ROOT, "label", String.class);
    private static final ColumnField<NewCounter, Counter, Long> NEXT =
            ColumnField.of(NewCounter.class, ROOT, "nextValue", Long.class);
    private static final ColumnField<NewCounter, Counter, Long> TOTAL =
            ColumnField.of(NewCounter.class, ROOT, "total", Long.class);
    private static final ColumnField<NewCounter, Counter, Boolean> FLAGGED =
            ColumnField.of(NewCounter.class, ROOT, "flagged", Boolean.class, String.class, new FlagConverter());
    private static final ColumnField<SourceRow, Source, Long> SOURCE_ID =
            ColumnField.of(SourceRow.class, SOURCE, "id", Long.class);

    private static final InsertColumns<NewCounter, Counter> COLUMNS = InsertColumns.<NewCounter, Counter>of(ROOT)
            .addKey(ID, NewCounter::id)
            .add(LABEL, NewCounter::label);

    private static final ExpressionField<NewCounter, Long> SEQUENCE = Expr.nextval("counter_seq");

    // ---- AC-WRT-46

    @Test
    void ac_wrt_46_each_site_records_a_computed_assignment_on_its_column() {
        ModelUpdate<Counter, NewCounter> update = update().setExpression(NEXT, SEQUENCE).whereKey(1L).build();
        assertThat(update.assignments()).singleElement().isInstanceOfSatisfying(Assignment.Computed.class,
                computed -> {
                    assertThat(computed.column()).isEqualTo(NEXT);
                    assertThat(computed.value()).isEqualTo(SEQUENCE);
                });

        ModelInsert<Counter, NewCounter> values = values().setExpression(NEXT, SEQUENCE).build();
        assertThat(values.definition().constants()).singleElement().isInstanceOf(Assignment.Computed.class);

        ModelInsert<Counter, NewCounter> select = select().setExpression(NEXT, Expr.<SourceRow>nextval("s")).all()
                .build();
        assertThat(select.definition().constants()).singleElement().isInstanceOf(Assignment.Computed.class);

        ModelInsert<Counter, NewCounter> upsert = values().onConflict(ID)
                .doUpdate(u -> u.setExpression(NEXT, SEQUENCE).setExpression(TOTAL, Expr.constant(0L))).build();
        assertThat(upsert.definition().conflict().update().assignments())
                .allSatisfy(assignment -> assertThat(assignment).isInstanceOf(Assignment.Computed.class));
    }

    @Test
    void ac_wrt_46_an_expression_that_reads_a_column_or_binds_a_value_throws_mq1613_at_the_call() {
        assertCode(() -> update().setExpression(TOTAL, Expr.plus(TOTAL, 1L)), MqCode.MQ1613)
                .hasMessage("MQ1613: NewCounter.total: setExpression(..., (total + ?)) reads a column; a write "
                        + "expression reads no column and binds nothing, such as Expr.nextval, with Expr.constant "
                        + "for a fixed operand");
        assertCode(() -> update().setExpression(TOTAL, Expr.value(5L)), MqCode.MQ1613)
                .hasMessageContaining("binds a parameter");
        // A raw operand binds: Expr.constant is the bind-free way to offset a sequence.
        assertCode(() -> update().setExpression(TOTAL, Expr.plus(SEQUENCE, 1L)), MqCode.MQ1613)
                .hasMessageContaining("binds a parameter");
        assertThatCode(() -> update().setExpression(TOTAL, Expr.plus(SEQUENCE, Expr.constant(1_000L))))
                .doesNotThrowAnyException();

        assertCode(() -> values().setExpression(NEXT, Expr.plus(ID, 1L)), MqCode.MQ1613);
        assertCode(() -> values().onConflict(ID).doUpdate(u -> u.setExpression(NEXT, Expr.value(1L))), MqCode.MQ1613);
        assertCode(() -> values().onConflict(ID).doUpdate(u -> u.set(LABEL, "x").setExpression(NEXT,
                Expr.coalesce(TOTAL, 0L))), MqCode.MQ1613);
        assertCode(() -> select().setExpression(NEXT, Expr.plus(SOURCE_ID, 1L)), MqCode.MQ1613)
                .hasMessageContaining("reads a column");
    }

    @Test
    void ac_wrt_46_a_converted_column_throws_mq1609_at_build_on_every_site() {
        var converted = Expr.<NewCounter, Boolean>function("random_flag", Boolean.class);
        assertCode(() -> update().setExpression(FLAGGED, converted).whereKey(1L).build(), MqCode.MQ1609)
                .hasMessage("MQ1609: NewCounter.flagged: setExpression(...) on a column converted by FlagConverter; "
                        + "the expression's value has the entity attribute's type, not the model's");
        assertCode(() -> values().setExpression(FLAGGED, converted).build(), MqCode.MQ1609)
                .hasMessage("MQ1609: NewCounter.flagged: setExpression(...) on a column converted by FlagConverter; "
                        + "the expression's value has the entity attribute's type, not the model's");
        assertCode(() -> values().onConflict(ID).doUpdate(u -> u.setExpression(FLAGGED, converted)).build(),
                MqCode.MQ1609);
        assertCode(() -> select().setExpression(FLAGGED, Expr.<SourceRow, Boolean>function("random_flag",
                Boolean.class)).all().build(), MqCode.MQ1609);
    }

    @Test
    void ac_wrt_46_through_entities_with_a_computed_assignment_throws_mq1610() {
        assertCode(() -> update().setExpression(NEXT, SEQUENCE).whereKey(1L).throughEntities().build(),
                MqCode.MQ1610)
                .hasMessage("MQ1610: NewCounter: throughEntities() with setExpression(nextValue, ...); an entity-mode "
                        + "update sets each attribute in Java and leaves the @Version to the provider's flush");
    }

    @Test
    void ac_wrt_46_a_key_column_or_a_column_assigned_twice_is_refused_as_the_sites_other_assignments() {
        assertCode(() -> update().setExpression(ID, SEQUENCE).whereKey(1L).build(), MqCode.MQ1605);
        assertCode(() -> update().set(NEXT, 1L).setExpression(NEXT, SEQUENCE).whereKey(1L).build(), MqCode.MQ1602);
        assertCode(() -> values().setExpression(LABEL, Expr.constant("x")).build(), MqCode.MQ1801)
                .hasMessageContaining("a column the insert model already writes");
        assertCode(() -> values().set(NEXT, 1L).setExpression(NEXT, SEQUENCE).build(), MqCode.MQ1801)
                .hasMessageContaining("set(...) twice");
        assertCode(() -> values().onConflict(ID).doUpdate(u -> u.setExpression(ID, SEQUENCE)).build(),
                MqCode.MQ1804).hasMessageContaining("assigns a key column");
        assertCode(() -> values().onConflict(ID).doUpdate(u -> u.set(NEXT, 1L).setExpression(NEXT, SEQUENCE))
                .build(), MqCode.MQ1804).hasMessageContaining("assigns it twice");
    }

    @Test
    void ac_wrt_46_a_computed_conflict_assignment_binds_no_value_of_its_own() {
        ModelInsert<Counter, NewCounter> upsert = values().onConflict(ID)
                .doUpdate(u -> u.set(LABEL, "x").setExpression(NEXT, SEQUENCE)).keepVersion().build();
        // The set's value only: the computed one renders SQL.
        assertThat(upsert.conflictBinds(List.of())).isEqualTo(1);
    }

    @Test
    void ac_wrt_46_a_sequence_without_a_renderer_is_mq1509_before_any_statement_and_passes_with_one() {
        var none = RenderOptions.portable();
        var rendering = none.withSequenceRenderer((cb, sequence) -> fake());
        ModelUpdate<Counter, NewCounter> update = update().setExpression(NEXT, SEQUENCE).whereKey(1L).build();
        ModelInsert<Counter, NewCounter> values = values().setExpression(NEXT, SEQUENCE).build();
        ModelInsert<Counter, NewCounter> upsert = values().onConflict(ID)
                .doUpdate(u -> u.setExpression(NEXT, Expr.plus(SEQUENCE, Expr.constant(1L)))).build();
        ModelInsert<Counter, NewCounter> select = select().setExpression(NEXT, Expr.<SourceRow>nextval("s")).all()
                .build();

        assertCode(() -> update.checkSequences(none), MqCode.MQ1509)
                .hasMessage("MQ1509: NewCounter.nextValue: setExpression(..., nextval(counter_seq)): the database or "
                        + "the persistence provider has no sequence support (VendorProfile#sequences, "
                        + "ProviderSupport#sequences)");
        for (ModelInsert<Counter, NewCounter> insert : List.of(values, upsert, select)) {
            assertCode(() -> insert.checkSequences(none), MqCode.MQ1509).hasMessageContaining("no sequence support");
            assertThatCode(() -> insert.checkSequences(rendering)).doesNotThrowAnyException();
        }
        assertThatCode(() -> update.checkSequences(rendering)).doesNotThrowAnyException();
        // An expression that reads no sequence needs no renderer.
        assertThatCode(() -> update().setExpression(NEXT, Expr.constant(1L)).whereKey(1L).build()
                .checkSequences(none)).doesNotThrowAnyException();
    }

    // ---- AC-WRT-48
    @Test
    void ac_wrt_48_a_correlated_value_in_set_expression_is_mq1613_at_the_call() {
        ExpressionField<NewCounter, Long> sum = Correlated.sum(NewCounter.class, SubSelect.of(SOURCE_ID),
                (f, outer) -> f.compare(SOURCE_ID, Op.EQ, outer.column(ID)));
        ExpressionField<NewCounter, Long> count = Correlated.count(NewCounter.class, SubSelect.of(SOURCE_ID),
                (f, outer) -> f.compare(SOURCE_ID, Op.EQ, outer.column(ID)));
        ExpressionField<NewCounter, Long> lifted = Expr.plus(ColumnField.<NewCounter, Counter, Long>lifted(ID), 1L);
        for (ExpressionField<NewCounter, Long> value : List.of(sum, count, lifted)) {
            assertCode(() -> update().setExpression(NEXT, value), MqCode.MQ1613);
        }
    }

    private static ModelUpdate.Builder<Counter, Long, NewCounter> update() {
        return ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID));
    }

    private static ValuesInsert.Rows<Counter, Long, NewCounter> values() {
        return ValuesInsert.builder(COLUMNS, Long.class, List.of(new NewCounter(1L, "a"), new NewCounter(2L, "b")));
    }

    private static ModelInsert.Mapping<Counter, NewCounter, SourceRow> select() {
        return ModelInsert.select(InsertColumns.<NewCounter, Counter>of(ROOT).addKey(ID, NewCounter::id), SOURCE)
                .map(ID, SOURCE_ID);
    }

    @SuppressWarnings("unchecked")
    private static Expression<Long> fake() {
        return (Expression<Long>) Proxy.newProxyInstance(Expression.class.getClassLoader(),
                new Class<?>[] {Expression.class}, (proxy, method, args) -> null);
    }

    private static AbstractThrowableAssert<?, ? extends Throwable> assertCode(ThrowingCallable call, MqCode code) {
        return assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
