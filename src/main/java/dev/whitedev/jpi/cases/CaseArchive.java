package dev.whitedev.jpi.cases;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.whitedev.jpi.cases.InvestigationCase.Bookmark;
import dev.whitedev.jpi.cases.InvestigationCase.Definition;
import dev.whitedev.jpi.cases.InvestigationCase.Evidence;
import dev.whitedev.jpi.cases.InvestigationCase.Fingerprint;
import dev.whitedev.jpi.cases.InvestigationCase.Manifest;
import dev.whitedev.jpi.cases.InvestigationCase.Trace;
import dev.whitedev.jpi.deobfuscation.MappingEntry;
import dev.whitedev.jpi.evidence.EvidenceGraphBuilder;
import dev.whitedev.jpi.evidence.EvidenceStore.Graph;
import dev.whitedev.jpi.exceptions.ExceptionSnapshot;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class CaseArchive {
    public static final int VERSION = 2;
    public static final int CLASS_BYTES = 4 * 1024 * 1024;
    public static final int BYTECODE_BUDGET = 48 * 1024 * 1024;
    public static final int MAX_SAVED_CLASSES = 512;
    public static final int MAX_CATALOG = 25_000;
    public static final int MAX_EVENTS = 6_000;
    public static final int MAX_ANNOTATIONS = 64;
    private static final int SECTION_BYTES = 8 * 1024 * 1024;
    private static final int ARCHIVE_BYTES = 96 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).disableHtmlEscaping().create();
    private static final String EVIDENCE_GRAPH = "analysis/evidence-graph.json";
    private static final Set<String> SECTIONS = Set.of("manifest.json", "target/fingerprint.json", "classes/catalog.json",
            "runtime/timeline.jsonl", "runtime/traces.jsonl", "runtime/exceptions.json", "analysis/callgraph.json",
            "analysis/evidence.json", EVIDENCE_GRAPH, "workspace/mappings.json", "workspace/notes.json", "workspace/bookmarks.json");

    private CaseArchive() {
    }

    static int jsonLength(Object value) {
        return JSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;
    }

    public static void save(Path path, InvestigationCase data) throws IOException {
        Path destination = path.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(destination.getParent(), ".jpi-case-", ".tmp");
        try {
            try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(temporary))) {
                Writer writer = new Writer(stream);
                Manifest manifest = data.manifest();
                writer.section("manifest.json", new Manifest(VERSION, manifest.title(), manifest.startedAt(), manifest.endedAt(),
                        manifest.runtimeDetails(), manifest.notices()));
                writer.section("target/fingerprint.json", data.fingerprint());
                writer.section("classes/catalog.json", data.catalog());
                writer.lines("runtime/timeline.jsonl", data.timeline());
                writer.lines("runtime/traces.jsonl", data.traces());
                writer.section("runtime/exceptions.json", data.exceptions());
                writer.section("analysis/callgraph.json", data.callGraph());
                writer.section("analysis/evidence.json", data.evidence());
                writer.section(EVIDENCE_GRAPH, data.evidenceGraph() == null ? EvidenceGraphBuilder.saved(data) : data.evidenceGraph());
                writer.section("workspace/mappings.json", data.mappings());
                writer.section("workspace/notes.json", data.notes());
                writer.section("workspace/bookmarks.json", data.bookmarks());
                int saved = 0;
                int bytecodeBytes = 0;
                for (int index = 0; index < data.catalog().size(); index++) {
                    Definition definition = data.catalog().get(index);
                    byte[] bytes = data.bytecode().get(definition.id());
                    if (bytes == null) continue;
                    if (++saved > MAX_SAVED_CLASSES || (bytecodeBytes += bytes.length) > BYTECODE_BUDGET) {
                        throw new IOException("Saved class budget exceeded");
                    }
                    validateClass(definition, bytes);
                    writer.entry(classPath(index), bytes, CLASS_BYTES);
                }
                if (saved != data.bytecode().size()) throw new IOException("Class bytecode is missing a catalog entry");
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static InvestigationCase open(Path path) throws IOException {
        if (Files.size(path) > ARCHIVE_BYTES + 1024 * 1024) throw new IOException("Case file is too large");
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (zip.size() > SECTIONS.size() + MAX_SAVED_CLASSES) throw new IOException("Too many case entries");
            Reader reader = new Reader(zip);
            Manifest manifest = reader.section("manifest.json", Manifest.class);
            if (manifest == null || manifest.version() < 1 || manifest.version() > VERSION)
                throw new IOException("Unsupported case version");
            if (manifest.title() == null || manifest.endedAt() < manifest.startedAt()) {
                throw new IOException("Invalid case manifest");
            }
            List<Definition> catalog = reader.list("classes/catalog.json", Definition.class, MAX_CATALOG);
            Set<String> ids = new HashSet<>();
            Map<String, byte[]> classes = new LinkedHashMap<>();
            int bytecodeBytes = 0;
            for (int index = 0; index < catalog.size(); index++) {
                Definition definition = catalog.get(index);
                if (definition.id() == null || definition.name() == null || definition.loader() == null
                        || !ids.add(definition.id())) throw new IOException("Invalid or duplicate catalog definition");
                String entry = classPath(index);
                if (!reader.entries.containsKey(entry)) continue;
                byte[] bytes = reader.read(entry, CLASS_BYTES);
                if ((bytecodeBytes += bytes.length) > BYTECODE_BUDGET)
                    throw new IOException("Saved class budget exceeded");
                validateClass(definition, bytes);
                classes.put(definition.id(), bytes);
            }
            InvestigationCase data = new InvestigationCase(manifest, reader.section("target/fingerprint.json", Fingerprint.class),
                    catalog, classes, reader.lines("runtime/timeline.jsonl", TimelineEvent.class),
                    reader.lines("runtime/traces.jsonl", Trace.class),
                    reader.section("runtime/exceptions.json", ExceptionSnapshot.class),
                    reader.section("analysis/callgraph.json", String.class),
                    reader.list("workspace/mappings.json", MappingEntry.class, 1500),
                    reader.section("workspace/notes.json", String.class),
                    reader.list("workspace/bookmarks.json", Bookmark.class, MAX_ANNOTATIONS),
                    reader.list("analysis/evidence.json", Evidence.class, MAX_ANNOTATIONS),
                    manifest.version() == 1 ? null : reader.section(EVIDENCE_GRAPH, Graph.class));
            if (!reader.entries.isEmpty() || data.notes() == null || data.notes().length() > 16_000 || data.callGraph() == null) {
                throw new IOException("Unexpected or incomplete case contents");
            }
            validateReports(data);
            if (manifest.version() == VERSION && data.evidenceGraph() == null)
                throw new IOException("Missing evidence graph");
            return data;
        } catch (RuntimeException error) {
            throw new IOException("Invalid case contents", error);
        }
    }

    private static void validateReports(InvestigationCase data) throws IOException {
        for (MappingEntry entry : data.mappings()) {
            if (entry.kind() == null || entry.owner() == null || entry.originalName() == null
                    || entry.descriptor() == null || entry.mappedName() == null || entry.comment() == null
                    || entry.tags() == null || entry.color() == null) throw new IOException("Invalid saved mapping");
        }
        if (data.exceptions() == null) return;
        var snapshot = data.exceptions();
        if (snapshot.entries() == null || snapshot.entries().size() > 256)
            throw new IOException("Invalid exception snapshot");
        for (var entry : snapshot.entries()) {
            if (entry == null || entry.type() == null || entry.locations() == null || entry.locations().size() > 12) {
                throw new IOException("Invalid exception entry");
            }
            for (var location : entry.locations()) {
                if (location == null || location.owner() == null || location.method() == null
                        || location.descriptor() == null || location.stack() == null) {
                    throw new IOException("Invalid exception location");
                }
            }
        }
    }

    static void validateClass(Definition definition, byte[] bytes) throws IOException {
        if (bytes.length < 16 || bytes.length > CLASS_BYTES)
            throw new IOException("Class exceeds the saved bytecode limits");
        try {
            String name = new ClassReader(bytes).getClassName().replace('/', '.');
            if (!name.equals(definition.name())) throw new IOException("Bytecode does not match " + definition.name());
        } catch (RuntimeException error) {
            throw new IOException("Could not read class bytes for " + definition.name(), error);
        }
    }

    private static String classPath(int index) {
        return "classes/bytecode/" + index + ".class";
    }

    private static final class Writer {
        private final ZipOutputStream zip;
        private long total;

        private Writer(ZipOutputStream zip) {
            this.zip = zip;
        }

        void section(String name, Object value) throws IOException {
            entry(name, JSON.toJson(value).getBytes(StandardCharsets.UTF_8), SECTION_BYTES);
        }

        void lines(String name, List<?> values) throws IOException {
            if (values.size() > MAX_EVENTS) throw new IOException("Too many records in " + name);
            zip.putNextEntry(new ZipEntry(name));
            int size = 0;
            for (Object value : values) {
                byte[] bytes = (JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8);
                size += bytes.length;
                check(name, size, SECTION_BYTES, bytes.length);
                zip.write(bytes);
            }
            zip.closeEntry();
        }

        void entry(String name, byte[] bytes, int limit) throws IOException {
            check(name, bytes.length, limit, bytes.length);
            zip.putNextEntry(new ZipEntry(name));
            zip.write(bytes);
            zip.closeEntry();
        }

        private void check(String name, int size, int limit, int added) throws IOException {
            if (size > limit || (total += added) > ARCHIVE_BYTES)
                throw new IOException("Case size limit exceeded at " + name);
        }
    }

    private static final class Reader {
        private final ZipFile zip;
        private final Map<String, ZipEntry> entries = new HashMap<>();
        private long declared;

        Reader(ZipFile zip) throws IOException {
            this.zip = zip;
            var all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                String name = entry.getName();
                boolean bytecode = name.matches("classes/bytecode/(0|[1-9][0-9]{0,4})\\.class");
                if ((!SECTIONS.contains(name) && !bytecode) || entry.isDirectory() || entry.getSize() < 0
                        || entry.getSize() > (bytecode ? CLASS_BYTES : SECTION_BYTES)
                        || (declared += entry.getSize()) > ARCHIVE_BYTES || entries.putIfAbsent(name, entry) != null) {
                    throw new IOException("Invalid, duplicate or oversized case entry");
                }
            }
        }

        byte[] read(String name, int limit) throws IOException {
            ZipEntry entry = entries.remove(name);
            if (entry == null) throw new IOException("Missing case entry: " + name);
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(Math.min(limit, (int) entry.getSize()) + 1);
                CRC32 crc = new CRC32();
                crc.update(bytes);
                if (bytes.length != entry.getSize() || crc.getValue() != entry.getCrc()) {
                    throw new IOException("Corrupt case entry: " + name);
                }
                return bytes;
            }
        }

        <T> T section(String name, Class<T> type) throws IOException {
            return JSON.fromJson(new String(read(name, SECTION_BYTES), StandardCharsets.UTF_8), type);
        }

        <T> List<T> list(String name, Class<T> type, int limit) throws IOException {
            List<T> values = new ArrayList<>();
            try (JsonReader json = JSON.newJsonReader(new StringReader(new String(read(name, SECTION_BYTES), StandardCharsets.UTF_8)))) {
                json.beginArray();
                while (json.hasNext()) {
                    if (values.size() == limit) throw new IOException("Too many records in " + name);
                    values.add(JSON.fromJson(json, type));
                }
                json.endArray();
                if (json.peek() != JsonToken.END_DOCUMENT) throw new IOException("Invalid list in " + name);
                return List.copyOf(values);
            }
        }

        <T> List<T> lines(String name, Class<T> type) throws IOException {
            List<T> values = new ArrayList<>();
            try (var lines = new String(read(name, SECTION_BYTES), StandardCharsets.UTF_8).lines()) {
                var iterator = lines.iterator();
                while (iterator.hasNext()) {
                    if (values.size() >= MAX_EVENTS) throw new IOException("Too many records in " + name);
                    values.add(JSON.fromJson(iterator.next(), type));
                }
            }
            return List.copyOf(values);
        }
    }
}
