package dev.whitedev.jpi.heap;

import org.netbeans.lib.profiler.heap.ArrayItemValue;
import org.netbeans.lib.profiler.heap.GCRoot;
import org.netbeans.lib.profiler.heap.Heap;
import org.netbeans.lib.profiler.heap.HeapFactory;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;
import org.netbeans.lib.profiler.heap.ObjectArrayInstance;
import org.netbeans.lib.profiler.heap.ObjectFieldValue;
import org.netbeans.lib.profiler.heap.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HprofSnapshot {
    private static final int MAX_REFERENCES = 1000;
    private static final int MAX_PATH_NODES = 50000;
    private static final int MAX_PATH_DEPTH = 128;
    private static final int MAX_PATHS = 8;
    private final Heap heap;
    private final Path file;

    private HprofSnapshot(Heap heap, Path file) {
        this.heap = heap;
        this.file = file;
    }

    public static HprofSnapshot open(Path file) throws IOException {
        Path absolute = file.toRealPath();
        if (!Files.isRegularFile(absolute)) throw new IOException("Select a regular HPROF file");
        return new HprofSnapshot(HeapFactory.createHeap(absolute.toFile()), absolute);
    }

    public Path file() {
        return file;
    }

    public synchronized List<ObjectSummary> search(String classFilter, int limit) {
        if (limit < 1 || limit > 5000) throw new IllegalArgumentException("Result limit must be 1 to 5000");
        String filter = classFilter.trim().toLowerCase(Locale.ROOT);
        List<ObjectSummary> result = new ArrayList<>();
        for (Object item : heap.getAllClasses()) {
            JavaClass type = (JavaClass) item;
            if (!type.getName().toLowerCase(Locale.ROOT).contains(filter)) continue;
            var instances = type.getInstancesIterator();
            while (instances.hasNext() && result.size() < limit) result.add(summary((Instance) instances.next(), false));
            if (result.size() == limit) break;
        }
        return List.copyOf(result);
    }

    public synchronized List<ObjectSummary> biggest(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Dominator limit must be 1 to 100");
        List<ObjectSummary> result = new ArrayList<>();
        for (Object value : heap.getBiggestObjectsByRetainedSize(limit)) result.add(summary((Instance) value, true));
        return List.copyOf(result);
    }

    public synchronized ObjectReport inspect(long id, boolean retained, boolean excludeWeak) {
        Instance selected = heap.getInstanceByID(id);
        if (selected == null) throw new IllegalArgumentException("Object ID is not present in this snapshot: 0x" + Long.toHexString(id));
        List<Reference> incoming = new ArrayList<>();
        List<?> allIncoming = selected.getReferences();
        for (Object value : allIncoming) {
            if (incoming.size() == MAX_REFERENCES) break;
            incoming.add(reference((Value) value, true));
        }
        List<Reference> outgoing = new ArrayList<>();
        long outgoingCount = 0;
        for (Object value : selected.getFieldValues()) {
            if (value instanceof ObjectFieldValue field && field.getInstance() != null) {
                outgoingCount++;
                if (outgoing.size() < MAX_REFERENCES) outgoing.add(reference(field, false));
            }
        }
        JavaClass represented = heap.getJavaClassByID(id);
        if (represented != null) {
            for (Object value : represented.getStaticFieldValues()) {
                if (value instanceof ObjectFieldValue field && field.getInstance() != null) {
                    outgoingCount++;
                    if (outgoing.size() < MAX_REFERENCES) outgoing.add(reference(field, false));
                }
            }
        }
        if (selected instanceof ObjectArrayInstance array) {
            for (Object value : array.getItems()) {
                ArrayItemValue item = (ArrayItemValue) value;
                if (item.getInstance() == null) continue;
                outgoingCount++;
                if (outgoing.size() < MAX_REFERENCES) outgoing.add(reference(item, false));
            }
        }
        Paths paths = paths(selected, excludeWeak);
        return new ObjectReport(summary(selected, retained), allIncoming.size(), outgoingCount,
                List.copyOf(incoming), List.copyOf(outgoing), paths.paths, paths.limited, paths.visited);
    }

    private Paths paths(Instance selected, boolean excludeWeak) {
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        List<RootPath> result = new ArrayList<>();
        queue.add(new SearchNode(selected, null, "selected object", 0));
        visited.add(selected.getInstanceId());
        boolean limited = false;
        while (!queue.isEmpty() && result.size() < MAX_PATHS) {
            SearchNode current = queue.removeFirst();
            GCRoot root = heap.getGCRoot(current.instance);
            if (root != null) {
                List<PathStep> steps = new ArrayList<>();
                for (SearchNode node = current; node != null; node = node.child) {
                    steps.add(new PathStep(node.instance.getInstanceId(), label(node.instance), node.edge));
                }
                result.add(new RootPath(root.getKind(), List.copyOf(steps)));
                continue;
            }
            if (current.depth >= MAX_PATH_DEPTH) {
                limited = true;
                continue;
            }
            for (Object value : current.instance.getReferences()) {
                Value reference = (Value) value;
                if (excludeWeak && weak(reference)) continue;
                Instance source = reference.getDefiningInstance();
                if (source == null || visited.contains(source.getInstanceId())) continue;
                if (visited.size() >= MAX_PATH_NODES) {
                    limited = true;
                    break;
                }
                visited.add(source.getInstanceId());
                queue.addLast(new SearchNode(source, current, edge(reference), current.depth + 1));
            }
        }
        return new Paths(List.copyOf(result), limited || !queue.isEmpty(), visited.size());
    }

    private ObjectSummary summary(Instance instance, boolean retained) {
        return new ObjectSummary(instance.getInstanceId(), label(instance), instance.getSize(),
                retained ? instance.getRetainedSize() : -1L);
    }

    private String label(Instance instance) {
        JavaClass represented = heap.getJavaClassByID(instance.getInstanceId());
        return (represented == null ? instance.getJavaClass().getName() : represented.getName() + ".class")
                + "@" + Long.toHexString(instance.getInstanceId());
    }

    private Reference reference(Value value, boolean incoming) {
        Instance other = incoming ? value.getDefiningInstance() : target(value);
        return new Reference(other == null ? 0 : other.getInstanceId(),
                other == null ? "unresolved reference" : label(other), edge(value), weak(value));
    }

    private static Instance target(Value value) {
        if (value instanceof ObjectFieldValue field) return field.getInstance();
        if (value instanceof ArrayItemValue item) return item.getInstance();
        return null;
    }

    private static String edge(Value value) {
        if (value instanceof ObjectFieldValue field) {
            return (field.getField().isStatic() ? "static " : "")
                    + field.getField().getDeclaringClass().getName() + "." + field.getField().getName();
        }
        if (value instanceof ArrayItemValue item) return "[" + item.getIndex() + "]";
        return "reference";
    }

    private static boolean weak(Value value) {
        if (!(value instanceof ObjectFieldValue field) || !"referent".equals(field.getField().getName())
                || !"java.lang.ref.Reference".equals(field.getField().getDeclaringClass().getName())) return false;
        Instance source = value.getDefiningInstance();
        for (JavaClass type = source == null ? null : source.getJavaClass(); type != null; type = type.getSuperClass()) {
            String name = type.getName();
            if ("java.lang.ref.WeakReference".equals(name) || "java.lang.ref.SoftReference".equals(name)
                    || "java.lang.ref.PhantomReference".equals(name)) return true;
        }
        return false;
    }

    public record ObjectSummary(long id, String label, long shallowSize, long retainedSize) implements java.io.Serializable {}
    public record Reference(long id, String object, String edge, boolean weak) implements java.io.Serializable {}
    public record PathStep(long id, String object, String edgeToChild) implements java.io.Serializable {}
    public record RootPath(String kind, List<PathStep> steps) implements java.io.Serializable {}
    public record ObjectReport(ObjectSummary object, long incomingCount, long outgoingCount,
                               List<Reference> incoming, List<Reference> outgoing, List<RootPath> paths,
                               boolean pathsLimited, int visitedObjects) implements java.io.Serializable {}
    private record SearchNode(Instance instance, SearchNode child, String edge, int depth) {}
    private record Paths(List<RootPath> paths, boolean limited, int visited) {}
}
