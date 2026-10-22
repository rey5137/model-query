package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.OperandBinder;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import org.hibernate.SessionFactory;
import org.hibernate.Version;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.sqm.tree.domain.SqmPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Hibernate's operand binder: the lines it serves, the node types it takes per line and what it binds (AC-FLT-22), and
 * which mappings it says convert a value (AC-FLT-33, AC-FLT-34).
 */
class HibernateOperandBinderTest {

    enum Shade {
        LIGHT,
        DARK
    }

    /** Stores a shade as its lower-case name, so a value bound by class alone would match nothing. */
    static final class LowerShade implements AttributeConverter<Shade, String> {
        @Override
        public String convertToDatabaseColumn(Shade shade) {
            return shade == null ? null : shade.name().toLowerCase();
        }

        @Override
        public Shade convertToEntityAttribute(String column) {
            return column == null ? null : Shade.valueOf(column.toUpperCase());
        }
    }

    /** Stores {@code SKU:<code>}, as a prefixing converter of the attribute's own type. */
    static final class SkuPrefix implements AttributeConverter<String, String> {
        @Override
        public String convertToDatabaseColumn(String code) {
            return code == null ? null : "SKU:" + code;
        }

        @Override
        public String convertToEntityAttribute(String column) {
            return column == null ? null : column.substring(4);
        }
    }

    /** A value type mapped by an auto-applied converter alone, with no {@code @Convert} on the attribute. */
    record Code(String value) implements Serializable {}

    @Converter(autoApply = true)
    static final class CodeConverter implements AttributeConverter<Code, String> {
        @Override
        public String convertToDatabaseColumn(Code code) {
            return code == null ? null : "C-" + code.value();
        }

        @Override
        public Code convertToEntityAttribute(String column) {
            return column == null ? null : new Code(column.substring(2));
        }
    }

    @Embeddable
    static class Label {
        @Convert(converter = SkuPrefix.class)
        String labelSku;

        String text;
    }

    @Entity
    @Table(name = "operand_binder_rows")
    static class Row {
        @Id
        Long id;

        @Convert(converter = SkuPrefix.class)
        String sku;

        String name;

        Code code;

        @Embedded
        Label label;

        @ManyToOne
        Row parent;

        @Enumerated(EnumType.STRING)
        Shade named;

        @Convert(converter = LowerShade.class)
        Shade lowered;

        String[] tags;

        /** Primitive attributes, whose path type on 6.6 is a domain type rather than a mapping's basic type. */
        int rank;

        boolean flagged;

        Row() {}

        Row(long id, Shade shade, String... tags) {
            this.id = id;
            this.named = shade;
            this.lowered = shade;
            this.tags = tags;
            this.sku = id == 2 ? "B2" : "a" + id;
            this.rank = (int) id * 10;
            this.flagged = id == 2;
        }
    }

    /** A 6.6-style path source whose path type is no basic type, as an embeddable's or an entity's is. */
    public static final class NonBasicSource {
        public Object getSqmPathType() {
            return new Object();
        }
    }

    /** A 6.6-style path source over {@code type}, which a 7.4 binder, expecting the basic type itself, refuses. */
    public static final class BasicSource {
        private final Object type;

        BasicSource(Object type) {
            this.type = type;
        }

        public Object getSqmPathType() {
            return type;
        }
    }

    private static SessionFactory factory;

    @BeforeAll
    static void start() {
        factory = new Configuration().setProperty(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:operand-binder")
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                .addAnnotatedClass(Row.class)
                .addAnnotatedClass(CodeConverter.class)
                .buildSessionFactory();
        factory.inTransaction(session -> {
            session.persist(new Row(1, Shade.LIGHT, "red"));
            session.persist(new Row(2, Shade.DARK, "red", "blue"));
            session.persist(new Row(3, Shade.DARK, "blue"));
        });
    }

    @AfterAll
    static void stop() {
        factory.close();
    }

    @Test
    void ac_flt_22_operands_is_served_on_the_measured_lines_only() {
        assertThat(new HibernateProviderSupport().operands(factory)).isPresent();
        assertThat(HibernateProviderSupport.operands(factory, "6.6.58.Final")).isPresent();
        assertThat(HibernateProviderSupport.operands(factory, "7.4.11.Final")).isPresent();
        assertThat(HibernateProviderSupport.operands(factory, "6.5.3.Final")).isEmpty();
        assertThat(HibernateProviderSupport.operands(factory, "7.3.0.Final")).isEmpty();
        assertThat(HibernateProviderSupport.operands(factory, "7.5.0.Final")).isEmpty();
        assertThat(HibernateProviderSupport.operands(factory, "unknown")).isEmpty();
    }

    @Test
    void ac_flt_22_the_binder_refuses_a_node_type_its_line_does_not_expect() {
        HibernateOperandBinder six = HibernateOperandBinder.of("6.6.58.Final").orElseThrow();
        HibernateOperandBinder seven = HibernateOperandBinder.of("7.4.11.Final").orElseThrow();
        Object basic = factory.unwrap(SessionFactoryImplementor.class).getTypeConfiguration()
                .getBasicTypeForJavaType(String.class);

        assertThat(six.bindType(basic)).isSameAs(basic);
        assertThat(six.bindType(new BasicSource(basic))).isSameAs(basic);
        assertThat(seven.bindType(basic)).isSameAs(basic);
        // Never a fallback to the raw node type: 7.4's path node type is the basic type, not a source of one.
        assertThatThrownBy(() -> seven.bindType(new BasicSource(basic))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Hibernate 7.4.11.Final").hasMessageContaining(BasicSource.class.getName());
        assertThatThrownBy(() -> six.bindType(new NonBasicSource())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Hibernate 6.6.58.Final").hasMessageContaining(NonBasicSource.class.getName());
        for (HibernateOperandBinder binder : List.of(six, seven)) {
            assertThatThrownBy(() -> binder.bindType(new Object())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> binder.bindType(null)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not null");
        }
    }

    @Test
    void ac_flt_22_the_binder_types_each_basic_path_and_aggregate_of_this_line_with_a_new_parameter_per_call() {
        OperandBinder binder = new HibernateProviderSupport().operands(factory).orElseThrow();
        CriteriaBuilder cb = factory.getCriteriaBuilder();
        Root<Row> root = cb.createQuery(Row.class).from(Row.class);
        List<Expression<?>> typedBy = List.of(root.get("named"), root.get("lowered"), root.get("tags"),
                root.get("id"), cb.max(root.<Long>get("id")), cb.count(root));
        for (Expression<?> column : typedBy) {
            ParameterExpression<?> first = binder.parameter(cb, column, Object.class);
            assertThat(first).isNotNull().isNotSameAs(binder.parameter(cb, column, Object.class));
        }
        // An entity path's node type is no basic type on either line.
        assertThatThrownBy(() -> binder.parameter(cb, root, Row.class)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Hibernate " + Version.getVersionString());
    }

    @Test
    void ac_flt_22_the_binder_types_only_a_path_or_an_aggregate_over_one_and_declines_any_other_expression() {
        OperandBinder binder = new HibernateProviderSupport().operands(factory).orElseThrow();
        CriteriaBuilder cb = factory.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<Row> root = query.from(Row.class);
        Subquery<Long> maxId = query.subquery(Long.class);
        maxId.select(cb.max(maxId.from(Row.class).<Long>get("id")));
        Expression<Long> id = root.get("id");
        List<Expression<?>> typed = List.of(root.get("named"), root, cb.greatest(root.<Shade>get("named")),
                cb.count(root), cb.countDistinct(id), cb.sum(id));
        List<Expression<?>> declined = List.of(cb.function("pick", Shade.class, root.get("tags")),
                cb.upper(cb.function("text_of", String.class, id)), cb.coalesce(root.<Shade>get("named"), Shade.DARK),
                cb.<Shade>selectCase().when(cb.isNull(id), Shade.DARK).otherwise(root.get("named")),
                cb.sum(id, 1L), maxId, cb.max(cb.sum(id, 1L)), cb.literal(Shade.DARK));
        assertThat(typed).allMatch(binder::typesBy);
        assertThat(declined).noneMatch(binder::typesBy);
        for (Expression<?> column : declined) {
            // Never by the node type a function's Java class gave it (R-FLT-21).
            assertThatThrownBy(() -> binder.parameter(cb, column, Object.class))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("a path or an aggregate over one");
        }
    }

    @Test
    void ac_flt_22_a_primitive_int_or_boolean_attribute_binds_by_its_own_type() {
        assertThat(ids((cb, root) -> equal(cb, root.get("rank"), 20))).containsExactly(2L);
        assertThat(ids((cb, root) -> equal(cb, root.get("flagged"), true))).containsExactly(2L);
    }

    @Test
    void ac_flt_22_a_string_enum_a_converted_enum_and_an_array_bind_by_the_column_mapping() {
        assertThat(ids((cb, root) -> equal(cb, root.get("named"), Shade.DARK))).containsExactly(2L, 3L);
        assertThat(ids((cb, root) -> equal(cb, root.get("lowered"), Shade.LIGHT))).containsExactly(1L);
        assertThat(ids((cb, root) -> equal(cb, root.get("tags"), new String[] {"red", "blue"}))).containsExactly(2L);
    }

    @Test
    void ac_flt_33_a_jpa_converter_converts_on_a_path_through_a_join_or_embeddable_and_on_its_aggregates() {
        OperandBinder binder = new HibernateProviderSupport().operands(factory).orElseThrow();
        CriteriaBuilder cb = factory.getCriteriaBuilder();
        Root<Row> root = cb.createQuery(Row.class).from(Row.class);
        Expression<String> sku = root.get("sku");
        List<Expression<?>> converted = List.of(sku, root.get("lowered"), root.get("code"),
                root.join("parent").get("sku"), root.get("label").get("labelSku"), cb.least(sku), cb.greatest(sku),
                cb.greatest(root.join("parent").<String>get("sku")));
        assertThat(converted).allMatch(binder::typesBy).allMatch(binder::converts);
    }

    @Test
    void ac_flt_34_the_binder_says_a_plain_column_a_builtin_enum_or_a_declined_expression_converts_nothing() {
        OperandBinder binder = new HibernateProviderSupport().operands(factory).orElseThrow();
        CriteriaBuilder cb = factory.getCriteriaBuilder();
        Root<Row> root = cb.createQuery(Row.class).from(Row.class);
        Expression<String> name = root.get("name");
        // An @Enumerated enum, an array and an entity are no JPA converter; a function of a converted column is
        // declined, so never asked (R-VND-24).
        List<Expression<?>> unconverted = List.of(name, root.get("label").get("text"), root.get("named"),
                root.get("tags"), root.get("id"), cb.greatest(name), cb.count(root), root, root.join("parent"),
                cb.lower(root.get("sku")));
        assertThat(unconverted).noneMatch(binder::converts);
        assertThat(new OperandBinder() {
            @Override
            public <T> ParameterExpression<T> parameter(CriteriaBuilder b, Expression<?> typedBy, Class<T> type) {
                throw new UnsupportedOperationException();
            }
        }.converts(root.get("sku"))).isFalse();
    }

    @Test
    void ac_flt_34_the_binder_says_a_path_whose_node_type_lookup_throws_converts_nothing() {
        HibernateOperandBinder six = HibernateOperandBinder.of("6.6.58.Final").orElseThrow();
        HibernateOperandBinder seven = HibernateOperandBinder.of("7.4.11.Final").orElseThrow();
        // 6.6 unwraps the path source reflectively; its getSqmPathType() throwing, unchecked or checked, answers false.
        assertThat(six.converts(path(() -> {
            throw new IllegalStateException("no path type");
        }))).isFalse();
        assertThat(six.converts(path(() -> {
            throw new Exception("no path type");
        }))).isFalse();
        // A node type lookup that throws on either line answers false (R-VND-24).
        Expression<?> unresolved = path(null);
        assertThat(six.converts(unresolved)).isFalse();
        assertThat(seven.converts(unresolved)).isFalse();
    }

    /** A path source whose path type lookup is {@code pathType}. */
    public interface ThrowingSource {
        Object getSqmPathType() throws Exception;
    }

    /**
     * An {@link SqmPath} whose node type is a {@link ThrowingSource} running {@code pathType}, or, for a {@code null}
     * one, whose {@code getNodeType()} itself throws.
     */
    private static Expression<?> path(Callable<Object> pathType) {
        ThrowingSource source = () -> pathType.call();
        return (Expression<?>) Proxy.newProxyInstance(SqmPath.class.getClassLoader(), new Class<?>[] {SqmPath.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getNodeType" -> {
                        if (pathType == null) {
                            throw new IllegalStateException("no node type");
                        }
                        yield nodeType(method.getReturnType(), source);
                    }
                    case "toString" -> "throwing path";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A {@code type} (the line's declared node type) that is also a {@link ThrowingSource} delegating to it. */
    private static Object nodeType(Class<?> type, ThrowingSource source) {
        boolean declares = Arrays.stream(type.getMethods())
                .anyMatch(m -> m.getName().equals("getSqmPathType") && m.getParameterCount() == 0);
        Class<?>[] types = declares ? new Class<?>[] {type} : new Class<?>[] {type, ThrowingSource.class};
        return Proxy.newProxyInstance(HibernateOperandBinderTest.class.getClassLoader(), types,
                (proxy, method, args) -> {
                    if (!method.getName().equals("getSqmPathType")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return source.getSqmPathType();
                });
    }

    @Test
    void ac_flt_33_lower_of_the_binder_parameter_runs_the_converter_and_folds_both_sides() {
        assertThat(ids((cb, root) -> lowerEqual(cb, root.get("sku"), "A1"))).containsExactly(1L);
        assertThat(ids((cb, root) -> lowerEqual(cb, root.get("sku"), "b2"))).containsExactly(2L);
        assertThat(ids((cb, root) -> lowerEqual(cb, root.get("sku"), "SKU:a1"))).isEmpty();
    }

    /** {@code lower(column) = lower(?)}, the parameter typed by {@code column} through the binder (R-FLT-07). */
    private static Bound lowerEqual(CriteriaBuilder cb, Expression<String> column, String value) {
        OperandBinder binder = new HibernateProviderSupport().operands(factory).orElseThrow();
        ParameterExpression<String> parameter = binder.parameter(cb, column, String.class);
        @SuppressWarnings("unchecked")
        ParameterExpression<Object> bound = (ParameterExpression<Object>) (ParameterExpression<?>) parameter;
        return new Bound(cb.equal(cb.lower(column), cb.lower(parameter)), bound, value);
    }

    /** {@code column = ?}, the parameter typed by {@code column} through the binder, and the value it binds. */
    private static Bound equal(CriteriaBuilder cb, Expression<?> column, Object value) {
        OperandBinder binder = new HibernateProviderSupport().operands(factory).orElseThrow();
        ParameterExpression<Object> parameter = binder.parameter(cb, column, Object.class);
        return new Bound(cb.equal(column, parameter), parameter, value);
    }

    private record Bound(Predicate condition, ParameterExpression<Object> parameter, Object value) {}

    /** The ids of the rows {@code where}'s condition keeps, its parameter bound to its value. */
    private static List<Long> ids(BiFunction<CriteriaBuilder, Root<Row>, Bound> where) {
        return factory.fromSession(session -> {
            CriteriaBuilder cb = session.getCriteriaBuilder();
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<Row> root = query.from(Row.class);
            Bound bound = where.apply(cb, root);
            query.select(root.get("id")).where(bound.condition()).orderBy(cb.asc(root.get("id")));
            return session.createQuery(query).setParameter(bound.parameter(), bound.value()).getResultList();
        });
    }
}
