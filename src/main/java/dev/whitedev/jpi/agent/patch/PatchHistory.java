package dev.whitedev.jpi.agent.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Base64;

public final class PatchHistory {
    private final ArrayDeque<Entry> entries = new ArrayDeque<Entry>();
    private final int maxEntries;
    private final long maxBytes;
    private long storedBytes;
    private long sequence;

    public PatchHistory() {
        this(100, 64L * 1024 * 1024);
    }

    public PatchHistory(int maxEntries, long maxBytes) {
        if (maxEntries < 1 || maxBytes < 1) throw new IllegalArgumentException("History limits must be positive");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    public synchronized void record(String identifier, String name, String description, byte[] before, byte[] after) {
        long size = (long) before.length + after.length;
        if (size > maxBytes) return;
        Entry entry = new Entry(++sequence, System.currentTimeMillis(), identifier, name, description, before, after);
        entries.addLast(entry);
        storedBytes += size;
        while (entries.size() > maxEntries || storedBytes > maxBytes) {
            Entry removed = entries.removeFirst();
            storedBytes -= (long) removed.before.length + removed.after.length;
        }
    }

    public synchronized byte[] version(String identifier, long id, boolean before) throws IOException {
        for (Entry entry : entries) {
            if (entry.id == id && entry.identifier.equals(identifier)) {
                return (before ? entry.before : entry.after).clone();
            }
        }
        throw new IOException("Patch #" + id + " is unavailable for this class definition or has been evicted");
    }

    public synchronized String list(String identifier) {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (!identifier.isEmpty() && !entry.identifier.equals(identifier)) continue;
            out.append(entry.id).append('\t').append(entry.timestamp).append('\t')
                    .append(encoded(entry.identifier)).append('\t').append(encoded(entry.name)).append('\t')
                    .append(encoded(entry.description)).append('\t').append(entry.before.length).append('\t')
                    .append(entry.after.length).append('\t').append(sha256(entry.before)).append('\t')
                    .append(sha256(entry.after)).append('\n');
        }
        return out.toString();
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte value : digest) out.append(String.format("%02x", value & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Entry {
        final long id;
        final long timestamp;
        final String identifier;
        final String name;
        final String description;
        final byte[] before;
        final byte[] after;

        Entry(long id, long timestamp, String identifier, String name, String description, byte[] before, byte[] after) {
            this.id = id;
            this.timestamp = timestamp;
            this.identifier = identifier;
            this.name = name;
            this.description = description;
            this.before = before.clone();
            this.after = after.clone();
        }
    }
}
