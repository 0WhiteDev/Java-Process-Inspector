package dev.whitedev.jpi.agent.heap;

import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HeapObjectInspector {
    private static final int MAX_HANDLES = 20000;
    private static final int MAX_DETAIL_FIELDS = 1000;
    private static final int MAX_SCAN_FIELDS_PER_OBJECT = 1000;
    private static final int MAX_OUTGOING_REFERENCES = 512;

    private final Instrumentation instrumentation;
    private final LinkedHashMap<Long, ObjectHandle> handles = new LinkedHashMap<Long, ObjectHandle>();
    private long nextId;

    public HeapObjectInspector(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    public synchronized String scan(String payload) throws Exception {
        HeapScanConfig config = HeapScanConfig.parse(payload);
        handles.clear();
        long started = System.nanoTime();
        long deadline = started + config.timeoutMillis * 1_000_000L;
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<Object, Boolean>();
        ArrayDeque<Node> queue = new ArrayDeque<Node>();
        Map<String, ClassCount> counts = new LinkedHashMap<String, ClassCount>();
        int rootFields = collectRoots(config, queue, deadline);
        int matches = 0;
        long shallowBytes = 0L;
        boolean truncated = false;
        StringBuilder instances = new StringBuilder();

        while (!queue.isEmpty()) {
            if (visited.size() >= config.maxObjects || System.nanoTime() >= deadline) {
                truncated = true;
                break;
            }
            Node node = queue.removeFirst();
            if (visited.put(node.value, Boolean.TRUE) != null) continue;
            long size = size(node.value);
            shallowBytes += size;
            ClassCount count = counts.get(classKey(node.value.getClass()));
            if (count == null) {
                count = new ClassCount(node.value.getClass());
                counts.put(classKey(node.value.getClass()), count);
            }
            count.instances++;
            count.shallowBytes += size;

            Inspection inspection = inspect(node.value, config);
            if (matches(node.value, inspection, config)) {
                matches++;
                if (matches <= config.maxResults) appendInstance(instances, node, size, config);
            }
            if (node.depth >= config.maxDepth) continue;
            for (Reference reference : inspection.references) {
                if (reference.value == null || visited.containsKey(reference.value)) continue;
                String path = node.path + reference.path;
                long id = register(reference.value, path, node.root, node.depth + 1);
                queue.addLast(new Node(id, reference.value, path, node.root, node.depth + 1));
            }
        }

        long duration = (System.nanoTime() - started) / 1_000_000L;
        StringBuilder output = new StringBuilder();
        output.append('S').append('\t').append(visited.size()).append('\t').append(rootFields).append('\t')
                .append(matches).append('\t').append(shallowBytes).append('\t').append(duration).append('\t')
                .append(truncated).append('\t').append(instrumentation.getAllLoadedClasses().length).append('\n');
        appendCounts(output, counts);
        output.append(instances);
        return output.toString();
    }

    public synchronized void clear() {
        handles.clear();
    }

    public synchronized String object(String idText) throws Exception {
        long id;
        try {
            id = Long.parseLong(idText.trim());
        } catch (NumberFormatException error) {
            throw new java.io.IOException("Invalid heap object ID", error);
        }
        ObjectHandle handle = handles.get(Long.valueOf(id));
        Object value = handle == null ? null : handle.reference.get();
        if (value == null) throw new java.io.IOException("The sampled object is no longer reachable or its handle expired");
        int maxValueLength = 2048;
        StringBuilder output = new StringBuilder();
        output.append('H').append('\t').append(id).append('\t').append(encoded(value.getClass().getName())).append('\t')
                .append(encoded(loaderLabel(value.getClass().getClassLoader()))).append('\t')
                .append(size(value)).append('\t').append(handle.depth).append('\t').append(encoded(handle.root)).append('\t')
                .append(encoded(handle.path)).append('\t').append(encoded(preview(value, maxValueLength))).append('\n');
        if (scalar(value, maxValueLength) != null || terminalReference(value)) return output.toString();

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            int shown = Math.min(length, 512);
            for (int index = 0; index < shown; index++) {
                Object item = Array.get(value, index);
                appendMember(output, "A", value.getClass().getName(), "[" + index + "]",
                        value.getClass().getComponentType().getTypeName(), item,
                        handle.path + "[" + index + "]", handle.root, handle.depth + 1, maxValueLength);
            }
            if (shown < length) {
                output.append('X').append('\t').append(encoded("Array detail limited to 512 of " + length + " elements"))
                        .append('\n');
            }
            return output.toString();
        }

        int fields = 0;
        for (Field field : instanceFields(value.getClass())) {
            if (++fields > MAX_DETAIL_FIELDS) {
                output.append('X').append('\t').append(encoded("Field detail limited to " + MAX_DETAIL_FIELDS)).append('\n');
                break;
            }
            try {
                field.setAccessible(true);
                Object fieldValue = field.get(value);
                appendMember(output, "F", field.getDeclaringClass().getName(), field.getName(),
                        field.getType().getTypeName(), fieldValue, handle.path + "." + field.getName(),
                        handle.root, handle.depth + 1, maxValueLength);
            } catch (Throwable error) {
                output.append('F').append('\t').append(encoded(field.getDeclaringClass().getName())).append('\t')
                        .append(encoded(field.getName())).append('\t').append(encoded(field.getType().getTypeName()))
                        .append('\t').append(0).append('\t').append(encoded("<inaccessible>")).append('\n');
            }
        }
        return output.toString();
    }

    private int collectRoots(HeapScanConfig config, ArrayDeque<Node> queue, long deadline) {
        int fields = 0;
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (System.nanoTime() >= deadline || fields >= config.maxRootFields) break;
            if (!config.matchesRoot(type.getName()) || !rootTypeAllowed(type)) continue;
            for (Field field : type.getDeclaredFields()) {
                if (fields >= config.maxRootFields || System.nanoTime() >= deadline) break;
                if (!Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive() || field.isSynthetic()) continue;
                fields++;
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value == null) continue;
                    String root = type.getName() + "." + field.getName();
                    long id = register(value, root, root, 0);
                    queue.addLast(new Node(id, value, root, root, 0));
                } catch (Throwable ignored) {
                }
            }
        }
        return fields;
    }

    private Inspection inspect(Object value, HeapScanConfig config) {
        Inspection result = new Inspection();
        String own = scalar(value, config.maxValueLength);
        if (own != null && config.matchesValue(own)) result.valueMatch = true;
        if (terminal(value)) return result;
        Class<?> type = value.getClass();
        if (type.isArray()) {
            if (type.getComponentType().isPrimitive()) return result;
            int length = Math.min(Array.getLength(value), config.maxArrayElements);
            for (int index = 0; index < length; index++) {
                Object child = Array.get(value, index);
                if (child != null) result.references.add(new Reference("[" + index + "]", child));
                String text = scalar(child, config.maxValueLength);
                if (text != null && config.matchesValue(text)) result.valueMatch = true;
            }
            return result;
        }
        if (platformType(type)) return result;
        int examinedFields = 0;
        for (Field field : instanceFields(type)) {
            if (++examinedFields > MAX_SCAN_FIELDS_PER_OBJECT
                    || result.references.size() >= MAX_OUTGOING_REFERENCES) break;
            try {
                field.setAccessible(true);
                Object child = field.get(value);
                String text = scalar(child, config.maxValueLength);
                boolean fieldMatches = config.matchesField(field.getName());
                if (fieldMatches && config.matchesValue(text == null ? preview(child, config.maxValueLength) : text)) {
                    result.fieldMatch = true;
                }
                if (config.fieldFilter.isEmpty() && text != null && config.matchesValue(text)) result.valueMatch = true;
                if (child != null && !field.getType().isPrimitive() && !terminalReference(child)) {
                    result.references.add(new Reference("." + field.getName(), child));
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private boolean matches(Object value, Inspection inspection, HeapScanConfig config) {
        if (!config.matchesClass(value.getClass().getName())) return false;
        if (!config.fieldFilter.isEmpty()) return inspection.fieldMatch;
        if (!config.valueFilter.isEmpty()) return inspection.valueMatch;
        return true;
    }

    private void appendInstance(StringBuilder output, Node node, long size, HeapScanConfig config) {
        output.append('O').append('\t').append(node.id).append('\t').append(encoded(node.value.getClass().getName()))
                .append('\t').append(encoded(loaderLabel(node.value.getClass().getClassLoader())))
                .append('\t').append(size).append('\t').append(node.depth).append('\t').append(encoded(node.root))
                .append('\t').append(encoded(node.path)).append('\t')
                .append(encoded(preview(node.value, config.maxValueLength))).append('\n');
    }

    private static void appendCounts(StringBuilder output, Map<String, ClassCount> counts) {
        List<ClassCount> ordered = new ArrayList<ClassCount>(counts.values());
        Collections.sort(ordered, new Comparator<ClassCount>() {
            @Override public int compare(ClassCount left, ClassCount right) {
                int count = Long.compare(right.instances, left.instances);
                return count == 0 ? left.className.compareTo(right.className) : count;
            }
        });
        int limit = Math.min(ordered.size(), 2000);
        for (int index = 0; index < limit; index++) {
            ClassCount count = ordered.get(index);
            output.append('C').append('\t').append(encoded(count.className)).append('\t')
                    .append(encoded(count.loader)).append('\t').append(count.instances).append('\t')
                    .append(count.shallowBytes).append('\n');
        }
    }

    private void appendMember(StringBuilder output, String kind, String declaringClass, String name, String type,
                              Object value, String path, String root, int depth, int maximum) {
        long childId = 0L;
        if (value != null && scalar(value, maximum) == null && !terminalReference(value)) {
            childId = register(value, path, root, depth);
        }
        output.append(kind).append('\t').append(encoded(declaringClass)).append('\t').append(encoded(name)).append('\t')
                .append(encoded(type)).append('\t').append(childId).append('\t')
                .append(encoded(preview(value, maximum))).append('\n');
    }

    private long register(Object value, String path, String root, int depth) {
        long id = ++nextId;
        handles.put(Long.valueOf(id), new ObjectHandle(value, path, root, depth));
        while (handles.size() > MAX_HANDLES) handles.remove(handles.keySet().iterator().next());
        return id;
    }

    private long size(Object value) {
        try {
            return Math.max(0L, instrumentation.getObjectSize(value));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<Field>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) fields.add(field);
            }
        }
        return fields;
    }

    private static boolean terminal(Object value) {
        return value == null || scalar(value, 64) != null || terminalReference(value);
    }

    private static boolean terminalReference(Object value) {
        return value instanceof Class<?> || value instanceof ClassLoader || value instanceof Thread
                || value instanceof java.lang.reflect.Member || value instanceof java.lang.reflect.AccessibleObject;
    }

    private static boolean rootTypeAllowed(Class<?> type) {
        if (type.isArray() || type.isPrimitive() || platformType(type)) return false;
        String name = type.getName();
        return !name.startsWith("dev.whitedev.jpi.agent.") && !name.startsWith("dev.whitedev.jpi.protocol.");
    }

    private static boolean platformType(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("com.sun.");
    }

    private static String scalar(Object value, int maximum) {
        if (value == null) return "null";
        if (value instanceof String) {
            String text = (String) value;
            String selected = text.length() <= maximum ? text : text.substring(0, maximum) + "...";
            return limited("\"" + escaped(selected) + "\"", maximum + 5);
        }
        if (value instanceof Character) return "'" + escaped(String.valueOf(value)) + "'";
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof Float
                || value instanceof Double) return String.valueOf(value);
        if (value instanceof Enum<?>) return ((Enum<?>) value).name();
        return null;
    }

    private static String preview(Object value, int maximum) {
        if (value == null) return "null";
        String scalar = scalar(value, maximum);
        if (scalar != null) return scalar;
        Class<?> type = value.getClass();
        if (type.isArray()) return type.getComponentType().getTypeName() + "[" + Array.getLength(value) + "]";
        return type.getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String limited(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "...";
    }

    private static String classKey(Class<?> type) {
        return type.getName() + "@" + loaderLabel(type.getClassLoader());
    }

    private static String loaderLabel(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Node {
        final long id;
        final Object value;
        final String path;
        final String root;
        final int depth;

        Node(long id, Object value, String path, String root, int depth) {
            this.id = id;
            this.value = value;
            this.path = path;
            this.root = root;
            this.depth = depth;
        }
    }

    private static final class ObjectHandle {
        final WeakReference<Object> reference;
        final String path;
        final String root;
        final int depth;

        ObjectHandle(Object value, String path, String root, int depth) {
            reference = new WeakReference<Object>(value);
            this.path = path;
            this.root = root;
            this.depth = depth;
        }
    }

    private static final class Reference {
        final String path;
        final Object value;

        Reference(String path, Object value) {
            this.path = path;
            this.value = value;
        }
    }

    private static final class Inspection {
        final List<Reference> references = new ArrayList<Reference>();
        boolean valueMatch;
        boolean fieldMatch;
    }

    private static final class ClassCount {
        final String className;
        final String loader;
        long instances;
        long shallowBytes;

        ClassCount(Class<?> type) {
            className = type.getName();
            loader = loaderLabel(type.getClassLoader());
        }
    }
}
