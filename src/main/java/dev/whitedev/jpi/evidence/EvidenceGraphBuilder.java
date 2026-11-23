package dev.whitedev.jpi.evidence;

import dev.whitedev.jpi.cases.InvestigationCase;
import dev.whitedev.jpi.evidence.EvidenceStore.Graph;
import dev.whitedev.jpi.evidence.EvidenceStore.Kind;
import dev.whitedev.jpi.evidence.EvidenceStore.Symbol;
import dev.whitedev.jpi.exceptions.ExceptionSnapshot;
import dev.whitedev.jpi.ui.callgraph.CallGraphModel;
import dev.whitedev.jpi.ui.callgraph.CallGraphModel.ObservedCall;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Printer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class EvidenceGraphBuilder {
    private static final Call AMBIGUOUS = new Call("", "", 0, "");
    private final EvidenceStore store = new EvidenceStore();
    private final Map<String, Call> calls = new LinkedHashMap<>();
    private final Map<String, TreeMap<Long, Call>> threads = new HashMap<>();
    private final TreeMap<Long, Call> allCalls = new TreeMap<>();

    private EvidenceGraphBuilder() {
    }

    public static Graph live(List<TimelineEvent> events) {
        return live(events, null, List.of());
    }

    public static Graph live(List<TimelineEvent> events, ExceptionSnapshot exceptions, List<ObservedCall> observedCalls) {
        EvidenceGraphBuilder graph = new EvidenceGraphBuilder();
        graph.runtime(events, List.of());
        graph.exceptions(exceptions, List.of());
        graph.aggregateCalls(observedCalls);
        return graph.store.snapshot();
    }

    public static Graph saved(InvestigationCase data) {
        EvidenceGraphBuilder graph = new EvidenceGraphBuilder();
        graph.runtime(data.timeline(), data.traces());
        graph.exceptions(data.exceptions(), data.catalog());
        graph.aggregateCalls(CallGraphModel.observedCalls(data.callGraph()));
        graph.reports(data);
        graph.bytecode(data);
        if (!data.manifest().notices().isEmpty()) graph.store.markPartial();
        return graph.store.snapshot();
    }

    private void aggregateCalls(List<ObservedCall> observedCalls) {
        for (var call : observedCalls) {
            store.connect(method(call.caller()), method(call.callee()), Kind.OBSERVED,
                    "Call graph summary", call.count() + " observed calls; aggregate, loader unknown");
        }
    }

    private void runtime(List<TimelineEvent> events, List<InvestigationCase.Trace> traces) {
        for (var trace : traces) {
            String sequence = Long.toString(trace.sequence());
            Symbol symbol = new Symbol(trace.classId(), trace.owner(), trace.method(), trace.descriptor());
            String label = symbol.label();
            String node = store.addNode("Trace", sequence, "#" + sequence + " " + label, label,
                    symbol);
            store.connect(node, method(symbol), Kind.OBSERVED, "Trace", "Instrumented definition for call #" + sequence);
            calls.putIfAbsent(sequence, new Call(node, Long.toString(trace.parent()), trace.timestamp(), ""));
        }
        for (TimelineEvent event : events) {
            if (event.source() != TimelineSource.TRACE || event.callId().isEmpty()) continue;
            Call existing = calls.get(event.callId());
            boolean start = "ENTER".equals(event.phase());
            if (existing != null && !(start && existing.timestamp() < 0)
                    && (!existing.thread().isEmpty() || event.thread().isEmpty())) continue;
            String node = existing == null ? store.addNode("Trace", event.callId(),
                    "#" + event.callId() + " " + (event.subject().isEmpty() ? "Recorded call" : event.subject()),
                    "", event.symbol()) : existing.node();
            if (existing == null && event.symbol() != null) {
                store.connect(node, method(event.symbol()), Kind.OBSERVED, "Trace",
                        "Instrumented definition for call #" + event.callId());
            }
            long startedAt = existing == null ? -1 : existing.timestamp();
            if (start) startedAt = event.timestamp();
            calls.put(event.callId(), new Call(node, existing == null ? event.parentCallId() : existing.parent(),
                    startedAt, event.thread()));
        }
        for (var entry : calls.entrySet()) {
            Call call = entry.getValue();
            if (call.node().isEmpty()) continue;
            Call parent = calls.get(call.parent());
            if (parent != null && !"0".equals(call.parent())) {
                store.connect(parent.node(), call.node(), Kind.OBSERVED, "Trace", "Recorded parent call #" + call.parent());
            }
            if (call.timestamp() < 0) continue;
            allCalls.merge(call.timestamp(), call, (first, second) -> AMBIGUOUS);
            if (!call.thread().isEmpty()) {
                threads.computeIfAbsent(call.thread(), key -> new TreeMap<>())
                        .merge(call.timestamp(), call, (first, second) -> AMBIGUOUS);
            }
        }
        for (TimelineEvent event : events) {
            String source = event.source().toString();
            String label = event.summary().isBlank() ? source : event.summary();
            String node = store.addNode("Timeline", event.key(), label,
                    source + " | " + Instant.ofEpochMilli(event.timestamp()) + "\n" + event.details(), event.symbol());
            if (event.source() == TimelineSource.FIELD_WRITE && event.symbol() != null) {
                store.connect(method(event.symbol()), node, Kind.OBSERVED, source, "Writer recorded by the field write probe");
            }
            Call direct = calls.get(event.callId());
            if (direct != null && (!event.callId().equals("0")) && (event.thread().isEmpty()
                    || direct.thread().isEmpty() || event.thread().equals(direct.thread()))) {
                store.connect(direct.node(), node, Kind.OBSERVED, source, "Shared recorded call ID #" + event.callId());
            } else if (event.callId().isEmpty() && correlatable(event.source())) {
                correlate(node, event, source);
            }
        }
    }

    private static boolean correlatable(TimelineSource source) {
        return switch (source) {
            case NETWORK, FILE, API_HOOK, FIELD_WRITE, CFG, MARKER -> true;
            default -> false;
        };
    }

    private void correlate(String node, TimelineEvent event, String source) {
        boolean threaded = !event.thread().isEmpty();
        TreeMap<Long, Call> candidates = threaded ? threads.get(event.thread()) : allCalls;
        if (candidates == null) return;
        var before = candidates.floorEntry(event.timestamp());
        var after = candidates.higherEntry(event.timestamp());
        long past = before == null ? Long.MAX_VALUE : event.timestamp() - before.getKey();
        long future = after == null ? Long.MAX_VALUE : after.getKey() - event.timestamp();
        long distance = Math.min(past, future);
        if (past == future || distance > (threaded ? 2000 : 350)) return;
        Call nearest = (past < future ? before : after).getValue();
        if (nearest == AMBIGUOUS) return;
        store.connect(nearest.node(), node, Kind.TIME_CORRELATED, source,
                (threaded ? "Same recorded thread, " : "No thread identity, ")
                        + distance + " ms from call start; not evidence of causation");
    }

    private void exceptions(ExceptionSnapshot snapshot, List<InvestigationCase.Definition> catalog) {
        if (snapshot == null) return;
        if (snapshot.dropped() > 0) store.markPartial();
        Map<String, List<InvestigationCase.Definition>> definitions = new HashMap<>();
        for (var definition : catalog) {
            var matches = definitions.computeIfAbsent(definition.name(), key -> new ArrayList<>());
            if (matches.size() < 2) matches.add(definition);
        }
        for (var entry : snapshot.entries()) {
            String event = store.addNode("Exceptions", entry.type(), entry.type(),
                    entry.total() + " recorded creations in the exception snapshot", null);
            for (var location : entry.locations()) {
                Symbol symbol = new Symbol("", location.owner(), location.method(), location.descriptor());
                String site = method(symbol);
                store.connect(site, event, Kind.OBSERVED, "Exceptions",
                        location.count() + " recorded creations at line " + location.line() + "; loader unknown");
                var matches = definitions.getOrDefault(symbol.owner(), List.of());
                if (matches.size() == 1 && !symbol.descriptor().isEmpty()) {
                    Symbol candidate = new Symbol(matches.getFirst().id(), symbol.owner(), symbol.member(), symbol.descriptor());
                    store.connect(method(candidate), site, Kind.INFERRED, "Exception/catalog match",
                            "Only matching owner in the saved catalog; recording did not identify the loader");
                }
            }
        }
    }

    private void reports(InvestigationCase data) {
        Map<String, String> owners = new HashMap<>();
        for (var definition : data.catalog()) owners.put(definition.id(), definition.name());
        for (var report : data.evidence()) {
            String node = store.addNode(report.kind(),
                    EvidenceStore.id(report.title(), report.classId(), report.method(), report.descriptor(), report.text()),
                    report.title(), "Saved report. See Timeline and traces for the complete text.", null);
            String owner = owners.get(report.classId());
            if (owner != null && !report.method().isEmpty() && !report.descriptor().isEmpty()) {
                Symbol symbol = new Symbol(report.classId(), owner, report.method(), report.descriptor());
                store.connect(method(symbol), node, Kind.OBSERVED, "Saved analysis", "Report explicitly scoped to this method");
            }
        }
    }

    private void bytecode(InvestigationCase data) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        for (var definition : data.catalog()) {
            byte[] bytes = data.bytecode().get(definition.id());
            if (bytes == null) continue;
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                store.markPartial();
                break;
            }
            try {
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        Symbol source = new Symbol(definition.id(), definition.name(), name, descriptor);
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String member, String type, boolean isInterface) {
                                reference(opcode, owner, member, type);
                            }

                            @Override
                            public void visitFieldInsn(int opcode, String owner, String member, String type) {
                                reference(opcode, owner, member, type);
                            }

                            private void reference(int opcode, String owner, String member, String type) {
                                if (System.nanoTime() >= deadline) {
                                    store.markPartial();
                                    return;
                                }
                                Symbol target = new Symbol("", owner.replace('/', '.'), member, type);
                                store.connect(method(source), method(target), Kind.STATIC, "Saved bytecode / Xrefs",
                                        Printer.OPCODES[opcode] + " symbolic reference; resolution not observed");
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (RuntimeException error) {
                store.markPartial();
            }
        }
    }

    private String method(Symbol symbol) {
        return store.addNode("JVM symbol", symbol.key(), symbol.label(), symbol.definitionId().isEmpty()
                ? "Symbolic reference; defining loader unknown" : "Class definition: " + symbol.definitionId(), symbol);
    }

    private record Call(String node, String parent, long timestamp, String thread) {
    }
}
