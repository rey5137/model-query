package com.rey.modelquery.audit;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.SelectSet;
import java.util.BitSet;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private final String selectLabel;
    private final String modelName;
    private final BitSet selected;
    private final Map<String, Integer> propertyBits;
    private final Map<String, String> constants;
    private final BitSet read = new BitSet();
    private final Set<String> mapped;
    private final List<String> requestedProperties;
    private final ReadEvents.CallSiteStats callSiteStats;
    private final Set<String> inheritedReads = ConcurrentHashMap.newKeySet();
    private final long handoffThread = Thread.currentThread().getId();
    private final Set<ReadEvent> unselectedReads = new HashSet<>();

    record ReadEvent(String model, String property, String reader, boolean serializer, boolean otherThread) {}

    Tag(String callSite, String method, SelectSet<?> select, BitSet selected, Map<String, Integer> propertyBits) {
        this(callSite, method, select, selected, propertyBits, propertyBits.keySet());
    }

    Tag(String callSite, String method, SelectSet<?> select, BitSet selected, Map<String, Integer> propertyBits,
            Set<String> mapped) {
        this(callSite, method, select, selected, propertyBits, mapped, Map.of());
    }

    Tag(String callSite, String method, SelectSet<?> select, BitSet selected, Map<String, Integer> propertyBits,
            Set<String> mapped, Map<String, String> constants) {
        this(callSite, method, select, String.valueOf(select), "", selected, propertyBits, mapped, constants,
                List.of());
    }

    Tag(String callSite, String method, SelectSet<?> select, String selectLabel, String modelName,
            BitSet selected, Map<String, Integer> propertyBits, Set<String> mapped, Map<String, String> constants,
            List<String> requestedProperties) {
        this.callSite = Objects.requireNonNull(callSite, "callSite");
        this.method = Objects.requireNonNull(method, "method");
        this.select = select;
        this.selectLabel = Objects.requireNonNull(selectLabel, "selectLabel");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.selected = Objects.requireNonNull(selected, "selected");
        this.propertyBits = Collections.unmodifiableMap(Objects.requireNonNull(propertyBits, "propertyBits"));
        this.constants = Map.copyOf(constants);
        this.mapped = Set.copyOf(mapped);
        this.requestedProperties = List.copyOf(requestedProperties);
        this.callSiteStats = ReadEvents.registerCallSite(this);
    }

    String constant(String property) { return constants.getOrDefault(property, property); }

    String selectLabel() { return selectLabel; }

    String modelName() { return modelName; }

    List<String> requestedProperties() { return requestedProperties; }

    ReadEvents.CallSiteStats callSiteStats() { return callSiteStats; }

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

    synchronized void read(String property) {
        Integer bit = propertyBits.get(property);
        if (bit != null && !read.get(bit)) {
            read.set(bit);
            if (callSiteStats != null) {
                callSiteStats.readProperties.add(property);
            }
        }
    }

    synchronized boolean wasRead(String property) {
        Integer bit = propertyBits.get(property);
        return bit != null && read.get(bit);
    }

    synchronized void unselected(String model, String property, ReadSite.Site site) {
        ReadEvent event = new ReadEvent(model, property, site.location(), site.serializer(),
                Thread.currentThread().getId() != handoffThread);
        unselectedReads.add(event);
        ReadEvents.record(this, event);
    }

    synchronized List<ReadEvent> unselectedReads() {
        return List.copyOf(unselectedReads);
    }

    long handoffThread() {
        return handoffThread;
    }

    boolean isMapped(String property) {
        return mapped.contains(property);
    }

    void inheritedRead(Class<?> modelClass, String property, String owner) {
        String read = owner + "." + property;
        if (!inheritedReads.add(read)) {
            return;
        }
        ReadSite.Site site = ReadSite.capture(modelClass);
        if (site.ignored()) {
            inheritedReads.remove(read);
            return;
        }
        System.getLogger(Tag.class.getName()).log(System.Logger.Level.WARNING,
                "Read of unmapped inherited field " + read + " on model of " + callSite + " " + method);
        ReadEvents.recordInherited(this, read, site.location());
    }

    Set<String> inheritedReads() {
        return Set.copyOf(inheritedReads);
    }

    @Override
    @Incubating
    public String toString() {
        return "Tag[" + callSite + " " + method + " " + (select != null ? select : "*") + "]";
    }
}
