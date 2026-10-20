package com.rey.modelquery.hibernate;

import com.rey.modelquery.core.ExpectedNarrowing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.DiscriminatorConverter;
import org.hibernate.metamodel.mapping.EntityAssociationMapping;
import org.hibernate.metamodel.mapping.EntityDiscriminatorMapping;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.SingularAttributeMapping;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.JoinedSubclassEntityPersister;
import org.hibernate.persister.entity.SingleTableEntityPersister;
import org.hibernate.persister.entity.UnionSubclassEntityPersister;
import org.hibernate.query.sqm.ComparisonOperator;
import org.hibernate.query.sqm.spi.BaseSemanticQueryWalker;
import org.hibernate.query.sqm.tree.SqmJoinType;
import org.hibernate.query.sqm.tree.SqmVisitableNode;
import org.hibernate.query.sqm.tree.domain.SqmDerivedRoot;
import org.hibernate.query.sqm.tree.domain.SqmTreatedPath;
import org.hibernate.query.sqm.tree.from.SqmDerivedJoin;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmJoin;
import org.hibernate.query.sqm.tree.select.SqmQuerySpec;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.hibernate.sql.Template;
import org.hibernate.sql.ast.SqlAstJoinType;
import org.hibernate.sql.ast.spi.AbstractSqlAstWalker;
import org.hibernate.sql.ast.tree.cte.CteTableGroup;
import org.hibernate.sql.ast.tree.expression.CaseSearchedExpression;
import org.hibernate.sql.ast.tree.expression.CaseSimpleExpression;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.EntityTypeLiteral;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.QueryLiteral;
import org.hibernate.sql.ast.tree.expression.SelfRenderingExpression;
import org.hibernate.sql.ast.tree.expression.SqlSelectionExpression;
import org.hibernate.sql.ast.tree.from.FromClause;
import org.hibernate.sql.ast.tree.from.FunctionTableReference;
import org.hibernate.sql.ast.tree.from.NamedTableReference;
import org.hibernate.sql.ast.tree.from.QueryPartTableGroup;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.from.TableGroupJoin;
import org.hibernate.sql.ast.tree.from.TableReferenceJoin;
import org.hibernate.sql.ast.tree.from.UnionTableReference;
import org.hibernate.sql.ast.tree.from.ValuesTableReference;
import org.hibernate.sql.ast.tree.predicate.ComparisonPredicate;
import org.hibernate.sql.ast.tree.predicate.ExistsPredicate;
import org.hibernate.sql.ast.tree.predicate.GroupedPredicate;
import org.hibernate.sql.ast.tree.predicate.InListPredicate;
import org.hibernate.sql.ast.tree.predicate.Junction;
import org.hibernate.sql.ast.tree.predicate.NegatedPredicate;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.hibernate.spi.NavigablePath;

/**
 * The statement preflight's walk of a translated select (R-VND-26, RFC 0016 §11 guard 3): every query spec of the
 * statement, its CTE bodies, derived tables and sub-selects included, has each {@code from} root checked against the
 * rows its own entity holds. A root is the node the statement reads untreated, so a narrowing there loses rows: a
 * {@code SINGLE_TABLE} root read from a derived table restricted to some discriminator values, a
 * {@code TABLE_PER_CLASS} root read from a union that lacks one of its entity's tables, an {@code INNER} join from a
 * root to a table that holds only some of its subtypes' rows ({@code JOINED}), or a discriminator restriction in the
 * {@code where} that leaves out one of its entity's concrete subtypes, the discriminator a column, a formula,
 * {@code TABLE_PER_CLASS}'s {@code clazz_} or {@code JOINED}'s {@code CASE}. A subtype root is expected to hold only
 * its subtype's rows, and is checked against them. A treated {@code TABLE_PER_CLASS} join must exclude rows outside
 * its subtype: each extra union branch must have a null join key or an effective discriminator restriction (D-161).
 * Guarding selected values with a {@code CASE} does not exclude a matching {@code INNER} row. A to-one {@code LEFT}
 * join may keep it when all target reads, its presence key included, are null for that row.
 *
 * <p>A sub-select root the engine declares narrowed ({@link ExpectedNarrowing}, D-157) is expected rather than
 * refused: the correlated root of an {@code exists} that is a direct operand of its parent's {@code where} or
 * {@code having} (under {@code OR} and {@code NOT} too) and is correlated to an untreated parent root by a top-level
 * {@code =} of their single id columns. Neither line narrows a correlated count's own sub-select root, so none is
 * declared and one that narrows is refused. Each such root consumes one unused declaration of its kind and entity
 * whose subtype renders the same narrowing: the derived table's discriminator values on {@code SINGLE_TABLE} (a
 * column or a formula discriminator), the tables it is {@code INNER} joined to on {@code JOINED}, the union's tables
 * on {@code TABLE_PER_CLASS}, where the union may also keep the root entity's own table when none of its rows can
 * match ({@link #rendered}); a discriminator restriction on it must admit that subtype. A narrowed root that
 * consumes none, a declaration left unconsumed, an unknown kind, a declaration of several subtypes, or two
 * declarations of one kind and entity naming different subtypes are refusals.
 *
 * <p>A top-level {@code ofType} of the statement's own {@code where} is declared as a type restriction of its node
 * (D-158): the statement's single root of that entity may then be narrowed, in its table expression, its joins or by
 * a top-level discriminator restriction, to any set of rows that holds every declared type's, and need not be. Such a
 * declaration is consumed by nothing; it never describes a sub-select's root or another narrowing, which stay refused.
 *
 * <p>Both lines hide the {@code SINGLE_TABLE} and {@code TABLE_PER_CLASS} narrowing in the root's table expression,
 * an opaque SQL string, so those are compared with the persister's own table expression: lexically, never parsed. A
 * shape the walk does not recognise (a root that is neither an entity, a CTE nor a derived table, a function or values
 * table, an unknown persister, a discriminator read outside a {@code CASE} that is no top-level restriction) is a
 * refusal: the walk fails closed. One walker serves one statement; it keeps no state between statements and no verdict.
 *
 * @implSpec R-VND-26
 */
final class TreatPreflight extends AbstractSqlAstWalker {

    /** A branch of a {@code TABLE_PER_CLASS} union, its table the first token after {@code from}. */
    private static final Pattern UNION_BRANCH = Pattern.compile(" from (\\S+?)(?=\\)|\\s|$)");

    /** One branch of a {@code TABLE_PER_CLASS} union split on {@code union all}: its select list and its table. */
    private static final Pattern UNION_SELECT = Pattern.compile("select (.+) from (\\S+)");

    /** A discriminator read the walk cannot attribute to a single root of its query. */
    private static final Discriminated UNATTRIBUTED = new Discriminated(null, null);

    /**
     * A {@code SINGLE_TABLE} root's derived table restricted to some discriminator values, as both lines render it:
     * the table, the discriminator column, and the values of an {@code in} list or of one {@code =}.
     */
    private static final Pattern DERIVED = Pattern.compile(
            "\\(select \\* from (\\S+) t where t\\.(\\S+?)(?: in \\((.+)\\)|=(.+))\\)");

    /** The {@code exists} declarations no sub-select root has consumed yet, in build order. */
    private final List<ExpectedNarrowing> unused;

    /**
     * The statement's {@code ofType} restrictions (D-158): the provider may narrow their node to the declared types,
     * in its root or by its discriminator, but need not, so none has to be consumed.
     */
    private final List<ExpectedNarrowing> restrictions;

    /** The statement's own query spec, the only one whose roots a {@link #restrictions} declaration describes. */
    private QuerySpec top;

    private final Map<NavigablePath, List<TreatedJoin>> treatedJoins = new HashMap<>();

    private final Set<NavigablePath> checkedJoins = new HashSet<>();

    private final List<JoinRoot> joinRoots = new ArrayList<>();

    /** The sub-selects a declaration can narrow, found in their parent's clauses before they are walked. */
    private final Map<QuerySpec, Candidate> candidates = new IdentityHashMap<>();

    private String refusal;

    private TreatPreflight(List<ExpectedNarrowing> expected) {
        this.unused = new ArrayList<>(expected.stream()
                .filter(each -> each.kind() != ExpectedNarrowing.Kind.TYPE_RESTRICTION).toList());
        this.restrictions = expected.stream()
                .filter(each -> each.kind() == ExpectedNarrowing.Kind.TYPE_RESTRICTION).toList();
    }

    /**
     * The reason {@code statement} narrows a node it reads untreated, or empty when it narrows none but the
     * sub-select roots {@code expected} declares, each consumed once (R-VND-26, D-157).
     */
    static Optional<String> walk(SelectStatement statement, List<ExpectedNarrowing> expected) {
        return walk(statement, expected, null, null);
    }

    /** Adds the statement's declared treated joins, without relying on the selected expressions to identify them. */
    static Optional<String> walk(SelectStatement statement, List<ExpectedNarrowing> expected,
            SqmSelectStatement<?> sqm, SessionFactoryImplementor factory) {
        TreatPreflight walk = new TreatPreflight(expected);
        if (sqm != null) {
            sqm.accept(new BaseSemanticQueryWalker() {
                @Override
                public Object visitQuerySpec(SqmQuerySpec<?> spec) {
                    Set<SqmFrom<?, ?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                    spec.getFromClause().getRoots().forEach(root -> walk.treatedJoins(root, factory, seen, this));
                    return super.visitQuerySpec(spec);
                }
            });
        }
        walk.declarations(expected);
        walk.top = statement.getQueryPart() instanceof QuerySpec spec ? spec : null;
        if (walk.refusal == null) {
            statement.accept(walk);
        }
        if (walk.refusal == null && !walk.unused.isEmpty()) {
            ExpectedNarrowing left = walk.unused.get(0);
            walk.refuse("the engine declared a " + left.kind() + " narrowing of " + left.entity().getSimpleName()
                    + " as " + simpleNames(left.types()) + " that no sub-select root of the statement renders");
        }
        if (walk.refusal == null) {
            for (JoinRoot root : walk.joinRoots) {
                walk.joins(root.group(), root.spec());
                if (walk.refusal != null) {
                    break;
                }
            }
        }
        if (walk.refusal == null && !walk.checkedJoins.containsAll(walk.treatedJoins.keySet())) {
            walk.refuse("a treated TABLE_PER_CLASS join has no recognised SQL table group");
        }
        return Optional.ofNullable(walk.refusal);
    }

    private void treatedJoins(SqmFrom<?, ?> from, SessionFactoryImplementor factory, Set<SqmFrom<?, ?>> seen,
            BaseSemanticQueryWalker walker) {
        if (!seen.add(from)) {
            return;
        }
        if (from instanceof SqmTreatedPath<?, ?> treated && treated.getWrappedPath() instanceof SqmJoin<?, ?> join
                && factory.getMappingMetamodel().getEntityDescriptor(join.getJavaType())
                        instanceof UnionSubclassEntityPersister entity) {
            if (join.getSqmJoinType() != SqmJoinType.INNER && join.getSqmJoinType() != SqmJoinType.LEFT) {
                refuse("a treated TABLE_PER_CLASS join has an unrecognised join type");
            }
            treatedJoins.computeIfAbsent(join.getNavigablePath(), ignored -> new ArrayList<>())
                    .add(new TreatedJoin(entity, treated.getTreatTarget().getJavaType()));
        }
        // Hibernate's semantic walker does not descend into these FROM nodes on either supported line.
        if (from instanceof SqmDerivedRoot<?> derived) {
            derived.getQueryPart().accept(walker);
        } else if (from instanceof SqmDerivedJoin<?> derived) {
            derived.getQueryPart().accept(walker);
        } else if (from.getClass().getSimpleName().equals("SqmFunctionRoot")
                || from.getClass().getSimpleName().equals("SqmFunctionJoin")) {
            // Set-returning FROM functions exist only on 7.4; keep the module compilable against 6.6.
            try {
                Object function = from.getClass().getMethod("getFunction").invoke(from);
                if (function instanceof SqmVisitableNode node) {
                    node.accept(walker);
                } else {
                    refuse("a FROM function has no recognised semantic query part");
                }
            } catch (ReflectiveOperationException failed) {
                refuse("a FROM function has no accessible semantic query part");
            }
        }
        from.getSqmJoins().forEach(join -> treatedJoins(join, factory, seen, walker));
        from.getSqmTreats().forEach(treat -> treatedJoins(treat, factory, seen, walker));
    }

    /**
     * Refuses a declaration the walk cannot attribute: an unknown kind, an {@code exists} root of several subtypes or
     * two {@code exists} roots of one entity apart. A type restriction may name several types, and two may differ.
     */
    private void declarations(List<ExpectedNarrowing> expected) {
        for (ExpectedNarrowing each : expected) {
            String entity = each.entity().getSimpleName();
            if (each.kind() == ExpectedNarrowing.Kind.TYPE_RESTRICTION) {
                continue;
            }
            if (each.kind() != ExpectedNarrowing.Kind.EXISTS_ROOT) {
                refuse("a " + each.kind() + " narrowing of " + entity + " is a kind the preflight does not recognise");
                return;
            }
            if (each.types().size() != 1) {
                refuse("a " + each.kind() + " narrowing of " + entity + " as " + simpleNames(each.types())
                        + " at once is one no sub-select root renders");
                return;
            }
            for (ExpectedNarrowing other : expected) {
                if (other.kind() == each.kind() && other.entity() == each.entity()
                        && !other.types().equals(each.types())) {
                    refuse("two " + each.kind() + " narrowings of " + entity + " declare different subtypes, "
                            + simpleNames(each.types()) + " and " + simpleNames(other.types())
                            + ", which the preflight cannot attribute");
                    return;
                }
            }
        }
    }

    @Override
    public void visitQuerySpec(QuerySpec spec) {
        if (refusal != null) {
            return;
        }
        Candidate candidate = candidates.remove(spec);
        Map<String, Discriminated> discriminated = new HashMap<>();
        Map<String, Read> roots = new HashMap<>();
        for (TableGroup root : spec.getFromClause().getRoots()) {
            root(root, discriminated, roots, spec, candidate);
            joinRoots.add(new JoinRoot(root, spec));
        }
        if (refusal == null && spec.getWhereClauseRestrictions() != null && !discriminated.isEmpty()) {
            where(spec.getWhereClauseRestrictions(), discriminated, spec);
        }
        if (refusal == null) {
            exists(spec.getWhereClauseRestrictions(), roots);
            exists(spec.getHavingClauseRestrictions(), roots);
            super.visitQuerySpec(spec);
        }
    }

    /** Checks the actual join target, including nested joins, after the root and declaration checks have passed. */
    private void joins(TableGroup owner, QuerySpec spec) {
        for (TableGroupJoin join : Stream.concat(owner.getTableGroupJoins().stream(),
                owner.getNestedTableGroupJoins().stream()).toList()) {
            TableGroup group = join.getJoinedGroup();
            List<TreatedJoin> declared = treatedJoins.get(group.getNavigablePath());
            if (declared == null && group.isInitialized()
                    && group.getPrimaryTableReference() instanceof UnionTableReference) {
                refuse("a TABLE_PER_CLASS join has no recorded treated declaration");
                return;
            }
            if (declared != null) {
                checkedJoins.add(group.getNavigablePath());
                for (TreatedJoin treated : declared) {
                    treatedJoin(join, spec, treated);
                }
            }
            if (group.isInitialized()) {
                joins(group, spec);
            }
        }
    }

    private void treatedJoin(TableGroupJoin join, QuerySpec spec, TreatedJoin treated) {
        TableGroup group = join.getJoinedGroup();
        if (!group.isInitialized() || !(group.getPrimaryTableReference() instanceof NamedTableReference table)
                || (join.getJoinType() != SqlAstJoinType.INNER && join.getJoinType() != SqlAstJoinType.LEFT)) {
            refuse("a treated TABLE_PER_CLASS join has an unrecognised SQL shape");
            return;
        }
        String expression = table.getTableExpression();
        Set<String> observed = branches(expression);
        Set<String> allowed = narrowing(treated.entity(), treated.subtype());
        EntityMappingType subtype = subtype(treated.entity(), treated.subtype());
        if (allowed == null || subtype == null || observed.isEmpty()
                || !branches(treated.entity().getTableName()).containsAll(observed)
                || !knownUnion(expression)) {
            refuse("a treated TABLE_PER_CLASS join has an unrecognised union shape");
            return;
        }
        Set<String> outside = new TreeSet<>(observed);
        outside.removeAll(allowed);
        if (outside.isEmpty() || restrictedTo(spec, group, table.getIdentificationVariable(), treated.entity(),
                subtype, join.getPredicate())) {
            return;
        }
        if (join.getJoinType() == SqlAstJoinType.LEFT
                && nullLeftReads(spec, group, table.getIdentificationVariable(), treated, outside, expression)) {
            return;
        }
        List<Predicate> conjuncts = new ArrayList<>();
        if (join.getPredicate() != null) {
            conjuncts(join.getPredicate(), conjuncts);
        }
        for (String branch : outside) {
            boolean nullKey = conjuncts.stream().anyMatch(predicate -> {
                if (predicate instanceof ComparisonPredicate equal && equal.getOperator() == ComparisonOperator.EQUAL
                        && equal.getLeftHandExpression() instanceof ColumnReference left
                        && equal.getRightHandExpression() instanceof ColumnReference right) {
                    String alias = table.getIdentificationVariable();
                    ColumnReference key = alias.equals(left.getQualifier()) && !alias.equals(right.getQualifier())
                            ? left : alias.equals(right.getQualifier()) && !alias.equals(left.getQualifier())
                            ? right : null;
                    return key != null && nullId(expression, branch, key.getColumnExpression());
                }
                return false;
            });
            if (!nullKey) {
                refuse("the treated " + name(treated.entity()) + " join as " + treated.subtype().getSimpleName()
                        + " keeps the union branch " + branch + " without a null join key or subtype restriction;"
                        + " make the base entity abstract, declare the join LEFT, or read only subtype-own columns");
            }
        }
    }

    /** A to-one LEFT match is indistinguishable from a miss only when every target read is null or guarded. */
    private static boolean nullLeftReads(QuerySpec spec, TableGroup group, String alias, TreatedJoin treated,
            Set<String> outside, String expression) {
        if (!(group.getModelPart() instanceof EntityAssociationMapping)
                || !(group.getModelPart() instanceof SingularAttributeMapping)) {
            return false;
        }
        Set<String> aliases = new HashSet<>();
        targetAliases(group, aliases);
        class Reads extends AbstractSqlAstWalker {
            private boolean safe = true;

            @Override
            public void visitColumnReference(ColumnReference column) {
                String qualifier = column.getQualifier();
                if (aliases.contains(qualifier) && (!alias.equals(qualifier) || outside.stream()
                        .anyMatch(branch -> !nullId(expression, branch, column.getColumnExpression())))) {
                    safe = false;
                }
            }

            @Override
            public void visitCaseSearchedExpression(CaseSearchedExpression value) {
                Set<String> concrete = concrete(subtype(treated.entity(), treated.subtype()), new TreeSet<>());
                Map<String, Discriminated> target =
                        Map.of(alias, new Discriminated(treated.entity(), treated.entity()));
                boolean nullElse = value.getOtherwise() == null
                        || value.getOtherwise() instanceof QueryLiteral<?> literal && literal.getLiteralValue() == null;
                if (nullElse && !value.getWhenFragments().isEmpty()
                        && value.getWhenFragments().stream().allMatch(when -> {
                    Restriction restriction = restriction(when.getPredicate(), target);
                    return restriction != null && restriction.root() != UNATTRIBUTED
                            && restriction.admitted() != null && concrete.containsAll(restriction.admitted());
                })) {
                    return;
                }
                super.visitCaseSearchedExpression(value);
            }

            @Override
            public void visitFromClause(FromClause fromClause) {
                // Join equalities are checked separately; inspect expressions in every query clause.
            }

            @Override
            public void visitSqlSelectionExpression(SqlSelectionExpression selection) {
                // The select clause already checks the referenced expression, which may refer to itself.
            }
        }
        Reads reads = new Reads();
        spec.accept(reads);
        return reads.safe;
    }

    private static void targetAliases(TableGroup group, Set<String> aliases) {
        if (!group.isInitialized()) {
            return;
        }
        aliases.add(group.getPrimaryTableReference().getIdentificationVariable());
        group.getTableReferenceJoins().forEach(join ->
                aliases.add(join.getJoinedTableReference().getIdentificationVariable()));
        Stream.concat(group.getTableGroupJoins().stream(), group.getNestedTableGroupJoins().stream())
                .forEach(join -> targetAliases(join.getJoinedGroup(), aliases));
    }

    /** The same lexical union shape understood by {@link #nullId}; anything else fails closed. */
    private static boolean knownUnion(String expression) {
        return !expression.startsWith("(") ? !expression.contains(" ")
                : expression.endsWith(")") && Stream.of(expression.substring(1, expression.length() - 1)
                        .split(" union all ")).allMatch(branch -> UNION_SELECT.matcher(branch).matches());
    }

    /**
     * An order or group item that refers to a select item, which the select clause's walk reads: the base walker
     * follows it to the item's expression, which can be the reference itself, and never returns.
     */
    @Override
    public void visitSqlSelectionExpression(SqlSelectionExpression expression) {
    }

    @Override
    public void visitFunctionTableReference(FunctionTableReference reference) {
        refuse("a function table is a shape the preflight does not recognise");
    }

    @Override
    public void visitValuesTableReference(ValuesTableReference reference) {
        refuse("a values table is a shape the preflight does not recognise");
    }

    /**
     * Checks one {@code from} root of {@code spec} against its own entity's rows, records it in {@code roots} and its
     * discriminator column in {@code discriminated}. A narrowed root is refused, unless {@code spec} is a
     * {@code candidate} and the root consumes a declaration (D-157).
     */
    private void root(TableGroup root, Map<String, Discriminated> discriminated, Map<String, Read> roots,
            QuerySpec spec, Candidate candidate) {
        if (root instanceof CteTableGroup || root instanceof QueryPartTableGroup) {
            return; // the CTE body and the derived table's query are walked as statements of their own
        }
        if (!(root.getModelPart() instanceof AbstractEntityPersister entity)) {
            refuse("a " + root.getClass().getSimpleName() + " root is a shape the preflight does not recognise");
            return;
        }
        if (!(root.getPrimaryTableReference() instanceof NamedTableReference table)) {
            refuse(name(entity) + " is read from a " + root.getPrimaryTableReference().getClass().getSimpleName()
                    + ", a shape the preflight does not recognise");
            return;
        }
        String read = table.getTableExpression();
        Set<String> own;
        // How the root is narrowed, and what it reads then, compared with what a declared subtype renders.
        String lost = null;
        Set<String> narrowing = null;
        if (entity instanceof SingleTableEntityPersister single) {
            own = tables(single);
            if (!read.equals(single.getTableName())) {
                lost = name(entity) + " is read from a derived table restricted to some of its subtypes, so its "
                        + "other rows are lost";
                narrowing = derivedValues(single, read);
            }
        } else if (entity instanceof JoinedSubclassEntityPersister) {
            own = tables(entity);
            if (!own.contains(read)) {
                refuse(name(entity) + " is read from the table " + read + ", which holds only some of its rows");
                return;
            }
        } else if (entity instanceof UnionSubclassEntityPersister union) {
            own = branches(union.getTableName());
            Set<String> missing = new TreeSet<>(own);
            missing.removeAll(branches(read));
            if (!missing.isEmpty()) {
                lost = name(entity) + " is read from a union without the tables " + missing + ", so their rows are "
                        + "lost";
                narrowing = branches(read);
            }
        } else {
            refuse(name(entity) + " has a " + entity.getClass().getSimpleName() + ", a persister the preflight does "
                    + "not recognise");
            return;
        }
        Set<String> joined = new LinkedHashSet<>();
        for (TableReferenceJoin join : root.getTableReferenceJoins()) {
            String other = join.getJoinedTableReference().getTableExpression();
            if (join.getJoinType() != SqlAstJoinType.LEFT && !own.contains(other)) {
                joined.add(other);
            }
        }
        if (!joined.isEmpty()) {
            String inner = name(entity) + " is INNER joined to the table " + joined.iterator().next() + ", so the "
                    + "rows of its other subtypes are lost";
            if (lost != null || !(entity instanceof JoinedSubclassEntityPersister)) {
                refuse(inner); // a narrowing no strategy renders for one treat
                return;
            }
            lost = inner;
            narrowing = joined;
        }
        EntityMappingType readAs = entity;
        if (lost != null) {
            if (candidate == null) {
                if (spec != top || !typeRestricted(entity, narrowing)) {
                    refuse(lost);
                    return;
                }
            } else {
                readAs = attribute(candidate, entity, root, narrowing, spec, lost);
                if (readAs == null) {
                    return;
                }
            }
        }
        roots.put(table.getIdentificationVariable(), new Read(entity, lost != null));
        if (entity.getDiscriminatorMapping() != null) {
            discriminated.put(table.getIdentificationVariable(), new Discriminated(entity, readAs));
        }
    }

    /**
     * The subtype a narrowed root of a {@code candidate} sub-select is read as once it consumes a declaration, or
     * {@code null} after refusing it, {@code lost} saying how it was narrowed: an {@code exists} root must be
     * correlated to an untreated parent root by their ids, and the declaration must be of the candidate's kind and
     * the root's entity, with a subtype that renders {@code narrowing}.
     */
    private EntityMappingType attribute(Candidate candidate, AbstractEntityPersister entity, TableGroup group,
            Set<String> narrowing, QuerySpec spec, String lost) {
        NamedTableReference table = (NamedTableReference) group.getPrimaryTableReference();
        String root = name(entity) + "'s " + candidate.kind() + " sub-select root";
        if (candidate.kind() == ExpectedNarrowing.Kind.EXISTS_ROOT) {
            String uncorrelated = correlation(candidate, entity, table, spec);
            if (uncorrelated != null) {
                refuse(root + " " + uncorrelated + ": " + lost);
                return null;
            }
        }
        if (narrowing == null) {
            refuse(root + " is read from a derived table the preflight does not recognise: " + lost);
            return null;
        }
        for (Iterator<ExpectedNarrowing> each = unused.iterator(); each.hasNext(); ) {
            ExpectedNarrowing declared = each.next();
            Class<?> type = declared.types().iterator().next();
            String alias = table.getIdentificationVariable();
            if (declared.kind() == candidate.kind() && declared.entity() == entity.getMappedClass()
                    && narrowing.equals(rendered(entity, type, narrowing, table.getTableExpression(),
                            () -> restrictedTo(spec, group, alias, entity, subtype(entity, type))))) {
                each.remove();
                return subtype(entity, type);
            }
        }
        refuse(root + " is narrowed as no narrowing the engine declared renders it: " + lost);
        return null;
    }

    /**
     * Why an {@code exists} root read from {@code table} is not correlated to its parent's root, or {@code null}: a
     * top-level {@code =} of its single id column and the id column of an untreated parent root of its hierarchy.
     */
    private static String correlation(Candidate candidate, AbstractEntityPersister entity, NamedTableReference table,
            QuerySpec spec) {
        String id = idColumn(entity);
        if (id == null) {
            return "has no single id column to correlate";
        }
        if (spec.getWhereClauseRestrictions() == null) {
            return "is not correlated to its parent's root";
        }
        for (Predicate conjunct : conjuncts(spec.getWhereClauseRestrictions(), new ArrayList<>())) {
            if (conjunct instanceof ComparisonPredicate equal && equal.getOperator() == ComparisonOperator.EQUAL
                    && equal.getLeftHandExpression() instanceof ColumnReference left
                    && equal.getRightHandExpression() instanceof ColumnReference right) {
                for (ColumnReference[] sides : new ColumnReference[][] {{left, right}, {right, left}}) {
                    String why = correlation(candidate, entity, table, id, sides[0], sides[1]);
                    if (why == null || !why.isEmpty()) {
                        return why;
                    }
                }
            }
        }
        return "is not correlated to its parent's root by their ids";
    }

    /**
     * {@code null} when {@code own = parent} correlates the root read from {@code table} to an untreated parent root
     * by their ids, a reason when the parent root is narrowed, else empty.
     */
    private static String correlation(Candidate candidate, AbstractEntityPersister entity, NamedTableReference table,
            String id, ColumnReference own, ColumnReference parent) {
        Read outer = candidate.parentRoots().get(parent.getQualifier());
        if (own.isColumnExpressionFormula() || parent.isColumnExpressionFormula() || outer == null
                || !table.getIdentificationVariable().equals(own.getQualifier())
                || !id.equals(own.getColumnExpression())
                || outer.entity().getRootEntityDescriptor() != entity.getRootEntityDescriptor()
                || !parent.getColumnExpression().equals(idColumn(outer.entity()))) {
            return "";
        }
        return outer.narrowed() ? "is correlated to a parent root that is itself narrowed" : null;
    }

    /** {@code entity}'s single id column, or {@code null} when its id spans several. */
    private static String idColumn(AbstractEntityPersister entity) {
        String[] columns = entity.getIdentifierColumnNames();
        return columns.length == 1 ? columns[0] : null;
    }

    /**
     * What a root of {@code entity} treated as {@code type} reads when it is narrowed: the discriminator values of the
     * concrete entities of {@code type}'s on {@code SINGLE_TABLE}, the tables beyond {@code entity}'s it is joined to
     * on {@code JOINED}, its union's tables on {@code TABLE_PER_CLASS}; {@code null} for a type outside the hierarchy.
     */
    private static Set<String> narrowing(AbstractEntityPersister entity, Class<?> type) {
        EntityMappingType subtype = subtype(entity, type);
        if (subtype == null) {
            return null;
        }
        if (entity instanceof SingleTableEntityPersister) {
            Set<String> values = new HashSet<>();
            concreteTypes(subtype, new ArrayList<>()).forEach(each ->
                    values.add(((AbstractEntityPersister) each.getEntityPersister()).getDiscriminatorSQLValue()));
            return values;
        }
        if (entity instanceof JoinedSubclassEntityPersister) {
            Set<String> tables = tables(subtype);
            tables.removeAll(tables(entity));
            return tables;
        }
        return subtype.getEntityPersister() instanceof UnionSubclassEntityPersister union
                ? branches(union.getTableName()) : null;
    }

    /**
     * What a sub-select root of {@code entity} reads when the engine treats it as {@code type}, given what it was
     * {@code observed} reading from the table {@code expression}: its {@link #narrowing}, and on
     * {@code TABLE_PER_CLASS} also
     * {@code entity}'s own table when the union keeps it and none of that table's rows can match. Both lines keep the
     * correlated root's own table in the union of a treat as a subtype with subtypes of its own (D-157); its rows are
     * then left out only when that branch reads a null id, which the id correlation never matches, or when
     * {@code restricted}: a restriction of the root's {@code clazz_} to exactly {@code type}'s concrete entities is a
     * top-level conjunct of the {@code where} or of an {@code INNER} join's condition from the root. A branch whose
     * rows keep their id and are only read through a {@code CASE} guard is not: an {@code is null} test of the
     * guarded column matches them.
     */
    static Set<String> rendered(AbstractEntityPersister entity, Class<?> type, Set<String> observed,
            String expression, BooleanSupplier restricted) {
        Set<String> narrowing = narrowing(entity, type);
        if (narrowing == null || !(entity instanceof UnionSubclassEntityPersister union)
                || !observed.contains(union.getTableName(0)) || narrowing.contains(union.getTableName(0))) {
            return narrowing;
        }
        EntityMappingType subtype = subtype(entity, type);
        if (subtype.getSubMappingTypes().isEmpty()
                || !(nullId(expression, union.getTableName(0), idColumn(entity)) || restricted.getAsBoolean())) {
            return narrowing;
        }
        Set<String> kept = new HashSet<>(narrowing);
        kept.add(union.getTableName(0));
        return kept;
    }

    /**
     * Whether the {@code TABLE_PER_CLASS} union {@code expression} reads {@code table}'s rows with a null {@code id},
     * as {@code null as id} or, on some dialects, {@code cast(null as bigint) as id} in that table's branch;
     * {@code false} for a union of another shape.
     */
    private static boolean nullId(String expression, String table, String id) {
        if (id == null || !expression.startsWith("(") || !expression.endsWith(")")) {
            return false;
        }
        for (String branch : expression.substring(1, expression.length() - 1).split(" union all ")) {
            Matcher select = UNION_SELECT.matcher(branch);
            if (select.matches() && select.group(2).equals(table)) {
                Pattern nullId = Pattern.compile("(?:null|cast\\(null as [^,]+\\)) as " + Pattern.quote(id));
                return Stream.of(select.group(1).split(", ")).anyMatch(item -> nullId.matcher(item).matches());
            }
        }
        return false;
    }

    /**
     * Whether a top-level conjunct of {@code spec}'s {@code where}, or of the condition of an {@code INNER} join from
     * {@code group}, which drops the root's rows it fails as the {@code where} does, restricts the discriminator of
     * the root read as {@code alias} to exactly the concrete entities of {@code subtype}.
     */
    private static boolean restrictedTo(QuerySpec spec, TableGroup group, String alias,
            AbstractEntityPersister entity, EntityMappingType subtype) {
        return restrictedTo(spec, group, alias, entity, subtype, null);
    }

    private static boolean restrictedTo(QuerySpec spec, TableGroup group, String alias,
            AbstractEntityPersister entity, EntityMappingType subtype, Predicate on) {
        List<Predicate> conjuncts = new ArrayList<>();
        if (on != null) {
            conjuncts(on, conjuncts);
        }
        if (spec.getWhereClauseRestrictions() != null) {
            conjuncts(spec.getWhereClauseRestrictions(), conjuncts);
        }
        for (TableGroupJoin join : group.getTableGroupJoins()) {
            if (join.getJoinType() == SqlAstJoinType.INNER && join.getPredicate() != null) {
                conjuncts(join.getPredicate(), conjuncts);
            }
        }
        Map<String, Discriminated> root = Map.of(alias, new Discriminated(entity, entity));
        Set<String> concrete = concrete(subtype, new TreeSet<>());
        for (Predicate conjunct : conjuncts) {
            Restriction restriction = restriction(conjunct, root);
            if (restriction != null && restriction.root() != UNATTRIBUTED
                    && concrete.equals(restriction.admitted())) {
                return true;
            }
        }
        return false;
    }

    /** {@code entity} or the subtype of it whose class is {@code type}, or {@code null}. */
    private static EntityMappingType subtype(EntityMappingType entity, Class<?> type) {
        if (entity.getEntityPersister().getMappedClass() == type) {
            return entity;
        }
        for (EntityMappingType sub : entity.getSubMappingTypes()) {
            EntityMappingType found = subtype(sub, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The discriminator values a {@code SINGLE_TABLE} root's derived table {@code read} restricts its table to, or
     * {@code null} for a derived table of another shape.
     */
    private static Set<String> derivedValues(SingleTableEntityPersister entity, String read) {
        Matcher derived = DERIVED.matcher(read);
        EntityDiscriminatorMapping discriminator = entity.getDiscriminatorMapping();
        if (!derived.matches() || !derived.group(1).equals(entity.getTableName()) || discriminator == null) {
            return null;
        }
        // A formula discriminator renders its template over the derived table's alias, a column one qualified by it.
        String column = discriminator.isFormula()
                ? discriminator.getSelectionExpression().replace(Template.TEMPLATE, "t")
                : "t." + discriminator.getSelectionExpression();
        if (!column.equals("t." + derived.group(2))) {
            return null;
        }
        return derived.group(3) != null ? Set.of(derived.group(3).split(", ")) : Set.of(derived.group(4));
    }

    /**
     * Whether an {@code ofType} the engine declared keeps no row a root of {@code entity} narrowed to
     * {@code narrowing} loses (D-158): every declared type's rows are read, in the derived table's discriminator
     * values on {@code SINGLE_TABLE}, the union's tables on {@code TABLE_PER_CLASS}, and on {@code JOINED} every
     * table the root is {@code INNER} joined to holds them. Only the statement's own single root of that entity.
     */
    private boolean typeRestricted(AbstractEntityPersister entity, Set<String> narrowing) {
        if (narrowing == null || top.getFromClause().getRoots().stream()
                .filter(root -> root.getModelPart() == entity).count() != 1) {
            return false;
        }
        for (ExpectedNarrowing declared : restrictions) {
            if (declared.entity() != entity.getMappedClass()) {
                continue;
            }
            boolean kept = true;
            for (Class<?> type : declared.types()) {
                Set<String> rows = narrowing(entity, type);
                kept &= rows != null && (entity instanceof JoinedSubclassEntityPersister ? rows.containsAll(narrowing)
                        : narrowing.containsAll(rows));
            }
            if (kept) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code restriction}, a top-level conjunct of {@code spec}'s {@code where}, admits every type an
     * {@code ofType} the engine declared on its root keeps, so it loses no row that {@code ofType} keeps (D-158).
     */
    private boolean typeRestricted(Restriction restriction, Map<String, Discriminated> discriminated,
            QuerySpec spec) {
        AbstractEntityPersister entity = restriction.root().entity();
        if (spec != top || discriminated.values().stream().filter(root -> root.entity() == entity).count() != 1) {
            return false;
        }
        for (ExpectedNarrowing declared : restrictions) {
            if (declared.entity() == entity.getMappedClass() && declared.types().stream().allMatch(type -> {
                EntityMappingType subtype = subtype(restriction.root().readAs(), type);
                return subtype != null && restriction.admitted().contains(name(subtype));
            })) {
                return true;
            }
        }
        return false;
    }

    /**
     * Marks each {@code exists} that is a direct operand of {@code predicate}, under {@code AND}, {@code OR} and
     * {@code NOT}, as a sub-select whose correlated root a declaration can narrow.
     */
    private void exists(Predicate predicate, Map<String, Read> roots) {
        if (predicate instanceof GroupedPredicate grouped) {
            exists(grouped.getSubPredicate(), roots);
        } else if (predicate instanceof NegatedPredicate negated) {
            exists(negated.getPredicate(), roots);
        } else if (predicate instanceof Junction junction) {
            junction.getPredicates().forEach(each -> exists(each, roots));
        } else if (predicate instanceof ExistsPredicate exists
                && exists.getExpression().getQueryPart() instanceof QuerySpec sub) {
            candidates.put(sub, new Candidate(ExpectedNarrowing.Kind.EXISTS_ROOT, roots));
        }
    }

    /**
     * Checks the discriminator restrictions of a {@code where}: a top-level {@code in} or {@code =} on a root's
     * discriminator must admit each concrete entity of the entity the root is read as (its own, or the subtype a
     * declaration narrowed it to), and any other read of it outside a {@code CASE} guard is a narrowing the walk
     * cannot attribute. A discriminator is a column, a formula, {@code TABLE_PER_CLASS}'s {@code clazz_} or
     * {@code JOINED}'s {@code CASE} over the root's tables.
     */
    private void where(Predicate where, Map<String, Discriminated> discriminated, QuerySpec spec) {
        List<Predicate> others = new ArrayList<>();
        for (Predicate conjunct : conjuncts(where, new ArrayList<>())) {
            Restriction restriction = restriction(conjunct, discriminated);
            if (restriction == null) {
                others.add(conjunct);
                continue;
            }
            if (restriction.root() == UNATTRIBUTED) {
                refuse("a discriminator restriction reads no root of its query the preflight can attribute it to");
                return;
            }
            String entity = name(restriction.root().entity());
            if (restriction.admitted() == null) {
                refuse(entity + "'s discriminator is restricted to a value that names no entity, a narrowing the "
                        + "preflight cannot attribute");
                return;
            }
            Set<String> lost = concrete(restriction.root().readAs(), new TreeSet<>());
            lost.removeAll(restriction.admitted());
            if (!lost.isEmpty() && !typeRestricted(restriction, discriminated, spec)) {
                refuse(entity + " is restricted by its discriminator, so the rows of " + lost + " are lost");
                return;
            }
        }
        DiscriminatorReads reads = new DiscriminatorReads(discriminated);
        others.forEach(other -> other.accept(reads));
        if (reads.entity != null) {
            refuse(reads.entity + "'s discriminator is tested outside a top-level restriction, a narrowing the "
                    + "preflight cannot attribute");
        }
    }

    /**
     * The discriminator restriction {@code conjunct} is, a non-negated {@code in} or {@code =} on a root's
     * discriminator, with the names of the entities its values admit ({@code null} when one names none); or
     * {@code null} when it is none.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Restriction restriction(Predicate conjunct, Map<String, Discriminated> discriminated) {
        Expression tested;
        List<Expression> values;
        if (conjunct instanceof InListPredicate in && !in.isNegated()) {
            tested = in.getTestExpression();
            values = in.getListExpressions();
        } else if (conjunct instanceof ComparisonPredicate comparison
                && comparison.getOperator() == ComparisonOperator.EQUAL) {
            tested = comparison.getLeftHandExpression();
            values = List.of(comparison.getRightHandExpression());
        } else {
            return null;
        }
        Discriminated root = discriminator(tested, discriminated);
        if (root == null || root == UNATTRIBUTED) {
            return root == null ? null : new Restriction(root, null);
        }
        DiscriminatorConverter converter = root.entity().getDiscriminatorMapping().getValueConverter();
        Set<String> admitted = new HashSet<>();
        for (Expression value : values) {
            EntityMappingType indicated = null;
            if (value instanceof QueryLiteral<?> literal) {
                try {
                    indicated = converter.getDetailsForRelationalForm(literal.getLiteralValue()).getIndicatedEntity();
                } catch (RuntimeException unknown) {
                    // Left null: a value no entity of the hierarchy has.
                }
            } else if (value instanceof EntityTypeLiteral type) {
                indicated = type.getEntityTypeDescriptor();
            }
            if (indicated == null) {
                return new Restriction(root, null);
            }
            admitted.add(name(indicated));
        }
        return new Restriction(root, admitted);
    }

    /**
     * The root whose discriminator {@code tested} reads, {@link #UNATTRIBUTED} when it reads a discriminator of no
     * single root of {@code discriminated}, or {@code null} when it reads none. A {@code type(...)} read is typed by
     * its discriminator mapping and names its root's alias in its columns; 6.6 renders {@code JOINED}'s without
     * columns, so that one is never attributed.
     */
    private static Discriminated discriminator(Expression tested, Map<String, Discriminated> discriminated) {
        List<ColumnReference> columns = new ArrayList<>();
        tested.accept(new AbstractSqlAstWalker() {
            @Override
            public void visitColumnReference(ColumnReference column) {
                columns.add(column);
            }
        });
        if (tested.getExpressionType() instanceof EntityDiscriminatorMapping mapping) {
            EntityMappingType hierarchy = mapping.findContainingEntityMapping().getRootEntityDescriptor();
            Discriminated found = null;
            for (Map.Entry<String, Discriminated> each : discriminated.entrySet()) {
                if (each.getValue().entity().getRootEntityDescriptor() == hierarchy
                        && columns.stream().anyMatch(column -> each.getKey().equals(column.getQualifier()))) {
                    if (found != null) {
                        return UNATTRIBUTED;
                    }
                    found = each.getValue();
                }
            }
            return found == null ? UNATTRIBUTED : found;
        }
        return columns.size() == 1 && columns.get(0) == tested ? column(columns.get(0), discriminated) : null;
    }

    /**
     * The root that reads {@code column} as its discriminator column, formula or {@code clazz_}, or {@code null}.
     */
    private static Discriminated column(ColumnReference column, Map<String, Discriminated> discriminated) {
        Discriminated root = discriminated.get(column.getQualifier());
        if (root == null) {
            return null;
        }
        EntityDiscriminatorMapping discriminator = root.entity().getDiscriminatorMapping();
        String expected = discriminator.isFormula()
                ? discriminator.getSelectionExpression().replace(Template.TEMPLATE, column.getQualifier())
                : discriminator.getSelectionExpression();
        return column.isColumnExpressionFormula() == discriminator.isFormula()
                && column.getColumnExpression().equals(expected) ? root : null;
    }

    private static List<Predicate> conjuncts(Predicate predicate, List<Predicate> into) {
        if (predicate instanceof GroupedPredicate grouped) {
            return conjuncts(grouped.getSubPredicate(), into);
        }
        if (predicate instanceof Junction junction && junction.getNature() == Junction.Nature.CONJUNCTION) {
            junction.getPredicates().forEach(each -> conjuncts(each, into));
            return into;
        }
        into.add(predicate);
        return into;
    }

    /** {@code entity}'s concrete entities, itself included. */
    private static List<EntityMappingType> concreteTypes(EntityMappingType entity, List<EntityMappingType> into) {
        if (!entity.isAbstract()) {
            into.add(entity);
        }
        entity.getSubMappingTypes().forEach(sub -> concreteTypes(sub, into));
        return into;
    }

    private static String simpleNames(Set<Class<?>> types) {
        return types.stream().map(Class::getSimpleName).toList().toString();
    }

    /** The names of {@code entity}'s concrete entities, itself included. */
    private static Set<String> concrete(EntityMappingType entity, Set<String> into) {
        concreteTypes(entity, new ArrayList<>()).forEach(each -> into.add(name(each)));
        return into;
    }

    /** The tables holding each row of {@code entity}: its own and its supertypes', secondary tables included. */
    private static Set<String> tables(EntityMappingType entity) {
        Set<String> tables = new HashSet<>();
        for (EntityMappingType type = entity; type != null; type = type.getSuperMappingType()) {
            if (type.getEntityPersister() instanceof SingleTableEntityPersister single) {
                for (int i = 0; i < single.getTableSpan(); i++) {
                    tables.add(single.getTableName(i));
                }
            } else if (type.getEntityPersister() instanceof JoinedSubclassEntityPersister joined) {
                for (int i = 0; i < joined.getTableSpan(); i++) {
                    tables.add(joined.getTableName(i));
                }
            }
        }
        return tables;
    }

    /** The tables a {@code TABLE_PER_CLASS} table expression reads: a plain table, or each branch of its union. */
    private static Set<String> branches(String expression) {
        if (!expression.startsWith("(")) {
            return Set.of(expression);
        }
        Set<String> tables = new HashSet<>();
        Matcher branch = UNION_BRANCH.matcher(expression);
        while (branch.find()) {
            tables.add(branch.group(1));
        }
        return tables;
    }

    /** The entity's class, as the renderer's other reasons name it. */
    private static String name(EntityMappingType entity) {
        return entity.getEntityPersister().getMappedClass().getSimpleName();
    }

    private void refuse(String reason) {
        if (refusal == null) {
            refusal = reason;
        }
    }

    /** A root a query spec reads, and whether a declaration let it be narrowed. */
    private record Read(AbstractEntityPersister entity, boolean narrowed) {
    }

    private record TreatedJoin(UnionSubclassEntityPersister entity, Class<?> subtype) {
    }

    private record JoinRoot(TableGroup group, QuerySpec spec) {
    }

    /** A sub-select a declaration of {@code kind} can narrow, and the roots of its parent query spec. */
    private record Candidate(ExpectedNarrowing.Kind kind, Map<String, Read> parentRoots) {
    }

    /** A root with a discriminator, and the entity its rows are expected to be: its own, or a declared subtype. */
    private record Discriminated(AbstractEntityPersister entity, EntityMappingType readAs) {
    }

    /** A top-level discriminator restriction of {@code root}, admitting the entities named, or {@code null}. */
    private record Restriction(Discriminated root, Set<String> admitted) {
    }

    /** Finds a read of a root's discriminator outside a {@code CASE} guard, which only picks a shared column. */
    private static final class DiscriminatorReads extends AbstractSqlAstWalker {

        private final Map<String, Discriminated> discriminated;

        private String entity;

        DiscriminatorReads(Map<String, Discriminated> discriminated) {
            this.discriminated = discriminated;
        }

        @Override
        public void visitColumnReference(ColumnReference column) {
            Discriminated root = column(column, discriminated);
            if (entity == null && root != null) {
                entity = name(root.entity());
            }
        }

        /**
         * A sub-select's joins, whose conditions test a treated join's discriminator: a join is not checked, as in
         * the outer walk, and the sub-select's {@code where} is walked here and again as a statement of its own.
         */
        @Override
        public void visitFromClause(FromClause fromClause) {
        }

        /** 6.6 renders a {@code JOINED} discriminator as an expression of its own, typed by its mapping. */
        @Override
        public void visitSelfRenderingExpression(SelfRenderingExpression expression) {
            discriminator(expression);
        }

        @Override
        public void visitCaseSearchedExpression(CaseSearchedExpression expression) {
            // A CASE guard reads the discriminator to pick a column, and keeps every row; 7.4 renders a JOINED
            // discriminator as a CASE typed by its mapping, which is a read.
            discriminator(expression);
        }

        private void discriminator(Expression expression) {
            if (entity == null && expression.getExpressionType() instanceof EntityDiscriminatorMapping mapping) {
                entity = name(mapping.findContainingEntityMapping());
            }
        }

        @Override
        public void visitSqlSelectionExpression(SqlSelectionExpression expression) {
            // A select item's reference, as in the outer walk.
        }

        @Override
        public void visitCaseSimpleExpression(CaseSimpleExpression expression) {
            // As above.
        }
    }
}
