package com.rey.modelquery.audit;

import com.rey.modelquery.annotations.Incubating;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Renders the select-audit report and merges per-fork report files into one report (RFC 0026 Part C, R-INS-21).
 */
@Incubating
public final class SelectAuditReport {

    /** System property configuring the output directory for per-fork and merged reports. */
    @Incubating
    public static final String DIR_PROPERTY = "modelquery.selectAudit.dir";

    /** Default output directory for per-fork and merged reports. */
    @Incubating
    public static final String DEFAULT_DIR = "target/model-query";

    private static final String FORK_PREFIX = "select-audit-";
    private static final String MERGED_FILE = "select-audit.txt";
    private static final String RECORD_PREFIX = "# CALL_SITE\t";
    private static final String RUN_PREFIX = "# RUN\t";
    /** Run id of a fork whose parent or start time is unknown, and of a file without a header: always merged. */
    static final String UNKNOWN_RUN = "unknown";

    /**
     * System property naming the run a fork belongs to. When unset, the run is the parent process (the build JVM
     * that started the forks) and its start instant (R-INS-21).
     */
    @Incubating
    public static final String RUN_PROPERTY = "modelquery.selectAudit.run";

    /** The note that heads the summary when the session attached the agent itself (D-265). */
    static final String RUNTIME_ATTACH_NOTE = "agent attached at session open: JDK 21 warns about a dynamically loaded "
            + "agent (JEP 451) and a later JDK may refuse it; pass -javaagent, or -XX:+EnableDynamicAgentLoading to "
            + "keep the attach";

    private SelectAuditReport() {}

    /** Renders the current JVM's recorded audit events as a report string. */
    @Incubating
    public static String render() {
        return renderSnapshot(fromCurrentJvm(), false);
    }

    /**
     * Writes this JVM's report file into the configured directory ({@link #DIR_PROPERTY}, defaulting to
     * {@link #DEFAULT_DIR}), and logs its summary.
     *
     * @return the path of the written fork file
     */
    @Incubating
    public static Path writeForkReport() throws IOException {
        Path dir = Path.of(System.getProperty(DIR_PROPERTY, DEFAULT_DIR));
        return writeForkReport(dir);
    }

    /**
     * Writes this JVM's report file into {@code dir} as {@code select-audit-<fork>.txt}, and logs its summary.
     *
     * @param dir the directory to write the fork report file into
     * @return the path of the written fork file
     */
    @Incubating
    public static Path writeForkReport(Path dir) throws IOException {
        Objects.requireNonNull(dir, "dir");
        Files.createDirectories(dir);
        Snapshot snapshot = fromCurrentJvm();
        String text = RUN_PREFIX + runId() + "\t" + ProcessHandle.current().info().startInstant()
                .map(Instant::toEpochMilli).orElse(System.currentTimeMillis()) + "\n"
                + renderSnapshot(snapshot, true);
        Path file = dir.resolve(FORK_PREFIX + forkId() + ".txt");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        System.getLogger(SelectAuditReport.class.getName()).log(System.Logger.Level.INFO,
                summary(snapshot, file));
        return file;
    }

    /**
     * Merges all {@code select-audit-*.txt} fork files in {@code dir} into {@code select-audit.txt} in the same
     * directory, keeping only the newest run's files (a warning names how many older files were skipped), unioning read columns per call site and select set so a column is reported unread only when no fork
     * read it.
     *
     * @param dir the directory holding the per-fork files
     * @return the rendered merged report (without internal metadata lines)
     */
    @Incubating
    public static String merge(Path dir) throws IOException {
        Objects.requireNonNull(dir, "dir");
        List<Path> files;
        if (!Files.isDirectory(dir)) {
            files = List.of();
        } else {
            try (Stream<Path> stream = Files.list(dir)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.startsWith(FORK_PREFIX) && name.endsWith(".txt");
                        })
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
            }
        }
        Snapshot merged = new Snapshot();
        Map<Path, List<String>> contents = new LinkedHashMap<>();
        Map<String, Long> newestStart = new LinkedHashMap<>();
        Map<Path, String> runOf = new LinkedHashMap<>();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            contents.put(file, lines);
            String run = UNKNOWN_RUN;
            long start = 0L;
            if (!lines.isEmpty() && lines.get(0).startsWith(RUN_PREFIX)) {
                String[] parts = lines.get(0).substring(RUN_PREFIX.length()).split("\t", -1);
                run = parts[0];
                start = parts.length > 1 ? parseLong(parts[1], 0L) : 0L;
            }
            runOf.put(file, run);
            if (!run.equals(UNKNOWN_RUN)) {
                newestStart.merge(run, start, Math::max);
            }
        }
        // Files of several runs share a directory when it was not cleaned: keep only the newest run (R-INS-21).
        // A file of unknown run is never skipped: dropping a fork would make its read columns look unread.
        String newest = newestStart.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(UNKNOWN_RUN);
        int skipped = 0;
        for (Path file : files) {
            if (runOf.get(file).equals(newest) || runOf.get(file).equals(UNKNOWN_RUN)) {
                parseInto(contents.get(file), merged);
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            System.getLogger(SelectAuditReport.class.getName()).log(System.Logger.Level.WARNING,
                    "select-audit: skipped " + skipped + " fork file(s) from older runs in " + dir
                            + "; clear the directory or set -D" + RUN_PROPERTY + " to group forks of one build");
        }
        String rendered = renderSnapshot(merged, false);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(MERGED_FILE), rendered, StandardCharsets.UTF_8);
        return rendered;
    }

    /**
     * Merges a directory's per-fork files into {@code select-audit.txt} (R-INS-21). Uses the first argument as the
     * directory path, or {@link #DIR_PROPERTY} / {@link #DEFAULT_DIR} when no argument is given.
     *
     * @param args optional directory path
     */
    @Incubating
    public static void main(String... args) throws IOException {
        String dir = args != null && args.length > 0 && args[0] != null && !args[0].isBlank()
                ? args[0] : System.getProperty(DIR_PROPERTY, DEFAULT_DIR);
        merge(Path.of(dir));
    }

    private static String runId() {
        String configured = System.getProperty(RUN_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return ProcessHandle.current().parent()
                .flatMap(owner -> owner.info().startInstant().map(start -> "pid-" + owner.pid() + "-"
                        + start.toEpochMilli()))
                .orElse(UNKNOWN_RUN);
    }

    private static String forkId() {
        long pid = ProcessHandle.current().pid();
        String forkNum = System.getProperty("surefire.forkNumber");
        if (forkNum != null && !forkNum.isBlank()) {
            return "fork-" + forkNum.trim() + "-" + pid;
        }
        return Long.toString(pid);
    }

    private static String simpleModelName(String model) {
        int dot = model.lastIndexOf('.');
        int dollar = model.lastIndexOf('$');
        int cut = Math.max(dot, dollar);
        return cut >= 0 ? model.substring(cut + 1) : model;
    }

    private record UnselectedKey(String modelProperty, String reader, boolean otherThread, String callSite,
            String method) {}

    private record SerializerKey(String modelProperty, String serializer, String callSite, String method) {}

    private record CallSiteId(String callSite, String methodAndSelect, String model) {}

    private static final class CallSiteEntry {
        final List<String> requested = new ArrayList<>();
        final Set<String> read = new LinkedHashSet<>();
        final Set<String> tests = new LinkedHashSet<>();
        long calls;
    }

    private record InheritedKey(String modelProperty, String inherited, String reader, String callSite,
            String method) {}

    private record MissingHookKey(String model, String callSite, String method) {}

    private static final class Snapshot {
        final Map<UnselectedKey, Long> unselected = new LinkedHashMap<>();
        final Map<SerializerKey, Long> serializer = new LinkedHashMap<>();
        final Map<CallSiteId, CallSiteEntry> callSites = new LinkedHashMap<>();
        final Map<InheritedKey, Long> inherited = new LinkedHashMap<>();
        final Map<MissingHookKey, Long> missingHooks = new LinkedHashMap<>();
    }

    private static Snapshot fromCurrentJvm() {
        Snapshot snap = new Snapshot();
        ReadEvents.snapshot().entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<ReadEvents.Key, Long> e) -> e.getKey().callSite())
                        .thenComparing(e -> e.getKey().method())
                        .thenComparing(e -> e.getKey().read().model())
                        .thenComparing(e -> e.getKey().read().property())
                        .thenComparing(e -> e.getKey().read().reader()))
                .forEach(entry -> {
                    ReadEvents.Key key = entry.getKey();
                    Tag.ReadEvent ev = key.read();
                    if (ev.serializer()) {
                        SerializerKey sk = new SerializerKey(ev.model(), serializerName(ev.reader()),
                                key.callSite(), key.method());
                        snap.serializer.merge(sk, entry.getValue(), Long::sum);
                    } else {
                        UnselectedKey uk = new UnselectedKey(ev.model(), shortReader(ev.reader()),
                                ev.otherThread(), key.callSite(), key.method());
                        snap.unselected.merge(uk, entry.getValue(), Long::sum);
                    }
                });

        ReadEvents.callSites().entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<ReadEvents.CallSiteKey, ReadEvents.CallSiteStats> e) ->
                        e.getKey().callSite())
                        .thenComparing(e -> e.getKey().method())
                        .thenComparing(e -> e.getKey().selectLabel())
                        .thenComparing(e -> e.getKey().model()))
                .forEach(entry -> {
                    ReadEvents.CallSiteKey key = entry.getKey();
                    ReadEvents.CallSiteStats stats = entry.getValue();
                    String methodAndSelect = key.method().startsWith("child ")
                            ? key.method() : key.method() + ", select " + key.selectLabel();
                    CallSiteId id = new CallSiteId(key.callSite(), methodAndSelect, key.model());
                    CallSiteEntry cs = snap.callSites.computeIfAbsent(id, ignored -> new CallSiteEntry());
                    for (String prop : key.requestedProperties()) {
                        if (!cs.requested.contains(prop)) {
                            cs.requested.add(prop);
                        }
                    }
                    cs.read.addAll(stats.readProperties);
                    cs.tests.addAll(stats.tests);
                    cs.calls += stats.calls.sum();
                });

        ReadEvents.inheritedReads().entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<ReadEvents.InheritedKey, Long> e) -> e.getKey().callSite())
                        .thenComparing(e -> e.getKey().method())
                        .thenComparing(e -> e.getKey().model())
                        .thenComparing(e -> e.getKey().inherited())
                        .thenComparing(e -> e.getKey().reader()))
                .forEach(entry -> {
                    ReadEvents.InheritedKey k = entry.getKey();
                    String prop = k.inherited().substring(k.inherited().lastIndexOf('.') + 1);
                    InheritedKey ik = new InheritedKey(simpleModelName(k.model()) + "." + prop,
                            shortInherited(k.inherited()), shortReader(k.reader()), k.callSite(), k.method());
                    snap.inherited.merge(ik, entry.getValue(), Long::sum);
                });

        ReadEvents.missingHooks().entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<ReadEvents.MissingHookKey, Long> e) -> e.getKey().callSite())
                        .thenComparing(e -> e.getKey().method())
                        .thenComparing(e -> e.getKey().model()))
                .forEach(entry -> {
                    ReadEvents.MissingHookKey k = entry.getKey();
                    snap.missingHooks.merge(new MissingHookKey(simpleModelName(k.model()), k.callSite(), k.method()),
                            entry.getValue(), Long::sum);
                });
        return snap;
    }

    private static String renderSnapshot(Snapshot snap, boolean includeRecords) {
        StringBuilder sb = new StringBuilder();
        if (!snap.unselected.isEmpty()) {
            sb.append("UNSELECTED READS (").append(snap.unselected.size()).append(")\n");
            snap.unselected.forEach((k, count) -> {
                sb.append("  ").append(k.modelProperty()).append("  read at ").append(k.reader());
                if (k.otherThread()) {
                    sb.append(" (may not have failed a test)");
                }
                sb.append("  model of ").append(k.callSite()).append(' ').append(k.method());
                appendCount(sb, count);
                sb.append('\n');
            });
        }
        if (!snap.serializer.isEmpty()) {
            sb.append("SERIALIZER READS OF UNSELECTED COLUMNS (warning)\n");
            snap.serializer.forEach((k, count) -> {
                sb.append("  ").append(k.modelProperty()).append("  read by ").append(k.serializer())
                        .append("  model of ").append(k.callSite()).append(' ').append(k.method());
                appendCount(sb, count);
                sb.append('\n');
            });
        }
        List<String> unreadLines = new ArrayList<>();
        snap.callSites.forEach((id, entry) -> {
            String simpleModel = simpleModelName(id.model());
            List<String> unread = entry.requested.stream()
                    .filter(p -> !entry.read.contains(p))
                    .map(p -> simpleModel + "." + p)
                    .toList();
            if (!unread.isEmpty()) {
                unreadLines.add("  " + id.callSite() + " " + id.methodAndSelect() + ": " + String.join(", ", unread));
                if (!id.methodAndSelect().startsWith("child ")) {
                    long calls = Math.max(1L, entry.calls);
                    long testCount = Math.max(1L, entry.tests.size());
                    unreadLines.add("    never read in " + calls + (calls == 1 ? " call" : " calls")
                            + " across " + testCount + (testCount == 1 ? " test" : " tests"));
                }
            }
        });
        if (!unreadLines.isEmpty()) {
            sb.append("UNREAD COLUMNS (warning)\n");
            for (String line : unreadLines) {
                sb.append(line).append('\n');
            }
        }
        if (!snap.inherited.isEmpty()) {
            sb.append("READS OF UNMAPPED INHERITED FIELDS (warning)\n");
            snap.inherited.forEach((k, count) -> {
                sb.append("  ").append(k.modelProperty()).append(" (").append(k.inherited()).append(")  read at ")
                        .append(k.reader()).append("  model of ").append(k.callSite()).append(' ').append(k.method());
                appendCount(sb, count);
                sb.append('\n');
            });
        }
        if (!snap.missingHooks.isEmpty()) {
            sb.append("MODELS NOT OBSERVED (hook missing)\n");
            snap.missingHooks.forEach((k, count) -> {
                sb.append("  ").append(k.model()).append("  model of ").append(k.callSite()).append(' ')
                        .append(k.method());
                appendCount(sb, count);
                sb.append(": loaded before the agent; reads not recorded\n");
            });
        }
        if (includeRecords && !snap.callSites.isEmpty()) {
            snap.callSites.forEach((id, entry) -> sb.append(RECORD_PREFIX)
                    .append(id.callSite()).append('\t')
                    .append(id.methodAndSelect()).append('\t')
                    .append(id.model()).append('\t')
                    .append(String.join(",", entry.requested)).append('\t')
                    .append(String.join(",", entry.read)).append('\t')
                    .append(entry.calls).append('\t')
                    .append(String.join(",", entry.tests)).append('\n'));
        }
        return sb.toString();
    }

    private static void appendCount(StringBuilder sb, long count) {
        if (count > 1) {
            sb.append(" x").append(count);
        }
    }

    private static void parseInto(List<String> lines, Snapshot target) {
        String section = "";
        for (String line : lines) {
            if (line.startsWith(RECORD_PREFIX)) {
                String[] parts = line.substring(RECORD_PREFIX.length()).split("\t", -1);
                if (parts.length >= 6) {
                    CallSiteId id = new CallSiteId(parts[0], parts[1], parts[2]);
                    CallSiteEntry entry = target.callSites.computeIfAbsent(id, ignored -> new CallSiteEntry());
                    for (String req : parts[3].split(",")) {
                        if (!req.isEmpty() && !entry.requested.contains(req)) {
                            entry.requested.add(req);
                        }
                    }
                    for (String r : parts[4].split(",")) {
                        if (!r.isEmpty()) {
                            entry.read.add(r);
                        }
                    }
                    entry.calls += parseLong(parts[5], 1L);
                    if (parts.length >= 7) {
                        for (String t : parts[6].split(",")) {
                            if (!t.isEmpty()) {
                                entry.tests.add(t);
                            }
                        }
                    }
                }
                continue;
            }
            if (line.startsWith("UNSELECTED READS (")) {
                section = "UNSELECTED";
            } else if (line.equals("SERIALIZER READS OF UNSELECTED COLUMNS (warning)")) {
                section = "SERIALIZER";
            } else if (line.equals("UNREAD COLUMNS (warning)")) {
                section = "UNREAD";
            } else if (line.equals("READS OF UNMAPPED INHERITED FIELDS (warning)")) {
                section = "INHERITED";
            } else if (line.equals("MODELS NOT OBSERVED (hook missing)")) {
                section = "MISSING";
            } else if (line.startsWith("  ")) {
                parseSectionLine(section, line.substring(2), target);
            }
        }
    }

    private static void parseSectionLine(String section, String body, Snapshot target) {
        switch (section) {
            case "UNSELECTED" -> {
                int readAt = body.indexOf("  read at ");
                int modelOf = body.indexOf("  model of ", readAt + 10);
                if (readAt > 0 && modelOf > readAt) {
                    String prop = body.substring(0, readAt);
                    String readerPart = body.substring(readAt + 10, modelOf);
                    boolean otherThread = readerPart.endsWith(" (may not have failed a test)");
                    if (otherThread) {
                        readerPart = readerPart.substring(0,
                                readerPart.length() - " (may not have failed a test)".length());
                    }
                    CountTail tail = splitCount(body.substring(modelOf + 11));
                    int sp = tail.rest().indexOf(' ');
                    if (sp > 0) {
                        UnselectedKey key = new UnselectedKey(prop, readerPart, otherThread,
                                tail.rest().substring(0, sp), tail.rest().substring(sp + 1));
                        target.unselected.merge(key, tail.count(), Long::sum);
                    }
                }
            }
            case "SERIALIZER" -> {
                int readBy = body.indexOf("  read by ");
                int modelOf = body.indexOf("  model of ", readBy + 10);
                if (readBy > 0 && modelOf > readBy) {
                    String prop = body.substring(0, readBy);
                    String ser = body.substring(readBy + 10, modelOf);
                    CountTail tail = splitCount(body.substring(modelOf + 11));
                    int sp = tail.rest().indexOf(' ');
                    if (sp > 0) {
                        SerializerKey key = new SerializerKey(prop, ser,
                                tail.rest().substring(0, sp), tail.rest().substring(sp + 1));
                        target.serializer.merge(key, tail.count(), Long::sum);
                    }
                }
            }
            case "INHERITED" -> {
                int open = body.indexOf(" (");
                int readAt = body.indexOf(")  read at ", open + 2);
                int modelOf = body.indexOf("  model of ", readAt + 11);
                if (open > 0 && readAt > open && modelOf > readAt) {
                    String prop = body.substring(0, open);
                    String inh = body.substring(open + 2, readAt);
                    String reader = body.substring(readAt + 11, modelOf);
                    CountTail tail = splitCount(body.substring(modelOf + 11));
                    int sp = tail.rest().indexOf(' ');
                    if (sp > 0) {
                        InheritedKey key = new InheritedKey(prop, inh, reader,
                                tail.rest().substring(0, sp), tail.rest().substring(sp + 1));
                        target.inherited.merge(key, tail.count(), Long::sum);
                    }
                }
            }
            case "MISSING" -> {
                String suffix = ": loaded before the agent; reads not recorded";
                if (body.endsWith(suffix)) {
                    String head = body.substring(0, body.length() - suffix.length());
                    int modelOf = head.indexOf("  model of ");
                    if (modelOf > 0) {
                        String model = head.substring(0, modelOf);
                        CountTail tail = splitCount(head.substring(modelOf + 11));
                        int sp = tail.rest().indexOf(' ');
                        if (sp > 0) {
                            MissingHookKey key = new MissingHookKey(model,
                                    tail.rest().substring(0, sp), tail.rest().substring(sp + 1));
                            target.missingHooks.merge(key, tail.count(), Long::sum);
                        }
                    }
                }
            }
            default -> {}
        }
    }

    private record CountTail(String rest, long count) {}

    private static CountTail splitCount(String text) {
        int idx = text.lastIndexOf(" x");
        if (idx > 0 && idx + 2 < text.length()) {
            String num = text.substring(idx + 2);
            if (num.chars().allMatch(Character::isDigit)) {
                return new CountTail(text.substring(0, idx), Long.parseLong(num));
            }
        }
        return new CountTail(text, 1L);
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String summary(Snapshot snap, Path file) {
        long unreadCount = snap.callSites.values().stream()
                .mapToLong(e -> e.requested.stream().filter(p -> !e.read.contains(p)).count())
                .sum();
        return "select-audit report written to " + file + " (" + snap.unselected.size() + " unselected, "
                + snap.serializer.size() + " serializer warnings, " + unreadCount + " unread columns)"
                + (AuditAgent.attachedAtRuntime() ? "; " + RUNTIME_ATTACH_NOTE : "");
    }

    private static String shortReader(String location) {
        int paren = location.indexOf('(');
        String methodPart = paren >= 0 ? location.substring(0, paren) : location;
        int lastDot = methodPart.lastIndexOf('.');
        int prevDot = lastDot > 0 ? methodPart.lastIndexOf('.', lastDot - 1) : -1;
        String classAndMethod = prevDot >= 0 ? methodPart.substring(prevDot + 1) : methodPart;
        if (paren >= 0) {
            int colon = location.indexOf(':', paren);
            int close = location.indexOf(')', colon);
            if (colon >= 0 && close > colon) {
                return classAndMethod + location.substring(colon, close);
            }
        }
        return classAndMethod;
    }

    private static String shortInherited(String inherited) {
        int lastDot = inherited.lastIndexOf('.');
        if (lastDot <= 0) {
            return inherited;
        }
        String owner = inherited.substring(0, lastDot);
        String prop = inherited.substring(lastDot + 1);
        String simpleOwner = owner.substring(owner.lastIndexOf('.') + 1);
        String getter = prop.isEmpty() ? prop : "get" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
        return simpleOwner + "." + getter;
    }

    private static String serializerName(String reader) {
        if (reader.startsWith("com.fasterxml.jackson.") || reader.startsWith("tools.jackson.")) {
            return "Jackson";
        }
        if (reader.startsWith("jakarta.json.") || reader.startsWith("javax.json.")) {
            return "JSON-B";
        }
        return reader;
    }
}
