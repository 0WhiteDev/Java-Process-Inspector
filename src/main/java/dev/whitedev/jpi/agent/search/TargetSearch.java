package dev.whitedev.jpi.agent.search;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class TargetSearch {
    public interface Source {
        String identifier(Class<?> type);
        String loader(Class<?> type);
        byte[] bytes(Class<?> type);
    }

    private static final int RESULT_LIMIT = 200;
    private static final int CLASS_LIMIT = 100000;
    private static final int BYTECODE_CLASS_LIMIT = 5000;
    private static final long BYTE_BUDGET = 64L * 1024 * 1024;
    private final String[] terms;
    private final StringBuilder rows = new StringBuilder();
    private final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
    private final Set<String> seen = new HashSet<String>();
    private final long deadline = System.nanoTime() + 5_000_000_000L;
    private int inspected;
    private int unavailable;
    private long bytes;

    private TargetSearch(String query) {
        terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        for (String kind : new String[]{"CLASSES", "METHODS", "FIELDS", "STRINGS"}) counts.put(kind, 0);
    }

    public static String search(Class<?>[] loaded, String query, Source source) throws IOException {
        String value = query == null ? "" : query.trim();
        if (value.length() < 2 || value.length() > 200 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IOException("Search Everything requires 2 to 200 characters without line breaks");
        return new TargetSearch(value).run(loaded, source);
    }

    private String run(Class<?>[] loaded, final Source source) {
        Class<?>[] catalog = Arrays.copyOf(loaded, Math.min(CLASS_LIMIT, loaded.length));
        Arrays.sort(catalog, new Comparator<Class<?>>() {
            @Override public int compare(Class<?> left, Class<?> right) {
                int matched = Boolean.compare(matches(right.getName()), matches(left.getName()));
                if (matched != 0) return matched;
                int application = Boolean.compare(left.getClassLoader() == null, right.getClassLoader() == null);
                return application != 0 ? application : left.getName().compareTo(right.getName());
            }
        });
        boolean partial = false;
        for (Class<?> type : catalog) {
            String owner = type.getName();
            if (type.isArray() || type.isPrimitive()) continue;
            String id = source.identifier(type);
            String loader = source.loader(type);
            if (matches(owner)) hit("CLASSES", id, owner, "", "", loader, owner);
            if (inspected >= BYTECODE_CLASS_LIMIT || bytes >= BYTE_BUDGET || System.nanoTime() >= deadline) {
                partial = true;
                continue;
            }
            inspected++;
            byte[] code = source.bytes(type);
            if (code == null || code.length > 4 * 1024 * 1024) { unavailable++; continue; }
            if (bytes + code.length > BYTE_BUDGET) { partial = true; continue; }
            bytes += code.length;
            scan(code, id, owner, loader);
        }
        StringBuilder out = new StringBuilder("S\t1\t").append(catalog.length).append('\t').append(inspected)
                .append('\t').append(unavailable).append('\t').append(bytes).append('\t')
                .append(loaded.length > catalog.length).append('\t').append(partial || unavailable > 0).append('\n');
        for (Map.Entry<String, Integer> entry : counts.entrySet()) out.append("C\t").append(entry.getKey())
                .append('\t').append(entry.getValue()).append('\t').append(entry.getValue() > RESULT_LIMIT).append('\n');
        return out.append(rows).toString();
    }

    private void scan(byte[] bytes, String id, String owner, String loader) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (FieldNode field : node.fields) if (matches(owner + "." + field.name + " " + field.desc))
                hit("FIELDS", id, owner, field.name, field.desc, loader, field.name);
            for (MethodNode method : node.methods) {
                if (matches(owner + "." + method.name + " " + method.desc))
                    hit("METHODS", id, owner, method.name, method.desc, loader, method.name);
                for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof LdcInsnNode) {
                    Object constant = ((LdcInsnNode) instruction).cst;
                    if (constant instanceof String && matches((String) constant))
                        hit("STRINGS", id, owner, method.name, method.desc, loader, (String) constant);
                }
            }
        } catch (RuntimeException error) { unavailable++; }
    }

    private boolean matches(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        for (String term : terms) if (!value.contains(term)) return false;
        return true;
    }

    private void hit(String kind, String id, String owner, String member, String descriptor, String loader, String value) {
        int count = counts.get(kind);
        if (count >= RESULT_LIMIT) { counts.put(kind, count + 1); return; }
        String bounded = value.length() > 4096 ? value.substring(0, 4093) + "..." : value;
        String key = kind + '\0' + id + '\0' + member + '\0' + descriptor + '\0' + bounded;
        if (!seen.add(key)) return;
        counts.put(kind, count + 1);
        rows.append("R\t").append(kind).append('\t').append(encoded(id)).append('\t').append(encoded(owner))
                .append('\t').append(encoded(member)).append('\t').append(encoded(descriptor))
                .append('\t').append(encoded(loader)).append('\t').append(encoded(bounded)).append('\n');
    }

    private static String encoded(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
}
