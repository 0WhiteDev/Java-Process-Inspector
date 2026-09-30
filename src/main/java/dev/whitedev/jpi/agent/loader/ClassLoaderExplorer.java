package dev.whitedev.jpi.agent.loader;

import java.lang.instrument.Instrumentation;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ClassLoaderExplorer {
    private final ReferenceQueue<ClassLoader> queue = new ReferenceQueue<ClassLoader>();
    private final Map<LoaderKey, Entry> identities = new HashMap<LoaderKey, Entry>();
    private final Map<String, Entry> history = new LinkedHashMap<String, Entry>();
    private long sequence;

    public synchronized String snapshot(Class<?>[] classes, Instrumentation instrumentation) {
        long now = System.currentTimeMillis();
        collect(now);
        for (Entry entry : history.values()) {
            entry.count = 0;
            if (entry.key.get() != null) entry.sources.clear();
        }
        Map<String, Integer> counts = new HashMap<String, Integer>();
        StringBuilder definitions = new StringBuilder();
        for (Class<?> type : classes) {
            ClassLoader loader = type.getClassLoader();
            String id = loader == null ? "l:0" : register(loader, now).id;
            Integer count = counts.get(id);
            counts.put(id, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
            String source = source(type);
            if (loader != null && !source.isEmpty()) history.get(id).sources.add("Code source: " + source);
            definitions.append("C\t").append(encoded("c:" + Integer.toHexString(System.identityHashCode(type))))
                    .append('\t').append(encoded(type.getName())).append('\t').append(encoded(id))
                    .append('\t').append(encoded(source)).append('\t').append(type.isArray() ? "array" : type.isPrimitive() ? "primitive" : "definition")
                    .append('\n');
        }
        StringBuilder output = new StringBuilder("S\t" + now + "\t" + classes.length + "\n");
        appendLoader(output, "l:0", "", "", "Bootstrap", "active", 0, now,
                counts.containsKey("l:0") ? counts.get("l:0").intValue() : 0, -1L, "JVM bootstrap loader");
        for (Entry entry : history.values()) {
            ClassLoader loader = entry.key.get();
            if (loader != null) {
                entry.lastSeen = now;
                entry.count = counts.containsKey(entry.id) ? counts.get(entry.id).intValue() : 0;
                urls(loader, entry);
            } else if (entry.collectedAt == 0) {
                entry.collectedAt = now;
            }
            long size = -1L;
            if (loader != null && instrumentation != null) {
                try { size = instrumentation.getObjectSize(loader); } catch (RuntimeException ignored) {}
            }
            appendLoader(output, entry.id, entry.parent, entry.type, entry.label,
                    loader == null ? "collected" : "active", entry.firstSeen,
                    loader == null ? entry.collectedAt : entry.lastSeen, entry.count, size, entry.notice);
            for (String location : entry.sources) output.append("U\t").append(encoded(entry.id)).append('\t')
                    .append(encoded(location)).append('\n');
        }
        return output.append(definitions).toString();
    }

    private Entry register(ClassLoader loader, long now) {
        Entry known = identities.get(new LoaderKey(loader, null));
        if (known != null) return known;
        String id = "l:" + (++sequence);
        LoaderKey key = new LoaderKey(loader, queue);
        Entry entry = new Entry(id, key, loader.getClass().getName(), now);
        identities.put(key, entry);
        history.put(id, entry);
        try {
            ClassLoader parent = loader.getParent();
            entry.parent = parent == null ? "l:0" : register(parent, now).id;
        } catch (SecurityException error) {
            entry.parent = "";
            entry.notice = "Parent access denied";
        }
        return entry;
    }

    private void collect(long now) {
        LoaderKey key;
        while ((key = (LoaderKey) queue.poll()) != null) {
            Entry entry = identities.remove(key);
            if (entry != null) entry.collectedAt = now;
        }
        if (history.size() > 10000) {
            java.util.Iterator<Entry> entries = history.values().iterator();
            while (history.size() > 10000 && entries.hasNext()) {
                Entry entry = entries.next();
                if (entry.key.get() == null) entries.remove();
            }
        }
    }

    private static void urls(ClassLoader loader, Entry entry) {
        if (!(loader instanceof URLClassLoader)) {
            entry.notice = entry.notice.isEmpty() ? "URLs inferred from loaded-class code sources" : entry.notice;
            return;
        }
        try {
            if (loader.getClass().getMethod("getURLs").getDeclaringClass() != URLClassLoader.class) {
                entry.notice = "Custom getURLs override skipped; showing observed code sources";
                return;
            }
            for (URL url : ((URLClassLoader) loader).getURLs()) entry.sources.add("Loader URL: " + url.toExternalForm());
        } catch (ReflectiveOperationException error) {
            entry.notice = "Loader URLs unavailable";
        } catch (SecurityException error) {
            entry.notice = "Loader URL access denied";
        }
    }

    private static String source(Class<?> type) {
        try {
            java.security.ProtectionDomain domain = type.getProtectionDomain();
            java.security.CodeSource source = domain == null ? null : domain.getCodeSource();
            return source == null || source.getLocation() == null ? "" : source.getLocation().toExternalForm();
        } catch (SecurityException error) {
            return "";
        }
    }

    private static void appendLoader(StringBuilder output, String id, String parent, String type, String label,
                                     String status, long first, long last, int classes, long size, String notice) {
        output.append("L\t").append(encoded(id)).append('\t').append(encoded(parent)).append('\t')
                .append(encoded(type)).append('\t').append(encoded(label)).append('\t').append(status)
                .append('\t').append(first).append('\t').append(last).append('\t').append(classes)
                .append('\t').append(size).append('\t').append(encoded(notice)).append('\n');
    }

    private static String encoded(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final class LoaderKey extends WeakReference<ClassLoader> {
        final int hash;
        LoaderKey(ClassLoader loader, ReferenceQueue<ClassLoader> queue) {
            super(loader, queue);
            hash = System.identityHashCode(loader);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            return other instanceof LoaderKey && get() != null && get() == ((LoaderKey) other).get();
        }
    }

    private static final class Entry {
        final String id;
        final LoaderKey key;
        final String type;
        final String label;
        final long firstSeen;
        final Set<String> sources = new LinkedHashSet<String>();
        String parent = "";
        String notice = "";
        long lastSeen;
        long collectedAt;
        int count;
        Entry(String id, LoaderKey key, String type, long now) {
            this.id = id;
            this.key = key;
            this.type = type;
            this.label = type + "@" + Integer.toHexString(key.hash) + " [" + id + "]";
            this.firstSeen = now;
            this.lastSeen = now;
        }
    }
}
