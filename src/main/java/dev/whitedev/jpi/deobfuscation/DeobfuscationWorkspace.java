package dev.whitedev.jpi.deobfuscation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DeobfuscationWorkspace {
    private final Map<String, MappingEntry> entries = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public synchronized List<MappingEntry> entries() {
        List<MappingEntry> result = new ArrayList<>();
        for (MappingEntry entry : entries.values()) result.add(entry.copy());
        return result;
    }

    public synchronized void mergeInventory(MappingInventory inventory) {
        boolean changed = false;
        for (MappingEntry entry : inventory.entries()) {
            if (!entries.containsKey(entry.key())) {
                entries.put(entry.key(), entry.copy());
                changed = true;
            }
        }
        if (changed) changed();
    }

    public synchronized void replace(List<MappingEntry> values) {
        entries.clear();
        for (MappingEntry entry : values) entries.put(entry.key(), entry.copy());
        changed();
    }

    public synchronized void merge(List<MappingEntry> values) {
        for (MappingEntry entry : values) entries.put(entry.key(), entry.copy());
        changed();
    }

    public synchronized void update(MappingEntry value) {
        entries.put(value.key(), value.copy());
        changed();
    }

    public synchronized void remove(List<MappingEntry> values) {
        boolean changed = false;
        for (MappingEntry entry : values) changed |= entries.remove(entry.key()) != null;
        if (changed) changed();
    }

    public synchronized void clear() {
        if (entries.isEmpty()) return;
        entries.clear();
        changed();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public synchronized String packageAlias(String original) {
        MappingEntry entry = find(MappingKind.PACKAGE, "", original, "", -1);
        return mapped(entry) ? entry.mappedName() : original;
    }

    public synchronized String classAlias(String original) {
        MappingEntry direct = find(MappingKind.CLASS, "", original, "", -1);
        if (mapped(direct)) return direct.mappedName();
        int separator = original.lastIndexOf('.');
        if (separator < 0) return original;
        String packageName = original.substring(0, separator);
        MappingEntry mappedPackage = find(MappingKind.PACKAGE, "", packageName, "", -1);
        return mapped(mappedPackage) ? mappedPackage.mappedName() + original.substring(separator) : original;
    }

    public synchronized String classColor(String original) {
        MappingEntry entry = find(MappingKind.CLASS, "", original, "", -1);
        return entry == null || !entry.enabled() ? "" : entry.color();
    }

    public synchronized String methodColor(String owner, String name, String descriptor) {
        MappingEntry entry = find(MappingKind.METHOD, owner, name, descriptor, -1);
        return entry == null || !entry.enabled() ? "" : entry.color();
    }

    public synchronized String methodAlias(String owner, String name, String descriptor) {
        MappingEntry entry = find(MappingKind.METHOD, owner, name, descriptor, -1);
        return mapped(entry) ? entry.mappedName() : name;
    }

    public synchronized String fieldAlias(String owner, String name, String descriptor) {
        MappingEntry entry = find(MappingKind.FIELD, owner, name, descriptor, -1);
        return mapped(entry) ? entry.mappedName() : name;
    }

    public synchronized String fieldAlias(String owner, String name) {
        String alias = null;
        for (MappingEntry entry : entries.values()) {
            if (entry.kind() != MappingKind.FIELD || !entry.owner().equals(owner)
                    || !entry.originalName().equals(name) || !mapped(entry)) continue;
            if (alias != null && !alias.equals(entry.mappedName())) return name;
            alias = entry.mappedName();
        }
        return alias == null ? name : alias;
    }

    public synchronized void autoMap(MappingInventory inventory, boolean mapClasses, boolean mapMethods,
                                     boolean mapFields, boolean mapParameters, boolean mapPackages) {
        for (MappingEntry entry : inventory.entries()) {
            if (!entries.containsKey(entry.key())) entries.put(entry.key(), entry.copy());
        }
        Set<String> aliases = new HashSet<>();
        for (MappingEntry entry : entries.values()) if (!entry.mappedName().isEmpty()) aliases.add(entry.mappedName());
        int classIndex = 1;
        int methodIndex = 1;
        int fieldIndex = 1;
        int packageIndex = 1;
        Set<String> packages = new HashSet<>();
        for (MappingEntry inventoryEntry : inventory.entries()) {
            MappingEntry entry = entries.get(inventoryEntry.key());
            if (entry == null) continue;
            if (entry.kind() == MappingKind.CLASS) {
                int separator = entry.originalName().lastIndexOf('.');
                if (separator > 0) packages.add(entry.originalName().substring(0, separator));
                if (mapClasses && entry.mappedName().isEmpty()) {
                    String alias;
                    do { alias = "class_" + classIndex++; } while (aliases.contains(alias));
                    entry.setMappedName(alias);
                    aliases.add(alias);
                }
            } else if (entry.kind() == MappingKind.METHOD && mapMethods
                    && entry.mappedName().isEmpty() && !entry.originalName().startsWith("<")) {
                String alias;
                do { alias = "method_" + methodIndex++; } while (aliases.contains(alias));
                entry.setMappedName(alias);
                aliases.add(alias);
            } else if (entry.kind() == MappingKind.FIELD && mapFields && entry.mappedName().isEmpty()) {
                String alias;
                do { alias = "field_" + fieldIndex++; } while (aliases.contains(alias));
                entry.setMappedName(alias);
                aliases.add(alias);
            } else if (entry.kind() == MappingKind.PARAMETER && mapParameters && entry.mappedName().isEmpty()) {
                entry.setMappedName("param_" + (entry.parameterIndex() + 1));
            }
        }
        if (mapPackages) {
            List<String> ordered = new ArrayList<>(packages);
            Collections.sort(ordered);
            for (String packageName : ordered) {
                MappingEntry entry = find(MappingKind.PACKAGE, "", packageName, "", -1);
                if (entry == null) {
                    entry = new MappingEntry(MappingKind.PACKAGE, "", packageName, "", -1, 0);
                    entries.put(entry.key(), entry);
                }
                if (entry.mappedName().isEmpty()) {
                    String alias;
                    do { alias = "package_" + packageIndex++; } while (aliases.contains(alias));
                    entry.setMappedName(alias);
                    aliases.add(alias);
                }
            }
        }
        changed();
    }

    public synchronized TranslationResult translateSource(String source) {
        Map<String, String> resolved = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (MappingEntry entry : entries.values()) {
            if (!mapped(entry) || entry.kind() == MappingKind.PARAMETER) continue;
            String alias = entry.mappedName();
            if (!validAlias(alias)) continue;
            String original = entry.kind() == MappingKind.CLASS || entry.kind() == MappingKind.PACKAGE
                    ? entry.originalName() : entry.originalName();
            String previous = resolved.put(alias, original);
            if (previous != null && !previous.equals(original)) ambiguous.add(alias);
        }
        for (String alias : ambiguous) resolved.remove(alias);
        List<Map.Entry<String, String>> replacements = new ArrayList<>(resolved.entrySet());
        replacements.sort(Comparator.comparingInt((Map.Entry<String, String> value) -> value.getKey().length()).reversed());
        StringBuilder output = new StringBuilder(source.length() + 64);
        int count = 0;
        int index = 0;
        int state = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : 0;
            if (state == 1) {
                output.append(current);
                if (current == '\\' && next != 0) {
                    output.append(next);
                    index += 2;
                    continue;
                }
                if (current == '"') state = 0;
                index++;
                continue;
            }
            if (state == 2) {
                output.append(current);
                if (current == '\\' && next != 0) {
                    output.append(next);
                    index += 2;
                    continue;
                }
                if (current == '\'') state = 0;
                index++;
                continue;
            }
            if (state == 3) {
                output.append(current);
                if (current == '\n') state = 0;
                index++;
                continue;
            }
            if (state == 4) {
                output.append(current);
                if (current == '*' && next == '/') {
                    output.append(next);
                    index += 2;
                    state = 0;
                } else index++;
                continue;
            }
            if (current == '"') {
                state = 1;
                output.append(current);
                index++;
                continue;
            }
            if (current == '\'') {
                state = 2;
                output.append(current);
                index++;
                continue;
            }
            if (current == '/' && next == '/') {
                state = 3;
                output.append(current).append(next);
                index += 2;
                continue;
            }
            if (current == '/' && next == '*') {
                state = 4;
                output.append(current).append(next);
                index += 2;
                continue;
            }
            Map.Entry<String, String> match = match(source, index, replacements);
            if (match == null) {
                output.append(current);
                index++;
            } else {
                output.append(match.getValue());
                index += match.getKey().length();
                count++;
            }
        }
        return new TranslationResult(output.toString(), count, new ArrayList<>(ambiguous));
    }

    private MappingEntry find(MappingKind kind, String owner, String name, String descriptor, int index) {
        String key = kind.name() + '\u0000' + owner + '\u0000' + name + '\u0000' + descriptor + '\u0000' + index;
        return entries.get(key);
    }

    private static boolean mapped(MappingEntry entry) {
        return entry != null && entry.enabled() && !entry.mappedName().isEmpty();
    }

    private static boolean validAlias(String alias) {
        if (alias.isEmpty()) return false;
        String[] parts = alias.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) return false;
            for (int index = 1; index < part.length(); index++) {
                if (!Character.isJavaIdentifierPart(part.charAt(index))) return false;
            }
        }
        return true;
    }

    private static Map.Entry<String, String> match(String source, int offset,
                                                    List<Map.Entry<String, String>> replacements) {
        for (Map.Entry<String, String> replacement : replacements) {
            String alias = replacement.getKey();
            if (!source.regionMatches(offset, alias, 0, alias.length())) continue;
            int before = offset - 1;
            int after = offset + alias.length();
            if (before >= 0 && Character.isJavaIdentifierPart(source.charAt(before))) continue;
            if (after < source.length() && Character.isJavaIdentifierPart(source.charAt(after))) continue;
            return replacement;
        }
        return null;
    }

    private void changed() {
        for (Runnable listener : listeners) listener.run();
    }

    public static final class TranslationResult {
        private final String source;
        private final int replacements;
        private final List<String> ambiguousAliases;

        public TranslationResult(String source, int replacements, List<String> ambiguousAliases) {
            this.source = source;
            this.replacements = replacements;
            this.ambiguousAliases = ambiguousAliases;
        }

        public String source() { return source; }
        public int replacements() { return replacements; }
        public List<String> ambiguousAliases() { return Collections.unmodifiableList(ambiguousAliases); }
    }
}