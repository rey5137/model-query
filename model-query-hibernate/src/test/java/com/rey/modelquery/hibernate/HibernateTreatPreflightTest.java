package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ExpectedNarrowing;
import com.rey.modelquery.core.StatementShape;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.CriteriaUpdate;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.Version;
import jakarta.persistence.DiscriminatorValue;
import org.hibernate.annotations.DiscriminatorFormula;
import org.hibernate.annotations.DiscriminatorOptions;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.query.spi.QueryImplementor;
import org.hibernate.query.spi.SqmQuery;
import org.hibernate.query.sqm.spi.BaseSemanticQueryWalker;
import org.hibernate.query.sqm.tree.domain.SqmCorrelation;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmFromClause;
import org.hibernate.query.sqm.tree.from.SqmRoot;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.hibernate.sql.ast.spi.AbstractSqlAstWalker;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.predicate.ExistsPredicate;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The statement preflight (R-VND-26, AC-VND-21): each strategy's narrowing of a root the statement reads untreated is
 * refused and its anchored form admitted, on the Hibernate line the build runs, with no JDBC statement.
 */
class HibernateTreatPreflightTest {

    @Entity(name = "PfStPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @Table(name = "pf_st_payment")
    static class StPayment {
        @Id Long id;
        String status;
    }

    @Entity(name = "PfStCard")
    static class StCard extends StPayment {
        String number;
    }

    @Entity(name = "PfStVisa")
    static class StVisa extends StCard {
        String network;
    }

    @Entity(name = "PfStBank")
    static class StBank extends StPayment {
        String iban;
    }

    @Entity(name = "PfStHolder")
    static class StHolder {
        @Id Long id;
        @ManyToOne @JoinColumn(name = "main_id") StPayment main;
    }

    @Entity(name = "PfJdPayment")
    @Inheritance(strategy = InheritanceType.JOINED)
    @Table(name = "pf_jd_payment")
    static class JdPayment {
        @Id Long id;
        String status;
    }

    @Entity(name = "PfJdCard")
    @Table(name = "pf_jd_card")
    static class JdCard extends JdPayment {
        String number;
    }

    @Entity(name = "PfJdBank")
    @Table(name = "pf_jd_bank")
    static class JdBank extends JdPayment {
        String iban;
    }

    @Entity(name = "PfJdHolder")
    static class JdHolder {
        @Id Long id;
        @ManyToOne @JoinColumn(name = "main_id") JdPayment main;
    }

    @Entity(name = "PfTpPayment")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    @Table(name = "pf_tp_payment")
    static class TpPayment {
        @Id Long id;
        String status;
    }

    @Entity(name = "PfTpCard")
    @Table(name = "pf_tp_card")
    static class TpCard extends TpPayment {
        String number;
    }

    @Entity(name = "PfTpBank")
    @Table(name = "pf_tp_bank")
    static class TpBank extends TpPayment {
        String iban;
    }

    @Entity(name = "PfTpHolder")
    static class TpHolder {
        @Id Long id;
        @ManyToOne @JoinColumn(name = "main_id") TpPayment main;
    }

    @Entity(name = "PfForcePayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorOptions(force = true)
    @Table(name = "pf_force_payment")
    static class ForcePayment {
        @Id Long id;
        String status;
    }

    @Entity(name = "PfForceCard")
    static class ForceCard extends ForcePayment {
        String number;
    }

    @Entity(name = "PfForceBank")
    static class ForceBank extends ForcePayment {
        String iban;
    }

    @Entity(name = "PfNestPayment")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    @Table(name = "pf_nest_payment")
    static class NestPayment {
        @Id Long id;
        String status;
    }

    @Entity(name = "PfNestCard")
    @Table(name = "pf_nest_card")
    static class NestCard extends NestPayment {
        String number;
    }

    @Entity(name = "PfNestVisa")
    @Table(name = "pf_nest_visa")
    static class NestVisa extends NestCard {
        String network;
    }

    @Entity(name = "PfNestBank")
    @Table(name = "pf_nest_bank")
    static class NestBank extends NestPayment {
        String iban;
    }

    @Entity(name = "PfFormulaPayment")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorFormula("kind")
    @DiscriminatorValue("P")
    @Table(name = "pf_formula_payment")
    static class FormulaPayment {
        @Id Long id;
        String status;
        String kind = "P";
    }

    @Entity(name = "PfFormulaCard")
    @DiscriminatorValue("C")
    static class FormulaCard extends FormulaPayment {
        String number;
    }

    @Entity(name = "PfFormulaVisa")
    @DiscriminatorValue("V")
    static class FormulaVisa extends FormulaCard {
        String network;
    }

    @Entity(name = "PfFormulaBank")
    @DiscriminatorValue("B")
    static class FormulaBank extends FormulaPayment {
        String iban;
    }

    /** One strategy's fixture: its factory, root, subtype with {@code number}, and holder with {@code main}. */
    private record Strategy(String name, SessionFactory factory, Class<?> root, Class<?> card, Class<?> bank,
            Class<?> holder) {}

    private static List<Strategy> strategies;
    private static SessionFactory force;
    /** A {@code TABLE_PER_CLASS} card with a subtype of its own, whose treat keeps the root's table in its union. */
    private static Strategy nested;
    /** A {@code SINGLE_TABLE} hierarchy with a {@code @DiscriminatorFormula}. */
    private static Strategy formula;

    @BeforeAll
    static void start() {
        strategies = List.of(
                new Strategy("SINGLE_TABLE", factory("single", StPayment.class, StCard.class, StVisa.class,
                        StBank.class, StHolder.class), StPayment.class, StCard.class, StBank.class, StHolder.class),
                new Strategy("JOINED", factory("joined", JdPayment.class, JdCard.class, JdBank.class,
                        JdHolder.class), JdPayment.class, JdCard.class, JdBank.class, JdHolder.class),
                new Strategy("TABLE_PER_CLASS", factory("union", TpPayment.class, TpCard.class, TpBank.class,
                        TpHolder.class), TpPayment.class, TpCard.class, TpBank.class, TpHolder.class));
        force = factory("force", ForcePayment.class, ForceCard.class, ForceBank.class);
        nested = new Strategy("TABLE_PER_CLASS nested", factory("nested", NestPayment.class, NestCard.class,
                NestVisa.class, NestBank.class), NestPayment.class, NestCard.class, NestBank.class, null);
        formula = new Strategy("SINGLE_TABLE formula", factory("formula", FormulaPayment.class, FormulaCard.class,
                FormulaVisa.class, FormulaBank.class), FormulaPayment.class, FormulaCard.class, FormulaBank.class,
                null);
    }

    @AfterAll
    static void stop() {
        strategies.forEach(strategy -> strategy.factory().close());
        force.close();
        nested.factory().close();
        formula.factory().close();
    }

    private static SessionFactory factory(String name, Class<?>... entities) {
        Configuration cfg = new Configuration()
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:treat-preflight-" + name)
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                .setProperty(AvailableSettings.GENERATE_STATISTICS, "true");
        for (Class<?> entity : entities) {
            cfg.addAnnotatedClass(entity);
        }
        return cfg.buildSessionFactory();
    }

    /** The preflight of the statement {@code build} makes, which declares no narrowing. */
    private static Optional<String> preflight(SessionFactory factory, StatementShape shape,
            Function<CriteriaBuilder, CriteriaQuery<?>> build) {
        return preflight(factory, shape, List.of(), build);
    }

    /**
     * The preflight of the statement {@code build} makes, which declares the narrowings {@code expected}, asserting it
     * issued no JDBC statement.
     */
    private static Optional<String> preflight(SessionFactory factory, StatementShape shape,
            List<ExpectedNarrowing> expected, Function<CriteriaBuilder, CriteriaQuery<?>> build) {
        var renderer = new HibernateTreatRenderer(factory.unwrap(SessionFactoryImplementor.class));
        var statistics = factory.getStatistics();
        return factory.fromSession(session -> {
            CriteriaQuery<?> query = build.apply(session.getCriteriaBuilder());
            long prepared = statistics.getPrepareStatementCount();
            Optional<String> answer = renderer.preflight(session, query, shape, expected);
            assertThat(statistics.getPrepareStatementCount()).as("JDBC statements prepared").isEqualTo(prepared);
            return answer;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CriteriaQuery<Long> count(CriteriaBuilder cb, Strategy strategy, boolean anchored) {
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root root = query.from(strategy.root());
        return query.select(anchored ? cb.count(root.get("id")) : cb.count(root))
                .where(cb.isNull(cb.treat(root, strategy.card()).get("number")));
    }

    /**
     * {@code root.id} where a sub-select exists, or not, whose root correlates the outer root and is treated as each
     * of {@code targets}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CriteriaQuery<Long> exists(CriteriaBuilder cb, Strategy strategy, boolean negated,
            Class<?>... targets) {
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root root = query.from(strategy.root());
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root correlated = sub.correlate(root);
        List<jakarta.persistence.criteria.Predicate> reads = new ArrayList<>();
        for (Class<?> target : targets) {
            reads.add(cb.isNotNull(cb.treat(correlated, target).get("id")));
        }
        sub.select(cb.literal(1)).where(reads.toArray(jakarta.persistence.criteria.Predicate[]::new));
        jakarta.persistence.criteria.Predicate exists = cb.exists(sub);
        return query.select(root.get("id")).where(negated ? cb.not(exists) : exists);
    }

    /** {@code root.id} where an exists per target holds, each correlating the outer root treated as its target. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CriteriaQuery<Long> eachExists(CriteriaBuilder cb, Strategy strategy, Class<?>... targets) {
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root root = query.from(strategy.root());
        List<jakarta.persistence.criteria.Predicate> each = new ArrayList<>();
        for (Class<?> target : targets) {
            Subquery<Integer> sub = query.subquery(Integer.class);
            Root correlated = sub.correlate(root);
            sub.select(cb.literal(1)).where(cb.isNotNull(cb.treat(correlated, target).get("id")));
            each.add(cb.exists(sub));
        }
        return query.select(root.get("id")).where(each.toArray(jakarta.persistence.criteria.Predicate[]::new));
    }

    /**
     * {@code root.id} and a correlated count over a sub-select's own root, treated as {@code target}, of the rows
     * whose id is the outer root's.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CriteriaQuery<Tuple> counted(CriteriaBuilder cb, Strategy strategy, Class<?> target) {
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root root = query.from(strategy.root());
        Subquery<Long> count = query.subquery(Long.class);
        Root inner = count.from(strategy.root());
        count.select(cb.count(cb.treat(inner, target).get("id"))).where(cb.equal(inner.get("id"), root.get("id")));
        return query.multiselect(root.get("id"), count);
    }

    private static List<ExpectedNarrowing> declared(ExpectedNarrowing.Kind kind, Strategy strategy,
            Class<?>... types) {
        return List.of(new ExpectedNarrowing(kind, strategy.root(), Set.of(types)));
    }

    /** The line adapter that translates on the running line. */
    private static PreflightTranslation line(SessionFactoryImplementor sf) {
        return Stream.of(new Hibernate7Preflight(), new Hibernate6Preflight()).filter(each -> each.probes(sf))
                .findFirst().orElseThrow();
    }

    /** The SQM froms of a statement, sub-queries' included, with their joins and treats. */
    private static List<SqmFrom<?, ?>> froms(SqmSelectStatement<?> sqm) {
        List<SqmFrom<?, ?>> froms = new ArrayList<>();
        sqm.accept(new BaseSemanticQueryWalker() {
            @Override
            public Object visitFromClause(SqmFromClause fromClause) {
                fromClause.getRoots().forEach(root -> collect(root, froms));
                return super.visitFromClause(fromClause);
            }
        });
        return froms;
    }

    private static void collect(SqmFrom<?, ?> from, List<SqmFrom<?, ?>> into) {
        into.add(from);
        from.getSqmJoins().forEach(join -> collect(join, into));
        for (Object treat : from.getSqmTreats()) {
            collect((SqmFrom<?, ?>) treat, into);
        }
    }

    /**
     * The probe D-157's attribution rests on, as measured on 6.6.58 and 7.4.11: the sub-select's own root, the
     * treated correlation of the outer root, has the outer root's navigable path, in the SQL AST and in the SQM alike,
     * so a navigable path alone pairs it with two SQM froms, the outer root and the correlation; and
     * {@code SqmCorrelation#getCorrelatedRoot()} is the correlation itself, its parent the outer root.
     */
    @Test
    void ac_vnd_21_a_sub_select_root_shares_its_navigable_path_with_the_outer_root() {
        for (Strategy strategy : strategies) {
            var sf = strategy.factory().unwrap(SessionFactoryImplementor.class);
            strategy.factory().inSession(session -> {
                QueryImplementor<?> query = session.createQuery(exists(session.getCriteriaBuilder(), strategy,
                        false, strategy.card())).unwrap(QueryImplementor.class);
                var sqm = (SqmSelectStatement<?>) query.unwrap(SqmQuery.class).getSqmStatement();
                SelectStatement ast = line(sf).translate(sf, query, sqm);
                List<ExistsPredicate> exists = new ArrayList<>();
                ast.getQuerySpec().getWhereClauseRestrictions().accept(new AbstractSqlAstWalker() {
                    @Override
                    public void visitExistsPredicate(ExistsPredicate predicate) {
                        exists.add(predicate);
                    }
                });
                assertThat(exists).as(strategy.name()).hasSize(1);
                TableGroup outer = ast.getQuerySpec().getFromClause().getRoots().get(0);
                List<TableGroup> inner = exists.get(0).getExpression().getQuerySpec().getFromClause().getRoots();
                assertThat(inner).as(strategy.name()).hasSize(1);
                assertThat(inner.get(0).getNavigablePath()).as(strategy.name()).isEqualTo(outer.getNavigablePath());
                SqmRoot<?> sqmOuter = sqm.getQuerySpec().getFromClause().getRoots().get(0);
                List<SqmFrom<?, ?>> paired = froms(sqm).stream()
                        .filter(from -> from.getNavigablePath().equals(inner.get(0).getNavigablePath())).toList();
                assertThat(paired).as(strategy.name()).hasSize(2).first().isSameAs(sqmOuter);
                assertThat(paired.get(1)).as(strategy.name()).isInstanceOf(SqmCorrelation.class);
                // The correlation's parent is the outer root; its correlated root is the correlation itself.
                assertThat(paired.get(1).getCorrelationParent()).as(strategy.name()).isSameAs(sqmOuter);
                assertThat(((SqmCorrelation<?, ?>) paired.get(1)).getCorrelatedRoot()).as(strategy.name())
                        .isSameAs(paired.get(1));
                assertThat(paired.get(1).getSqmTreats()).as(strategy.name()).hasSize(1);
            });
        }
    }

    @Test
    void ac_vnd_21_an_unanchored_count_over_is_null_is_refused_and_its_anchored_form_admitted() {
        for (Strategy strategy : strategies) {
            assertThat(preflight(strategy.factory(), StatementShape.COUNT, cb -> count(cb, strategy, false)))
                    .as(strategy.name()).hasValueSatisfying(reason -> assertThat(reason)
                            .contains(strategy.root().getSimpleName()).contains("lost"));
            assertThat(preflight(strategy.factory(), StatementShape.COUNT, cb -> count(cb, strategy, true)))
                    .as(strategy.name()).isEmpty();
        }
    }

    @Test
    void ac_vnd_21_each_strategy_names_how_its_root_was_narrowed() {
        Strategy single = strategies.get(0);
        Strategy joined = strategies.get(1);
        Strategy union = strategies.get(2);
        assertThat(preflight(single.factory(), StatementShape.COUNT, cb -> count(cb, single, false)))
                .hasValue("StPayment is read from a derived table restricted to some of its subtypes, so its other "
                        + "rows are lost");
        assertThat(preflight(joined.factory(), StatementShape.COUNT, cb -> count(cb, joined, false)))
                .hasValue("JdPayment is INNER joined to the table pf_jd_card, so the rows of its other subtypes "
                        + "are lost");
        assertThat(preflight(union.factory(), StatementShape.COUNT, cb -> count(cb, union, false)))
                .hasValue("TpPayment is read from a union without the tables [pf_tp_bank, pf_tp_payment], so "
                        + "their rows are lost");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ac_vnd_21_a_page_shape_reading_the_root_only_through_the_treat_is_refused() {
        for (Strategy strategy : strategies) {
            // A keyset page after a non-NULL cursor value: OR-expanded, anchored by the root id.
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, cb -> {
                CriteriaQuery<Tuple> query = cb.createTupleQuery();
                Root root = query.from(strategy.root());
                Path<String> number = cb.treat(root, strategy.card()).get("number");
                return query.multiselect(number, root.get("id").alias("mq_0id")).where(cb.or(
                        cb.greaterThan(number, "x"), cb.and(cb.equal(number, "x"), cb.gt(root.get("id"), 5))));
            })).as(strategy.name()).isEmpty();
            // After a NULL cursor value: IS NULL AND id > ?, anchored.
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, cb -> {
                CriteriaQuery<Tuple> query = cb.createTupleQuery();
                Root root = query.from(strategy.root());
                Path<String> number = cb.treat(root, strategy.card()).get("number");
                return query.multiselect(number, root.get("id").alias("mq_0id"))
                        .where(cb.isNull(number), cb.gt(root.get("id"), 5));
            })).as(strategy.name()).isEmpty();
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, cb -> {
                CriteriaQuery<Tuple> query = cb.createTupleQuery();
                Root root = query.from(strategy.root());
                return query.multiselect(cb.treat(root, strategy.card()).get("number"));
            })).as(strategy.name()).hasValueSatisfying(reason -> assertThat(reason).contains("lost"));
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ac_vnd_21_a_sub_select_root_is_walked() {
        for (Strategy strategy : strategies) {
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, cb -> {
                CriteriaQuery<Long> query = cb.createQuery(Long.class);
                Root root = query.from(strategy.root());
                Subquery<String> numbers = query.subquery(String.class);
                Root inner = numbers.from(strategy.root());
                numbers.select(cb.treat(inner, strategy.card()).get("number"));
                return query.select(root.get("id")).where(cb.in(root.get("status")).value(numbers));
            })).as(strategy.name()).hasValueSatisfying(reason -> assertThat(reason).contains("lost"));
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, cb -> {
                CriteriaQuery<Long> query = cb.createQuery(Long.class);
                Root root = query.from(strategy.root());
                Subquery<Long> ids = query.subquery(Long.class);
                Root inner = ids.from(strategy.root());
                ids.select(inner.get("id")).where(cb.isNull(cb.treat(inner, strategy.card()).get("number")));
                return query.select(root.get("id")).where(cb.in(root.get("id")).value(ids));
            })).as(strategy.name()).isEmpty();
        }
    }

    @Test
    void ac_vnd_21_a_declared_exists_root_is_expected_and_a_counted_root_is_not_narrowed() {
        var exists = ExpectedNarrowing.Kind.EXISTS_ROOT;
        for (Strategy strategy : strategies) {
            for (boolean negated : new boolean[] {false, true}) {
                assertThat(preflight(strategy.factory(), StatementShape.CONTENT,
                        declared(exists, strategy, strategy.card()),
                        cb -> exists(cb, strategy, negated, strategy.card())))
                        .as(strategy.name() + " negated " + negated).isEmpty();
            }
            // Neither line narrows a correlated count's own treated root, so it passes with no declaration (D-157).
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, List.of(),
                    cb -> counted(cb, strategy, strategy.card()))).as(strategy.name()).isEmpty();
            // Two equal exists are two declarations, each consumed once.
            var twice = new ArrayList<>(declared(exists, strategy, strategy.card()));
            twice.addAll(twice);
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, twice,
                    cb -> eachExists(cb, strategy, strategy.card(), strategy.card()))).as(strategy.name()).isEmpty();
        }
    }

    @Test
    void ac_vnd_21_a_union_keeping_its_root_table_and_a_formula_derived_table_render_the_declared_subtype() {
        var exists = ExpectedNarrowing.Kind.EXISTS_ROOT;
        for (var each : List.of(List.of(nested, NestVisa.class), List.of(formula, FormulaVisa.class))) {
            Strategy strategy = (Strategy) each.get(0);
            Class<?> visa = (Class<?>) each.get(1);
            // A card with a subtype of its own, its subtype and a sibling: each renders as declared.
            for (Class<?> target : List.of(strategy.card(), visa, strategy.bank())) {
                assertThat(preflight(strategy.factory(), StatementShape.CONTENT, declared(exists, strategy, target),
                        cb -> exists(cb, strategy, false, target))).as(strategy.name() + " " + target).isEmpty();
            }
            // Wider, narrower and sibling declarations do not.
            for (Class<?> wrong : List.of(strategy.root(), visa, strategy.bank())) {
                assertThat(preflight(strategy.factory(), StatementShape.CONTENT, declared(exists, strategy, wrong),
                        cb -> exists(cb, strategy, false, strategy.card()))).as(strategy.name() + " " + wrong)
                        .hasValueSatisfying(reason -> assertThat(reason)
                                .contains("no narrowing the engine declared renders it"));
            }
            // The statement's own root stays refused when narrowed, and admitted in its anchored form.
            assertThat(preflight(strategy.factory(), StatementShape.COUNT, cb -> count(cb, strategy, false)))
                    .as(strategy.name()).hasValueSatisfying(reason -> assertThat(reason).contains("lost"));
            assertThat(preflight(strategy.factory(), StatementShape.COUNT, cb -> count(cb, strategy, true)))
                    .as(strategy.name()).isEmpty();
        }
    }

    /**
     * The nested union keeps its root's table only where none of its rows can match (D-157): read with a null id
     * under the card's own column, admitted; read with their id under an inherited column, through a {@code CASE}
     * guard, refused, since an {@code is null} matches them. A leaf never keeps it, and a kept table needs a null id
     * or a {@code clazz_} restriction to exactly the declared subtype.
     */
    @Test
    void ac_vnd_21_a_union_keeps_its_root_table_only_when_none_of_its_rows_can_match() {
        var exists = ExpectedNarrowing.Kind.EXISTS_ROOT;
        for (boolean negated : new boolean[] {false, true}) {
            for (String read : List.of("number", "status", "status is null")) {
                Optional<String> verdict = preflight(nested.factory(), StatementShape.CONTENT,
                        declared(exists, nested, NestCard.class), cb -> treatedRead(cb, negated, read));
                if (read.equals("number")) {
                    assertThat(verdict).as(read).isEmpty();
                } else {
                    assertThat(verdict).as(read).hasValueSatisfying(reason -> assertThat(reason)
                            .contains("no narrowing the engine declared renders it")
                            .contains("pf_nest_bank"));
                }
            }
        }
        var sf = nested.factory().unwrap(SessionFactoryImplementor.class);
        var payment = (AbstractEntityPersister) sf.getMappingMetamodel().getEntityDescriptor(NestPayment.class);
        String nullId = "(select null as id, null as status, 0 as clazz_ from pf_nest_payment union all select id, "
                + "status, 1 as clazz_ from pf_nest_card union all select id, status, 2 as clazz_ from pf_nest_visa)";
        String castId = nullId.replace("null as id", "cast(null as bigint) as id");
        String keptId = nullId.replace("null as id, null as status", "id, status");
        Set<String> card = Set.of("pf_nest_payment", "pf_nest_card", "pf_nest_visa");
        for (String union : List.of(nullId, castId)) {
            assertThat(TreatPreflight.rendered(payment, NestCard.class, card, union, () -> false)).isEqualTo(card);
        }
        assertThat(TreatPreflight.rendered(payment, NestCard.class, card, keptId, () -> false))
                .containsExactlyInAnyOrder("pf_nest_card", "pf_nest_visa");
        assertThat(TreatPreflight.rendered(payment, NestCard.class, card, keptId, () -> true)).isEqualTo(card);
        // A leaf has no subtype of its own: a union keeping the root's table beside it renders no declaration.
        String leaf = "(select null as id, null as status, 0 as clazz_ from pf_nest_payment union all select id, "
                + "status, 2 as clazz_ from pf_nest_visa)";
        assertThat(TreatPreflight.rendered(payment, NestVisa.class, Set.of("pf_nest_payment", "pf_nest_visa"), leaf,
                () -> true)).containsExactly("pf_nest_visa");
    }

    /** {@code root.id} where a sub-select exists, or not, reading the correlated root treated as a nested card. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CriteriaQuery<Long> treatedRead(CriteriaBuilder cb, boolean negated, String read) {
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root root = query.from(NestPayment.class);
        Subquery<Integer> sub = query.subquery(Integer.class);
        Path<String> column = cb.treat(sub.correlate(root), NestCard.class).get(read.split(" ")[0]);
        sub.select(cb.literal(1)).where(read.endsWith("null") ? cb.isNull(column) : cb.equal(column, "x"));
        jakarta.persistence.criteria.Predicate exists = cb.exists(sub);
        return query.select(root.get("id")).where(negated ? cb.not(exists) : exists);
    }

    @Test
    void ac_vnd_21_a_sub_select_root_narrowed_other_than_declared_is_refused() {
        var exists = ExpectedNarrowing.Kind.EXISTS_ROOT;
        for (Strategy strategy : strategies) {
            String name = strategy.name();
            // Wider, sibling: no declaration renders the card narrowing.
            for (List<ExpectedNarrowing> wrong : List.of(declared(exists, strategy, strategy.root()),
                    declared(exists, strategy, strategy.bank()))) {
                assertThat(preflight(strategy.factory(), StatementShape.CONTENT, wrong,
                        cb -> exists(cb, strategy, false, strategy.card()))).as(name + " " + wrong)
                        .hasValueSatisfying(reason -> assertThat(reason).contains("EXISTS_ROOT sub-select root")
                                .contains("no narrowing the engine declared renders it"));
            }
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT,
                    declared(exists, strategy, strategy.card()), cb -> counted(cb, strategy, strategy.card())))
                    .as(name).hasValueSatisfying(reason -> assertThat(reason).contains("EXISTS_ROOT narrowing"));
            // A missing declaration, and one left unconsumed.
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT,
                    cb -> exists(cb, strategy, false, strategy.card()))).as(name)
                    .hasValueSatisfying(reason -> assertThat(reason).contains("lost"));
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT,
                    declared(exists, strategy, strategy.card()), cb -> count(cb, strategy, true))).as(name)
                    .hasValue("the engine declared a EXISTS_ROOT narrowing of " + strategy.root().getSimpleName()
                            + " as [" + strategy.card().getSimpleName() + "] that no sub-select root of the "
                            + "statement renders");
            // Two equal exists with one declaration: the second exists consumes none.
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT,
                    declared(exists, strategy, strategy.card()),
                    cb -> eachExists(cb, strategy, strategy.card(), strategy.card()))).as(name)
                    .hasValueSatisfying(reason -> assertThat(reason).contains("EXISTS_ROOT"));
            // One root treated as two subtypes at once, and two exists treating different subtypes.
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT,
                    declared(exists, strategy, strategy.card(), strategy.bank()),
                    cb -> exists(cb, strategy, false, strategy.card(), strategy.bank()))).as(name)
                    .hasValueSatisfying(reason -> assertThat(reason).contains("at once"));
            var apart = new ArrayList<>(declared(exists, strategy, strategy.card()));
            apart.addAll(declared(exists, strategy, strategy.bank()));
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, apart,
                    cb -> eachExists(cb, strategy, strategy.card(), strategy.bank()))).as(name)
                    .hasValueSatisfying(reason -> assertThat(reason).contains("cannot attribute"));
        }
        // Narrower: a declared subtype below the rendered one.
        Strategy single = strategies.get(0);
        assertThat(preflight(single.factory(), StatementShape.CONTENT, declared(exists, single, StVisa.class),
                cb -> exists(cb, single, false, StCard.class)))
                .hasValueSatisfying(reason -> assertThat(reason).contains("no narrowing the engine declared"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ac_vnd_21_a_narrowing_the_engine_cannot_declare_is_refused_even_when_declared() {
        var exists = ExpectedNarrowing.Kind.EXISTS_ROOT;
        for (Strategy strategy : strategies) {
            List<ExpectedNarrowing> declared = declared(exists, strategy, strategy.card());
            // CASE WHEN EXISTS: the exists is inside an expression, not an operand of the where.
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, declared, cb -> {
                CriteriaQuery<Integer> query = cb.createQuery(Integer.class);
                Root root = query.from(strategy.root());
                Subquery<Integer> sub = query.subquery(Integer.class);
                Root correlated = sub.correlate(root);
                sub.select(cb.literal(1)).where(cb.isNotNull(cb.treat(correlated, strategy.card()).get("id")));
                return query.select(cb.<Integer>selectCase().when(cb.exists(sub), 1).otherwise(0));
            })).as(strategy.name()).hasValueSatisfying(reason -> assertThat(reason).contains("lost"));
            // An in sub-select's own root.
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, declared, cb -> {
                CriteriaQuery<Long> query = cb.createQuery(Long.class);
                Root root = query.from(strategy.root());
                Subquery<String> numbers = query.subquery(String.class);
                Root inner = numbers.from(strategy.root());
                numbers.select(cb.treat(inner, strategy.card()).get("number"));
                return query.select(root.get("id")).where(cb.in(root.get("status")).value(numbers));
            })).as(strategy.name()).hasValueSatisfying(reason -> assertThat(reason).contains("lost"));
            // An exists correlating a join, not the parent's root: its root renders no narrowing to consume one.
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, declared, cb -> {
                CriteriaQuery<Long> query = cb.createQuery(Long.class);
                Root holder = query.from(strategy.holder());
                Join main = holder.join("main");
                Subquery<Integer> sub = query.subquery(Integer.class);
                Join correlated = sub.correlate(main);
                sub.select(cb.literal(1)).where(cb.isNotNull(cb.treat(correlated, strategy.card()).get("id")));
                return query.select(holder.get("id")).where(cb.exists(sub));
            })).as(strategy.name()).hasValueSatisfying(reason -> assertThat(reason)
                    .contains("that no sub-select root of the statement renders"));
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ac_vnd_21_treated_joins_and_a_subtype_root_are_expected_restrictions() {
        for (Strategy strategy : strategies) {
            for (JoinType type : List.of(JoinType.LEFT, JoinType.INNER)) {
                assertThat(preflight(strategy.factory(), StatementShape.CONTENT, cb -> {
                    CriteriaQuery<Tuple> query = cb.createTupleQuery();
                    Root holder = query.from(strategy.holder());
                    Join main = holder.join("main", type);
                    return query.multiselect(holder.get("id"))
                            .where(cb.isNull(cb.treat(main, strategy.card()).get("number")));
                })).as(strategy.name() + " " + type).isEmpty();
            }
            assertThat(preflight(strategy.factory(), StatementShape.COUNT, cb -> {
                CriteriaQuery<Long> query = cb.createQuery(Long.class);
                Root card = query.from(strategy.card());
                return query.select(cb.count(card)).where(cb.isNull(card.get("number")));
            })).as(strategy.name()).isEmpty();
        }
    }

    @Test
    void ac_vnd_21_a_treat_beside_a_subtype_root_is_refused() {
        SessionFactory single = strategies.get(0).factory();
        assertThat(preflight(single, StatementShape.COUNT, cb -> {
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<StCard> card = query.from(StCard.class);
            return query.select(cb.count(card)).where(cb.isNull(cb.treat(card, StVisa.class).get("network")));
        })).hasValueSatisfying(reason -> assertThat(reason).startsWith("StCard is read from a derived table"));
    }

    @Test
    void ac_vnd_21_a_discriminator_restriction_that_leaves_out_a_subtype_is_refused() {
        SessionFactory single = strategies.get(0).factory();
        // A forced discriminator restricts every statement to all of the hierarchy's values: expected.
        assertThat(preflight(force, StatementShape.COUNT, cb -> {
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<ForcePayment> root = query.from(ForcePayment.class);
            return query.select(cb.count(root.get("id")))
                    .where(cb.isNull(cb.treat(root, ForceCard.class).get("number")));
        })).isEmpty();
        assertThat(preflight(force, StatementShape.COUNT, cb -> {
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<ForcePayment> root = query.from(ForcePayment.class);
            return query.select(cb.count(root)).where(cb.isNull(cb.treat(root, ForceCard.class).get("number")));
        })).hasValueSatisfying(reason -> assertThat(reason).contains("derived table"));
        // A top-level type restriction the statement didn't attribute (ofType lands in M28.5): to a literal type,
        // and to a bound one, whose value the walk can't read.
        assertThat(preflight(single, StatementShape.COUNT, cb -> {
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<StPayment> root = query.from(StPayment.class);
            return query.select(cb.count(root.get("id"))).where(cb.equal(root.type(), cb.literal(StCard.class)));
        })).hasValue("StPayment is restricted by its discriminator, so the rows of [StBank, StPayment, StVisa] "
                + "are lost");
        assertThat(preflight(single, StatementShape.COUNT, cb -> {
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<StPayment> root = query.from(StPayment.class);
            return query.select(cb.count(root.get("id"))).where(cb.equal(root.type(), StCard.class));
        })).hasValue("StPayment's discriminator is restricted to a value that names no entity, a narrowing the "
                + "preflight cannot attribute");
        // A discriminator test under an OR can't be attributed at all.
        assertThat(preflight(single, StatementShape.COUNT, cb -> {
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<StPayment> root = query.from(StPayment.class);
            return query.select(cb.count(root.get("id")))
                    .where(cb.or(cb.equal(root.type(), StCard.class), cb.equal(root.get("status"), "x")));
        })).hasValueSatisfying(reason -> assertThat(reason).contains("cannot attribute"));
        // TABLE_PER_CLASS's clazz_, JOINED's CASE and a formula are discriminators too: a type restriction leaving a
        // subtype out is refused, under an OR it can't be attributed, and one admitting every subtype is expected.
        for (Strategy strategy : List.of(nested, strategies.get(1), formula)) {
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, cb -> typed(cb, strategy, false,
                    strategy.card()))).as(strategy.name()).hasValueSatisfying(reason -> assertThat(reason)
                            .containsAnyOf("lost", "cannot attribute"));
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, cb -> typed(cb, strategy, true,
                    strategy.card()))).as(strategy.name()).hasValueSatisfying(reason -> assertThat(reason)
                            .contains("tested outside a top-level restriction"));
        }
        assertThat(preflight(formula.factory(), StatementShape.CONTENT, cb -> typed(cb, formula, false,
                FormulaCard.class))).hasValue("FormulaPayment is restricted by its discriminator, so the rows of "
                        + "[FormulaBank, FormulaPayment, FormulaVisa] are lost");
        for (Strategy strategy : List.of(nested, formula)) {
            Class<?> visa = strategy == nested ? NestVisa.class : FormulaVisa.class;
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, cb -> typed(cb, strategy, false,
                    strategy.root(), strategy.card(), visa, strategy.bank()))).as(strategy.name()).isEmpty();
        }
    }

    /** The concrete entities an {@code ofType(card)} renders: the card and its own subtype, if it has one. */
    private static Class<?>[] cards(Strategy strategy) {
        return strategy.card() == StCard.class ? new Class<?>[] {StCard.class, StVisa.class}
                : strategy == nested ? new Class<?>[] {NestCard.class, NestVisa.class}
                : strategy == formula ? new Class<?>[] {FormulaCard.class, FormulaVisa.class}
                : new Class<?>[] {strategy.card()};
    }

    /**
     * {@code root.id} of a treated statement, its card's {@code number} null, restricted as the engine renders a
     * top-level {@code ofType} to {@code types}: a {@code TYPE IN} of their literals, here or in an {@code exists}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CriteriaQuery<Long> ofType(CriteriaBuilder cb, Strategy strategy, boolean inExists,
            Class<?>... types) {
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root root = query.from(strategy.root());
        var treated = cb.isNull(cb.treat(root, strategy.card()).get("number"));
        if (types.length == 0) {
            return query.select(root.get("id")).where(treated);
        }
        Root typed = root;
        Subquery<Long> sub = null;
        if (inExists) {
            sub = query.subquery(Long.class);
            typed = sub.from(strategy.root());
        }
        var in = typed.type().in(Stream.of(types).map(cb::literal)
                .toArray(jakarta.persistence.criteria.Expression[]::new));
        if (sub != null) {
            sub.select(typed.get("id")).where(in, cb.equal(typed.get("id"), root.get("id")));
            return query.select(root.get("id")).where(treated, cb.exists(sub));
        }
        return query.select(root.get("id")).where(treated, in);
    }

    private static List<Strategy> typedStrategies() {
        return Stream.concat(strategies.stream(), Stream.of(nested, formula)).toList();
    }

    @Test
    void ac_flt_30_a_declared_type_restriction_is_expected_and_needs_no_narrowing() {
        var restriction = ExpectedNarrowing.Kind.TYPE_RESTRICTION;
        for (Strategy strategy : typedStrategies()) {
            Class<?>[] cards = cards(strategy);
            // Undeclared, the restriction loses the other subtypes' rows.
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, cb -> ofType(cb, strategy, false,
                    cards))).as(strategy.name()).isPresent();
            // 6.6 renders JOINED's type with no column naming its root, so it stays unattributed there.
            boolean unattributed = strategy.root() == JdPayment.class
                    && line(strategy.factory().unwrap(SessionFactoryImplementor.class)) instanceof Hibernate6Preflight;
            for (StatementShape shape : List.of(StatementShape.CONTENT, StatementShape.COUNT)) {
                var answer = assertThat(preflight(strategy.factory(), shape, declared(restriction, strategy, cards),
                        cb -> ofType(cb, strategy, false, cards))).as(strategy.name() + " " + shape);
                if (unattributed) {
                    answer.hasValue("a discriminator restriction reads no root of its query the preflight can "
                            + "attribute it to");
                } else {
                    answer.isEmpty();
                }
            }
            // A declaration whose node the statement doesn't narrow is no refusal: the provider need not narrow it.
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, declared(restriction, strategy, cards),
                    cb -> ofType(cb, strategy, false))).as(strategy.name()).isEmpty();
        }
    }

    @Test
    void ac_flt_30_a_narrowing_no_declared_type_restriction_explains_is_refused() {
        var restriction = ExpectedNarrowing.Kind.TYPE_RESTRICTION;
        for (Strategy strategy : typedStrategies()) {
            Class<?>[] cards = cards(strategy);
            // Declared as the bank, rendered as the card: the bank's rows are lost.
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT,
                    declared(restriction, strategy, strategy.bank()), cb -> ofType(cb, strategy, false, cards)))
                    .as(strategy.name()).isPresent();
            // A declaration of the statement's own where never explains a sub-select's restriction.
            assertThat(preflight(strategy.factory(), StatementShape.CONTENT, declared(restriction, strategy, cards),
                    cb -> ofType(cb, strategy, true, cards))).as(strategy.name()).isPresent();
        }
        // Two restrictions of one node to different types are two ofType conjuncts, not a conflict.
        Strategy single = strategies.get(0);
        assertThat(preflight(single.factory(), StatementShape.CONTENT,
                List.of(new ExpectedNarrowing(restriction, StPayment.class, Set.of(StCard.class, StVisa.class)),
                        new ExpectedNarrowing(restriction, StPayment.class, Set.of(StVisa.class))),
                cb -> ofType(cb, single, false, StCard.class, StVisa.class))).isEmpty();
    }

    /** {@code root.id} whose type is one of {@code types}, alone or in an {@code or} with its status. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CriteriaQuery<Long> typed(CriteriaBuilder cb, Strategy strategy, boolean or, Class<?>... types) {
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root root = query.from(strategy.root());
        jakarta.persistence.criteria.Predicate typed = types.length == 1
                ? cb.equal(root.type(), cb.literal(types[0]))
                : root.type().in(Stream.of(types).map(cb::literal)
                        .toArray(jakarta.persistence.criteria.Expression[]::new));
        return query.select(root.get("id")).where(or ? cb.or(typed, cb.equal(root.get("status"), "x")) : typed);
    }

    @Test
    void ac_vnd_21_an_unrecognised_shape_is_refused() {
        // A root the walk has no rule for: neither an entity, a CTE nor a derived table.
        InvocationHandler nothing = (proxy, method, arguments) -> method.isDefault()
                ? InvocationHandler.invokeDefault(proxy, method, arguments) : null;
        TableGroup unknown = (TableGroup) Proxy.newProxyInstance(TableGroup.class.getClassLoader(),
                new Class<?>[] {TableGroup.class}, nothing);
        QuerySpec spec = new QuerySpec(true);
        spec.getFromClause().addRoot(unknown);
        assertThat(TreatPreflight.walk(new SelectStatement(spec), List.of())).hasValueSatisfying(reason ->
                assertThat(reason).contains("root is a shape the preflight does not recognise"));
        // A write is never preflighted, whatever its shape says.
        SessionFactory single = strategies.get(0).factory();
        var renderer = new HibernateTreatRenderer(single.unwrap(SessionFactoryImplementor.class));
        single.inSession(session -> {
            CriteriaUpdate<StPayment> update = session.getCriteriaBuilder().createCriteriaUpdate(StPayment.class);
            update.from(StPayment.class);
            update.set("status", "x");
            assertThat(renderer.preflight(session, update, StatementShape.WRITE, List.of()))
                    .hasValue("only a select statement is preflighted");
            CriteriaQuery<Long> query = session.getCriteriaBuilder().createQuery(Long.class);
            query.select(session.getCriteriaBuilder().count(query.from(StPayment.class)));
            assertThat(renderer.preflight(session, query, StatementShape.WRITE, List.of()))
                    .hasValue("only a select statement is preflighted");
        });
    }

    @Test
    void ac_vnd_21_exactly_one_line_adapter_translates_on_the_running_line() {
        var sf = force.unwrap(SessionFactoryImplementor.class);
        boolean hibernate6 = new Hibernate6Preflight().probes(sf);
        assertThat(new Hibernate7Preflight().probes(sf)).isNotEqualTo(hibernate6);
        String tested = hibernate6 ? new Hibernate6Preflight().tested() : new Hibernate7Preflight().tested();
        assertThat(Version.getVersionString()).startsWith(tested.substring(0, 4));
    }

    @Test
    void ac_vnd_21_a_preflighted_statement_still_runs_and_each_call_walks_it_again() {
        Strategy single = strategies.get(0);
        var renderer = new HibernateTreatRenderer(single.factory().unwrap(SessionFactoryImplementor.class));
        single.factory().inTransaction(session -> {
            StPayment plain = new StPayment();
            plain.id = 1L;
            StCard card = new StCard();
            card.id = 2L;
            StCard numbered = new StCard();
            numbered.id = 3L;
            numbered.number = "4111";
            List.of(plain, card, numbered).forEach(session::persist);
        });
        single.factory().inSession(session -> {
            CriteriaQuery<Long> anchored = count(session.getCriteriaBuilder(), single, true);
            assertThat(renderer.preflight(session, anchored, StatementShape.COUNT, List.of())).isEmpty();
            assertThat(renderer.preflight(session, anchored, StatementShape.COUNT, List.of())).isEmpty();
            // The plain payment's number reads NULL through the treat, so it counts (R-COL-35).
            assertThat(session.createQuery(anchored).getSingleResult()).isEqualTo(2L);
        });
        single.factory().inTransaction(session -> session.createMutationQuery("delete from PfStPayment")
                .executeUpdate());
    }
}
