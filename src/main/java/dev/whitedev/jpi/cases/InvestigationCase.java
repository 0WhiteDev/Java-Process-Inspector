package dev.whitedev.jpi.cases;

import dev.whitedev.jpi.deobfuscation.MappingEntry;
import dev.whitedev.jpi.evidence.EvidenceStore.Graph;
import dev.whitedev.jpi.exceptions.ExceptionSnapshot;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record InvestigationCase(Manifest manifest, Fingerprint fingerprint, List<Definition> catalog,
                                Map<String, byte[]> bytecode, List<TimelineEvent> timeline, List<Trace> traces,
                                ExceptionSnapshot exceptions, String callGraph, List<MappingEntry> mappings,
                                String notes, List<Bookmark> bookmarks, List<Evidence> evidence, Graph evidenceGraph) {
    public InvestigationCase(Manifest manifest, Fingerprint fingerprint, List<Definition> catalog,
                             Map<String, byte[]> bytecode, List<TimelineEvent> timeline, List<Trace> traces,
                             ExceptionSnapshot exceptions, String callGraph, List<MappingEntry> mappings,
                             String notes, List<Bookmark> bookmarks, List<Evidence> evidence) {
        this(manifest, fingerprint, catalog, bytecode, timeline, traces, exceptions, callGraph, mappings, notes, bookmarks,
                evidence, null);
    }

    public InvestigationCase {
        catalog = List.copyOf(catalog);
        bytecode = Map.copyOf(bytecode);
        timeline = List.copyOf(timeline);
        traces = List.copyOf(traces);
        mappings = List.copyOf(mappings);
        bookmarks = List.copyOf(bookmarks);
        evidence = List.copyOf(evidence);
    }

    public record Manifest(int version, String title, long startedAt, long endedAt, boolean runtimeDetails,
                           List<String> notices) {
        public Manifest {
            notices = List.copyOf(notices);
        }
    }

    public record Fingerprint(String id, String mainClass, String javaVersion, int loadedClasses) {
    }

    public record Definition(String id, String name, String loader) {
        public Definition {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(loader);
        }

        @Override
        public String toString() {
            return name + "  [" + loader + "]";
        }
    }

    public record Trace(long sequence, long parent, long timestamp, long durationNanos, String outcome,
                        String classId, String owner, String method, String descriptor, String details) {
        public Trace {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(classId);
            Objects.requireNonNull(owner);
            Objects.requireNonNull(method);
            Objects.requireNonNull(descriptor);
            Objects.requireNonNull(details);
        }

        public String symbol() {
            return owner + "." + method + descriptor;
        }
    }

    public record Bookmark(String classId, String method, String descriptor, String label) {
        public Bookmark {
            Objects.requireNonNull(classId);
            Objects.requireNonNull(method);
            Objects.requireNonNull(descriptor);
            Objects.requireNonNull(label);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public record Evidence(String kind, String title, String classId, String method, String descriptor, String text) {
        public Evidence {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(title);
            Objects.requireNonNull(classId);
            Objects.requireNonNull(method);
            Objects.requireNonNull(descriptor);
            Objects.requireNonNull(text);
        }
    }
}
