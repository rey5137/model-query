package com.rey.modelquery.tck.pag;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.inheritance.PagingEntities.StBank;
import com.rey.modelquery.tck.inheritance.PagingEntities.StCard;
import com.rey.modelquery.tck.inheritance.PagingEntities.StPayment;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/** A concurrent commit can contradict a treated offset page's earlier count (AC-PAG-32). */
class OffsetPageWarningTest {

    private record PaymentRow(Long id) {}

    private static final String BOUND = "private-bound-reference";
    private static final TableField<StPayment, StPayment> ROOT = TableField.root(StPayment.class);
    private static final ColumnField<PaymentRow, StPayment, Long> ID =
            ColumnField.of(PaymentRow.class, ROOT, "id", Long.class);
    private static final ColumnField<PaymentRow, StCard, String> REFERENCE =
            ColumnField.of(PaymentRow.class, ROOT.treat(StCard.class), "reference", String.class);

    @TckTest
    void ac_pag_32_a_contradicted_total_warns_once_per_query_identity_without_bound_values(TckDatabase db) {
        seeded(db, (factory, between) -> factory.inSession(em -> capturing(warnings -> {
            var query = query(true);
            var executor = executor(em);
            // An early page remains consistent even though the commit changes the total.
            page(factory, between, executor, query, 0, 4, CountMode.COUNT, 1, 2);
            assertThat(warnings).isEmpty();
            page(factory, between, executor, query, 3, 5, CountMode.COUNT, 4, 5);
            assertThat(warnings).singleElement().asString()
                    .contains("MQ2214: PaymentRow: offset 3 returned 2 rows with total 4",
                            "committed between the count and content", "REPEATABLE READ", "snapshot")
                    .doesNotContain(BOUND);
            page(factory, between, executor, query, 4, 6, CountMode.COUNT, 5, 6);
            // A different executor retains the warning state of the same immutable query definition.
            page(factory, between, executor(em), query, 5, 7, CountMode.COUNT, 6, 7);
            assertThat(warnings).hasSize(1);
            page(factory, between, executor, query(true), 6, 8, CountMode.COUNT, 7, 8);
            assertThat(warnings).hasSize(2).allSatisfy(message -> assertThat(message).doesNotContain(BOUND));
        })));
    }

    @TckTest
    void ac_pag_32_empty_early_uncounted_count_only_and_untreated_pages_are_silent(TckDatabase db) {
        seeded(db, (factory, between) -> factory.inSession(em -> capturing(warnings -> {
            var executor = executor(em);
            var empty = executor.page(query(true), PageSpec.ofOffset(99, 2), CountMode.COUNT);
            assertThat(empty.content()).isEmpty();
            assertThat(empty.total()).hasValue(3);
            var early = executor.page(query(true), PageSpec.ofOffset(0, 2), CountMode.COUNT);
            assertThat(early.content()).extracting(PaymentRow::id).containsExactly(1L, 2L);
            assertThat(early.total()).hasValue(3);
            var last = executor.page(query(true), PageSpec.ofOffset(1, 2), CountMode.COUNT);
            assertThat(last.content()).extracting(PaymentRow::id).containsExactly(2L, 3L);
            assertThat(last.total()).hasValue(3);
            page(factory, between, executor, query(true), 2, 4, CountMode.NO_COUNT, 3, 4);
            var count = executor.page(query(true), PageSpec.ofOffset(3, 2), CountMode.ONLY_COUNT);
            assertThat(count.content()).isEmpty();
            assertThat(count.total()).hasValue(4);
            page(factory, between, executor, query(false), 3, 5, CountMode.COUNT, 4, 5);
            assertThat(warnings).isEmpty();
        })));
    }

    private static ModelQuery<StPayment, Long, PaymentRow> query(boolean treated) {
        var builder = ModelQuery.builder(ROOT, row -> new PaymentRow(row.get(ID)))
                .select(SelectSet.of(ID)).primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc());
        return (treated ? builder.where(f -> f.eq(REFERENCE, Optional.of(BOUND))) : builder).build();
    }

    private static ModelQueryExecutor<StPayment> executor(EntityManager em) {
        return ModelQueryExecutor.create(em, StPayment.class, ModelQueryConfig.defaults());
    }

    private static void page(SessionFactory factory, AtomicReference<Runnable> between,
                             ModelQueryExecutor<StPayment> executor, ModelQuery<StPayment, Long, PaymentRow> query,
                             int offset, long insertId, CountMode mode, long first, long second) {
        between.set(() -> factory.inTransaction(em -> em.persist(card(insertId))));
        var page = executor.page(query, PageSpec.ofOffset(offset, 2), mode);
        assertThat(between.get()).as("the row committed before the content statement").isNull();
        assertThat(page.content()).extracting(PaymentRow::id).containsExactly(first, second);
        if (mode == CountMode.COUNT) {
            assertThat(page.total()).hasValue(insertId - 1);
        } else {
            assertThat(page.total()).isEmpty();
        }
    }

    private static void seeded(TckDatabase db, BiConsumer<SessionFactory, AtomicReference<Runnable>> work) {
        AtomicReference<Runnable> between = new AtomicReference<>();
        StatementInspector inspector = sql -> {
            if (sql.stripLeading().startsWith("select") && !sql.contains("count(")) {
                Runnable commit = between.getAndSet(null);
                if (commit != null) {
                    commit.run();
                }
            }
            return sql;
        };
        Configuration cfg = new Configuration().addAnnotatedClass(StPayment.class).addAnnotatedClass(StCard.class)
                .addAnnotatedClass(StBank.class).setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop");
        cfg.getProperties().put(AvailableSettings.STATEMENT_INSPECTOR, inspector);
        try (SessionFactory factory = cfg.buildSessionFactory()) {
            factory.inTransaction(em -> {
                for (long id = 1; id <= 3; id++) {
                    em.persist(card(id));
                }
            });
            work.accept(factory, between);
        }
    }

    private static StCard card(long id) {
        StCard card = new StCard();
        card.id = id;
        card.reference = BOUND;
        return card;
    }

    private static void capturing(Consumer<List<String>> work) {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger("com.rey.modelquery.jpa.DefaultModelQueryExecutor");
        Level previous = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String message = new SimpleFormatter().formatMessage(record);
                if (message.startsWith("MQ2214:")) {
                    assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                    warnings.add(message);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.setLevel(Level.WARNING);
        logger.addHandler(handler);
        try {
            work.accept(warnings);
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }
}
