package com.rey.modelquery.hibernate;

import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.jpa.spi.ReturningShape;
import com.rey.modelquery.jpa.spi.ReturningSupport;
import com.rey.modelquery.jpa.spi.WriteStatement;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.hibernate.Version;
import org.hibernate.engine.jdbc.spi.JdbcCoordinator;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.EntityAssociationMapping;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.metamodel.mapping.ManagedMappingType;
import org.hibernate.metamodel.mapping.MappingModelExpressible;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.metamodel.mapping.SqlTypedMapping;
import org.hibernate.query.spi.DomainQueryExecutionContext;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.spi.SqmQuery;
import org.hibernate.query.sqm.spi.SqmParameterMappingModelResolutionAccess;
import org.hibernate.query.sqm.sql.SqmTranslation;
import org.hibernate.query.sqm.tree.SqmDmlStatement;
import org.hibernate.query.sqm.tree.expression.SqmParameter;
import org.hibernate.query.sqm.tree.insert.SqmInsertStatement;
import org.hibernate.query.sqm.tree.update.SqmUpdateStatement;
import org.hibernate.resource.jdbc.spi.LogicalConnectionImplementor;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.spi.FromClauseAccess;
import org.hibernate.sql.ast.tree.MutationStatement;
import org.hibernate.sql.ast.tree.delete.DeleteStatement;
import org.hibernate.sql.ast.tree.insert.InsertSelectStatement;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.update.UpdateStatement;
import org.hibernate.sql.exec.spi.ExecutionContext;
import org.hibernate.sql.exec.spi.JdbcOperationQueryMutation;
import org.hibernate.sql.exec.spi.JdbcParameterBinder;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;

/**
 * Hibernate's {@link ReturningSupport} (R-WRT-50, R-VND-17): it translates the write's own statement through
 * Hibernate's mutation translator, applies the vendor's returning shape, and runs it through the session's
 * {@link JdbcCoordinator}, so SQL logging, the {@code StatementInspector}, the parameter binders, the converters and
 * query-space invalidation are Hibernate's own. Every {@code org.hibernate..internal..} call this class makes lives in
 * the two line adapters beside it ({@link Hibernate6Mutations}, {@link Hibernate7Mutations}); nothing else in the
 * module imports such a type (R-WRT-50, enforced by a layering rule in the TCK).
 *
 * @implSpec R-WRT-50, R-VND-17
 */
final class HibernateMutations implements ReturningSupport {

    /** Singleton access */
    static final HibernateMutations INSTANCE = new HibernateMutations();

    private static final System.Logger LOG = System.getLogger(HibernateMutations.class.getName());

    /** The line adapters, the newest first: at most one probes true on a Hibernate line (R-WRT-50). */
    private static final List<Mutations> LINES = List.of(new Hibernate7Mutations(), new Hibernate6Mutations());

    /** The factories already warned about, so one WARN per factory outside the tested range (R-WRT-50). */
    private static final Set<SessionFactoryImplementor> WARNED =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    /** The adapter probed for each factory, so a factory is probed, and warned about, once (R-WRT-50). */
    private static final Map<SessionFactoryImplementor, Optional<Mutations>> PROBED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The SQL that passed the first-use check on a factory, so the first use of each statement is checked and the
     * rest are not; a statement that failed is not recorded and is refused again on every use (R-WRT-50).
     */
    private static final Map<SessionFactoryImplementor, Set<String>> CHECKED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The Hibernate keyword a {@code RETURNING} shape appends. */
    private static final String RETURNING = " returning ";

    /** The conflict target an arbiter predicate is spliced after, whatever whitespace follows it (R-WRT-52). */
    private static final String CONFLICT = "on conflict";

    private HibernateMutations() {
    }

    /**
     * Whether a line adapter serves {@code emf}'s Hibernate and {@code entity} writes one statement: Hibernate runs a
     * {@code @SecondaryTable} or JOINED-inheritance mutation as more than one statement, which no one statement can
     * return rows for, so that write refuses with {@code MQ4011} (R-WRT-50).
     */
    @Override
    public boolean rendered(EntityManagerFactory emf, Class<?> entity, ReturningShape shape) {
        return shape != null && entity != null && HibernateProviderSupport.sessionFactory(emf)
                .map(sf -> line(sf).isPresent() && HibernateProviderSupport.tablesOf(sf, entity).size() <= 1)
                .orElse(false);
    }

    @Override
    public List<Object[]> rows(Query statement, Class<?> entity, List<String> attributes, ReturningShape shape) {
        Objects.requireNonNull(statement, "statement");
        Mutations line = requireLine(((DomainQueryExecutionContext) statement).getSession().getFactory());
        return rows(line, statement, entity, attributes, shape);
    }

    /**
     * The adapter that serves {@code sf}'s Hibernate line, or the {@code MQ4011} of a line whose translation calls
     * this adapter makes have moved: a returning write and a conflict arbiter are refused, a plain write runs
     * (R-WRT-50, R-WRT-52).
     */
    static Mutations requireLine(SessionFactoryImplementor sf) {
        return line(sf).orElseThrow(() -> new ModelQueryExecutionException(MqCode.MQ4011, "no mutation adapter "
                + "serves Hibernate " + Version.getVersionString() + ": its mutation translation calls moved from the "
                + "ones the adapters of this version make, so a returning write or a conflict arbiter is refused "
                + "(R-WRT-50, R-WRT-52)"));
    }

    /** {@link #rows(Query, Class, List, ReturningShape)}, on {@code line}, the adapter probed for this factory. */
    private static List<Object[]> rows(Mutations line, Query statement, Class<?> entity, List<String> attributes,
            ReturningShape shape) {
        DomainQueryExecutionContext context = (DomainQueryExecutionContext) statement;
        return execute(context, translate(line, statement, entity, attributes, shape));
    }

    /**
     * Runs {@code statement}, an arbiter upsert whose {@code executeUpdate()} the engine calls, with the predicate of
     * its conflict target spliced into the SQL Hibernate translates, and returns the rows it wrote (R-WRT-52). The
     * statement is translated, checked on its first use and run through the session's own coordinator, as
     * {@link #rows} runs a returning write.
     */
    static int executeUpdate(Query statement) {
        Objects.requireNonNull(statement, "statement");
        DomainQueryExecutionContext context = (DomainQueryExecutionContext) statement;
        Mutations line = requireLine(context.getSession().getFactory());
        return count(context, translate(line, statement, null, List.of(), null));
    }

    /**
     * The SQL {@code statement} is run with, without running it: {@link #rows} translation alone, for the tests that
     * check the shapes of R-WRT-50's table on a dialect without a database of that vendor. A test seam, as
     * {@link HibernateLockSupport#isRendered} is.
     */
    static String translated(Query statement, Class<?> entity, List<String> attributes, ReturningShape shape) {
        Mutations line = requireLine(((DomainQueryExecutionContext) statement).getSession().getFactory());
        return translate(line, statement, entity, attributes, shape).sql();
    }

    /**
     * The statement {@code line} translates for {@code attributes} with {@code shape} applied and checked, with the
     * conflict arbiter of {@code statement} spliced in when it carries one (R-WRT-50, R-WRT-52). A {@code null}
     * {@code shape} renders no returning clause: the statement is an arbiter upsert run for its count alone.
     */
    private static Translated translate(Mutations line, Query statement, Class<?> entity, List<String> attributes,
            ReturningShape shape) {
        DomainQueryExecutionContext context = (DomainQueryExecutionContext) statement;
        SharedSessionContractImplementor session = context.getSession();
        SessionFactoryImplementor sf = session.getFactory();
        QueryOptions options = context.getQueryOptions();
        SqmDmlStatement<?> dml = (SqmDmlStatement<?>) ((SqmQuery) statement).getSqmStatement();
        line.invalidate(session, dml); // as Hibernate's own bulk path invalidates its spaces
        Mutations.Translation lines = line.translate(sf, dml, options, context);
        SqmTranslation<? extends MutationStatement> translation = lines.translation();
        MutationStatement ast = translation.getSqlAst();
        List<SqlTypedMapping> columns = new ArrayList<>();
        List<ColumnReference> returning = new ArrayList<>();
        if (entity != null) {
            EntityMappingType mapping = sf.getRuntimeMetamodels().getMappingMetamodel().getEntityDescriptor(entity);
            for (String attribute : attributes) {
                SelectableMapping selectable = selectable(mapping, attribute, ast);
                columns.add(selectable);
                returning.add(new ColumnReference(ast.getTargetTable(), selectable));
            }
        }
        JdbcParameterBindings bindings = lines.bindings();
        SqlAstTranslator<?> translator = sf.getJdbcServices().getJdbcEnvironment().getSqlAstTranslatorFactory()
                .buildMutationTranslator(sf, returning.isEmpty() ? ast : attach(ast, returning));
        JdbcOperationQueryMutation operation = (JdbcOperationQueryMutation) translator.translate(bindings, options);
        String arbiter = arbiter(statement);
        String rendered = arbiter == null ? operation.getSqlString() : splice(operation.getSqlString(), arbiter);
        String sql = shape == null ? rendered : shape(shape, rendered, returning);
        if (arbiter != null) {
            checkArbiter(sf, kind(dml), rendered, sql, operation.getParameterBinders().size(), shape != null);
        } else if (shape != null) {
            check(sf, kind(dml), rendered, sql, operation.getParameterBinders().size());
        }
        return new Translated(sql, operation, bindings, columns, line.execution(context));
    }

    /** The arbiter predicate {@code statement} carries, or {@code null} when it is not an arbiter upsert (R-WRT-52). */
    private static String arbiter(Query statement) {
        return ArbiterQuery.of(statement).map(ArbiterQuery::arbiter).orElse(null);
    }

    /** The SQL {@code rendered} with {@code arbiter} spliced after its conflict target (R-WRT-52). */
    static String splice(String rendered, String arbiter) {
        int close = anchor(rendered);
        if (close < 0) {
            throw mismatch(rendered, "its conflict target, which the arbiter predicate follows, is not there exactly "
                    + "once");
        }
        return rendered.substring(0, close + 1) + " where " + arbiter + rendered.substring(close + 1);
    }

    /** A statement translated for execution: its SQL, its operation and what it returns, and how its rows are read. */
    private record Translated(String sql, JdbcOperationQueryMutation operation, JdbcParameterBindings bindings,
            List<SqlTypedMapping> columns, ExecutionContext execution) {}

    /**
     * {@code rendered} with {@code shape} applied (R-WRT-50): a {@code SUFFIX} shape is the {@code returning} clause
     * Hibernate rendered, refused where it rendered none; a {@code DELTA_TABLE} shape wraps the
     * statement as {@code FINAL TABLE} on an update or an insert, and {@code OLD TABLE} on a delete, which Hibernate
     * renders itself only for its own insert and update statements.
     */
    private static String shape(ReturningShape shape, String rendered, List<ColumnReference> columns) {
        String list = columns.stream().map(ColumnReference::getColumnExpression).collect(Collectors.joining(", "));
        return switch (shape) {
            case SUFFIX -> suffix(rendered);
            case DELTA_TABLE -> "select " + list + " from " + delta(rendered) + " table (" + rendered + ")";
        };
    }

    /**
     * {@code rendered} as the suffix shape needs it: Hibernate's own translator rendered the {@code returning} clause
     * because the statement carries its returning columns, so this refuses rather than appending a clause of its own.
     */
    private static String suffix(String rendered) {
        if (!rendered.contains(RETURNING)) {
            throw mismatch(rendered, "Hibernate rendered no returning clause for it");
        }
        return rendered;
    }

    /**
     * Whether {@code rendered} writes the delta table a {@code DELTA_TABLE} shape reads: {@code final} or {@code old}.
     */
    private static String delta(String rendered) {
        return switch (leading(rendered)) {
            case "update", "insert", "merge" -> "final";
            case "delete" -> "old";
            default -> throw mismatch(rendered, "the shape reads the delta table of a write");
        };
    }

    /**
     * {@link #checkSql} on the first use of {@code sql} on {@code sf} that passes, and on no later one: a statement
     * that fails is refused again on its next use rather than run unchecked (R-WRT-50). A test seam, as
     * {@link HibernateLockSupport#isRendered} is.
     */
    static void check(SessionFactoryImplementor sf, WriteStatement kind, String rendered, String sql, int binders) {
        Set<String> passed = CHECKED.computeIfAbsent(sf, factory -> ConcurrentHashMap.newKeySet());
        if (passed.contains(sql)) {
            return;
        }
        checkSql(rendered, sql, binders, kind);
        passed.add(sql);
    }

    /**
     * {@link #checkArbiterSql} on the first use of {@code sql} on {@code sf} that passes, and on no later one
     * (R-WRT-52). A test seam, as {@link HibernateLockSupport#isRendered} is.
     */
    static void checkArbiter(SessionFactoryImplementor sf, WriteStatement kind, String rendered, String sql,
            int binders, boolean returnsRows) {
        Set<String> passed = CHECKED.computeIfAbsent(sf, factory -> ConcurrentHashMap.newKeySet());
        if (passed.contains(sql)) {
            return;
        }
        checkArbiterSql(rendered, sql, binders, kind, returnsRows);
        passed.add(sql);
    }

    /**
     * The index of the {@code )} closing {@code rendered}'s conflict target, which the arbiter predicate is spliced
     * after (R-WRT-52), or {@code -1} unless {@code on conflict} occurs exactly once outside a string literal and is
     * followed by a parenthesised column list (INV-5): a literal's text is no anchor, and neither is a named
     * constraint's target.
     */
    private static int anchor(String rendered) {
        String text = masked(rendered);
        int at = text.indexOf(CONFLICT);
        if (at < 0 || text.indexOf(CONFLICT, at + 1) >= 0) {
            return -1;
        }
        int open = at + CONFLICT.length();
        while (open < text.length() && Character.isWhitespace(text.charAt(open))) {
            open++;
        }
        if (open == text.length() || text.charAt(open) != '(') {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '(') {
                depth++;
            } else if (text.charAt(i) == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** {@code sql} in lower case with every single-quoted literal's characters blanked, each at its own index. */
    private static String masked(String sql) {
        StringBuilder text = new StringBuilder(sql.length());
        boolean quoted = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                quoted = !quoted; // a doubled quote closes and reopens the literal
                text.append(c);
            } else {
                text.append(quoted ? ' ' : Character.toLowerCase(c));
            }
        }
        return text.toString();
    }

    /**
     * The statement kind {@code dml} is, for the first-use check: the SQM insert statements of both a plain insert and
     * an upsert, an update, else a delete.
     */
    private static WriteStatement kind(SqmDmlStatement<?> dml) {
        if (dml instanceof SqmUpdateStatement) {
            return WriteStatement.UPDATE;
        }
        return dml instanceof SqmInsertStatement ? WriteStatement.INSERT : WriteStatement.DELETE;
    }

    /**
     * The first-use check of {@code sql} (R-WRT-50): the statement's own keyword, one placeholder per binder and the
     * shape's anchor once. A translation that disagrees is {@code MQ4011} before execution, so a Hibernate that moved
     * one of the calls refuses the write rather than returning the wrong rows (INV-5). A test seam, as
     * {@link HibernateLockSupport#isRendered} is.
     */
    static void checkSql(String rendered, String sql, int binders, WriteStatement kind) {
        checkKeyword(rendered, sql, kind);
        String unquoted = unquoted(sql); // a literal's ? or keyword is no placeholder and no anchor (R-COL-27)
        if (occurrences(unquoted, "?") != binders) {
            throw mismatch(sql, "it binds " + occurrences(unquoted, "?") + " placeholders with " + binders
                    + " binders");
        }
        checkReturning(unquoted, sql);
    }

    /**
     * The first-use check of an arbiter upsert's {@code sql} (R-WRT-52): the statement's own keyword, one placeholder
     * per binder, the conflict target its arbiter predicate was spliced after found exactly once, and, where the
     * statement returns rows too, the returning clause as {@link #checkSql} checks it. A translation that disagrees is
     * {@code MQ4011} before execution (INV-5). A test seam, as {@link HibernateLockSupport#isRendered} is.
     */
    static void checkArbiterSql(String rendered, String sql, int binders, WriteStatement kind, boolean returnsRows) {
        checkKeyword(rendered, sql, kind);
        if (anchor(rendered) < 0) {
            throw mismatch(sql, "its conflict target, which the arbiter predicate was spliced after, is not there "
                    + "exactly once");
        }
        String unquoted = unquoted(sql);
        if (occurrences(unquoted, "?") != binders) {
            throw mismatch(sql, "it binds " + occurrences(unquoted, "?") + " placeholders with " + binders
                    + " binders");
        }
        if (returnsRows) {
            checkReturning(unquoted, sql);
        }
    }

    /** {@code rendered}'s leading keyword, checked against {@code kind}'s (R-WRT-50). */
    private static void checkKeyword(String rendered, String sql, WriteStatement kind) {
        List<String> expected = leadingKeywords(kind);
        if (!expected.contains(leading(rendered))) {
            throw mismatch(sql, "it does not start with " + String.join(" or ", expected));
        }
    }

    /** The returning clause of {@code unquoted}, checked to be the shape's single one (R-WRT-50). */
    private static void checkReturning(String unquoted, String sql) {
        if (unquoted.contains(" final table (") || unquoted.contains(" old table (")) {
            if (occurrences(unquoted, RETURNING) > 0 || occurrences(unquoted, " table (") != 1) {
                throw mismatch(sql, "its delta table and its returning clause do not agree");
            }
        } else if (occurrences(unquoted, RETURNING) != 1) {
            throw mismatch(sql, "it has no single returning clause");
        }
    }

    /** {@code sql} with every single-quoted literal emptied, its doubled quotes included. */
    private static String unquoted(String sql) {
        return sql.replaceAll("'(?:[^']|'')*'", "''");
    }

    /** The rows {@code translated} returns, prepared, bound, run and released as Hibernate's own mutation executor. */
    private static List<Object[]> execute(DomainQueryExecutionContext context, Translated translated) {
        return run(context, translated, "returning the rows of", (statement, session) -> {
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(read(resultSet, translated.columns(), session));
                }
            }
            return rows;
        });
    }

    /** The rows {@code translated} wrote, run as Hibernate's own mutation executor runs an update (R-WRT-52). */
    private static int count(DomainQueryExecutionContext context, Translated translated) {
        return run(context, translated, "splicing a conflict arbiter into", (statement, session) ->
                statement.executeUpdate());
    }

    /** What one translated statement does with the {@link PreparedStatement} bound for it. */
    @FunctionalInterface
    private interface StatementBody<T> {
        T run(PreparedStatement statement, SharedSessionContractImplementor session) throws SQLException;
    }

    /**
     * {@code body} on the {@link PreparedStatement} {@code translated} is prepared, timed and bound as, with the
     * statement released and {@code afterStatement} run whatever happens. {@code what} names the operation in the
     * {@code JDBC} exception.
     */
    private static <T> T run(DomainQueryExecutionContext context, Translated translated, String what,
            StatementBody<T> body) {
        SharedSessionContractImplementor session = context.getSession();
        QueryOptions options = context.getQueryOptions();
        JdbcOperationQueryMutation operation = translated.operation();
        session.autoFlushIfRequired(operation.getAffectedTableNames());
        var jdbcServices = session.getFactory().getJdbcServices();
        LogicalConnectionImplementor connection = session.getJdbcCoordinator().getLogicalConnection();
        ExecutionContext execution = translated.execution();
        String sql = jdbcServices.getDialect().addSqlHintOrComment(translated.sql(), options,
                session.getFactory().getSessionFactoryOptions().isCommentsEnabled());
        PreparedStatement statement = session.getJdbcCoordinator().getStatementPreparer().prepareStatement(sql);
        try {
            if (options.getTimeout() != null) {
                statement.setQueryTimeout(options.getTimeout());
            }
            int position = 1;
            for (JdbcParameterBinder binder : operation.getParameterBinders()) {
                binder.bindParameterValue(statement, position++, translated.bindings(), execution);
            }
            return body.run(statement, session);
        } catch (SQLException e) {
            throw jdbcServices.getSqlExceptionHelper().convert(e, "JDBC exception " + what + " [" + sql + "]");
        } finally {
            connection.getResourceRegistry().release(statement);
            execution.afterStatement(connection);
        }
    }

    /** One row's values, in the returning query's column order, as the entity holds them, before any converter. */
    private static Object[] read(ResultSet resultSet, List<SqlTypedMapping> columns,
            SharedSessionContractImplementor session) throws SQLException {
        Object[] row = new Object[columns.size()];
        for (int i = 0; i < row.length; i++) {
            row[i] = read(columns.get(i), resultSet, i + 1, session);
        }
        return row;
    }

    /**
     * One column's value: the JDBC read through the column's relational extractor, then the mapping's
     * {@code AttributeConverter}, if any, to the value the attribute holds, as Hibernate's own result reads it.
     */
    private static Object read(SqlTypedMapping column, ResultSet resultSet, int position,
            SharedSessionContractImplementor session) throws SQLException {
        JdbcMapping mapping = column.getJdbcMapping();
        return mapping.convertToDomainValue(mapping.getJdbcValueExtractor().extract(resultSet, position, session));
    }

    /**
     * The selectable {@code attribute} names on the table {@code ast} writes: its path resolves through
     * {@code entity}'s mapping, every segment but the last through an embeddable, to a column of that table, or it is
     * the id of a to-one association, which is the association's foreign-key column (R-WRT-48 allows it).
     * {@code MQ1809} refuses a path that does not resolve, a column that is not one, and a column on another table,
     * such as a secondary table's, a joined supertable's or the target's of a to-one whose key is not on the written
     * table (R-WRT-50, R-WRT-48).
     */
    private static SelectableMapping selectable(EntityMappingType entity, String attribute, MutationStatement ast) {
        SelectableMapping selectable = selectable(entity, attribute);
        String written = ast.getTargetTable().getTableExpression();
        if (!selectable.getContainingTableExpression().equals(written)) {
            throw refuse(attribute, "it is on " + selectable.getContainingTableExpression() + ", not on the written "
                    + "table " + written);
        }
        return selectable;
    }

    /**
     * The selectable {@code attribute} names on {@code entity}: a column of its table, or the id of a to-one
     * association, which is the association's foreign-key column. Shared by a returning column and a conflict
     * arbiter's column (R-WRT-50, R-WRT-52).
     */
    static SelectableMapping selectable(EntityMappingType entity, String attribute) {
        String[] path = attribute.split("\\.");
        ManagedMappingType owner = entity;
        AttributeMapping mapping = null;
        SelectableMapping selectable = null;
        for (int i = 0; i < path.length && selectable == null; i++) {
            mapping = attribute(owner, path[i]);
            if (mapping == null) {
                throw refuse(attribute, "its path " + attribute + " does not resolve on " + entity.getEntityName());
            }
            if (i == path.length - 2) {
                selectable = foreignKey(mapping, path[i + 1]);
            }
            owner = mapping.getMappedType() instanceof ManagedMappingType managed ? managed : null;
        }
        if (selectable == null && mapping instanceof SelectableMapping column) {
            selectable = column;
        }
        if (selectable == null) {
            throw refuse(attribute, "it is the " + path[path.length - 1] + " association itself, not a column");
        }
        return selectable;
    }

    /**
     * The foreign-key column of {@code mapping} when it is a to-one association referencing its target's primary key
     * and {@code id} names that key, so a returning query's to-one id is read from the written row; else
     * {@code null}. A composite key has no single column and stays {@code null}.
     */
    private static SelectableMapping foreignKey(AttributeMapping mapping, String id) {
        if (mapping instanceof EntityAssociationMapping association && association.isReferenceToPrimaryKey()
                && id.equals(association.getAssociatedEntityMappingType().getIdentifierMapping().getAttributeName())
                && association.getForeignKeyDescriptor().getKeyPart() instanceof SelectableMapping key) {
            return key;
        }
        return null;
    }

    /**
     * The attribute {@code segment} names on {@code owner}: one of its mappings, or, on an entity, its
     * {@code @Id} attribute, which Hibernate's attribute container does not list
     * ({@code findAttributeMapping("id")} is null on an entity whose identifier is a plain field).
     */
    private static AttributeMapping attribute(ManagedMappingType owner, String segment) {
        if (owner == null) {
            return null;
        }
        AttributeMapping mapping = owner.findAttributeMapping(segment);
        if (mapping == null && owner instanceof EntityMappingType entity
                && segment.equals(entity.getIdentifierMapping().getAttributeName())) {
            return entity.getIdentifierMapping().asAttributeMapping();
        }
        return mapping;
    }

    /**
     * The {@code MutationStatement} of {@code ast} with {@code columns} returned, which Hibernate's own paths set. An
     * update's and a delete's returning columns go through their constructors; an insert's do too, on a new statement
     * that keeps the insert's common table expressions and gets its target columns, values, conflict clause and
     * source select copied over, from which Hibernate's own translator renders the {@code returning} clause.
     */
    private static MutationStatement attach(MutationStatement ast, List<ColumnReference> columns) {
        if (ast instanceof UpdateStatement update) {
            return new UpdateStatement(update.getTargetTable(), update.getFromClause(), update.getAssignments(),
                    update.getRestriction(), columns);
        }
        if (ast instanceof InsertSelectStatement insert) {
            InsertSelectStatement returned = new InsertSelectStatement(insert, insert.getTargetTable(), columns);
            returned.addTargetColumnReferences(insert.getTargetColumns());
            returned.setValuesList(insert.getValuesList());
            returned.setConflictClause(insert.getConflictClause());
            returned.setSourceSelectStatement(insert.getSourceSelectStatement());
            return returned;
        }
        DeleteStatement delete = (DeleteStatement) ast;
        return new DeleteStatement(delete.getTargetTable(), delete.getFromClause(), delete.getRestriction(), columns);
    }

    /** The adapter that serves {@code sf}'s Hibernate line, when one does and every call this class makes exists. */
    static Optional<Mutations> line(SessionFactoryImplementor sf) {
        return PROBED.computeIfAbsent(sf, factory -> {
            if (!reachable()) {
                return Optional.empty();
            }
            Optional<Mutations> line = LINES.stream().filter(candidate -> candidate.probes(factory)).findFirst();
            line.ifPresent(candidate -> warnOfLine(factory, candidate));
            return line;
        });
    }

    /**
     * Records that no adapter serves {@code sf}, as a line whose translation calls moved probes, so its refusals can
     * be exercised on the running Hibernate. A test seam, as {@link HibernateLockSupport#isRendered} is.
     */
    static void unserved(SessionFactoryImplementor sf) {
        PROBED.put(sf, Optional.empty());
    }

    /** One WARN per factory whose Hibernate is outside {@code line}'s tested range, as R-VND-07 logs the profile. */
    private static void warnOfLine(SessionFactoryImplementor sf, Mutations line) {
        if (warned(sf, line)) {
            LOG.log(System.Logger.Level.WARNING, "model-query uses the mutation adapter tested on Hibernate {0} on "
                    + "Hibernate {1}, which it was not tested on; a returning write still refuses with MQ4011 when "
                    + "one of the calls it makes moved (R-WRT-50)", line.tested(), Version.getVersionString());
        }
    }

    /**
     * Whether {@code sf} runs a Hibernate outside {@code line}'s tested range and has not been warned about yet, and
     * so records the one WARN {@link #warnOfLine} logs (R-WRT-50). A test seam, as
     * {@link HibernateLockSupport#isRendered} is.
     */
    static boolean warned(SessionFactoryImplementor sf, Mutations line) {
        return !withinTestedRange(Version.getVersionString(), line.tested()) && WARNED.add(sf);
    }

    /** Whether {@code version} is on the major.minor line {@code tested} was written against (R-WRT-50). */
    static boolean withinTestedRange(String version, String tested) {
        return minor(version).equals(minor(tested));
    }

    /** The major.minor of {@code version}, such as {@code 6.6} of {@code 6.6.58.Final}. */
    private static String minor(String version) {
        String[] parts = version.split("\\.");
        return parts.length < 2 ? version : parts[0] + "." + parts[1];
    }

    /**
     * Whether the provider calls this class makes still exist on the running Hibernate (the adapters probe the rest).
     */
    private static boolean reachable() {
        return constructor(UpdateStatement.class, 5) != null
                && constructor(DeleteStatement.class, 4) != null
                && constructor(InsertSelectStatement.class, 3) != null
                && method(InsertSelectStatement.class, "addTargetColumnReferences", 1) != null
                && method(InsertSelectStatement.class, "setValuesList", 1) != null
                && method(InsertSelectStatement.class, "setConflictClause", 1) != null
                && method(InsertSelectStatement.class, "setSourceSelectStatement", 1) != null;
    }

    /** The {@code owner.name} method of {@code parameters} parameters, or {@code null} when this line has none. */
    static Method method(Class<?> owner, String name, int parameters) {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) {
                return method;
            }
        }
        return null;
    }

    /** The no-argument {@code owner.name} method returning {@code returns}, or {@code null} when this line has none. */
    static Method getter(Class<?> owner, String name, Class<?> returns) {
        try {
            Method method = owner.getMethod(name);
            return method.getParameterCount() == 0 && returns.isAssignableFrom(method.getReturnType()) ? method : null;
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    /** The {@code owner} constructor of {@code parameters} parameters ending in a list, or {@code null} without one. */
    private static Constructor<?> constructor(Class<?> owner, int parameters) {
        for (Constructor<?> constructor : owner.getConstructors()) {
            if (constructor.getParameterCount() == parameters
                    && constructor.getParameterTypes()[parameters - 1] == List.class) {
                return constructor;
            }
        }
        return null;
    }

    /** The resolution both lines' binding calls take, from {@code translation}'s own parameter resolutions. */
    static SqmParameterMappingModelResolutionAccess resolutions(
            SqmTranslation<? extends MutationStatement> translation) {
        return new SqmParameterMappingModelResolutionAccess() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> MappingModelExpressible<T> getResolvedMappingModelType(SqmParameter<T> parameter) {
                return (MappingModelExpressible<T>) translation.getSqmParameterMappingModelTypeResolutions()
                        .get(parameter);
            }
        };
    }

    /** The from-clause access 6.6's binding call takes, which 7.x resolves itself. */
    static java.util.function.Function<NavigablePath, TableGroup> tableGroupAccess(
            SqmTranslation<? extends MutationStatement> translation) {
        FromClauseAccess access = translation.getFromClauseAccess();
        return access::findTableGroup;
    }

    /** The {@code MQ4011} a line whose calls moved throws instead of running a translation it cannot trust. */
    static ModelQueryExecutionException cannotTranslate(ReflectiveOperationException moved) {
        return new ModelQueryExecutionException(MqCode.MQ4011, "the Hibernate line of "
                + Version.getVersionString() + " moved a mutation translation call this adapter makes: "
                + moved + "; a returning write needs an adapter for this line (R-WRT-50)");
    }

    /** The {@code MQ1809} refusal of the returning column {@code attribute}, a definition error (R-WRT-50). */
    private static ModelQueryDefinitionException refuse(String attribute, String because) {
        return new ModelQueryDefinitionException(MqCode.MQ1809, "returning " + attribute + " cannot read "
                + because);
    }

    /** The {@code MQ4011} refusal of the statement {@code sql}, whose translation is not the shape's (R-WRT-50). */
    private static ModelQueryExecutionException mismatch(String sql, String because) {
        return new ModelQueryExecutionException(MqCode.MQ4011, "the factory translated the write to [" + sql
                + "] for a returning write or a conflict arbiter, and " + because + "; it needs an adapter for this "
                + "Hibernate line (R-WRT-50, R-WRT-52)");
    }

    /** The first keyword of {@code sql}, lower case. */
    private static String leading(String sql) {
        return sql.stripLeading().split("\\s+")[0].toLowerCase(Locale.ROOT);
    }

    /**
     * The keywords a {@code kind} statement's own translation may begin with (R-WRT-50): an upsert may go through a
     * database's own {@code MERGE}, which H2 renders for one.
     */
    private static List<String> leadingKeywords(WriteStatement kind) {
        return switch (kind) {
            case UPDATE -> List.of("update");
            case DELETE -> List.of("delete");
            case INSERT -> List.of("insert", "merge");
        };
    }

    /** How often {@code token} occurs in {@code sql}. */
    private static int occurrences(String sql, String token) {
        return (sql.length() - sql.replace(token, "").length()) / token.length();
    }
}
