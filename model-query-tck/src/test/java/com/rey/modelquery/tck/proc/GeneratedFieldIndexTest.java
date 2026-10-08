package com.rey.modelquery.tck.proc;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.FieldIndex;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SortSpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The generated {@code fields()} against the sort (spec processor/31 R-GEN-33, R-GEN-32): a name that resolves through
 * the index names the same field as the same name used as a sort property (R-QRY-14).
 */
class GeneratedFieldIndexTest {

    /** The select field each select name of {@code index} holds, which the query selects whole. */
    private static <M> SelectSet<M> selectingEverything(FieldIndex<M> index) {
        return index.resolve(index.names().stream().filter(name -> index.select(name).isPresent()).toList()).select();
    }

    private static <E, K, M> void assertSortNamesTheSameField(
            ModelQuery.Builder<E, K, M> builder, FieldIndex<M> index, List<String> expected) {
        ModelQuery<E, K, M> query = builder.select(selectingEverything(index)).build();
        var sorted = new ArrayList<String>();
        for (String name : index.names()) {
            SelectField<M, ?> held = index.select(name).orElse(null);
            if (held == null) {
                continue;
            }
            sorted.add(name);
            assertThat(query.orderedBy(SortSpec.of(SortSpec.Key.asc(name))).orderBy())
                    .as("sorting by '%s'", name)
                    .singleElement()
                    .satisfies(order -> assertThat(order.column()).isSameAs(held));
        }
        assertThat(sorted).containsExactlyElementsOf(expected);
    }

    @Test
    void ac_gen_23_a_column_and_a_nested_join_column_resolve_to_the_field_the_sort_names() {
        assertSortNamesTheSameField(QOrderView.query(), QOrderView.fields(), List.of(
                "id", "status", "referrerKey", "referrer.id", "referrer.name", "referrer.country"));
    }

    @Test
    void ac_gen_23_an_expression_and_a_named_aggregate_resolve_to_the_field_the_sort_names() {
        assertSortNamesTheSameField(QOrderBandTotals.query(), QOrderBandTotals.fields(), List.of("band", "doubled"));
        assertSortNamesTheSameField(QStatusSummary.query(), QStatusSummary.fields(),
                List.of("status", "orders", "revenue", "firstPlaced"));
    }
}
