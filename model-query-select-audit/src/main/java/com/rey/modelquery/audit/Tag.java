package com.rey.modelquery.audit;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.SelectSet;
import java.util.BitSet;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * One call's audit tag, shared by every model returned by that call (R-INS-18). Holds the captured call site,
 * the executor method or child load name, the query's {@link SelectSet}, and the selection {@link BitSet} shared per
 * call, query and model path. Holds no model and no {@code Row}.
 */
@Incubating
public final class Tag {

    private final String callSite;
    private final String method;
    private final SelectSet<?> select;
    private final BitSet selected;
    private final Map<String, Integer> propertyBits;

    Tag(String callSite, String method, SelectSet<?> select, BitSet selected, Map<String, Integer> propertyBits) {
        this.callSite = Objects.requireNonNull(callSite, "callSite");
        this.method = Objects.requireNonNull(method, "method");
        this.select = select;
        this.selected = Objects.requireNonNull(selected, "selected");
        this.propertyBits = Collections.unmodifiableMap(Objects.requireNonNull(propertyBits, "propertyBits"));
    }

    /** The captured application call site, as {@code ClassName.method:line}. */
    @Incubating
    public String callSite() {
        return callSite;
    }

    /** The executor method that returned the model, or the child load description. */
    @Incubating
    public String method() {
        return method;
    }

    /** The query's {@link SelectSet}, or {@code null} when none was specified. */
    @Incubating
    public SelectSet<?> select() {
        return select;
    }

    /** A defensive snapshot of the selection bits for this call and model class. */
    @Incubating
    public BitSet selected() {
        return (BitSet) selected.clone();
    }

    /** Whether the named property was selected in this model's query row. */
    @Incubating
    public boolean isSelected(String property) {
        Integer bit = propertyBits.get(Objects.requireNonNull(property, "property"));
        return bit != null && selected.get(bit);
    }

    /** Whether the property at the given bit index was selected. */
    @Incubating
    public boolean isSelected(int bit) {
        return bit >= 0 && selected.get(bit);
    }

    /** Map of property name to bit index for this model class. */
    @Incubating
    public Map<String, Integer> propertyBits() {
        return propertyBits;
    }

    @Override
    @Incubating
    public String toString() {
        return "Tag[" + callSite + " " + method + " " + (select != null ? select : "*") + "]";
    }
}
