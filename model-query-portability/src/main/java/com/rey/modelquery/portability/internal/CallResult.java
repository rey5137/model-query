package com.rey.modelquery.portability.internal;

import com.rey.modelquery.portability.Target;
import java.util.Objects;

/**
 * One call on one target: its outcome, whether only its first statement was certified, and the detail its report
 * line prints (the SQL reached, or the refusal or error).
 *
 * @param label the call's label, a sweep's {@code Holder.FIELD operation}
 * @param target the target it ran on
 * @param outcome how it ended
 * @param firstStatementOnly whether a statement after the first could still be refused, so {@code REACHED} certifies
 *     the first statement only
 * @param detail the SQL reached, or the refusal or error, on one line
 * @param statement the page statement represented, or {@code NONE}
 * @param child the path of the fetch plan's child load the line reports, {@code orders.lines}, or empty for the call's
 *     own statement (D-224)
 * @implSpec R-INS-11, R-INS-12, R-INS-17
 */
public record CallResult(String label, Target target, Outcome outcome, boolean firstStatementOnly, String detail,
        Statement statement, String child) {

    /** Page statement labels (D-222). */
    public enum Statement {
        NONE(""), COUNT("count"), CONTENT("content"), KEYS("keys"), CONTENT_AND_COUNT("content+count");

        private final String label;

        Statement(String label) {
            this.label = label;
        }
    }

    /** A result with no page statement label. */
    public CallResult(String label, Target target, Outcome outcome, boolean firstStatementOnly, String detail) {
        this(label, target, outcome, firstStatementOnly, detail, Statement.NONE);
    }

    /** A result of the call's own statement, with no child path. */
    public CallResult(String label, Target target, Outcome outcome, boolean firstStatementOnly, String detail,
            Statement statement) {
        this(label, target, outcome, firstStatementOnly, detail, statement, "");
    }

    /** The same outcome for a particular page statement. */
    public CallResult withStatement(Statement statement) {
        return new CallResult(label, target, outcome, firstStatementOnly, detail, statement, child);
    }

    /** The same outcome reported for the child load at {@code path} (D-224). */
    public CallResult withChild(String path) {
        return new CallResult(label, target, outcome, firstStatementOnly, detail, statement, path);
    }

    /** Checks the components. */
    public CallResult {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(child, "child");
    }

    /** Whether the call is portable on the target: it reached a statement with no refusal (D-133). */
    public boolean passes() {
        return outcome == Outcome.REACHED;
    }

    /**
     * The report line: {@code label[ [statement]][ child path] on target: OUTCOME[ (first statement only)] detail}.
     */
    public String line() {
        String qualifier = outcome == Outcome.REACHED && firstStatementOnly ? " (first statement only)" : "";
        String suffix = statement == Statement.NONE ? "" : " [" + statement.label + "]";
        String at = child.isEmpty() ? "" : " child " + child;
        return label + suffix + at + " on " + target + ": " + outcome + qualifier + " " + detail;
    }

    @Override
    public String toString() {
        return line();
    }
}
