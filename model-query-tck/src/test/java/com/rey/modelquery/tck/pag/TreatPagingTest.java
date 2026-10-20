package com.rey.modelquery.tck.pag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.KeysetNullKeys;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.inheritance.PagingEntities;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/** Treated paging on all inheritance strategies and Tier-1 vendors (AC-PAG-29, AC-PAG-30). */
class TreatPagingTest {

    private record PaymentRow(Long id, String reference) {}

    @TckTest
    void ac_pag_29_treated_keysets_walk_both_ways_through_nulls_and_refuse_a_sibling_cursor(TckDatabase db) {
        for (Tree<?, ?, ?> tree : trees()) {
            tree.seeded(db, tree::keysets);
        }
    }

    @TckTest
    void ac_pag_30_offset_pages_and_full_exports_visit_each_treated_predicate_match_once(TckDatabase db) {
        for (Tree<?, ?, ?> tree : trees()) {
            tree.seeded(db, tree::offsetsAndExports);
        }
    }

    private static List<Tree<?, ?, ?>> trees() {
        return List.of(
                new Tree<>(PagingEntities.StPayment.class, PagingEntities.StCard.class, PagingEntities.StBank.class),
                new Tree<>(PagingEntities.JdPayment.class, PagingEntities.JdCard.class, PagingEntities.JdBank.class),
                new Tree<>(PagingEntities.TpPayment.class, PagingEntities.TpCard.class, PagingEntities.TpBank.class));
    }

    private static final class Tree<P, C extends P, B extends P> {
        private final Class<P> payment;
        private final Class<C> card;
        private final Class<B> bank;
        private final TableField<P, P> root;
        private final ColumnField<PaymentRow, P, Long> id;
        private final ColumnField<PaymentRow, C, String> cardReference;
        private final ColumnField<PaymentRow, B, String> bankReference;

        Tree(Class<P> payment, Class<C> card, Class<B> bank) {
            this.payment = payment;
            this.card = card;
            this.bank = bank;
            root = TableField.root(payment);
            id = ColumnField.of(PaymentRow.class, root, "id", Long.class);
            cardReference = ColumnField.of(PaymentRow.class, root.treat(card), "reference", String.class);
            bankReference = ColumnField.of(PaymentRow.class, root.treat(bank), "reference", String.class);
        }

        private ModelQuery.Builder<P, Long, PaymentRow> query() {
            return ModelQuery.builder(root, row -> new PaymentRow(row.get(id), row.get(cardReference)))
                    .select(SelectSet.of(id, cardReference)).primaryKey(PrimaryKey.of(id));
        }

        void keysets(EntityManager em) {
            var executor = ModelQueryExecutor.create(em, payment, ModelQueryConfig.defaults()
                    .keysetNullKeys(KeysetNullKeys.HONOUR_NULL_PRECEDENCE));
            List<PaymentRow> fixture = List.of(new PaymentRow(1L, null), new PaymentRow(2L, "A"),
                    new PaymentRow(3L, null), new PaymentRow(4L, null), new PaymentRow(5L, "C"),
                    new PaymentRow(6L, null));
            for (boolean ascending : List.of(true, false)) {
                for (boolean nullsFirst : List.of(true, false)) {
                    OrderField<PaymentRow, String> order = ascending ? cardReference.asc() : cardReference.desc();
                    var query = query().orderBy(nullsFirst ? order.nullsFirst() : order.nullsLast()).keyset().build();
                    Comparator<String> values = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
                    Comparator<PaymentRow> expected = Comparator.comparing(PaymentRow::reference,
                            nullsFirst ? Comparator.nullsFirst(values) : Comparator.nullsLast(values));
                    expected = expected.thenComparing(PaymentRow::id,
                            ascending ? Comparator.naturalOrder() : Comparator.reverseOrder());
                    List<PaymentRow> rows = fixture.stream().sorted(expected).toList();
                    assertThat(executor.count(query)).as(payment.getName()).isEqualTo(rows.size());
                    for (int size : new int[] {1, 2, 3}) {
                        List<PaymentRow> forward = new ArrayList<>();
                        KeysetSpec spec = KeysetSpec.first(size);
                        KeysetSlice<PaymentRow> page;
                        boolean resumedAfterNull = false;
                        do {
                            page = executor.page(query, spec);
                            forward.addAll(page.content());
                            assertThat(forward).hasSizeLessThanOrEqualTo(fixture.size());
                            if (page.hasNext()) {
                                spec = KeysetSpec.after(page.nextCursor().orElseThrow(), size);
                                resumedAfterNull |= spec.values()[0] == null;
                            }
                        } while (page.hasNext());
                        assertThat(forward).as(payment.getName()).containsExactlyElementsOf(rows);
                        assertThat(forward).extracting(PaymentRow::id).doesNotHaveDuplicates();
                        assertThat(resumedAfterNull).isTrue();
                        List<PaymentRow> backward = new ArrayList<>(page.content());
                        while (page.hasPrevious()) {
                            page = executor.page(query, KeysetSpec.before(page.previousCursor().orElseThrow(), size));
                            backward.addAll(0, page.content());
                            assertThat(backward).hasSizeLessThanOrEqualTo(fixture.size());
                        }
                        assertThat(backward).as(payment.getName()).containsExactlyElementsOf(rows);
                    }
                }
            }
            var cards = query().orderBy(cardReference.asc().nullsFirst()).keyset().build();
            var banks = query().orderBy(bankReference.asc().nullsFirst()).keyset().build();
            String cursor = executor.page(cards, KeysetSpec.first(2)).nextCursor().orElseThrow();
            assertThatThrownBy(() -> executor.page(banks, KeysetSpec.after(cursor, 2)))
                    .isInstanceOf(ModelQueryExecutionException.class).hasMessageContaining("treat")
                    .extracting(failure -> ((ModelQueryExecutionException) failure).code()).isEqualTo(MqCode.MQ2209);
        }

        void offsetsAndExports(EntityManager em) {
            var executor = ModelQueryExecutor.create(em, payment, ModelQueryConfig.defaults());
            for (boolean nulls : List.of(true, false)) {
                List<Long> expected = nulls ? List.of(1L, 3L, 4L, 6L) : List.of(2L, 5L);
                for (boolean keyset : List.of(false, true)) {
                    var builder = query().orderBy(id.asc())
                            .where(f -> nulls ? f.isNull(cardReference) : f.isNotNull(cardReference));
                    var query = (keyset ? builder.keyset() : builder).build();
                    List<PaymentRow> paged = new ArrayList<>();
                    for (int offset = 0; offset < expected.size(); offset += 2) {
                        var page = executor.page(query, PageSpec.ofOffset(offset, 2), CountMode.COUNT);
                        assertThat(page.total()).hasValue(expected.size());
                        paged.addAll(page.content());
                    }
                    assertThat(paged).extracting(PaymentRow::id).containsExactlyElementsOf(expected);
                    var exported = new ArrayList<PaymentRow>();
                    assertThat(executor.export(query, ExportOptions.of(2), page -> page, exported::add))
                            .isEqualTo(expected.size());
                    assertThat(exported).extracting(PaymentRow::id).containsExactlyElementsOf(expected);
                }
            }
        }

        void seeded(TckDatabase db, Consumer<EntityManager> work) {
            Configuration cfg = new Configuration().addAnnotatedClass(payment).addAnnotatedClass(card)
                    .addAnnotatedClass(bank).setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                    .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop");
            try (SessionFactory factory = cfg.buildSessionFactory()) {
                factory.inTransaction(em -> {
                    em.persist(row(payment, 1, null));
                    em.persist(row(card, 2, "A"));
                    em.persist(row(card, 3, null));
                    em.persist(row(bank, 4, "B"));
                    em.persist(row(card, 5, "C"));
                    em.persist(row(bank, 6, null));
                });
                factory.inSession(work::accept);
            }
        }

        private static Object row(Class<?> type, long id, String reference) {
            try {
                Object row = type.getDeclaredConstructor().newInstance();
                type.getField("id").set(row, id);
                if (reference != null) {
                    type.getField("reference").set(row, reference);
                }
                return row;
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
