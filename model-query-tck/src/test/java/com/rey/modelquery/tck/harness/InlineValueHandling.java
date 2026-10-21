package com.rey.modelquery.tck.harness;

import com.rey.modelquery.tck.col.JoinTestSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * A factory of the fixture entities with {@code hibernate.criteria.value_handling_mode=inline}, which renders a
 * criteria value given without a parameter as a literal, and a {@link StatementInspector} that fails a statement whose
 * SQL text carries one of the watched input values as a literal, before it runs. Structural literals are classified
 * separately and recorded, not failed: {@code like}'s {@code ESCAPE} character, SQL {@code NULL}, and the literals a
 * test declares structural, a discriminator restriction or a user's own {@code cb.literal} or {@code Expr.constant}
 * (spec api/12 R-FLT-08; TCK AC-FLT-35). Every suite that checks a value path under the setting reuses it.
 */
public final class InlineValueHandling implements AutoCloseable {

    /** A quoted string literal, a doubled quote inside it included. */
    private static final Pattern QUOTED = Pattern.compile("'(?:[^']|'')*'");
    /** A number outside an identifier ({@code o1_0.id}'s digits are not one). */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.$])\\d+(?:\\.\\d+)?(?![\\w.])");
    /** SQL {@code NULL} as a value, not in {@code IS NULL} or {@code IS NOT NULL}. */
    private static final Pattern NULL_VALUE = Pattern.compile("(?<!\\bis )(?<!\\bnot )\\bnull\\b");

    private final Inspector inspector = new Inspector();
    private final SessionFactory factory;

    private InlineValueHandling(TckDatabase db) {
        factory = JoinTestSupport.sessionFactory(JoinTestSupport.dataSource(db), Map.of(
                AvailableSettings.CRITERIA_VALUE_HANDLING_MODE, "inline",
                AvailableSettings.STATEMENT_INSPECTOR, inspector));
    }

    /** A factory of the fixture entities over {@code db} with inline value handling and the inspector. */
    public static InlineValueHandling open(TckDatabase db) {
        return new InlineValueHandling(db);
    }

    public SessionFactory factory() {
        return factory;
    }

    /**
     * Watches {@code inputs} from the next statement on, the statements and literals recorded so far cleared: a
     * {@code String} fails a statement with a quoted literal containing it, case-insensitively, so a lowered or
     * wildcarded value is caught; a {@code Number} one with a numeric literal of its value. {@code structural} lists
     * the literal texts, quotes included, that are definition text rather than input.
     */
    public void watch(List<?> inputs, List<String> structural) {
        inspector.inputs = List.copyOf(inputs);
        inspector.declared = List.copyOf(structural);
        inspector.statements.clear();
        inspector.structural.clear();
    }

    /** As {@link #watch(List, List)} with no declared structural literal. */
    public void watch(Object... inputs) {
        watch(List.of(inputs), List.of());
    }

    /** The SQL of every statement inspected since the last {@code watch}, lower-cased. */
    public List<String> statements() {
        return List.copyOf(inspector.statements);
    }

    /** The structural literals those statements carried, each as it appears, {@code escape '\'} for instance. */
    public List<String> structural() {
        return List.copyOf(inspector.structural);
    }

    @Override
    public void close() {
        factory.close();
    }

    /** Fails a statement carrying a watched input as a literal; records the structural ones. */
    private static final class Inspector implements StatementInspector {

        private final List<String> statements = new CopyOnWriteArrayList<>();
        private final List<String> structural = new CopyOnWriteArrayList<>();
        private volatile List<?> inputs = List.of();
        private volatile List<String> declared = List.of();

        @Override
        public String inspect(String sql) {
            String text = sql.toLowerCase(Locale.ROOT);
            statements.add(text);
            var leaks = new ArrayList<String>();
            Matcher quoted = QUOTED.matcher(text);
            var outside = new StringBuilder();
            int last = 0;
            while (quoted.find()) {
                outside.append(text, last, quoted.start()).append(' ');
                last = quoted.end();
                String literal = quoted.group();
                if (text.substring(0, quoted.start()).stripTrailing().endsWith("escape")) {
                    structural.add("escape " + literal);
                } else if (declared.stream().anyMatch(literal::equalsIgnoreCase)) {
                    structural.add(literal);
                } else if (inputs.stream().anyMatch(input -> input instanceof String s
                        && literal.contains(s.toLowerCase(Locale.ROOT)))) {
                    leaks.add(literal);
                }
            }
            outside.append(text.substring(last));
            Matcher number = NUMBER.matcher(outside);
            while (number.find()) {
                BigDecimal value = new BigDecimal(number.group());
                if (declared.contains(number.group())) {
                    structural.add(number.group());
                } else if (inputs.stream().anyMatch(input -> input instanceof Number n
                        && new BigDecimal(n.toString()).compareTo(value) == 0)) {
                    leaks.add(number.group());
                }
            }
            Matcher nulls = NULL_VALUE.matcher(outside);
            while (nulls.find()) {
                structural.add("null");
            }
            if (!leaks.isEmpty()) {
                throw new AssertionError("input values " + leaks + " reached the SQL text as literals under inline "
                        + "value handling (R-FLT-08): " + sql);
            }
            return sql;
        }
    }
}
