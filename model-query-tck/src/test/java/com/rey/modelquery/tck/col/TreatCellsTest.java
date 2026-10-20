package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Correlated;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.StatementShape;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.TreatChecks;
import com.rey.modelquery.core.TreatRenderer;
import com.rey.modelquery.core.TreatSite;
import com.rey.modelquery.core.TreatUse;
import com.rey.modelquery.hibernate.HibernateProviderSupport;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.inheritance.CellEntities;
import com.rey.modelquery.tck.inheritance.VariantEntities;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.Version;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/**
 * Hibernate's own treat renderer over {@link CellEntities}' three strategies and {@link VariantEntities}' mapping
 * variants, on each line and Tier-1 vendor: every admitted cell runs with its ids, values, row count and total, and the
 * renderer probed with synthesized sites admits exactly the use sets the cases ran (AC-VND-19); the read rules of
 * R-COL-34 and R-COL-35 hold on real rows (AC-COL-44, 45, 46, 50, 51, 52).
 *
 * <p>Rows: payment 1; cards 2 ({@code 1111}, owned by holder 14) and 3 (no number); bank 4; premium 5 ({@code 5555},
 * tier {@code gold}; on {@code SINGLE_TABLE} code {@code C5}). Holders 10 to 15 reach payments 1, 2, 4, 5, none and
 * 3; holders 10 and 11 are card 2's, 13 is card 5's.
 */
class TreatCellsTest {

    /** A mapping variant's runs measured no root-id count: a case counting one of these sets is refused there. */
    private static final Set<Set<TreatUse>> ROOT_ID_COUNTED =
            uses("NULL_TEST", "NULL_TEST+NEGATED", "PREDICATE+NULL_TEST+DISJUNCTION");

    /**
     * Every use set of at most four uses without {@code TYPE}, the candidates a renderer's treat table is probed with
     * (AC-VND-19).
     */
    private static final List<Set<TreatUse>> CANDIDATES = candidates(false);

    /** Every use set with {@code TYPE}, of any size, the candidates its type table is probed with (AC-COL-49). */
    private static final List<Set<TreatUse>> TYPE_CANDIDATES = candidates(true);

    @TckTest
    void ac_vnd_19_every_admitted_cell_runs_per_strategy_with_its_values_rows_and_total(TckDatabase db) {
        for (Tree<?, ?, ?> tree : trees()) {
            tree.seeded(db, (em, renderer) -> {
                var ran = new HashMap<String, Set<Set<TreatUse>>>();
                for (Case each : tree.cells()) {
                    run(em, renderer, tree, each, ran);
                }
                for (Case each : tree.shared()) {
                    run(em, renderer, tree, each, ran);
                }
                tree.assertEveryAdmittedCellRan(renderer, ran);
            });
            // A correlated count of a left treated join from an untreated sub-select root reads no anchored root.
            tree.refusedBeforeAnyStatement(db, executor ->
                    assertTreatRefused(() -> executor.list(raw(tree.counted(true)), Limit.of(5))));
        }
    }

    @TckTest
    void ac_vnd_19_every_admitted_cell_runs_on_each_mapping_variant(TckDatabase db) {
        for (Tree<?, ?, ?> tree : variants()) {
            tree.seeded(db, (em, renderer) -> {
                var ran = new HashMap<String, Set<Set<TreatUse>>>();
                for (Case each : tree.cells()) {
                    run(em, renderer, tree, each, ran);
                }
                tree.assertEveryAdmittedCellRan(renderer, ran);
            });
        }
    }

    @TckTest
    void ac_col_44_a_treated_column_reads_subtype_and_grandchild_values_and_null_on_siblings(TckDatabase db) {
        for (Tree<?, ?, ?> tree : trees()) {
            tree.seeded(db, (em, renderer) -> {
                for (Case each : tree.reads()) {
                    run(em, renderer, tree.name, each);
                }
                // A keyless model selects the root id as mq_0id and reads the same rows.
                var keyless = executor(em, tree.keyless());
                assertThat(keyless.list(raw(tree.keyless()), Limit.of(20))).as(tree.name)
                        .containsExactly("1:null", "2:1111", "3:null", "4:null", "5:5555");
                // A subtype's to-one through a treated join would join INNER, whatever the column's type: MQ1001.
                for (Class<?> type : List.of(Long.class, tree.holder)) {
                    var owner = tree.holderPairs(ColumnField.of(String.class, tree.leftCards, "owner", type));
                    assertThatThrownBy(() -> executor(em, owner).list(raw(owner), Limit.of(5))).as(tree.name)
                            .isExactlyInstanceOf(ModelQueryDefinitionException.class)
                            .hasMessageContaining("join it with a TableField")
                            .extracting(failure -> ((ModelQueryDefinitionException) failure).code())
                            .isEqualTo(MqCode.MQ1001);
                }
                // An attribute neither the declared target nor the subtype has is still MQ1002. On SINGLE_TABLE the
                // renderer, which checks first, can't tell whether a sibling shares its column: MQ4016 naming it.
                var missing = tree.holderPairs(ColumnField.of(String.class, tree.leftCards, "missing", Long.class));
                if (tree.sharedAttributes.isEmpty()) {
                    assertThatThrownBy(() -> executor(em, missing).list(raw(missing), Limit.of(5))).as(tree.name)
                            .isExactlyInstanceOf(ModelQueryDefinitionException.class)
                            .extracting(failure -> ((ModelQueryDefinitionException) failure).code())
                            .isEqualTo(MqCode.MQ1002);
                } else {
                    assertThatThrownBy(() -> executor(em, missing).list(raw(missing), Limit.of(5))).as(tree.name)
                            .isExactlyInstanceOf(ModelQueryConfigurationException.class)
                            .hasMessageContaining("missing maps no column")
                            .extracting(failure -> ((ModelQueryConfigurationException) failure).code())
                            .isEqualTo(MqCode.MQ4016);
                }
            });
        }
    }

    @TckTest
    void ac_col_45_operators_keep_only_matching_subtype_rows_or_include_siblings_as_sql_does(TckDatabase db) {
        for (Tree<?, ?, ?> tree : trees()) {
            tree.seeded(db, (em, renderer) -> {
                for (Case each : tree.operators()) {
                    run(em, renderer, tree.name, each);
                }
            });
        }
    }

    @TckTest
    void ac_col_46_nulls_order_places_siblings_and_a_treated_group_key_is_refused_before_any_statement(
            TckDatabase db) {
        for (Tree<?, ?, ?> tree : trees()) {
            tree.seeded(db, (em, renderer) -> {
                run(em, renderer, tree.name, tree.ordered("nulls first", tree.number.asc().nullsFirst(),
                        List.of("1", "3", "4", "2", "5")));
                run(em, renderer, tree.name, tree.ordered("nulls last", tree.number.desc().nullsLast(),
                        List.of("5", "2", "1", "3", "4")));
            });
            // The source case lands with sources over a treated root (M28.8).
            tree.refusedBeforeAnyStatement(db, executor -> {
                assertTreatRefused(() -> executor.list(raw(tree.groupedByNumber()), Limit.of(5)));
                assertTreatRefused(() -> executor.list(raw(tree.countingNumbers()), Limit.of(5)));
                assertTreatRefused(() -> executor.count(raw(tree.groupedByNumber())));
            });
        }
    }

    @TckTest
    void ac_col_50_a_mid_level_subtype_root_reads_its_rows_and_its_grandchild_rows_only(TckDatabase db) {
        for (Tree<?, ?, ?> tree : trees()) {
            tree.seeded(db, (em, renderer) -> tree.subtypeRoot(em));
        }
    }

    @TckTest
    void ac_col_51_a_single_table_column_a_sibling_maps_reads_null_on_the_siblings_rows(TckDatabase db) {
        Tree<CellEntities.StPayment, CellEntities.StCard, CellEntities.StHolder> tree = singleTable();
        var code = ColumnField.of(String.class, tree.cards, "code", String.class);
        var alpha = ColumnField.of(String.class, tree.cards, "alpha", String.class);
        tree.seeded(db, (em, renderer) -> {
            Set<Set<TreatUse>> none = Set.of();
            run(em, renderer, tree.name, new Case("same-named select", tree.pairs(code), List.of("1:null", "2:C2",
                    "3:null", "4:null", "5:C5"), uses("SELECT"), none));
            run(em, renderer, tree.name, new Case("differently named select", tree.pairs(alpha), List.of("1:null",
                    "2:A2", "3:null", "4:null", "5:null"), uses("SELECT"), none));
            run(em, renderer, tree.name, tree.ordered("same-named order", code.asc().nullsLast(),
                    List.of("2", "5", "1", "3", "4")));
            // Only the bank maps B4 and B4b; through the card they are NULL, so nothing matches.
            for (var where : List.<UnaryOperator<Filters<String>>>of(f -> f.eq(code, "B4"), f -> f.eq(alpha, "B4b"))) {
                var query = tree.ids(where);
                assertThat(executor(em, query).list(raw(query), Limit.of(5))).as(tree.name).isEmpty();
            }
            var orCount = tree.ids(f -> f.or(a -> a.eq(code, "C2"), b -> b.eq(tree.status, "s4")));
            assertThat(executor(em, orCount).count(raw(orCount))).isEqualTo(2);
        });
        // A standalone shared-column predicate's count was not measured: refused before any statement.
        tree.refusedBeforeAnyStatement(db, executor -> {
            assertTreatRefused(() -> executor.count(raw(tree.ids(f -> f.eq(code, "C2")))));
            assertTreatRefused(() -> executor.page(raw(tree.ids(f -> f.isNull(alpha))), PageSpec.of(0, 2),
                    CountMode.COUNT));
        });
    }

    @TckTest
    void ac_col_52_totals_and_counts_equal_the_content_rows_through_the_real_renderer(TckDatabase db) {
        for (Tree<?, ?, ?> tree : trees()) {
            tree.seeded(db, (em, renderer) -> {
                for (Case each : tree.totals()) {
                    run(em, renderer, tree.name, each);
                }
            });
        }
    }

    @TckTest
    void ac_col_49_type_of_selects_matches_groups_and_orders_by_the_exact_class(TckDatabase db) {
        List<Tree<?, ?, ?>> typed = new ArrayList<>(trees());
        typed.add(variants().stream().filter(tree -> tree.name.equals("joined-explicit")).findFirst().orElseThrow());
        for (Tree<?, ?, ?> tree : typed) {
            tree.seeded(db, (em, renderer) -> {
                for (Case each : tree.types()) {
                    run(em, renderer, tree.name, each);
                }
                if (tree.typeOrdered) {
                    tree.repeatedTypeOrder(em);
                }
            });
            assertThatThrownBy(() -> ModelQuery.builder(tree.root, row -> String.valueOf(row.get(tree.id)))
                    .select(SelectSet.of(tree.id)).primaryKey(PrimaryKey.of(tree.id))
                    .orderBy(tree.type.asc(), tree.id.asc()).keyset().build()).as(tree.name)
                    .isExactlyInstanceOf(ModelQueryDefinitionException.class)
                    .extracting(failure -> ((ModelQueryDefinitionException) failure).code()).isEqualTo(MqCode.MQ1208);
            tree.refusedBeforeAnyStatementIn(db, em -> {
                // An absent LEFT row has no type to read: the provider fails on it rather than reading NULL.
                var leftTyped = tree.holderPairs(tree.leftType);
                assertTreatRefused(() -> executor(em, leftTyped).list(raw(leftTyped), Limit.of(5)));
                var leftOrdered = tree.holderIds(f -> f, tree.leftType.asc());
                assertTreatRefused(() -> executor(em, leftOrdered).list(raw(leftOrdered), Limit.of(5)));
                if (!tree.typeOrdered) {
                    // Without a discriminator JOINED renders order by case ... end, invalid SQL (R-COL-36).
                    var ordered = tree.typeOrder();
                    assertTreatRefused(() -> executor(em, ordered).list(raw(ordered), Limit.of(5)));
                    assertTreatRefused(() -> executor(em, ordered).page(raw(ordered), PageSpec.of(0, 2),
                            CountMode.NO_COUNT));
                }
            });
        }
    }

    @TckTest
    void ac_flt_30_of_type_keeps_subtypes_and_grandchildren_and_its_empty_forms_skip_or_keep_nothing(
            TckDatabase db) {
        for (Tree<?, ?, ?> tree : trees()) {
            tree.seeded(db, (em, renderer) -> tree.ofTypes(em));
        }
    }

    @TckTest
    void ac_flt_31_not_of_type_keeps_absent_left_joins_and_its_empty_forms_keep_every_row(TckDatabase db) {
        for (Tree<?, ?, ?> tree : trees()) {
            tree.seeded(db, (em, renderer) -> tree.notOfTypes(em));
        }
    }

    // ---- running a case

    /**
     * One definition: the rows its content returns, the use sets of its {@code CONTENT} sites, and those of its
     * {@code COUNT} sites, or {@code null} when its count is refused ({@code MQ4016}); {@code unordered} when the
     * content's order is unspecified, as a group's or a type order's is; {@code clientSide} when the renderer refuses
     * the count of its groups ({@code GROUPED_COUNT}) and the engine counts them client-side, its {@code count} sites
     * then checked as {@code GROUPED} (D-159).
     */
    private record Case(String name, ModelQuery<?, ?, String> query, List<String> rows, Set<Set<TreatUse>> content,
            Set<Set<TreatUse>> count, boolean unordered, boolean clientSide) {

        Case(String name, ModelQuery<?, ?, String> query, List<String> rows, Set<Set<TreatUse>> content,
                Set<Set<TreatUse>> count) {
            this(name, query, rows, content, count, false);
        }

        Case(String name, ModelQuery<?, ?, String> query, List<String> rows, Set<Set<TreatUse>> content,
                Set<Set<TreatUse>> count, boolean unordered) {
            this(name, query, rows, content, count, unordered, false);
        }
    }

    private static void run(EntityManager em, TreatRenderer renderer, String tree, Case each) {
        try {
            check(em, renderer, tree + " " + each.name, each);
        } catch (RuntimeException e) {
            throw new AssertionError(tree + " " + each.name + ": " + e.getMessage(), e);
        }
    }

    /**
     * Runs {@code each} on {@code tree}, recording its sites' use sets in {@code ran} by kind, shape and anchoring. A
     * mapping variant's runs measured no root-id count, so there a case counting a null-accepting set expects its
     * count refused.
     */
    private static void run(EntityManager em, TreatRenderer renderer, Tree<?, ?, ?> tree, Case each,
            Map<String, Set<Set<TreatUse>>> ran) {
        Case adjusted = tree.variant && each.count != null && each.count.stream().anyMatch(ROOT_ID_COUNTED::contains)
                ? new Case(each.name, each.query, each.rows, each.content, null) : each;
        run(em, renderer, tree.name, adjusted);
        List<StatementShape> shapes = adjusted.count == null ? List.of(StatementShape.CONTENT)
                : List.of(StatementShape.CONTENT, adjusted.clientSide ? StatementShape.GROUPED : StatementShape.COUNT);
        for (StatementShape shape : shapes) {
            for (TreatSite site : TreatChecks.check(adjusted.query, RenderOptions.portable()
                    .withTreatRenderer(renderer), em.getMetamodel(), shape, false, false)) {
                // A grouped definition's sites are GROUPED, and GROUPED_COUNT when checked for a count.
                ran.computeIfAbsent(key(tree.kind(site), site.shape(), site.anchored()), k -> new HashSet<>())
                        .add(site.uses());
            }
        }
    }

    private static String key(String kind, StatementShape shape, boolean anchored) {
        return kind + " " + shape + (anchored ? "" : " unanchored");
    }

    /** The use sets {@code renderer} admits for one synthesized site, probed over {@link #CANDIDATES}. */
    private static Set<Set<TreatUse>> admitted(TreatRenderer renderer, Class<?> entity, Class<?> subtype,
            Optional<String> attribute, StatementShape shape, boolean anchored) {
        return (entity == subtype ? TYPE_CANDIDATES : CANDIDATES).stream().filter(uses -> renderer.refusal(
                new TreatSite(entity, subtype, attribute, shape, anchored, uses)).isEmpty())
                .collect(Collectors.toSet());
    }

    private static List<Set<TreatUse>> candidates(boolean type) {
        List<Set<TreatUse>> sets = new ArrayList<>();
        TreatUse[] all = TreatUse.values();
        int typeBit = 1 << TreatUse.TYPE.ordinal();
        for (int mask = 0; mask < 1 << all.length; mask++) {
            if (type ? (mask & typeBit) != 0 : (mask & typeBit) == 0 && Integer.bitCount(mask) <= 4) {
                var uses = EnumSet.noneOf(TreatUse.class);
                for (int i = 0; i < all.length; i++) {
                    if ((mask & 1 << i) != 0) {
                        uses.add(all[i]);
                    }
                }
                sets.add(Set.copyOf(uses));
            }
        }
        return List.copyOf(sets);
    }

    private static void check(EntityManager em, TreatRenderer renderer, String as, Case each) {
        assertThat(sites(em, renderer, each.query, StatementShape.CONTENT)).as(as).isEqualTo(each.content);
        var executor = executor(em, each.query);
        var query = raw(each.query);
        if (each.unordered) {
            assertThat(executor.list(query, Limit.of(20))).as(as).containsExactlyInAnyOrderElementsOf(each.rows);
        } else {
            assertThat(executor.list(query, Limit.of(20))).as(as).containsExactlyElementsOf(each.rows);
        }
        if (each.count == null) {
            assertTreatRefused(() -> executor.count(query));
            assertTreatRefused(() -> executor.page(query, PageSpec.of(0, 2), CountMode.COUNT));
            return;
        }
        if (each.clientSide) {
            // The count's GROUPED_COUNT site is refused, so the engine counts the groups client-side (D-159).
            assertTreatRefused(() -> sites(em, renderer, each.query, StatementShape.COUNT));
            assertThat(sites(em, renderer, each.query, StatementShape.GROUPED)).as(as).isEqualTo(each.count);
        } else {
            assertThat(sites(em, renderer, each.query, StatementShape.COUNT)).as(as).isEqualTo(each.count);
        }
        assertThat(executor.count(query)).as(as).isEqualTo(each.rows.size());
        Slice<String> page = executor.page(query, PageSpec.of(0, 2), CountMode.COUNT);
        if (each.unordered) {
            assertThat(page.content()).as(as).hasSize(Math.min(2, each.rows.size())).isSubsetOf(each.rows);
        } else {
            assertThat(page.content()).as(as).containsExactlyElementsOf(each.rows.subList(0,
                    Math.min(2, each.rows.size())));
        }
        assertThat(page.total()).as(as).hasValue(each.rows.size());
    }

    /** The use sets of {@code query}'s sites in {@code shape}, as the executor checks them against the renderer. */
    private static Set<Set<TreatUse>> sites(EntityManager em, TreatRenderer renderer, ModelQuery<?, ?, ?> query,
            StatementShape shape) {
        return TreatChecks.check(query, RenderOptions.portable().withTreatRenderer(renderer), em.getMetamodel(),
                shape, false, false).stream().map(TreatSite::uses).collect(Collectors.toSet());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ModelQueryExecutor<Object> executor(EntityManager em, ModelQuery<?, ?, ?> query) {
        return (ModelQueryExecutor) ModelQueryExecutor.create(em, query.rootEntity(), ModelQueryConfig.defaults());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ModelQuery<Object, ?, String> raw(ModelQuery<?, ?, String> query) {
        return (ModelQuery) query;
    }

    private static void assertTreatRefused(Runnable action) {
        assertThatThrownBy(action::run).isExactlyInstanceOf(ModelQueryConfigurationException.class)
                .extracting(failure -> ((ModelQueryConfigurationException) failure).code()).isEqualTo(MqCode.MQ4016);
    }

    private static Set<Set<TreatUse>> uses(String... cells) {
        return Arrays.stream(cells).map(cell -> Set.copyOf(Arrays.stream(cell.split("\\+")).map(TreatUse::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(TreatUse.class))))).collect(Collectors.toSet());
    }

    // ---- the hierarchies

    private static List<Tree<?, ?, ?>> trees() {
        return List.of(singleTable(),
                new Tree<>("JOINED", CellEntities.JdPayment.class, CellEntities.JdCard.class,
                        CellEntities.JdBank.class, CellEntities.JdPremium.class, CellEntities.JdHolder.class)
                        .typeUnordered(),
                new Tree<>("TABLE_PER_CLASS", CellEntities.TpPayment.class, CellEntities.TpCard.class,
                        CellEntities.TpBank.class, CellEntities.TpPremium.class, CellEntities.TpHolder.class));
    }

    private static Tree<CellEntities.StPayment, CellEntities.StCard, CellEntities.StHolder> singleTable() {
        return new Tree<>("SINGLE_TABLE", CellEntities.StPayment.class, CellEntities.StCard.class,
                CellEntities.StBank.class, CellEntities.StPremium.class, CellEntities.StHolder.class)
                .sharing("code", "alpha");
    }

    /** The measured mapping variants of RFC 0016 §10, each over the same rows as the core trees. */
    private static List<Tree<?, ?, ?>> variants() {
        return List.of(
                new Tree<>("force", VariantEntities.ForcePayment.class, VariantEntities.ForceCard.class,
                        VariantEntities.ForceBank.class, VariantEntities.ForcePremium.class,
                        VariantEntities.ForceHolder.class).variant(),
                new Tree<>("formula", VariantEntities.FormulaPayment.class, VariantEntities.FormulaCard.class,
                        VariantEntities.FormulaBank.class, VariantEntities.FormulaPremium.class,
                        VariantEntities.FormulaHolder.class).variant(),
                new Tree<>("optional-secondary", VariantEntities.OptionalPayment.class,
                        VariantEntities.OptionalCard.class, VariantEntities.OptionalBank.class,
                        VariantEntities.OptionalPremium.class, VariantEntities.OptionalHolder.class).variant(),
                new Tree<>("required-secondary", VariantEntities.RequiredPayment.class,
                        VariantEntities.RequiredCard.class, VariantEntities.RequiredBank.class,
                        VariantEntities.RequiredPremium.class, VariantEntities.RequiredHolder.class).variant(),
                new Tree<>("joined-explicit", VariantEntities.JoinedPayment.class, VariantEntities.JoinedCard.class,
                        VariantEntities.JoinedBank.class, VariantEntities.JoinedPremium.class,
                        VariantEntities.JoinedHolder.class).variant(),
                new Tree<>("joined-explicit-ignored", VariantEntities.JoinedPayment.class,
                        VariantEntities.JoinedCard.class, VariantEntities.JoinedBank.class,
                        VariantEntities.JoinedPremium.class, VariantEntities.JoinedHolder.class)
                        .variant("hibernate.discriminator.ignore_explicit_for_joined").typeUnordered(),
                new Tree<>("joined-implicit", CellEntities.JdPayment.class, CellEntities.JdCard.class,
                        CellEntities.JdBank.class, CellEntities.JdPremium.class, CellEntities.JdHolder.class)
                        .variant("hibernate.discriminator.implicit_for_joined"));
    }

    /** The sub-select vocabulary of a correlated count. */
    private static final class Inner {}

    /** One strategy's hierarchy, its fields and the cases it runs. */
    private static final class Tree<P, C extends P, H> {
        final String name;
        final Class<P> payment;
        final Class<C> card;
        final Class<? extends P> bank;
        final Class<? extends C> premium;
        final Class<H> holder;
        final TableField<P, P> root;
        final TableField<P, C> cards;
        final ColumnField<String, P, Long> id;
        final ColumnField<String, P, String> status;
        final ColumnField<String, C, String> number;
        final ColumnField<String, ? extends C, String> tier;
        /** A card's holders, the path of an {@code exists} under the treat. */
        final TableField<C, H> cardHolders;
        final ColumnField<String, H, Long> cardHolderId;
        final TableField<H, H> holders;
        final ColumnField<String, H, Long> holderId;
        /** The holder's payment, left joined and treated as a card. */
        final TableField<P, C> leftCards;
        final ColumnField<String, C, String> leftNumber;
        final ColumnField<String, C, String> innerNumber;
        /** The card's owner, an inner join below a left treated join. */
        final ColumnField<String, H, Long> ownerId;
        /** The card's attributes whose column a sibling also maps ({@code SINGLE_TABLE}, AC-COL-51). */
        final Set<String> sharedAttributes = new HashSet<>();
        /** Factory settings set to {@code true}. */
        final List<String> settings = new ArrayList<>();
        /** Whether this is a measured mapping variant, whose runs had no root-id count. */
        boolean variant;
        /** Whether a {@code typeOf} order renders: not on {@code JOINED} without a discriminator column. */
        boolean typeOrdered = true;
        /** Each payment's exact class, and that of the holder's left-joined payment. */
        final ExpressionField<String, Class<? extends P>> type;
        final TableField<H, P> leftPayments;
        final ExpressionField<String, Class<? extends P>> leftType;

        Tree(String name, Class<P> payment, Class<C> card, Class<? extends P> bank, Class<? extends C> premium,
                Class<H> holder) {
            this.name = name;
            this.payment = payment;
            this.card = card;
            this.bank = bank;
            this.premium = premium;
            this.holder = holder;
            this.root = TableField.root(payment);
            this.cards = root.treat(card);
            this.id = ColumnField.of(String.class, root, "id", Long.class);
            this.status = ColumnField.of(String.class, root, "status", String.class);
            this.number = ColumnField.of(String.class, cards, "number", String.class);
            this.tier = ColumnField.of(String.class, root.treat(premium), "tier", String.class);
            this.cardHolders = TableField.join(cards, "holders", JoinType.LEFT);
            this.cardHolderId = ColumnField.of(String.class, cardHolders, "id", Long.class);
            this.holders = TableField.root(holder);
            this.holderId = ColumnField.of(String.class, holders, "id", Long.class);
            this.leftCards = TableField.<H, P>join(holders, "payment", JoinType.LEFT).treat(card);
            this.leftNumber = ColumnField.of(String.class, leftCards, "number", String.class);
            this.innerNumber = ColumnField.of(String.class,
                    TableField.<H, P>join(holders, "payment", JoinType.INNER).treat(card), "number", String.class);
            this.ownerId = ColumnField.of(String.class, TableField.<C, H>join(leftCards, "owner", JoinType.INNER),
                    "id", Long.class);
            this.type = Expr.typeOf(String.class, root);
            this.leftPayments = TableField.join(holders, "payment", JoinType.LEFT);
            this.leftType = Expr.typeOf(String.class, leftPayments);
        }

        Tree<P, C, H> typeUnordered() {
            typeOrdered = false;
            return this;
        }

        Tree<P, C, H> sharing(String... attributes) {
            sharedAttributes.addAll(List.of(attributes));
            return this;
        }

        Tree<P, C, H> variant(String... factorySettings) {
            variant = true;
            settings.addAll(List.of(factorySettings));
            return this;
        }

        /**
         * A site's kind: a type read, a treated join's own site, a read of a shared column, or any other treated
         * read.
         */
        String kind(TreatSite site) {
            if (site.uses().contains(TreatUse.TYPE)) {
                return "type";
            }
            String attribute = site.attribute().orElse(null);
            if (attribute == null) {
                return "join";
            }
            int dot = attribute.indexOf('.');
            return sharedAttributes.contains(dot < 0 ? attribute : attribute.substring(0, dot)) ? "shared" : "column";
        }

        /**
         * The renderer's admitted use sets, probed per kind, shape and anchoring with synthesized sites, equal the
         * use sets the cases ran: every admitted cell is a TCK case, and every case ran an admitted cell (AC-VND-19).
         */
        void assertEveryAdmittedCellRan(TreatRenderer renderer, Map<String, Set<Set<TreatUse>>> ran) {
            Map<String, Optional<String>> kinds = new LinkedHashMap<>();
            kinds.put("column", Optional.of("number"));
            kinds.put("join", Optional.empty());
            if (!sharedAttributes.isEmpty()) {
                kinds.put("shared", Optional.of(sharedAttributes.iterator().next()));
            }
            kinds.put("type", Optional.empty());
            kinds.forEach((kind, attribute) -> {
                // A type site's entity and subtype are both its node's entity (R-VND-26).
                Class<?> subtype = kind.equals("type") ? payment : card;
                for (StatementShape shape : StatementShape.values()) {
                    for (boolean anchored : List.of(true, false)) {
                        String key = key(kind, shape, anchored);
                        // The engine never anchors a grouped statement, and the renderer doesn't read the anchoring
                        // of a grouped type key, so an anchored one is admitted as the unanchored one ran (D-158).
                        boolean grouped = kind.equals("type")
                                && (shape == StatementShape.GROUPED || shape == StatementShape.GROUPED_COUNT);
                        Set<Set<TreatUse>> expected = new HashSet<>(ran.getOrDefault(grouped
                                ? key(kind, shape, false) : key, Set.of()));
                        // The table's GROUP_BY+TYPE row has no engine site here: a model selects its group keys.
                        // Without a discriminator the count of type groups is refused and runs client-side (D-159).
                        if (grouped && (shape == StatementShape.GROUPED || typeOrdered)) {
                            expected.addAll(uses("GROUP_BY+TYPE"));
                        }
                        assertThat(admitted(renderer, payment, subtype, attribute, shape, anchored))
                                .as(name + " " + key).isEqualTo(expected);
                    }
                }
            });
            assertThat(ran.keySet()).as(name).allMatch(key -> kinds.containsKey(key.substring(0, key.indexOf(' '))));
        }

        /**
         * The shared-column cells ({@code SINGLE_TABLE} only, AC-COL-51): through the card {@code code} is
         * {@code C2} and {@code C5}, and {@code NULL} on the bank's row, which maps {@code B4} in the same column.
         * Only the {@code or} contexts count. No {@code SUBQUERY} cell: an outer column inside a path {@code exists}
         * is {@code MQ1302}.
         */
        List<Case> shared() {
            if (sharedAttributes.isEmpty()) {
                return List.of();
            }
            var code = ColumnField.of(String.class, cards, "code", String.class);
            var alpha = ColumnField.of(String.class, cards, "alpha", String.class);
            Set<Set<TreatUse>> none = Set.of();
            List<Case> cases = new ArrayList<>();
            cases.add(new Case("shared select", pairs(code), List.of("1:null", "2:C2", "3:null", "4:null", "5:C5"),
                    uses("SELECT"), none));
            cases.add(new Case("shared differently named select", pairs(alpha),
                    List.of("1:null", "2:A2", "3:null", "4:null", "5:null"), uses("SELECT"), none));
            cases.add(new Case("shared select coalesce", pairs(Expr.coalesce(code, "none")),
                    List.of("1:none", "2:C2", "3:none", "4:none", "5:C5"), uses("SELECT+EXPRESSION"), none));
            cases.add(ordered("shared order", code.asc().nullsLast(), List.of("2", "5", "1", "3", "4")));
            sharedPredicate(cases, "shared eq", f -> f.eq(code, "C2"), List.of("2"), false, "PREDICATE");
            sharedPredicate(cases, "shared eq of the sibling's value", f -> f.eq(code, "B4"), List.of(), false,
                    "PREDICATE");
            sharedPredicate(cases, "shared not eq", f -> f.not(n -> n.eq(code, "C2")), List.of("5"), false,
                    "PREDICATE+NEGATED");
            sharedPredicate(cases, "shared or eq", f -> f.or(a -> a.eq(code, "C2"), b -> b.eq(status, "s4")),
                    List.of("2", "4"), true, "PREDICATE+DISJUNCTION");
            sharedPredicate(cases, "shared or not eq", f -> f.or(a -> a.not(n -> n.eq(code, "C2")),
                    b -> b.eq(status, "s1")), List.of("1", "5"), true, "PREDICATE+NEGATED+DISJUNCTION");
            sharedPredicate(cases, "shared isNull", f -> f.isNull(code), List.of("1", "3", "4"), false, "NULL_TEST");
            sharedPredicate(cases, "shared not isNull", f -> f.not(n -> n.isNull(code)), List.of("2", "5"), false,
                    "NULL_TEST+NEGATED");
            sharedPredicate(cases, "shared or isNull", f -> f.or(a -> a.isNull(code), b -> b.eq(status, "s2")),
                    List.of("1", "2", "3", "4"), true, "NULL_TEST+DISJUNCTION");
            sharedPredicate(cases, "shared not or isNull", f -> f.not(n -> n.or(a -> a.isNull(code),
                    b -> b.eq(status, "s2"))), List.of("5"), true, "NULL_TEST+NEGATED+DISJUNCTION");
            sharedPredicate(cases, "shared ne", f -> f.ne(code, "C2"), List.of("1", "3", "4", "5"), false,
                    "PREDICATE+NULL_TEST+DISJUNCTION");
            return cases;
        }

        private void sharedPredicate(List<Case> cases, String name, UnaryOperator<Filters<String>> where,
                List<String> rows, boolean counted, String... cells) {
            cases.add(new Case(name, ids(where), rows, uses(cells), counted ? uses(cells) : null));
        }

        /** Each admitted cell, once (AC-VND-19). */
        List<Case> cells() {
            Set<Set<TreatUse>> none = Set.of();
            List<Case> cases = new ArrayList<>();
            cases.addAll(predicates());
            cases.add(new Case("select", pairs(number), List.of("1:null", "2:1111", "3:null", "4:null", "5:5555"),
                    uses("SELECT"), none));
            cases.add(new Case("select coalesce", pairs(Expr.coalesce(number, "none")),
                    List.of("1:none", "2:1111", "3:none", "4:none", "5:5555"), uses("SELECT+EXPRESSION"), none));
            cases.add(ordered("order", number.asc().nullsFirst(), List.of("1", "3", "4", "2", "5")));
            cases.add(ordered("order coalesce", Expr.coalesce(number, "0").desc(), List.of("5", "2", "1", "3", "4")));
            // A predicate over an expression undercounted on the spike's count path: content only.
            cases.add(new Case("eq coalesce", ids(f -> f.eq(Expr.coalesce(number, "none"), "none")),
                    List.of("1", "3", "4"), uses("PREDICATE+EXPRESSION"), null));
            cases.addAll(joins());
            cases.add(new Case("correlated count", counted(false), List.of("1:0", "2:1", "3:1", "4:0", "5:1"),
                    uses("SELECT+AGGREGATE+SUBQUERY"), none));
            cases.addAll(types());
            return cases;
        }

        /**
         * The admitted type cells, once each (AC-COL-49, AC-FLT-30, AC-FLT-31): the spike's {@code TYPE} rows marked
         * {@code ALLOW}, with the order only where a discriminator column is mapped. The table's
         * {@code COUNT_DERIVED FROM_TYPE} row has no engine site: a type read makes no treated statement, so its count
         * is the plain one (D-158).
         */
        List<Case> types() {
            Set<Set<TreatUse>> none = Set.of();
            List<Case> cases = new ArrayList<>();
            cases.add(new Case("type select", typePairs(), List.of("1:" + payment.getSimpleName(),
                    "2:" + card.getSimpleName(), "3:" + card.getSimpleName(), "4:" + bank.getSimpleName(),
                    "5:" + premium.getSimpleName()), uses("SELECT+TYPE"), none));
            if (typeOrdered) {
                // The content statement also selects its order keys, so the order key is a select site too.
                cases.add(new Case("type order", typeOrder(), List.of("1", "2", "3", "4", "5"),
                        uses("ORDER+TYPE", "SELECT+TYPE"), none, true));
            }
            predicate(cases, "type eq", f -> f.eq(type, card), List.of("2", "3"), "PREDICATE+TYPE");
            predicate(cases, "type in", f -> f.in(type, List.<Class<? extends P>>of(card, bank)),
                    List.of("2", "3", "4"), "PREDICATE+TYPE");
            predicate(cases, "ofType", f -> f.ofType(root, card), List.of("2", "3", "5"), "PREDICATE+TYPE");
            predicate(cases, "notOfType", f -> f.notOfType(root, card), List.of("1", "4"), "PREDICATE+TYPE");
            // No row of the table: admitted per site from the COMBINED_TYPE_TREAT/OR rows (D-158).
            predicate(cases, "or ofType", f -> f.or(a -> a.ofType(root, bank), b -> b.eq(status, "s1")),
                    List.of("1", "4"), "PREDICATE+DISJUNCTION+TYPE");
            // Holder 14 has no payment: TYPE(...) NOT IN (...) OR TYPE(...) IS NULL keeps it (R-FLT-28).
            holderPredicate(cases, "left notOfType", f -> f.notOfType(leftPayments, card), List.of("10", "12", "14"),
                    "PREDICATE+NEGATED+NULL_TEST+DISJUNCTION+LEFT_JOIN+TYPE");
            // A grouped total counts the groups, so its count statement groups by the type too. A model query
            // selects its group keys, so an unselected one is a select site as well. On JOINED without a
            // discriminator, Hibernate's count of groups (createCountQuery, 6.6 and 7.4) prunes the subtype joins and
            // its CASE type reads one type, so the renderer refuses it and the groups are counted client-side (D-159).
            boolean clientSide = !typeOrdered;
            Set<Set<TreatUse>> grouped = uses("SELECT+GROUP_BY+TYPE");
            cases.add(new Case("group by type", groupedByType(true), List.of(payment.getSimpleName() + ":1",
                    card.getSimpleName() + ":2", bank.getSimpleName() + ":1", premium.getSimpleName() + ":1"),
                    grouped, grouped, true, clientSide));
            cases.add(new Case("group by an unselected type", groupedByType(false), List.of("1", "2", "1", "1"),
                    grouped, grouped, true, clientSide));
            cases.add(new Case("group by type and a column", groupedByTypeAndStatus(), List.of(
                    payment.getSimpleName() + ":s1:1", card.getSimpleName() + ":s2:1", card.getSimpleName() + ":s3:1",
                    bank.getSimpleName() + ":s4:1", premium.getSimpleName() + ":s5:1"), grouped, grouped, true,
                    clientSide));
            return cases;
        }

        /** The root-anchored predicate cells, content and count alike. */
        List<Case> predicates() {
            List<Case> cases = new ArrayList<>();
            predicate(cases, "eq", f -> f.eq(number, "1111"), List.of("2"), "PREDICATE");
            predicate(cases, "not eq", f -> f.not(n -> n.eq(number, "1111")), List.of("5"), "PREDICATE+NEGATED");
            predicate(cases, "or eq", f -> f.or(a -> a.eq(number, "1111"), b -> b.eq(status, "s4")),
                    List.of("2", "4"), "PREDICATE+DISJUNCTION");
            predicate(cases, "or not eq", f -> f.or(a -> a.not(n -> n.eq(number, "1111")), b -> b.eq(status, "s1")),
                    List.of("1", "5"), "PREDICATE+NEGATED+DISJUNCTION");
            predicate(cases, "exists", f -> f.exists(cardHolders, e -> e.eq(cardHolderId, 10L)), List.of("2"),
                    "PREDICATE+SUBQUERY");
            predicate(cases, "not exists", f -> f.not(n -> n.exists(cardHolders)), List.of("1", "3", "4"),
                    "PREDICATE+NEGATED+SUBQUERY");
            predicate(cases, "or exists", f -> f.or(a -> a.exists(cardHolders, e -> e.eq(cardHolderId, 13L)),
                    b -> b.eq(status, "s1")), List.of("1", "5"), "PREDICATE+DISJUNCTION+SUBQUERY");
            predicate(cases, "not or exists", f -> f.not(n -> n.or(a -> a.exists(cardHolders),
                    b -> b.eq(status, "s1"))), List.of("3", "4"), "PREDICATE+NEGATED+DISJUNCTION+SUBQUERY");
            predicate(cases, "isNull", f -> f.isNull(number), List.of("1", "3", "4"), "NULL_TEST");
            predicate(cases, "not isNull", f -> f.not(n -> n.isNull(number)), List.of("2", "5"),
                    "NULL_TEST+NEGATED");
            predicate(cases, "or isNull", f -> f.or(a -> a.isNull(number), b -> b.eq(status, "s2")),
                    List.of("1", "2", "3", "4"), "NULL_TEST+DISJUNCTION");
            predicate(cases, "not or isNull", f -> f.not(n -> n.or(a -> a.isNull(number), b -> b.eq(status, "s2"))),
                    List.of("5"), "NULL_TEST+NEGATED+DISJUNCTION");
            predicate(cases, "ne", f -> f.ne(number, "1111"), List.of("1", "3", "4", "5"),
                    "PREDICATE+NULL_TEST+DISJUNCTION");
            predicate(cases, "exists ne", f -> f.exists(cardHolders, e -> e.ne(cardHolderId, 10L)),
                    List.of("2", "5"), "PREDICATE+NULL_TEST+DISJUNCTION+SUBQUERY", "PREDICATE+SUBQUERY");
            return cases;
        }

        /** The treated-join cells, from the holder's left and inner joins to the payment. */
        List<Case> joins() {
            Set<Set<TreatUse>> none = Set.of();
            Set<Set<TreatUse>> innerJoin = uses("INNER_JOIN");
            List<Case> cases = new ArrayList<>();
            cases.add(new Case("left select", holderPairs(leftNumber),
                    List.of("10:null", "11:1111", "12:null", "13:5555", "14:null", "15:null"),
                    uses("SELECT+LEFT_JOIN"), none));
            cases.add(new Case("inner select", holderPairs(innerNumber), List.of("11:1111", "13:5555", "15:null"),
                    uses("SELECT+INNER_JOIN"), innerJoin));
            cases.add(new Case("left order", holderIds(f -> f, leftNumber.asc().nullsLast()),
                    List.of("11", "13", "10", "12", "14", "15"), uses("ORDER+LEFT_JOIN", "SELECT+LEFT_JOIN"), none));
            cases.add(new Case("inner order", holderIds(f -> f, innerNumber.asc().nullsFirst()),
                    List.of("15", "11", "13"), uses("ORDER+INNER_JOIN", "SELECT+INNER_JOIN"),
                    innerJoin));
            cases.add(new Case("an inner join below a left treated join", holderPairs(ownerId), List.of("11:14"),
                    uses("SELECT+LEFT_JOIN"), uses("LEFT_JOIN")));
            for (var join : List.of("LEFT_JOIN", "INNER_JOIN")) {
                var value = join.equals("LEFT_JOIN") ? leftNumber : innerNumber;
                boolean left = join.equals("LEFT_JOIN");
                holderPredicate(cases, join + " eq", f -> f.eq(value, "1111"), List.of("11"), "PREDICATE+" + join);
                holderPredicate(cases, join + " isNull", f -> f.isNull(value),
                        left ? List.of("10", "12", "14", "15") : List.of("15"), "NULL_TEST+" + join);
                holderPredicate(cases, join + " not isNull", f -> f.not(n -> n.isNull(value)), List.of("11", "13"),
                        "NULL_TEST+NEGATED+" + join);
                holderPredicate(cases, join + " ne", f -> f.ne(value, "1111"),
                        left ? List.of("10", "12", "13", "14", "15") : List.of("13", "15"),
                        "PREDICATE+NULL_TEST+DISJUNCTION+" + join);
            }
            return cases;
        }

        /** What a treated column reads in the select list: the subtype, the grandchild, a sibling (AC-COL-44). */
        List<Case> reads() {
            Set<Set<TreatUse>> none = Set.of();
            return List.of(
                    new Case("keyed select", pairs(number), List.of("1:null", "2:1111", "3:null", "4:null",
                            "5:5555"), uses("SELECT"), none),
                    new Case("select coalesce", pairs(Expr.coalesce(number, "none")),
                            List.of("1:none", "2:1111", "3:none", "4:none", "5:5555"), uses("SELECT+EXPRESSION"),
                            none),
                    new Case("grandchild select", pairs(tier), List.of("1:null", "2:null", "3:null", "4:null",
                            "5:gold"), uses("SELECT"), none));
        }

        /** The operators of AC-COL-45, each with its page total. */
        List<Case> operators() {
            List<Case> cases = new ArrayList<>();
            predicate(cases, "eq", f -> f.eq(number, "1111"), List.of("2"), "PREDICATE");
            predicate(cases, "in", f -> f.in(number, List.of("1111", "5555")), List.of("2", "5"), "PREDICATE");
            predicate(cases, "like", f -> f.like(number, "55", LikeMode.CONTAINS), List.of("5"), "PREDICATE");
            predicate(cases, "isNotNull", f -> f.isNotNull(number), List.of("2", "5"), "NULL_TEST");
            predicate(cases, "isNull", f -> f.isNull(number), List.of("1", "3", "4"), "NULL_TEST");
            predicate(cases, "ne", f -> f.ne(number, "1111"), List.of("1", "3", "4", "5"),
                    "PREDICATE+NULL_TEST+DISJUNCTION");
            predicate(cases, "notIn", f -> f.notIn(number, List.of("1111")), List.of("1", "3", "4", "5"),
                    "PREDICATE+NULL_TEST+DISJUNCTION");
            // SQL NOT over NULL is NULL, so not(eq) drops the sibling rows and the card without a number.
            predicate(cases, "not eq", f -> f.not(n -> n.eq(number, "1111")), List.of("5"), "PREDICATE+NEGATED");
            predicate(cases, "or eq", f -> f.or(a -> a.eq(number, "5555"), b -> b.eq(status, "s4")),
                    List.of("4", "5"), "PREDICATE+DISJUNCTION");
            predicate(cases, "grandchild eq", f -> f.eq(tier, "gold"), List.of("5"), "PREDICATE");
            return cases;
        }

        /** Totals and counts with a select-only or order-only treat and the null-accepting sets (AC-COL-52). */
        List<Case> totals() {
            Set<Set<TreatUse>> none = Set.of();
            List<Case> cases = new ArrayList<>();
            cases.add(new Case("select only", pairs(number), List.of("1:null", "2:1111", "3:null", "4:null",
                    "5:5555"), uses("SELECT"), none));
            cases.add(ordered("order only", number.desc().nullsLast(), List.of("5", "2", "1", "3", "4")));
            predicate(cases, "isNull", f -> f.isNull(number), List.of("1", "3", "4"), "NULL_TEST");
            predicate(cases, "ne", f -> f.ne(number, "5555"), List.of("1", "2", "3", "4"),
                    "PREDICATE+NULL_TEST+DISJUNCTION");
            predicate(cases, "notIn", f -> f.notIn(number, List.of("1111", "5555")), List.of("1", "3", "4"),
                    "PREDICATE+NULL_TEST+DISJUNCTION");
            predicate(cases, "not isNotNull", f -> f.not(n -> n.isNotNull(number)), List.of("1", "3", "4"),
                    "NULL_TEST+NEGATED");
            return cases;
        }

        private void predicate(List<Case> cases, String name, UnaryOperator<Filters<String>> where,
                List<String> rows, String... cells) {
            // A path exists reports its path and each treated read inside it.
            cases.add(new Case(name, ids(where), rows, uses(cells), uses(cells)));
        }

        private void holderPredicate(List<Case> cases, String name, UnaryOperator<Filters<String>> where,
                List<String> rows, String cell) {
            cases.add(new Case(name, holderIds(where, holderId.asc()), rows, uses(cell), uses(cell)));
        }

        Case ordered(String name, OrderField<String, ?> order, List<String> rows) {
            var query = ModelQuery.builder(root, row -> String.valueOf(row.get(id))).select(SelectSet.of(id))
                    .primaryKey(PrimaryKey.of(id)).orderBy(order, id.asc()).build();
            // The content statement also selects its order keys, so an order key is a select site too.
            return new Case(name, query, rows, order.column() instanceof ColumnField<?, ?, ?>
                    ? uses("ORDER", "SELECT") : uses("ORDER+EXPRESSION", "SELECT+EXPRESSION"), Set.of());
        }

        ModelQuery<P, ?, String> ids(UnaryOperator<Filters<String>> where) {
            return ModelQuery.builder(root, row -> String.valueOf(row.get(id))).select(SelectSet.of(id))
                    .primaryKey(PrimaryKey.of(id)).where(where).orderBy(id.asc()).build();
        }

        ModelQuery<P, ?, String> pairs(SelectField<String, ?> value) {
            return ModelQuery.builder(root, row -> row.get(id) + ":" + row.get(value)).select(SelectSet.of(id, value))
                    .primaryKey(PrimaryKey.of(id)).orderBy(id.asc()).build();
        }

        /**
         * An offset order by the type, unspecified but repeated: two runs of a list and of each offset page return the
         * same rows, each type's rows together and by id within it (AC-COL-49).
         */
        void repeatedTypeOrder(EntityManager em) {
            var ordered = typeOrder();
            var executor = executor(em, ordered);
            List<String> first = executor.list(raw(ordered), Limit.of(20));
            assertThat(executor.list(raw(ordered), Limit.of(20))).as(name).isEqualTo(first);
            assertThat(first).as(name).containsExactlyInAnyOrder("1", "2", "3", "4", "5");
            // The cards 2 and 3 share a type: adjacent, by id.
            assertThat(first.indexOf("3") - first.indexOf("2")).as(name + " " + first).isEqualTo(1);
            for (int offset : List.of(0, 2, 4)) {
                Slice<String> page = executor.page(raw(ordered), PageSpec.of(offset / 2, 2), CountMode.COUNT);
                assertThat(page.content()).as(name + " page " + offset)
                        .isEqualTo(first.subList(offset, Math.min(offset + 2, 5)));
                assertThat(page.total()).as(name).hasValue(5);
                assertThat(executor.page(raw(ordered), PageSpec.of(offset / 2, 2), CountMode.COUNT).content())
                        .as(name + " page " + offset).isEqualTo(page.content());
            }
        }

        /**
         * {@code ofType} keeps the subtype and its grandchild, varargs and collections alike; the root type keeps every
         * row; an empty {@code Optional} skips the filter and an empty collection keeps no row (AC-FLT-30). With a
         * treated read in the same statement it runs per site, but 6.6's preflight can't attribute {@code JOINED}'s
         * type restriction to its root: {@code MQ4016} there, the rows on 7.4 (D-158).
         */
        void ofTypes(EntityManager em) {
            Map<String, UnaryOperator<Filters<String>>> filters = new LinkedHashMap<>();
            filters.put("2,3,5", f -> f.ofType(root, card));
            filters.put("4,5", f -> f.ofType(root, bank, premium));
            filters.put("1,2,3,4,5", f -> f.ofType(root, payment));
            filters.put("2,3,4,5", f -> f.ofType(root, List.<Class<? extends P>>of(card, bank)));
            filters.put("5", f -> f.ofType(root, Optional.of(List.<Class<? extends P>>of(premium))));
            filters.put("1,2,3,4,5 skipped", f -> f.ofType(root, Optional.<List<Class<? extends P>>>empty()));
            filters.put("", f -> f.ofType(root, List.<Class<? extends P>>of()));
            filters.forEach((rows, where) -> {
                var query = ids(where);
                List<String> expected = rows.isEmpty() ? List.of() : List.of(rows.split(" ")[0].split(","));
                assertThat(executor(em, query).list(raw(query), Limit.of(20))).as(name + " " + rows)
                        .containsExactlyElementsOf(expected);
                assertThat(executor(em, query).count(raw(query))).as(name + " " + rows).isEqualTo(expected.size());
            });
            var combined = ids(f -> f.ofType(root, card).isNull(number));
            if (payment == CellEntities.JdPayment.class && Version.getVersionString().startsWith("6.")) {
                assertThatThrownBy(() -> executor(em, combined).list(raw(combined), Limit.of(5))).as(name)
                        .isExactlyInstanceOf(ModelQueryConfigurationException.class)
                        .hasMessageContaining("attribute")
                        .extracting(failure -> ((ModelQueryConfigurationException) failure).code())
                        .isEqualTo(MqCode.MQ4016);
            } else {
                assertThat(executor(em, combined).list(raw(combined), Limit.of(5))).as(name).containsExactly("3");
                assertThat(executor(em, combined).count(raw(combined))).as(name).isEqualTo(1);
            }
        }

        /**
         * {@code notOfType} keeps the other types' rows and, over a LEFT join, the rows where it is absent; an empty
         * collection keeps every row and an empty {@code Optional} skips the filter (AC-FLT-31).
         */
        void notOfTypes(EntityManager em) {
            Map<String, UnaryOperator<Filters<String>>> filters = new LinkedHashMap<>();
            filters.put("1,4", f -> f.notOfType(root, card));
            filters.put("1,2,3", f -> f.notOfType(root, bank, premium));
            filters.put("1,2,3,5", f -> f.notOfType(root, Optional.of(List.<Class<? extends P>>of(bank))));
            filters.put("1,2,3,4,5", f -> f.notOfType(root, List.<Class<? extends P>>of()));
            filters.put("1,2,3,4,5 skipped", f -> f.notOfType(root, Optional.<List<Class<? extends P>>>empty()));
            filters.forEach((rows, where) -> {
                var query = ids(where);
                List<String> expected = List.of(rows.split(" ")[0].split(","));
                assertThat(executor(em, query).list(raw(query), Limit.of(20))).as(name + " " + rows)
                        .containsExactlyElementsOf(expected);
                assertThat(executor(em, query).count(raw(query))).as(name + " " + rows).isEqualTo(expected.size());
            });
            // Holders 10 and 12 reach the payment and the bank; holder 14 reaches no payment.
            var reached = holderIds(f -> f.notOfType(leftPayments, card, bank), holderId.asc());
            assertThat(executor(em, reached).list(raw(reached), Limit.of(20))).as(name).containsExactly("10", "14");
            assertThat(executor(em, reached).count(raw(reached))).as(name).isEqualTo(2);
        }

        ModelQuery<P, ?, String> typePairs() {
            return ModelQuery.builder(root, row -> row.get(id) + ":" + row.get(type).getSimpleName())
                    .select(SelectSet.of(id, type)).primaryKey(PrimaryKey.of(id)).orderBy(id.asc()).build();
        }

        ModelQuery<P, ?, String> typeOrder() {
            return ModelQuery.builder(root, row -> String.valueOf(row.get(id))).select(SelectSet.of(id))
                    .primaryKey(PrimaryKey.of(id)).orderBy(type.asc(), id.asc()).build();
        }

        /** The payments counted per exact class, with the class selected or not. */
        ModelQuery<P, ?, String> groupedByType(boolean selected) {
            var counted = Agg.<String>count(id);
            return selected
                    ? ModelQuery.builder(root, row -> row.get(type).getSimpleName() + ":" + row.get(counted))
                            .select(SelectSet.of(type, counted)).groupBy(type).build()
                    : ModelQuery.builder(root, row -> String.valueOf(row.get(counted)))
                            .select(SelectSet.of(counted)).groupBy(type).build();
        }

        /** The payments counted per exact class and status. */
        ModelQuery<P, ?, String> groupedByTypeAndStatus() {
            var counted = Agg.<String>count(id);
            return ModelQuery.builder(root, row -> row.get(type).getSimpleName() + ":" + row.get(status) + ":"
                    + row.get(counted)).select(SelectSet.of(type, status, counted)).groupBy(type, status).build();
        }

        ModelQuery<P, ?, String> keyless() {
            return ModelQuery.builder(root, row -> row.get(id) + ":" + row.get(number))
                    .select(SelectSet.of(id, number)).orderBy(id.asc()).build();
        }

        ModelQuery<H, ?, String> holderIds(UnaryOperator<Filters<String>> where, OrderField<String, ?> order) {
            var builder = ModelQuery.builder(holders, row -> String.valueOf(row.get(holderId)))
                    .select(SelectSet.of(holderId)).primaryKey(PrimaryKey.of(holderId)).where(where);
            return order.column() == holderId ? builder.orderBy(order).build()
                    : builder.orderBy(order, holderId.asc()).build();
        }

        ModelQuery<H, ?, String> holderPairs(SelectField<String, ?> value) {
            return ModelQuery.builder(holders, row -> row.get(holderId) + ":" + row.get(value))
                    .select(SelectSet.of(holderId, value)).primaryKey(PrimaryKey.of(holderId))
                    .orderBy(holderId.asc()).build();
        }

        /**
         * Each payment and a correlated count over a sub-select's own treated root, or over a left treated join from
         * the holders of the payment (R-COL-31): 1 for a card, 0 for a sibling.
         */
        ModelQuery<P, ?, String> counted(boolean throughHolders) {
            SelectField<String, Long> count;
            if (throughHolders) {
                var sub = TableField.root(holder);
                TableField<H, P> paid = TableField.join(sub, "payment", JoinType.LEFT);
                var paidId = ColumnField.of(Inner.class, paid, "id", Long.class);
                count = Correlated.count(String.class,
                        SubSelect.of(ColumnField.of(Inner.class, paid.treat(card), "id", Long.class)),
                        (s, outer) -> s.compare(paidId, Op.EQ, outer.column(id)));
            } else {
                var sub = TableField.root(payment);
                var subId = ColumnField.of(Inner.class, sub, "id", Long.class);
                count = Correlated.count(String.class,
                        SubSelect.of(ColumnField.of(Inner.class, sub.treat(card), "id", Long.class)),
                        (s, outer) -> s.compare(subId, Op.EQ, outer.column(id)));
            }
            return pairs(count);
        }

        ModelQuery<P, ?, String> groupedByNumber() {
            var counted = Agg.<String>count(id);
            return ModelQuery.builder(root, row -> row.get(number) + ":" + row.get(counted))
                    .select(SelectSet.of(number, counted)).groupBy(number).build();
        }

        ModelQuery<P, ?, String> countingNumbers() {
            var counted = Agg.<String>count(number);
            return ModelQuery.builder(root, row -> row.get(status) + ":" + row.get(counted))
                    .select(SelectSet.of(status, counted)).groupBy(status).build();
        }

        /** AC-COL-50: content, count, keyset and export of a card-rooted model, and the lock rule per strategy. */
        void subtypeRoot(EntityManager em) {
            TableField<C, C> cardRoot = TableField.root(card);
            var cardId = ColumnField.of(String.class, cardRoot, "id", Long.class);
            var cardNumber = ColumnField.of(String.class, cardRoot, "number", String.class);
            var query = ModelQuery.builder(cardRoot, row -> row.get(cardId) + ":" + row.get(cardNumber))
                    .select(SelectSet.of(cardId, cardNumber)).primaryKey(PrimaryKey.of(cardId))
                    .orderBy(cardId.asc()).keyset().build();
            var executor = ModelQueryExecutor.create(em, card, ModelQueryConfig.defaults());
            List<String> rows = List.of("2:1111", "3:null", "5:5555");
            assertThat(executor.list(query, Limit.of(10))).as(name).containsExactlyElementsOf(rows);
            assertThat(executor.count(query)).as(name).isEqualTo(3);
            KeysetSlice<String> first = executor.page(query, KeysetSpec.first(2));
            assertThat(first.content()).as(name).containsExactly("2:1111", "3:null");
            assertThat(executor.page(query, KeysetSpec.after(first.nextCursor().orElseThrow(), 2)).content())
                    .as(name).containsExactly("5:5555");
            var exported = new ArrayList<String>();
            assertThat(executor.export(query, ExportOptions.of(2), page -> page, exported::add)).isEqualTo(3);
            assertThat(exported).as(name).containsExactlyElementsOf(rows);
            em.getTransaction().begin();
            try {
                if (name.equals("SINGLE_TABLE")) {
                    // One table: the lock applies to the subtype's rows.
                    var locked = ModelQuery.builder(cardRoot, row -> row.get(cardId) + ":" + row.get(cardNumber))
                            .select(SelectSet.of(cardId, cardNumber)).primaryKey(PrimaryKey.of(cardId))
                            .orderBy(cardId.asc()).lock(RowLock.exclusive()).build();
                    assertThat(executor.first(locked)).as(name).contains("2:1111");
                } else {
                    // A base root mapped to more than one table can't be locked whole (tablesOf).
                    var locked = ModelQuery.builder(root, row -> String.valueOf(row.get(id)))
                            .select(SelectSet.of(id)).primaryKey(PrimaryKey.of(id)).orderBy(id.asc())
                            .lock(RowLock.exclusive()).build();
                    var base = ModelQueryExecutor.create(em, payment, ModelQueryConfig.defaults());
                    assertThatThrownBy(() -> base.first(locked)).as(name).isInstanceOf(ModelQueryException.class)
                            .extracting(failure -> ((ModelQueryException) failure).code()).isEqualTo(MqCode.MQ4010);
                }
            } finally {
                em.getTransaction().rollback();
            }
        }

        // ---- schema and rows

        /** Creates the schema, writes the rows and runs {@code work} with Hibernate's own renderer. */
        void seeded(TckDatabase db, Work work) {
            try (SessionFactory schema = schema(db)) {
                schema.inTransaction(em -> {
                    Object owner = holder(14, null, null);
                    em.persist(owner);
                    Object p1 = row(payment, 1, "s1");
                    Object p2 = row(card, 2, "s2", "number", "1111", "owner", owner, "code", "C2", "alpha", "A2");
                    Object p3 = row(card, 3, "s3");
                    Object p4 = row(bank, 4, "s4", "iban", "DE1", "code", "B4", "beta", "B4b");
                    Object p5 = row(premium, 5, "s5", "number", "5555", "tier", "gold", "code", "C5");
                    for (Object each : List.of(p1, p2, p3, p4, p5)) {
                        em.persist(each);
                    }
                    em.persist(holder(10, p1, p2));
                    em.persist(holder(11, p2, p2));
                    em.persist(holder(12, p4, null));
                    em.persist(holder(13, p5, p5));
                    em.persist(holder(15, p3, null));
                });
                TreatRenderer renderer = new HibernateProviderSupport().treats(schema).orElseThrow();
                schema.inSession(em -> work.run(em, renderer));
            }
        }

        /** {@code work} on a payment executor over an empty schema, capturing that it runs no statement. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        void refusedBeforeAnyStatement(TckDatabase db, Consumer<ModelQueryExecutor<Object>> work) {
            refusedBeforeAnyStatementIn(db, em -> work.accept((ModelQueryExecutor) ModelQueryExecutor.create(em,
                    payment, ModelQueryConfig.defaults())));
        }

        /** {@code work} in a session over an empty schema, capturing that it runs no statement. */
        void refusedBeforeAnyStatementIn(TckDatabase db, Consumer<EntityManager> work) {
            try (SessionFactory schema = schema(db)) {
                List<String> statements = SqlSnapshots.capture(db, ds -> serving(ds, work)).stream()
                        .filter(sql -> !sql.startsWith("create ") && !sql.startsWith("drop ")).toList();
                assertThat(statements).as(name).isEmpty();
            }
        }

        private SessionFactory schema(TckDatabase db) {
            return configuration().setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                    .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                    .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop").buildSessionFactory();
        }

        private void serving(DataSource ds, Consumer<EntityManager> work) {
            Configuration cfg = configuration();
            cfg.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds);
            cfg.setProperty(AvailableSettings.HBM2DDL_AUTO, "none");
            try (SessionFactory factory = cfg.buildSessionFactory()) {
                factory.inSession(work::accept);
            }
        }

        private Configuration configuration() {
            Configuration cfg = new Configuration().addAnnotatedClass(payment).addAnnotatedClass(card)
                    .addAnnotatedClass(bank).addAnnotatedClass(premium).addAnnotatedClass(holder);
            settings.forEach(setting -> cfg.setProperty(setting, "true"));
            return cfg;
        }

        private Object holder(long holderKey, Object paid, Object carded) {
            return row(holder, holderKey, null, "payment", paid, "card", carded);
        }

        /** A row of {@code type}: its id, its status when given, and each named value its entity maps. */
        private static Object row(Class<?> type, long key, String rowStatus, Object... values) {
            try {
                Object row = type.getDeclaredConstructor().newInstance();
                type.getField("id").set(row, key);
                if (rowStatus != null) {
                    type.getField("status").set(row, rowStatus);
                }
                for (int i = 0; i < values.length; i += 2) {
                    String field = (String) values[i];
                    if (Arrays.stream(type.getFields()).anyMatch(each -> each.getName().equals(field))) {
                        type.getField(field).set(row, values[i + 1]);
                    }
                }
                return row;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @FunctionalInterface
    private interface Work {
        void run(EntityManager em, TreatRenderer renderer);
    }
}
