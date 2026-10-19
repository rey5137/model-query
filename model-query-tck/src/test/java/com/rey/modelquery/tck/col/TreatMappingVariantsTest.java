package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.inheritance.CellEntities;
import com.rey.modelquery.tck.inheritance.GateEntities;
import com.rey.modelquery.tck.inheritance.VariantEntities;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.AnnotationException;
import org.hibernate.SessionFactory;
import org.hibernate.Version;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/**
 * The mapping gates and variants of AC-VND-18 through the executor and Hibernate's own renderer, on each line: a gated
 * hierarchy is {@code MQ4016} before any statement, a root restriction keeps only its visible rows, and each variant
 * runs its measured cells and refuses the ones its runs lacked.
 */
class TreatMappingVariantsTest {

    @TckTest
    void ac_vnd_18_composite_ids_and_null_or_not_null_discriminators_are_refused_before_any_statement(
            TckDatabase db) {
        for (var mapping : List.of(
                new Mapping<>("id-class", GateEntities.IdClassPayment.class, GateEntities.IdClassCard.class),
                new Mapping<>("embedded-id", GateEntities.EmbeddedPayment.class, GateEntities.EmbeddedCard.class),
                new Mapping<>("null", GateEntities.NullPayment.class, GateEntities.NullCard.class),
                new Mapping<>("not-null", GateEntities.NotNullPayment.class, GateEntities.NotNullCard.class))) {
            assertGated(db, mapping);
        }
    }

    @TckTest
    void ac_vnd_18_a_subtype_sql_restriction_is_refused_on_6_6_and_fails_bootstrap_on_7_4(TckDatabase db) {
        var mapping = new Mapping<>("subtype-restriction", GateEntities.SubtypeRestrictedPayment.class,
                GateEntities.SubtypeRestrictedCard.class);
        if (Version.getVersionString().startsWith("7.")) {
            assertThatThrownBy(() -> mapping.schema(db).close()).isInstanceOf(AnnotationException.class);
            return;
        }
        assertGated(db, mapping);
    }

    private static <P, C extends P> void assertGated(TckDatabase db, Mapping<P, C> mapping) {
        try (SessionFactory schema = mapping.schema(db)) {
            // A count of a query that only selects the treated column has no treated site, so it filters on one.
            assertThat(statements(db, ds -> mapping.serving(ds, executor -> {
                assertRefused(() -> executor.list(mapping.numbers(), Limit.of(5)), mapping.name);
                assertRefused(() -> executor.count(mapping.numbers(f -> f.eq(mapping.number, "1111"))),
                        mapping.name);
            }))).as(mapping.name).isEmpty();
        }
    }

    @TckTest
    void ac_vnd_18_a_root_sql_restriction_keeps_only_its_visible_rows_through_treat_on_each_strategy(TckDatabase db) {
        for (var mapping : List.of(
                new Mapping<>("root-restriction-single-table", GateEntities.RootStPayment.class,
                        GateEntities.RootStCard.class),
                new Mapping<>("root-restriction-joined", GateEntities.RootJdPayment.class,
                        GateEntities.RootJdCard.class),
                new Mapping<>("root-restriction-table-per-class", GateEntities.RootTpPayment.class,
                        GateEntities.RootTpCard.class))) {
            rootRestriction(db, mapping);
        }
    }

    private static <P, C extends P> void rootRestriction(TckDatabase db, Mapping<P, C> mapping) {
        try (SessionFactory schema = mapping.schema(db)) {
            schema.inTransaction(em -> {
                em.persist(mapping.row(mapping.payment, 1, "s1", null));
                em.persist(mapping.row(mapping.card, 2, "s2", "1111"));
                em.persist(mapping.row(mapping.card, 3, "hidden", "3333"));
                em.persist(mapping.row(mapping.card, 4, "s4", null));
            });
            mapping.serving(db, executor -> {
                assertThat(executor.list(mapping.numbers(), Limit.of(5))).as(mapping.name)
                        .containsExactly("1:null", "2:1111", "4:null");
                assertThat(executor.count(mapping.numbers())).as(mapping.name).isEqualTo(3);
                assertThat(executor.count(mapping.numbers(f -> f.eq(mapping.number, "3333")))).isZero();
                assertThat(executor.count(mapping.numbers(f -> f.isNull(mapping.number)))).as(mapping.name)
                        .isEqualTo(2);
            });
        }
    }

    @TckTest
    void ac_vnd_18_each_mapping_variant_runs_its_measured_cells_and_refuses_the_rest(TckDatabase db) {
        for (var mapping : List.of(
                new Mapping<>("force", VariantEntities.ForcePayment.class, VariantEntities.ForceCard.class,
                        VariantEntities.ForceBank.class).also(VariantEntities.ForceHolder.class),
                new Mapping<>("formula", VariantEntities.FormulaPayment.class, VariantEntities.FormulaCard.class,
                        VariantEntities.FormulaBank.class).also(VariantEntities.FormulaHolder.class),
                new Mapping<>("optional-secondary", VariantEntities.OptionalPayment.class,
                        VariantEntities.OptionalCard.class, VariantEntities.OptionalBank.class)
                                .also(VariantEntities.OptionalHolder.class),
                new Mapping<>("required-secondary", VariantEntities.RequiredPayment.class,
                        VariantEntities.RequiredCard.class, VariantEntities.RequiredBank.class)
                                .also(VariantEntities.RequiredHolder.class),
                new Mapping<>("joined-explicit", VariantEntities.JoinedPayment.class,
                        VariantEntities.JoinedCard.class, VariantEntities.JoinedBank.class)
                                .also(VariantEntities.JoinedHolder.class),
                new Mapping<>("joined-explicit-ignored", VariantEntities.JoinedPayment.class,
                        VariantEntities.JoinedCard.class, VariantEntities.JoinedBank.class)
                                .also(VariantEntities.JoinedHolder.class)
                                .with("hibernate.discriminator.ignore_explicit_for_joined"),
                new Mapping<>("joined-implicit", CellEntities.JdPayment.class, CellEntities.JdCard.class,
                        CellEntities.JdBank.class).also(CellEntities.JdHolder.class)
                        .with("hibernate.discriminator.implicit_for_joined"))) {
            variant(db, mapping, true);
        }
        // The core mapping, the control: its root-id count of a null test is admitted.
        variant(db, new Mapping<>("single-table", CellEntities.StPayment.class, CellEntities.StCard.class,
                CellEntities.StBank.class).also(CellEntities.StHolder.class), false);
    }

    private static <P, C extends P> void variant(TckDatabase db, Mapping<P, C> mapping, boolean variant) {
        try (SessionFactory schema = mapping.schema(db)) {
            schema.inTransaction(em -> {
                em.persist(mapping.row(mapping.payment, 1, "s1", null));
                em.persist(mapping.row(mapping.card, 2, "s2", "1111"));
                em.persist(mapping.row(mapping.card, 3, "s3", null));
                em.persist(mapping.row(mapping.others.get(0), 4, "s4", null));
            });
            var nulls = mapping.numbers(f -> f.isNull(mapping.number));
            mapping.serving(db, executor -> {
                assertThat(executor.list(mapping.numbers(), Limit.of(5))).as(mapping.name)
                        .containsExactly("1:null", "2:1111", "3:null", "4:null");
                assertThat(executor.count(mapping.numbers())).as(mapping.name).isEqualTo(4);
                assertThat(executor.list(nulls, Limit.of(5))).as(mapping.name)
                        .containsExactly("1:null", "3:null", "4:null");
                assertThat(executor.count(mapping.numbers(f -> f.eq(mapping.number, "1111")))).as(mapping.name)
                        .isEqualTo(1);
                assertThat(executor.count(mapping.numbers(f -> f.or(a -> a.eq(mapping.number, "1111"),
                        b -> b.isNull(mapping.number))))).as(mapping.name).isEqualTo(4);
                if (!variant) {
                    assertThat(executor.count(nulls)).as(mapping.name).isEqualTo(3);
                }
            });
            if (variant) {
                // A standalone null test's count was not measured on a variant: refused before any statement.
                assertThat(statements(db, ds -> mapping.serving(ds, executor ->
                        assertThatThrownBy(() -> executor.count(nulls))
                                .isExactlyInstanceOf(ModelQueryConfigurationException.class)
                                .hasMessageContaining("an unmeasured use set for this mapping variant")
                                .satisfies(failure -> assertThat(((ModelQueryConfigurationException) failure).code())
                                        .isEqualTo(MqCode.MQ4016))))).as(mapping.name).isEmpty();
            }
        }
    }

    /** The statements {@code work} runs, without the temporary tables a factory over a hierarchy creates. */
    private static List<String> statements(TckDatabase db, SqlSnapshots.SqlWork work) {
        return SqlSnapshots.capture(db, work).stream()
                .filter(sql -> !sql.startsWith("create ") && !sql.startsWith("drop ")).toList();
    }

    private static void assertRefused(Runnable action, String mapping) {
        assertThatThrownBy(action::run).as(mapping).isExactlyInstanceOf(ModelQueryConfigurationException.class)
                .satisfies(failure -> assertThat(((ModelQueryConfigurationException) failure).code())
                        .isEqualTo(MqCode.MQ4016));
    }

    /** One mapped hierarchy: a root with {@code id} and {@code status}, a card subtype with {@code number}. */
    private static final class Mapping<P, C extends P> {
        final String name;
        final Class<P> payment;
        final Class<C> card;
        final List<Class<? extends P>> others;
        final List<String> settings = new ArrayList<>();
        /** Entities outside the hierarchy its mapping references. */
        final List<Class<?>> extras = new ArrayList<>();
        final TableField<P, P> root;
        final ColumnField<String, P, Long> id;
        final ColumnField<String, C, String> number;

        @SafeVarargs
        Mapping(String name, Class<P> payment, Class<C> card, Class<? extends P>... others) {
            this.name = name;
            this.payment = payment;
            this.card = card;
            this.others = List.of(others);
            this.root = TableField.root(payment);
            this.id = ColumnField.of(String.class, root, "id", Long.class);
            this.number = ColumnField.of(String.class, root.treat(card), "number", String.class);
        }

        Mapping<P, C> with(String setting) {
            settings.add(setting);
            return this;
        }

        Mapping<P, C> also(Class<?> entity) {
            extras.add(entity);
            return this;
        }

        ModelQuery<P, ?, String> numbers() {
            return numbers(f -> f);
        }

        ModelQuery<P, ?, String> numbers(UnaryOperator<Filters<String>> where) {
            return ModelQuery.builder(root, row -> row.get(id) + ":" + row.get(number))
                    .select(SelectSet.of(id, number)).where(where).orderBy(id.asc()).build();
        }

        Object row(Class<?> type, long rowId, String status, String value) {
            try {
                Object row = type.getDeclaredConstructor().newInstance();
                type.getField("id").set(row, rowId);
                type.getField("status").set(row, status);
                if (value != null) {
                    type.getField("number").set(row, value);
                }
                return row;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        private Configuration configuration() {
            Configuration cfg = new Configuration().addAnnotatedClass(payment).addAnnotatedClass(card);
            others.forEach(cfg::addAnnotatedClass);
            extras.forEach(cfg::addAnnotatedClass);
            settings.forEach(setting -> cfg.setProperty(setting, "true"));
            return cfg;
        }

        SessionFactory schema(TckDatabase db) {
            return configuration().setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                    .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop").buildSessionFactory();
        }

        /** {@code work} on an executor over the database, through the provider's own treat renderer. */
        void serving(TckDatabase db, Consumer<ModelQueryExecutor<P>> work) {
            Configuration cfg = configuration().setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                    .setProperty(AvailableSettings.HBM2DDL_AUTO, "none");
            try (SessionFactory factory = cfg.buildSessionFactory()) {
                inSession(factory, work);
            }
        }

        /** {@code work} over {@code ds}, for a capture that must see no statement. */
        void serving(DataSource ds, Consumer<ModelQueryExecutor<P>> work) {
            Configuration cfg = configuration();
            cfg.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds);
            cfg.setProperty(AvailableSettings.HBM2DDL_AUTO, "none");
            try (SessionFactory factory = cfg.buildSessionFactory()) {
                inSession(factory, work);
            }
        }

        private void inSession(SessionFactory factory, Consumer<ModelQueryExecutor<P>> work) {
            factory.inSession(em -> work.accept(ModelQueryExecutor.create((EntityManager) em, payment,
                    ModelQueryConfig.defaults())));
        }
    }
}
