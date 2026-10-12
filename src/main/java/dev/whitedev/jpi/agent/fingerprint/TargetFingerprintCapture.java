package dev.whitedev.jpi.agent.fingerprint;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LineNumberNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class TargetFingerprintCapture {
    public interface BytecodeSource { byte[] read(Class<?> type) throws Exception; }

    private TargetFingerprintCapture() {}

    public static String capture(Class<?>[] loaded, String selection, BytecodeSource source) throws Exception {
        if (selection.length() > 32768) throw new IOException("Fingerprint selection is too large");
        Set<String> requested = new HashSet<>();
        for (String name : selection.split("[\\n,]")) if (!name.trim().isEmpty()) requested.add(name.trim());
        if (requested.size() > 128) throw new IOException("Select at most 128 classes for bytecode hashing");
        String command = System.getProperty("sun.java.command", "").trim();
        String main = command.isEmpty() ? "" : command.split("\\s+", 2)[0];
        if (requested.isEmpty() && !main.isEmpty()) requested.add(main);
        Class<?>[] catalog = Arrays.copyOf(loaded, Math.min(loaded.length, 50000));
        Map<Class<?>, String> keys = new IdentityHashMap<>();
        for (Class<?> type : catalog) keys.put(type, key(type));
        Arrays.sort(catalog, (left, right) -> keys.get(left).compareTo(keys.get(right)));
        StringBuilder out = new StringBuilder("H\t1\t").append(System.currentTimeMillis()).append('\t')
                .append(encoded(main)).append('\t').append(encoded(System.getProperty("java.version", "")))
                .append('\t').append(loaded.length).append('\n');
        int inspected = 0;
        long bytes = 0;
        if (loaded.length > catalog.length) out.append("T\n");
        for (int index = 0; index < catalog.length; index++) {
            if (out.length() > 6 * 1024 * 1024) {
                out.append("T\n");
                break;
            }
            Class<?> type = catalog[index];
            if (type.isArray() || type.isPrimitive()) continue;
            String key = keys.get(type);
            String hash = "";
            String status = "not selected";
            StringBuilder members = new StringBuilder();
            if (requested.contains(type.getName()) && inspected < 128) {
                inspected++;
                try {
                    byte[] code = source.read(type);
                    if (code.length > 4 * 1024 * 1024 || bytes + code.length > 32L * 1024 * 1024) {
                        status = "bytecode budget exceeded";
                    } else {
                        bytes += code.length;
                        hash = hash(code);
                        ClassNode node = new ClassNode();
                        new ClassReader(code).accept(node, ClassReader.SKIP_FRAMES);
                        for (FieldNode field : node.fields) member(members, "F", key, field.name, field.desc, "");
                        for (MethodNode method : node.methods) {
                            Set<Integer> lines = new TreeSet<>();
                            for (AbstractInsnNode instruction : method.instructions) {
                                if (instruction instanceof LineNumberNode) lines.add(((LineNumberNode) instruction).line);
                            }
                            StringBuilder lineList = new StringBuilder();
                            for (Integer line : lines) {
                                if (lineList.length() > 0) lineList.append(',');
                                lineList.append(line);
                            }
                            member(members, "M", key, method.name, method.desc, lineList.toString());
                        }
                        status = "hashed";
                    }
                } catch (Exception error) {
                    hash = "";
                    members.setLength(0);
                    status = "unavailable: " + error.getClass().getSimpleName();
                }
            } else if (requested.contains(type.getName())) status = "definition limit reached";
            if (out.length() + members.length() > 7 * 1024 * 1024) {
                members.setLength(0);
                hash = "";
                status = "metadata budget exceeded";
            }
            out.append("C\t").append(encoded(key)).append('\t').append(encoded(type.getName())).append('\t')
                    .append(encoded(loader(type.getClassLoader()))).append('\t').append(hash).append('\t')
                    .append(encoded(status)).append('\n').append(members);
        }
        return out.toString();
    }

    private static void member(StringBuilder out, String kind, String key, String name, String descriptor, String lines) throws IOException {
        out.append(kind).append('\t').append(encoded(key)).append('\t').append(encoded(name)).append('\t')
                .append(encoded(descriptor)).append('\t').append(lines).append('\n');
        if (out.length() > 1024 * 1024) throw new IOException("Class member metadata exceeds 1 MiB");
    }

    private static String key(Class<?> type) {
        return type.getName().replaceAll("/0x[0-9a-fA-F]+", "/hidden") + "|" + loader(type.getClassLoader());
    }

    private static String loader(ClassLoader loader) {
        StringBuilder value = new StringBuilder();
        int depth = 0;
        while (loader != null && depth++ < 32) {
            if (value.length() > 0) value.append('/');
            value.append(loader.getClass().getName());
            loader = loader.getParent();
        }
        return value.append("/bootstrap").toString();
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
}
