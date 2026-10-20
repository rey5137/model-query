package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.TreatTestSupport.ServingProvider;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.inheritance.TypeEntities;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.HibernateException;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/**
 * {@code ofType} and {@code notOfType} over an abstract subtype and a subtype mapped only in {@code orm.xml}, per
 * strategy, and the three type reads over a discriminator value no entity maps (AC-COL-49, AC-FLT-30, AC-FLT-31).
 *
 * <p>Rows: payment 1, debit card 2, gift card 3 (the {@code orm.xml} entity), bank 4; on {@code SINGLE_TABLE} also row
 * 9, whose discriminator value no entity maps.
 */
class TypeReadsTest {

    private static final List<Hierarchy<?>> HIERARCHIES = List.of(
            new Hierarchy<>("SINGLE_TABLE", TypeEntities.StPayment.class, TypeEntities.StCard.class,
                    TypeEntities.StDebit.class, TypeEntities.StGift.class, TypeEntities.StBank.class,
                    TypeEntities.StStray.class),
            new Hierarchy<>("JOINED", TypeEntities.JdPayment.class, TypeEntities.JdCard.class,
                    TypeEntities.JdDebit.class, TypeEntities.JdGift.class, TypeEntities.JdBank.class,
                    TypeEntities.JdStray.class),
            new Hierarchy<>("TABLE_PER_CLASS", TypeEntities.TpPayment.class, TypeEntities.TpCard.class,
                    TypeEntities.TpDebit.class, TypeEntities.TpGift.class, TypeEntities.TpBank.class,
                    TypeEntities.TpStray.class));

    @TckTest
    void ac_flt_30_of_type_keeps_an_orm_xml_subtype_and_accepts_an_abstract_type(TckDatabase db) {
        seeded(db, em -> {
            for (Hierarchy<?> each : HIERARCHIES) {
                each.ofTypes(em);
            }
        });
    }

    @TckTest
    void ac_flt_31_an_unmapped_discriminator_value_is_kept_by_not_of_type_dropped_by_of_type_and_fails_type_of(
            TckDatabase db) {
        // Written by a factory that maps the ghost, whose discriminator check (7.4) admits its value, and read by one
        // that doesn't, as after a deployment dropped the subtype.
        seeded(db, TypeEntities.StGhost.class, HIERARCHIES.get(0)::unmapped);
    }

    @TckTest
    void ac_col_49_an_unmapped_type_is_mq1106_and_a_type_read_without_a_renderer_is_mq4014(TckDatabase db) {
        assertThat(SqlSnapshots.capture(db, ds -> {
            try (SessionFactory factory = factory(ds)) {
                HIERARCHIES.forEach(each -> factory.inSession(each::refusals));
            }
            // Hibernate serves a renderer on its measured lines (R-VND-26), so a provider without one stands in, on
            // a factory of its own: the executor keeps the provider support it first finds for a factory.
            try (SessionFactory factory = factory(ds)) {
                ServingProvider.serving(factory, null, () -> HIERARCHIES.forEach(each ->
                        factory.inSession(each::withoutRenderer)));
            }
        }).stream().filter(sql -> !sql.startsWith("create ") && !sql.startsWith("drop "))).isEmpty();
    }

    private static SessionFactory factory(DataSource ds) {
        Configuration cfg = configuration();
        cfg.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds);
        cfg.setProperty(AvailableSettings.HBM2DDL_AUTO, "none");
        return cfg.buildSessionFactory();
    }

    private static void seeded(TckDatabase db, Consumer<EntityManager> work) {
        seeded(db, null, work);
    }

    /** Creates the schema and the rows, with row 9 of {@code ghost} when given, and reads them without it. */
    private static void seeded(TckDatabase db, Class<?> ghost, Consumer<EntityManager> work) {
        Configuration written = configuration();
        if (ghost != null) {
            written.addAnnotatedClass(ghost);
        }
        try (SessionFactory schema = jdbc(written, db, "create-drop")) {
            schema.inTransaction(em -> {
                HIERARCHIES.forEach(each -> each.insertRows(em));
                if (ghost != null) {
                    em.persist(Hierarchy.row(ghost, 9));
                }
            });
            try (SessionFactory reading = jdbc(configuration(), db, "none")) {
                reading.inSession(work::accept);
            }
        }
    }

    private static SessionFactory jdbc(Configuration cfg, TckDatabase db, String ddl) {
        return cfg.setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                .setProperty(AvailableSettings.HBM2DDL_AUTO, ddl).buildSessionFactory();
    }

    private static Configuration configuration() {
        Configuration cfg = new Configuration().addResource("META-INF/type-orm.xml");
        for (Hierarchy<?> each : HIERARCHIES) {
            cfg.addAnnotatedClass(each.payment).addAnnotatedClass(each.card).addAnnotatedClass(each.debit)
                    .addAnnotatedClass(each.bank);
        }
        return cfg;
    }

    private static void assertCode(Runnable action, Class<? extends RuntimeException> type, MqCode code,
            String message) {
        assertThatThrownBy(action::run).isExactlyInstanceOf(type).hasMessageContaining(message)
                .extracting(failure -> type == ModelQueryDefinitionException.class
                        ? ((ModelQueryDefinitionException) failure).code()
                        : ((ModelQueryConfigurationException) failure).code()).isEqualTo(code);
    }

    /** One strategy's hierarchy: the payment, its abstract card with a debit and a gift, a bank and a stray. */
    private static final class Hierarchy<P> {
        final String name;
        final Class<P> payment;
        final Class<? extends P> card;
        final Class<? extends P> debit;
        final Class<? extends P> gift;
        final Class<? extends P> bank;
        final Class<? extends P> stray;
        final TableField<P, P> root;
        final ColumnField<String, P, Long> id;
        final ExpressionField<String, Class<? extends P>> type;

        Hierarchy(String name, Class<P> payment, Class<? extends P> card, Class<? extends P> debit,
                Class<? extends P> gift, Class<? extends P> bank, Class<? extends P> stray) {
            this.name = name;
            this.payment = payment;
            this.card = card;
            this.debit = debit;
            this.gift = gift;
            this.bank = bank;
            this.stray = stray;
            this.root = TableField.root(payment);
            this.id = ColumnField.of(String.class, root, "id", Long.class);
            this.type = Expr.typeOf(String.class, root);
        }

        void insertRows(EntityManager em) {
            em.persist(row(payment, 1));
            em.persist(row(debit, 2));
            em.persist(row(gift, 3));
            em.persist(row(bank, 4));
        }

        private static Object row(Class<?> entity, long key) {
            try {
                Object row = entity.getDeclaredConstructor().newInstance();
                entity.getField("id").set(row, key);
                entity.getField("status").set(row, "s" + key);
                return row;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        ModelQuery<P, ?, String> ids(UnaryOperator<Filters<String>> where) {
            return ModelQuery.builder(root, row -> String.valueOf(row.get(id))).select(SelectSet.of(id))
                    .primaryKey(PrimaryKey.of(id)).where(where).orderBy(id.asc()).build();
        }

        private List<String> list(EntityManager em, ModelQuery<P, ?, String> query) {
            return ModelQueryExecutor.create(em, payment, ModelQueryConfig.defaults()).list(query, Limit.of(20));
        }

        private long count(EntityManager em, ModelQuery<P, ?, String> query) {
            return ModelQueryExecutor.create(em, payment, ModelQueryConfig.defaults()).count(query);
        }

        /**
         * The abstract card keeps its concrete subtypes' rows, the debit's and the {@code orm.xml} gift's; the gift
         * alone keeps its row; the negation keeps the rest (AC-FLT-30).
         */
        void ofTypes(EntityManager em) {
            var abstractCards = ids(f -> f.ofType(root, card));
            assertThat(list(em, abstractCards)).as(name).containsExactly("2", "3");
            assertThat(count(em, abstractCards)).as(name).isEqualTo(2);
            assertThat(list(em, ids(f -> f.ofType(root, gift)))).as(name).containsExactly("3");
            assertThat(list(em, ids(f -> f.ofType(root, bank, gift)))).as(name).containsExactly("3", "4");
            assertThat(list(em, ids(f -> f.notOfType(root, card)))).as(name).containsExactly("1", "4");
            assertThat(list(em, ids(f -> f.eq(type, gift)))).as(name).containsExactly("3");
        }

        /**
         * Row 9's discriminator value maps no entity: {@code notOfType} keeps it, since its list doesn't name it;
         * {@code ofType} drops it, even of the root type; {@code typeOf} fails on it with the provider's error, never
         * skipping it (AC-FLT-31, RFC 0016 spike).
         */
        void unmapped(EntityManager em) {
            assertThat(list(em, ids(f -> f))).as(name).containsExactly("1", "2", "3", "4", "9");
            assertThat(list(em, ids(f -> f.notOfType(root, card)))).as(name).containsExactly("1", "4", "9");
            assertThat(count(em, ids(f -> f.notOfType(root, card)))).as(name).isEqualTo(3);
            assertThat(list(em, ids(f -> f.ofType(root, payment)))).as(name).containsExactly("1", "2", "3", "4");
            assertThat(count(em, ids(f -> f.ofType(root, payment)))).as(name).isEqualTo(4);
            var typed = ModelQuery.builder(root, row -> row.get(id) + ":" + row.get(type).getSimpleName())
                    .select(SelectSet.of(id, type)).primaryKey(PrimaryKey.of(id)).orderBy(id.asc()).build();
            assertThatThrownBy(() -> list(em, typed)).as(name).isExactlyInstanceOf(HibernateException.class)
                    .hasMessageContaining("discriminator value").hasMessageEndingWith(": TypeStGhost");
        }

        /** {@code MQ1106} for a type no mapping names, before any statement (R-FLT-28). */
        void refusals(EntityManager em) {
            for (UnaryOperator<Filters<String>> where : List.<UnaryOperator<Filters<String>>>of(
                    f -> f.ofType(root, stray), f -> f.notOfType(root, debit, stray))) {
                assertCode(() -> list(em, ids(where)), ModelQueryDefinitionException.class, MqCode.MQ1106,
                        "is not a mapped entity");
            }
        }

        /** {@code MQ4014} for each type read without the provider's renderer, before any statement. */
        void withoutRenderer(EntityManager em) {
            var typed = ModelQuery.builder(root, row -> row.get(id) + ":" + row.get(type).getSimpleName())
                    .select(SelectSet.of(id, type)).primaryKey(PrimaryKey.of(id)).orderBy(id.asc()).build();
            for (ModelQuery<P, ?, String> query : List.of(typed, ids(f -> f.ofType(root, card)),
                    ids(f -> f.notOfType(root, card)), ids(f -> f.eq(type, debit)))) {
                assertCode(() -> list(em, query), ModelQueryConfigurationException.class, MqCode.MQ4014,
                        "TreatRenderer");
            }
        }
    }
}
