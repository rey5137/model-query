package com.rey.modelquery.tck.spr;

import static com.rey.modelquery.tck.spr.ModelQueryRepositoryTest.assertSameAsExecutor;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.fch.Line;
import com.rey.modelquery.tck.fch.OrderLines;
import com.rey.modelquery.tck.fch.QLine;
import com.rey.modelquery.tck.fch.QOrderLines;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;

/**
 * A fetch plan on the query runs through the repository's {@code findAll}, {@code findPage} in each count mode and
 * {@code export} as it does on the executor: the same statements, and the children loaded (spec api/15 AC-FCH-01,
 * integration/50 R-SPR-01).
 */
class FetchPlanRepositoryTest {

    private static final FetchPlan<OrderLines> PLAN =
            FetchPlan.of(QOrderLines.ALL).child(QOrderLines.ITEMS, FetchPlan.of(QLine.ALL));

    private static final ModelQuery<OrderEntity, Long, OrderLines> ORDERS =
            QOrderLines.query().orderBy(QOrderLines.ID.asc()).fetch(PLAN).build();

    @TckTest
    void ac_fch_01_find_all_through_the_repository_loads_the_children(TckDatabase db) {
        List<OrderLines> orders = assertSameAsExecutor(db,
                executor -> executor.list(ORDERS, Limit.of(30)),
                repository -> repository.findAll(ORDERS, Limit.of(30)));

        assertThat(orders).hasSize(30);
        assertLoaded(orders);
    }

    @TckTest
    void ac_fch_01_find_page_through_the_repository_loads_the_children_in_each_count_mode(TckDatabase db) {
        for (CountMode mode : CountMode.values()) {
            FindPageTest.Paged page = assertSameAsExecutor(db,
                    executor -> FindPageTest.Paged.of(executor.page(ORDERS, PageSpec.of(2, 20), mode)),
                    repository -> FindPageTest.Paged.of(repository.findPage(ORDERS, PageRequest.of(2, 20), mode)));

            if (mode == CountMode.ONLY_COUNT) {
                assertThat(page.content()).isEmpty();
                assertThat(page.total()).isNotNull();
            } else {
                assertThat(page.content()).hasSize(20);
                assertLoaded(page.content().stream().map(OrderLines.class::cast).toList());
            }
        }
    }

    @TckTest
    void ac_fch_01_export_through_the_repository_loads_the_children_on_each_page(TckDatabase db) {
        List<OrderLines> exported = assertSameAsExecutor(db,
                executor -> export(sink -> executor.export(ORDERS, ExportOptions.of(40), page -> page, sink::add)),
                repository -> export(sink -> repository.export(ORDERS, ExportOptions.of(40), page -> page,
                        sink::add)));

        assertThat(exported).hasSizeGreaterThan(40);
        assertLoaded(exported);
    }

    private static List<OrderLines> export(Function<List<OrderLines>, Long> export) {
        List<OrderLines> sunk = new ArrayList<>();
        assertThat(export.apply(sunk)).isEqualTo(sunk.size());
        return sunk;
    }

    private static void assertLoaded(List<OrderLines> orders) {
        assertThat(orders).anySatisfy(order -> assertThat(order.items()).isNotEmpty());
        assertThat(orders).allSatisfy(order -> assertThat(order.items()).extracting(Line::id).isSorted());
    }
}
