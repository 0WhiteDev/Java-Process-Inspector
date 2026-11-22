package dev.whitedev.jpi.cases;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.cases.InvestigationCase.Definition;
import dev.whitedev.jpi.cases.InvestigationCase.Fingerprint;
import dev.whitedev.jpi.cases.InvestigationCase.Manifest;
import dev.whitedev.jpi.cases.InvestigationCase.Trace;
import dev.whitedev.jpi.deobfuscation.MappingEntry;
import dev.whitedev.jpi.exceptions.ExceptionSnapshot;
import dev.whitedev.jpi.fingerprint.TargetFingerprint;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

public final class CaseRecorder {
    private static final int RECORD_BUDGET = 6 * 1024 * 1024;
    private final boolean details;
    private final String title;
    private final long startedAt;
    private final long traceBaseline;
    private final Fingerprint fingerprint;
    private final Map<String, Definition> catalog = new LinkedHashMap<>();
    private final Map<String, byte[]> bytes = new LinkedHashMap<>();
    private final List<TimelineEvent> timeline = new ArrayList<>();
    private final List<String> notices;
    private int savedBytes;
    private int droppedTimeline;
    private int timelineBytes;
    private int catalogBytes = 2;
    private long endedAt;

    private CaseRecorder(String title, boolean details, long baseline, Fingerprint fingerprint, List<String> notices) {
        this.title = title;
        this.details = details;
        this.fingerprint = fingerprint;
        this.notices = new ArrayList<>(notices);
        traceBaseline = baseline;
        startedAt = System.currentTimeMillis();
    }

    public static CaseRecorder start(InspectorSession session, String title, boolean details,
                                     BooleanSupplier current) throws IOException {
        List<String> notices = new ArrayList<>();
        Fingerprint fingerprint = optional(notices, "Target fingerprint", () -> {
            String raw = request(session, current, Operation.TARGET_FINGERPRINT, "#case-catalog");
            TargetFingerprint value = TargetFingerprint.parse(raw);
            return new Fingerprint(value.id(), clip(value.mainClass(), 512), clip(value.javaVersion(), 128), value.loadedCount());
        }, null);
        String inventory = request(session, current, Operation.CLASSES, "");
        long baseline = optional(notices, "Trace baseline", () ->
                latestTrace(request(session, current, Operation.TRACE_HISTORY, "")), -1L);
        CaseRecorder recorder = new CaseRecorder(clip(title.strip(), 160), details, baseline, fingerprint, notices);
        recorder.mergeCatalog(inventory);
        return recorder;
    }

    public void refreshCatalog(InspectorSession session, BooleanSupplier current) throws IOException {
        String inventory = request(session, current, Operation.CLASSES, "");
        if (current.getAsBoolean()) mergeCatalog(inventory);
    }

    private synchronized void mergeCatalog(String inventory) {
        Set<String> duplicates = new HashSet<>();
        for (String line : inventory.split("\n")) {
            String[] fields = line.split("\t", 4);
            if (fields.length >= 3 && (fields[0].startsWith("c:") || fields[0].startsWith("b:"))
                    && fields[0].length() <= 1024 && duplicates.add(fields[0]) && !catalog.containsKey(fields[0])) {
                Definition definition = new Definition(fields[0], clip(fields[1], 768), clip(fields[2], 512));
                int added = CaseArchive.jsonLength(definition) + 1;
                if (catalog.size() >= CaseArchive.MAX_CATALOG || catalogBytes + added > RECORD_BUDGET) {
                    notice("Class catalog reached its entry or size limit.");
                    break;
                }
                catalog.put(definition.id(), definition);
                catalogBytes += added;
            }
        }
    }

    public synchronized List<Definition> catalog() {
        return List.copyOf(catalog.values());
    }

    public synchronized Set<String> savedDefinitions() {
        return Set.copyOf(bytes.keySet());
    }

    public synchronized void event(TimelineEvent event) {
        if (endedAt != 0) return;
        if (timeline.size() >= CaseArchive.MAX_EVENTS) {
            droppedTimeline++;
            return;
        }
        String key = "event-" + (timeline.size() + 1);
        String summary = details ? clip(event.summary(), 192) : event.source().toString();
        TimelineEvent observation = new TimelineEvent(key, event.timestamp(), event.source(),
                details ? clip(event.thread(), 64) : "", numericId(event.callId()), numericId(event.parentCallId()), summary,
                details ? clip(event.details(), 256) : "", details ? clip(event.subject(), 96) : "",
                details ? clip(event.phase(), 32) : "", details ? clip(event.value(), 96) : "");
        int cost = CaseArchive.jsonLength(observation) + 1;
        if (timelineBytes + cost > RECORD_BUDGET) {
            droppedTimeline++;
            return;
        }
        timelineBytes += cost;
        timeline.add(observation);
    }

    public int capture(InspectorSession session, List<Definition> selected, BooleanSupplier current) {
        int added = 0;
        long deadline = System.nanoTime() + 5_000_000_000L;
        for (Definition definition : selected) {
            if (!current.getAsBoolean()) break;
            if (System.nanoTime() >= deadline) {
                notice("Class capture reached the time limit. Capture smaller batches to continue.");
                break;
            }
            synchronized (this) {
                if (endedAt != 0) break;
                if (bytes.size() >= CaseArchive.MAX_SAVED_CLASSES || savedBytes >= CaseArchive.BYTECODE_BUDGET) {
                    notice("Saved class bytecode reached its entry or size limit.");
                    break;
                }
                if (!definition.equals(catalog.get(definition.id()))) continue;
                if (bytes.containsKey(definition.id())) continue;
            }
            try {
                byte[] data = session.request(Operation.CLASS_BYTES, definition.id());
                CaseArchive.validateClass(definition, data);
                synchronized (this) {
                    if (endedAt != 0 || !current.getAsBoolean()) break;
                    if (savedBytes + data.length > CaseArchive.BYTECODE_BUDGET) {
                        notice("Saved bytecode reached the 48 MiB budget.");
                        break;
                    }
                    bytes.put(definition.id(), data);
                    savedBytes += data.length;
                    added++;
                }
            } catch (IOException error) {
                notice("Could not save bytecode for " + definition.name() + ".");
            }
        }
        return added;
    }

    public synchronized void seal() {
        if (endedAt == 0) endedAt = System.currentTimeMillis();
    }

    public synchronized boolean isSealed() {
        return endedAt != 0;
    }

    public InvestigationCase finish(InspectorSession session, List<MappingEntry> mappings, BooleanSupplier current) {
        seal();
        List<String> failures = new ArrayList<>();
        List<Trace> traces = traceBaseline < 0 ? List.of() : optional(failures, "Trace history",
                () -> traces(request(session, current, Operation.TRACE_HISTORY, ""), failures), List.of());
        ExceptionSnapshot exceptions = optional(failures, "Exception snapshot",
                () -> exceptions(request(session, current, Operation.EXCEPTION_SNAPSHOT, "30")), null);
        String graph = optional(failures, "Call graph", () -> {
            String value = request(session, current, Operation.TRACE_GRAPH, "");
            if (value.length() > 1024 * 1024) throw new IOException("Call graph exceeds case budget");
            return value;
        }, "");
        optional(failures, "Final class catalog", () -> {
            refreshCatalog(session, current);
            return true;
        }, false);
        failures.forEach(this::notice);
        return snapshot(mappings, traces, exceptions, graph);
    }

    public InvestigationCase interrupted(List<MappingEntry> mappings) {
        seal();
        notice("Target connection changed. Final runtime snapshots were not collected.");
        return snapshot(mappings, List.of(), null, "");
    }

    private synchronized InvestigationCase snapshot(List<MappingEntry> mappings, List<Trace> traces,
                                                    ExceptionSnapshot exceptions, String graph) {
        List<String> summary = new ArrayList<>(notices);
        if (droppedTimeline > 0) summary.add(droppedTimeline + " timeline events exceeded the recording limit.");
        List<MappingEntry> mapped = new ArrayList<>();
        int mappingBytes = 0;
        for (MappingEntry entry : mappings) {
            if (entry.enabled() && entry.mappedName().isBlank() && entry.comment().isBlank()
                    && entry.tags().isBlank() && entry.color().isBlank()) continue;
            MappingEntry bounded = new MappingEntry(entry.kind(), clip(entry.owner(), 256),
                    clip(entry.originalName(), 128), clip(entry.descriptor(), 256), entry.parameterIndex(), entry.access(),
                    clip(entry.mappedName(), 128), clip(entry.comment(), 256), clip(entry.tags(), 128),
                    clip(entry.color(), 32), entry.enabled());
            if ((mappingBytes += CaseArchive.jsonLength(bounded) + 1) > RECORD_BUDGET || mapped.size() >= 1500) {
                summary.add("Mappings reached the case entry or size limit.");
                break;
            }
            mapped.add(bounded);
        }
        return new InvestigationCase(new Manifest(CaseArchive.VERSION, title, startedAt, Math.max(endedAt, startedAt), details, summary),
                fingerprint, catalog(), bytes, timeline, traces, exceptions, graph, mapped, "", List.of(), List.of());
    }

    private synchronized void notice(String message) {
        if (notices.size() < 32 && !notices.contains(message)) notices.add(clip(message, 256));
    }

    private List<Trace> traces(String wire, List<String> failures) {
        List<Trace> result = new ArrayList<>();
        long previous = traceBaseline;
        int size = 0;
        for (String line : wire.split("\n")) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 17 || !fields[0].equals("E")) continue;
            long sequence = Long.parseLong(fields[1]);
            if (sequence <= traceBaseline) continue;
            if (result.size() == CaseArchive.MAX_EVENTS) {
                failures.add("Trace recording exceeded the case event limit.");
                break;
            }
            if (sequence > previous + 1 && !failures.contains("Trace history has gaps; the agent's history is bounded."))
                failures.add("Trace history has gaps; the agent's history is bounded.");
            previous = sequence;
            String extra = details ? "Thread: " + decoded(fields[11], 64) + "\nArguments: " + decoded(fields[13], 128)
                    + "\nReturn: " + decoded(fields[14], 128) + "\nException: " + decoded(fields[15], 128)
                    + "\nStack: " + decoded(fields[16], 256) : "";
            Trace trace = new Trace(sequence, Long.parseLong(fields[2]), Long.parseLong(fields[3]), Long.parseLong(fields[5]),
                    clip(fields[6], 24), decoded(fields[7], 128), decoded(fields[8], 192), decoded(fields[9], 96),
                    decoded(fields[10], 192), extra);
            if ((size += CaseArchive.jsonLength(trace) + 1) > RECORD_BUDGET) {
                failures.add("Trace recording exceeded the case byte budget.");
                break;
            }
            result.add(trace);
        }
        return result;
    }

    private ExceptionSnapshot exceptions(String wire) {
        ExceptionSnapshot snapshot = ExceptionSnapshot.parse(wire);
        return new ExceptionSnapshot(snapshot.timestamp(), snapshot.state(), snapshot.windowSeconds(), snapshot.dropped(), "",
                snapshot.entries().stream().limit(256).map(entry -> new ExceptionSnapshot.Entry(
                        clip(entry.type(), 256), entry.windowCount(), entry.total(), entry.firstSeen(), entry.lastSeen(),
                        entry.threads(), entry.threadOverflow(), entry.locations().stream().limit(12)
                        .map(location -> new ExceptionSnapshot.Location(location.count(), clip(location.owner(), 256),
                                clip(location.method(), 128), clip(location.descriptor(), 256), location.line(),
                                details ? clip(location.stack(), 256) : "")).toList())).toList());
    }

    private static long latestTrace(String wire) {
        long last = 0;
        for (String line : wire.split("\n")) {
            String[] fields = line.split("\t", 3);
            if (fields.length == 3 && fields[0].equals("E")) last = Math.max(last, Long.parseLong(fields[1]));
        }
        return last;
    }

    private static String request(InspectorSession session, BooleanSupplier current, Operation operation, String value) throws IOException {
        if (!current.getAsBoolean()) throw new IOException("Target connection changed");
        return session.requestText(operation, value);
    }

    private static String numericId(String value) {
        return value.length() <= 24 && value.matches("[0-9]+") ? value : "";
    }

    private static String decoded(String value, int limit) {
        return clip(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8), limit);
    }

    static String clip(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 3) + "...";
    }

    private static <T> T optional(List<String> failures, String label, Source<T> source, T fallback) {
        try {
            return source.get();
        } catch (IOException | RuntimeException error) {
            failures.add(label + " unavailable.");
            return fallback;
        }
    }

    @FunctionalInterface
    private interface Source<T> {
        T get() throws IOException;
    }
}
