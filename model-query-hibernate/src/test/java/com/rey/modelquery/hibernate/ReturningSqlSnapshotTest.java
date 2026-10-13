package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.ReturningShape;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Query;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaUpdate;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import java.util.Arrays;
import java.util.List;
import org.hibernate.Session;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;

/**
 * R-WRT-50's returning rendering (spec vendor/41 §7, R-VND-17): the SQL a returning update and delete translate to,
 * per Hibernate line and dialect, which the M20.1 spike confirmed against RFC 0010 §1. Both lines render the same SQL,
 * so there is no {@code sql/hibernate7/returning.sql} variant; this test runs on whichever line the build uses and
 * asserts the same strings on both. The committed snapshots {@code sql/{h2,postgresql}/returning.sql} hold the
 * statements a real engine run executes (TCK AC-WRT-42).
 */
class ReturningSqlSnapshotTest {

    @Entity(name = "Item")
    static class Item {
        @Id
        Long id;

        String name;

        Integer qty;

        Item(Long id, String name, Integer qty) {
            this.id = id;
            this.name = name;
            this.qty = qty;
        }

        Item() {
        }
    }

    @Entity(name = "Multi")
    @SecondaryTable(name = "multi_extra")
    static class Multi {
        @Id
        Long id;

        String name;

        @Column(table = "multi_extra")
        String extra;
    }

    /** A value an {@code AttributeConverter} stores as text, so its domain type is not its JDBC type. */
    record Code(String value) {}

    enum Shade { LIGHT, DARK }

    static class CodeConverter implements AttributeConverter<Code, String> {
        @Override
        public String convertToDatabaseColumn(Code code) {
            return code == null ? null : "code:" + code.value();
        }

        @Override
        public Code convertToEntityAttribute(String stored) {
            return stored == null ? null : new Code(stored.substring("code:".length()));
        }
    }

    /** A converted column, an enum and a to-one, each read back as the entity holds it. */
    @Entity(name = "Tagged")
    static class Tagged {
        @Id
        Long id;

        @Convert(converter = CodeConverter.class)
        Code code;

        @Enumerated(EnumType.STRING)
        Shade shade;

        @ManyToOne
        Item item;

        Tagged(Long id, Code code, Shade shade, Item item) {
            this.id = id;
            this.code = code;
            this.shade = shade;
            this.item = item;
        }

        Tagged() {
        }
    }

    private static final String H2_UPDATE =
            "select name, qty from final table (update Item i1_0 set name=? where i1_0.id=?)";
    private static final String H2_DELETE =
            "select name, qty from old table (delete from Item i1_0 where i1_0.id=?)";
    private static final String PG_UPDATE = "update Item i1_0 set name=? where i1_0.id=? returning name,qty";
    private static final String PG_DELETE = "delete from Item i1_0 where i1_0.id=? returning name,qty";

    // ---- R-WRT-50 rendering, vendor/41 §7

    @Test
    void ac_vnd_14_returning_renders_vendor_41_section_7_per_dialect_and_statement_kind() {
        try (SessionFactoryImplementor sf = factory("h2-render", new H2Dialect());
                Session session = sf.openSession()) {
            assertThat(render(session, "update", ReturningShape.DELTA_TABLE)).isEqualTo(H2_UPDATE);
            assertThat(render(session, "delete", ReturningShape.DELTA_TABLE)).isEqualTo(H2_DELETE);
        }
        try (SessionFactoryImplementor sf = factory("pg-render", new PostgreSQLDialect());
                Session session = sf.openSession()) {
            assertThat(render(session, "update", ReturningShape.SUFFIX)).isEqualTo(PG_UPDATE);
            assertThat(render(session, "delete", ReturningShape.SUFFIX)).isEqualTo(PG_DELETE);
        }
    }

    /**
     * A returning query that selects the identifier resolves it, though Hibernate's attribute container lists only
     * the entity's properties ({@code findAttributeMapping("id")} is null).
     */
    @Test
    void ac_wrt_42_a_returning_query_may_select_the_identifier_its_path_resolves_to() {
        try (SessionFactoryImplementor sf = factory("id-render", new H2Dialect());
                Session session = sf.openSession()) {
            assertThat(render(session, "update", ReturningShape.DELTA_TABLE, List.of("id", "name")))
                    .isEqualTo("select id, name from final table (update Item i1_0 set name=? where i1_0.id=?)");
        }
    }

    @Test
    void ac_vnd_14_rendered_is_true_for_a_single_table_root_and_false_for_a_secondary_table() {
        try (SessionFactoryImplementor sf = factory("rendered", new H2Dialect())) {
            assertThat(HibernateMutations.INSTANCE.rendered(sf, Item.class, ReturningShape.DELTA_TABLE)).isTrue();
            assertThat(HibernateMutations.INSTANCE.rendered(sf, Multi.class, ReturningShape.DELTA_TABLE)).isFalse();
        }
    }

    // ---- R-WRT-50 execution on H2, the delta-table shape

    @Test
    void ac_wrt_42_h2_delta_table_returns_the_changed_rows_after_the_update_and_before_the_delete() {
        try (SessionFactoryImplementor sf = factory("h2-run", new H2Dialect());
                Session session = sf.openSession()) {
            session.beginTransaction();
            session.persist(new Item(1L, "before", 3));
            session.persist(new Item(2L, "other", 4));
            session.getTransaction().commit();
            session.beginTransaction();
            assertThat(describe(rows(session, "update"))).containsExactly(List.of("after", 3));
            assertThat(describe(rows(session, "delete"))).containsExactly(List.of("other", 4));
            session.getTransaction().commit();
        }
    }

    /**
     * A converted column comes back as its attribute value, after the {@code AttributeConverter}, an enum as the
     * constant, and a to-one's id from the written row's foreign key, since the database returns the written table's
     * columns (R-WRT-50, R-WRT-48).
     */
    @Test
    void ac_wrt_42_a_converted_column_an_enum_and_a_to_one_id_come_back_as_the_entity_holds_them() {
        List<String> attributes = List.of("id", "code", "shade", "item.id");
        try (SessionFactoryImplementor sf = factory("h2-typed", new H2Dialect());
                Session session = sf.openSession()) {
            session.beginTransaction();
            Item item = new Item(7L, "owner", 1);
            session.persist(item);
            session.persist(new Tagged(10L, new Code("a"), Shade.DARK, item));
            session.persist(new Tagged(11L, new Code("b"), Shade.DARK, item));
            session.getTransaction().commit();
            session.beginTransaction();
            assertThat(HibernateMutations.translated(tagged(session), Tagged.class, attributes,
                    ReturningShape.DELTA_TABLE)).isEqualTo("select id, code, shade, item_id from final table "
                            + "(update Tagged t1_0 set shade=? where t1_0.id=?)");
            assertThat(describe(HibernateMutations.INSTANCE.rows(tagged(session), Tagged.class, attributes,
                    ReturningShape.DELTA_TABLE))).containsExactly(List.of(10L, new Code("a"), Shade.LIGHT, 7L));
            session.getTransaction().rollback();
        }
    }

    /** A column on a secondary table is {@code MQ1809}, a definition error naming that table (R-WRT-50). */
    @Test
    void ac_wrt_42_a_returning_column_on_a_secondary_table_is_mq1809_naming_the_table() {
        try (SessionFactoryImplementor sf = factory("secondary", new H2Dialect());
                Session session = sf.openSession()) {
            CriteriaBuilder cb = session.getCriteriaBuilder();
            CriteriaUpdate<Multi> update = cb.createCriteriaUpdate(Multi.class);
            Root<Multi> root = update.getRoot();
            update.set(root.<String>get("name"), "after");
            update.where(cb.equal(root.get("id"), 1L));
            Query statement = session.createQuery(update);
            assertThatThrownBy(() -> HibernateMutations.translated(statement, Multi.class, List.of("id", "extra"),
                    ReturningShape.DELTA_TABLE))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1809))
                    .hasMessageContaining("returning extra cannot read it is on multi_extra");
        }
    }

    /** {@code update Tagged set shade = LIGHT where id = 10}. */
    private static Query tagged(Session session) {
        CriteriaBuilder cb = session.getCriteriaBuilder();
        CriteriaUpdate<Tagged> update = cb.createCriteriaUpdate(Tagged.class);
        Root<Tagged> root = update.getRoot();
        update.set(root.<Shade>get("shade"), Shade.LIGHT);
        update.where(cb.equal(root.get("id"), 10L));
        return session.createQuery(update);
    }

    /** The SQL {@code statement} of {@code kind} translates to, on the running Hibernate line, with {@code shape}. */
    private static String render(Session session, String kind, ReturningShape shape) {
        return render(session, kind, shape, List.of("name", "qty"));
    }

    /** {@link #render(Session, String, ReturningShape)} for a query that returns {@code attributes}, in that order. */
    private static String render(Session session, String kind, ReturningShape shape, List<String> attributes) {
        return HibernateMutations.translated(statement(session, kind), Item.class, attributes, shape);
    }

    /** The rows {@code statement} of {@code kind} returns on H2, on the running Hibernate line. */
    private static List<Object[]> rows(Session session, String kind) {
        return HibernateMutations.INSTANCE.rows(statement(session, kind), Item.class, List.of("name", "qty"),
                ReturningShape.DELTA_TABLE);
    }

    /** The rows as lists, for an assertion that names each changed row's values rather than only a count. */
    private static List<List<Object>> describe(List<Object[]> rows) {
        return rows.stream().map(row -> Arrays.asList(row)).toList();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Query statement(Session session, String kind) {
        CriteriaBuilder cb = session.getCriteriaBuilder();
        if (kind.equals("update")) {
            CriteriaUpdate<Item> update = cb.createCriteriaUpdate(Item.class);
            Root<Item> root = update.getRoot();
            update.set((Path) root.get("name"), "after");
            update.where(cb.equal(root.get("id"), 1L));
            return session.createQuery(update);
        }
        CriteriaDelete<Item> delete = cb.createCriteriaDelete(Item.class);
        Root<Item> root = delete.getRoot();
        delete.where(cb.equal(root.get("id"), 2L));
        return session.createQuery(delete);
    }

    private static SessionFactoryImplementor factory(String name, Dialect dialect) {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL,
                        "jdbc:h2:mem:returning-" + name + ";DB_CLOSE_DELAY=-1")
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                .applySetting(AvailableSettings.DIALECT, dialect)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop");
        return (SessionFactoryImplementor) new Configuration().addAnnotatedClass(Item.class)
                .addAnnotatedClass(Multi.class).addAnnotatedClass(Tagged.class).buildSessionFactory(registry.build());
    }
}
