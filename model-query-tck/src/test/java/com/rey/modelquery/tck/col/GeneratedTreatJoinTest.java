package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.inheritance.CellEntities;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/** Generated downcast, upcast and treated-root joins on every inheritance strategy (AC-COL-47). */
public class GeneratedTreatJoinTest {

    @TckTest
    void ac_col_47_single_table_preserves_left_presence_and_restricts_inner_targets(TckDatabase db) {
        var hierarchy = new Hierarchy<>(CellEntities.StPayment.class, CellEntities.StCard.class,
                CellEntities.StBank.class, CellEntities.StPremium.class, CellEntities.StHolder.class);
        check(db, hierarchy,
                QStHolderView.query().select(QStHolderView.DEFAULT.with(QStHolderView.LEFT))
                        .orderBy(QStHolderView.ID.asc()).build(),
                restrictsInnerTargets(QStHolderView.query().select(QStHolderView.DEFAULT.with(QStHolderView.INNER))
                        .orderBy(QStHolderView.ID.asc()).build()),
                QStHolderView.query().select(QStHolderView.DEFAULT.with(QStHolderView.UP))
                        .orderBy(QStHolderView.ID.asc()).build(),
                QStPaymentView.query().select(QStPaymentView.DEFAULT.with(QStPaymentView.OWNER))
                        .orderBy(QStPaymentView.ID.asc()).build());
    }

    @TckTest
    void ac_col_47_joined_preserves_left_presence_and_restricts_inner_targets(TckDatabase db) {
        var hierarchy = new Hierarchy<>(CellEntities.JdPayment.class, CellEntities.JdCard.class,
                CellEntities.JdBank.class, CellEntities.JdPremium.class, CellEntities.JdHolder.class);
        check(db, hierarchy,
                QJdHolderView.query().select(QJdHolderView.DEFAULT.with(QJdHolderView.LEFT))
                        .orderBy(QJdHolderView.ID.asc()).build(),
                restrictsInnerTargets(QJdHolderView.query().select(QJdHolderView.DEFAULT.with(QJdHolderView.INNER))
                        .orderBy(QJdHolderView.ID.asc()).build()),
                QJdHolderView.query().select(QJdHolderView.DEFAULT.with(QJdHolderView.UP))
                        .orderBy(QJdHolderView.ID.asc()).build(),
                QJdPaymentView.query().select(QJdPaymentView.DEFAULT.with(QJdPaymentView.OWNER))
                        .orderBy(QJdPaymentView.ID.asc()).build());
    }

    @TckTest
    void ac_col_47_table_per_class_preserves_left_presence_and_restricts_inner_targets(TckDatabase db) {
        var hierarchy = new Hierarchy<>(CellEntities.TpPayment.class, CellEntities.TpCard.class,
                CellEntities.TpBank.class, CellEntities.TpPremium.class, CellEntities.TpHolder.class);
        check(db, hierarchy,
                QTpHolderView.query().select(QTpHolderView.DEFAULT.with(QTpHolderView.LEFT))
                        .orderBy(QTpHolderView.ID.asc()).build(),
                holders -> {
                    // D-161: the inherited id and status keep the base branch's real join key, so the INNER
                    // downcast is refused before any statement; reading only the subtype's own number leaves the
                    // base branch a NULL key, and the join still drops holder 16.
                    var inherited = QTpHolderView.query().select(QTpHolderView.DEFAULT.with(QTpHolderView.INNER))
                            .orderBy(QTpHolderView.ID.asc()).build();
                    assertRefused(() -> holders.list(inherited, Limit.of(20)));
                    assertRefused(() -> holders.page(inherited, PageSpec.of(0, 2), CountMode.COUNT));
                    var own = QTpHolderNumberView.query()
                            .select(QTpHolderNumberView.DEFAULT.with(QTpHolderNumberView.INNER))
                            .orderBy(QTpHolderNumberView.ID.asc()).keyset().build();
                    var ownRows = allPages(holders, own);
                    assertThat(ownRows).extracting(TpHolderNumberView::id).containsExactly(11L, 12L, 15L);
                    assertThat(ownRows).extracting(row -> row.inner().map(TpCardNumberView::number))
                            .containsExactly(Optional.of("1111"), Optional.empty(), Optional.of("4444"));
                },
                QTpHolderView.query().select(QTpHolderView.DEFAULT.with(QTpHolderView.UP))
                        .orderBy(QTpHolderView.ID.asc()).build(),
                QTpPaymentView.query().select(QTpPaymentView.DEFAULT.with(QTpPaymentView.OWNER))
                        .orderBy(QTpPaymentView.ID.asc()).build());
    }

    private static <P, H> void check(TckDatabase db, Hierarchy<P, H> hierarchy,
            ModelQuery<H, Long, ? extends HolderProjection> left,
            Consumer<ModelQueryExecutor<H>> inner,
            ModelQuery<H, Long, ? extends HolderProjection> up,
            ModelQuery<P, Long, ? extends PaymentProjection> treatedRoot) {
        try (SessionFactory factory = hierarchy.schema(db)) {
            factory.inTransaction(hierarchy::seed);
            factory.inSession(em -> {
                var holders = ModelQueryExecutor.create((EntityManager) em, hierarchy.holder,
                        ModelQueryConfig.defaults());
                var leftRows = holders.list(left, Limit.of(20));
                assertThat(leftRows).extracting(HolderProjection::id)
                        .containsExactly(11L, 12L, 13L, 14L, 15L, 16L, 99L);
                assertThat(leftRows).extracting(row -> row.left().map(CardProjection::id))
                        .containsExactly(Optional.of(1L), Optional.of(2L), Optional.empty(), Optional.empty(),
                                Optional.of(4L), Optional.empty(), Optional.empty());
                assertThat(leftRows.get(0).left().orElseThrow().number()).isEqualTo("1111");
                CardProjection nullValues = leftRows.get(1).left().orElseThrow();
                assertThat(nullValues.number()).isNull();
                assertThat(nullValues.status()).isNull();
                assertThat(leftRows.get(4).left().orElseThrow().number()).isEqualTo("4444");

                inner.accept(holders);

                var upRows = holders.list(up, Limit.of(20));
                assertThat(upRows).extracting(HolderProjection::id)
                        .containsExactly(11L, 12L, 13L, 14L, 15L, 16L, 99L);
                assertThat(upRows).extracting(row -> row.up().map(PaymentProjection::id))
                        .containsExactly(Optional.of(1L), Optional.of(2L), Optional.empty(), Optional.empty(),
                                Optional.of(4L), Optional.empty(), Optional.empty());
                assertThat(upRows.get(0).up().orElseThrow().status()).isEqualTo("paid");
                assertThat(upRows.get(4).up().orElseThrow().status()).isEqualTo("premium");

                var payments = ModelQueryExecutor.create((EntityManager) em, hierarchy.payment,
                        ModelQueryConfig.defaults());
                var rootRows = payments.list(treatedRoot, Limit.of(20));
                assertThat(rootRows).extracting(PaymentProjection::id).containsExactly(1L, 2L, 3L, 4L, 5L);
                assertThat(rootRows).extracting(row -> row.owner().map(OwnerProjection::id))
                        .containsExactly(Optional.of(99L), Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty());
            });
        }
    }

    private static <H> Consumer<ModelQueryExecutor<H>> restrictsInnerTargets(
            ModelQuery<H, Long, ? extends HolderProjection> inner) {
        return holders -> {
            var innerRows = holders.list(inner, Limit.of(20));
            assertThat(innerRows).extracting(HolderProjection::id).containsExactly(11L, 12L, 15L);
            assertThat(innerRows).extracting(row -> row.inner().map(CardProjection::id))
                    .containsExactly(Optional.of(1L), Optional.of(2L), Optional.of(4L));
            assertThat(innerRows.get(1).inner().orElseThrow().number()).isNull();
            assertThat(innerRows.get(1).inner().orElseThrow().status()).isNull();
        };
    }

    private static <H, R> List<R> allPages(ModelQueryExecutor<H> executor, ModelQuery<H, Long, R> query) {
        List<R> rows = new ArrayList<>();
        KeysetSpec spec = KeysetSpec.first(1);
        KeysetSlice<R> page;
        do {
            page = executor.page(query, spec);
            rows.addAll(page.content());
            assertThat(rows).hasSizeLessThanOrEqualTo(7);
            if (page.hasNext()) {
                spec = KeysetSpec.after(page.nextCursor().orElseThrow(), 1);
            }
        } while (page.hasNext());
        return rows;
    }

    private static void assertRefused(Runnable action) {
        assertThatThrownBy(action::run).isExactlyInstanceOf(ModelQueryConfigurationException.class)
                .extracting(failure -> ((ModelQueryConfigurationException) failure).code()).isEqualTo(MqCode.MQ4016);
    }

    public interface CardProjection {
        Long id();
        String number();
        String status();
    }

    public interface OwnerProjection {
        Long id();
    }

    public interface PaymentProjection {
        Long id();
        String status();
        Optional<? extends OwnerProjection> owner();
    }

    public interface HolderProjection {
        Long id();
        Optional<? extends CardProjection> left();
        Optional<? extends CardProjection> inner();
        Optional<? extends PaymentProjection> up();
    }

    @QueryModel(root = CellEntities.StCard.class)
    public record StCardView(@PrimaryKey Long id, String number, String status)
            implements CardProjection {}

    @QueryModel(root = CellEntities.StHolder.class)
    public record StOwnerView(@PrimaryKey Long id) implements OwnerProjection {}

    @QueryModel(root = CellEntities.StPayment.class)
    public record StPaymentView(@PrimaryKey Long id, String status,
            @Join(treat = CellEntities.StCard.class) Optional<StOwnerView> owner) implements PaymentProjection {}

    @QueryModel(root = CellEntities.StHolder.class)
    public record StHolderView(@PrimaryKey Long id,
            @Join(attribute = "payment") Optional<StCardView> left,
            @Join(attribute = "payment", type = JoinKind.INNER) Optional<StCardView> inner,
            @Join(attribute = "card") Optional<StPaymentView> up) implements HolderProjection {}

    @QueryModel(root = CellEntities.JdCard.class)
    public record JdCardView(@PrimaryKey Long id, String number, String status)
            implements CardProjection {}

    @QueryModel(root = CellEntities.JdHolder.class)
    public record JdOwnerView(@PrimaryKey Long id) implements OwnerProjection {}

    @QueryModel(root = CellEntities.JdPayment.class)
    public record JdPaymentView(@PrimaryKey Long id, String status,
            @Join(treat = CellEntities.JdCard.class) Optional<JdOwnerView> owner) implements PaymentProjection {}

    @QueryModel(root = CellEntities.JdHolder.class)
    public record JdHolderView(@PrimaryKey Long id,
            @Join(attribute = "payment") Optional<JdCardView> left,
            @Join(attribute = "payment", type = JoinKind.INNER) Optional<JdCardView> inner,
            @Join(attribute = "card") Optional<JdPaymentView> up) implements HolderProjection {}

    @QueryModel(root = CellEntities.TpCard.class)
    public record TpCardView(@PrimaryKey Long id, String number, String status)
            implements CardProjection {}

    @QueryModel(root = CellEntities.TpHolder.class)
    public record TpOwnerView(@PrimaryKey Long id) implements OwnerProjection {}

    @QueryModel(root = CellEntities.TpPayment.class)
    public record TpPaymentView(@PrimaryKey Long id, String status,
            @Join(treat = CellEntities.TpCard.class) Optional<TpOwnerView> owner) implements PaymentProjection {}

    @QueryModel(root = CellEntities.TpHolder.class)
    public record TpHolderView(@PrimaryKey Long id,
            @Join(attribute = "payment") Optional<TpCardView> left,
            @Join(attribute = "payment", type = JoinKind.INNER) Optional<TpCardView> inner,
            @Join(attribute = "card") Optional<TpPaymentView> up) implements HolderProjection {}

    /** Keyed by the subtype's own column, so the INNER downcast reads nothing inherited (D-161). */
    @QueryModel(root = CellEntities.TpCard.class)
    public record TpCardNumberView(@PrimaryKey String number) {}

    @QueryModel(root = CellEntities.TpHolder.class)
    public record TpHolderNumberView(@PrimaryKey Long id,
            @Join(attribute = "payment", type = JoinKind.INNER) Optional<TpCardNumberView> inner) {}

    private record Hierarchy<P, H>(Class<P> payment, Class<? extends P> card, Class<? extends P> bank,
            Class<? extends P> premium, Class<H> holder) {

        SessionFactory schema(TckDatabase db) {
            return new Configuration().addAnnotatedClass(payment).addAnnotatedClass(card).addAnnotatedClass(bank)
                    .addAnnotatedClass(premium).addAnnotatedClass(holder)
                    .setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                    .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop").buildSessionFactory();
        }

        void seed(EntityManager em) {
            H owner = row(holder, 99);
            em.persist(owner);
            P normal = row(card, 1, "status", "paid", "number", "1111", "owner", owner);
            P nullValues = row(card, 2);
            P sibling = row(bank, 3, "status", "bank");
            P grandchild = row(premium, 4, "status", "premium", "number", "4444");
            P base = row(payment, 5, "status", "base");
            for (P each : List.of(normal, nullValues, sibling, grandchild, base)) {
                em.persist(each);
            }
            em.persist(row(holder, 11, "payment", normal, "card", normal));
            em.persist(row(holder, 12, "payment", nullValues, "card", nullValues));
            em.persist(row(holder, 13, "payment", sibling));
            em.persist(row(holder, 14));
            em.persist(row(holder, 15, "payment", grandchild, "card", grandchild));
            em.persist(row(holder, 16, "payment", base));
        }
    }

    private static <T> T row(Class<T> type, long id, Object... values) {
        try {
            T row = type.getDeclaredConstructor().newInstance();
            type.getField("id").set(row, id);
            for (int i = 0; i < values.length; i += 2) {
                type.getField((String) values[i]).set(row, values[i + 1]);
            }
            return row;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
