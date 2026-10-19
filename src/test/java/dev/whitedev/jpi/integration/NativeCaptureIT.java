package dev.whitedev.jpi.integration;

import dev.whitedev.jpi.attach.*;
import dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact;
import dev.whitedev.jpi.protocol.Operation;
import org.junit.jupiter.api.*;
import org.objectweb.asm.ClassReader;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Files;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.junit.jupiter.api.io.TempDir;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class NativeCaptureIT {
    @TempDir Path archiveParent;
    private final AttachService service = new AttachService(new File(System.getProperty("jpi.agent.jar", "target/jpi.jar")));

    @Test void capturesNewClassesAndOneShotRetransformationDoesNotDisableTheStream() throws Exception {
        try (Fixture fixture = start()) {
            InspectorSession session = fixture.session;
            assertTrue(session.requestText(Operation.JVMTI_CAPTURE_START, "").contains("active=1"));
            define(session, "ContinuousFirst");
            assertTrue(session.request(Operation.JVMTI_CLASS_BYTES, AttachTarget.class.getName()).length > 0);
            define(session, "ContinuousSecond");
            String events = session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "");
            for (String name : List.of("ContinuousFirst", "ContinuousSecond")) {
                long sequence = sequence(events, name);
                assertEquals(name, new ClassReader(session.request(Operation.JVMTI_CAPTURE_BYTES, Long.toString(sequence))).getClassName());
            }
            String stopped = session.requestText(Operation.JVMTI_CAPTURE_STOP, "");
            assertTrue(stopped.contains("active=0"));
            define(session, "ContinuousAfterStop");
            String after = session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "");
            assertFalse(after.contains(Base64.getEncoder().encodeToString("ContinuousAfterStop".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            assertThrows(IOException.class, () -> session.request(Operation.JVMTI_CAPTURE_BYTES, "0"));
            assertEquals("pong", session.requestText(Operation.PING, ""));
        }
    }

    @Test void boundsTheBufferAndReportsEvictions() throws Exception {
        try (Fixture fixture = start()) {
            InspectorSession session = fixture.session;
            session.request(Operation.JVMTI_CAPTURE_START, "");
            StringBuilder source = new StringBuilder("public class CaptureOverflow {");
            for (int i = 0; i < 300; i++) source.append("static class C").append(i).append(" {}");
            source.append("public static void execute(java.io.PrintStream out) throws Exception { for(int i=0;i<300;i++) Class.forName(\"CaptureOverflow$C\"+i,true,CaptureOverflow.class.getClassLoader()); out.print(\"done\"); }}");
            assertEquals("done", session.requestText(Operation.EXECUTE, source.toString()));
            String events = session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "");
            assertTrue(metric(events, "events") <= 256, events);
            assertTrue(metric(events, "bytes") <= 16 * 1024 * 1024, events);
            assertTrue(metric(events, "dropped") > 0, events);
            assertEquals("CaptureOverflow$C299", new ClassReader(session.request(Operation.JVMTI_CAPTURE_BYTES,
                    Long.toString(sequence(events, "CaptureOverflow$C299")))).getClassName());
        }
    }

    @Test void archivesDecodedTransientClassesAndDuplicateNamesBeyondThePreviewBuffer() throws Exception {
        Path unicodeDirectory = Files.createDirectory(archiveParent.resolve("źródło_測試"));
        try (Fixture fixture = start()) {
            InspectorSession session = fixture.session;
            session.request(Operation.JVMTI_CAPTURE_ARCHIVE_START, unicodeDirectory + "\n67108864");
            byte[] first = payload("TransientDecoded", "first");
            byte[] second = payload("TransientDecoded", "second");
            assertEquals("first", load(session, first, 93));
            long firstSequence = sequenceForBytes(session, session.requestText(Operation.JVMTI_CAPTURE_EVENTS, ""), first);
            assertEquals("second", load(session, second, 17));
            String encoded = encrypted(first, 93);
            assertEquals("done", session.requestText(Operation.EXECUTE,
                    "public class ArchiveBurst { public static void execute(java.io.PrintStream out) throws Exception { "
                    + "for(int i=0;i<300;i++) dev.whitedev.jpi.integration.TransientClassFixture.load(\"" + encoded
                    + "\",93); out.print(\"done\"); }}"));
            String stopped = session.requestText(Operation.JVMTI_CAPTURE_STOP, "");
            assertEquals(0, metric(stopped, "archive_errors"), stopped);
            assertTrue(metric(stopped, "dropped") > 0, stopped);
            assertEquals(0, metric(stopped, "archive_active"), stopped);
            assertEquals(0, metric(stopped, "archive_queued"), stopped);
            assertThrows(IOException.class, () -> session.request(Operation.JVMTI_CAPTURE_BYTES, Long.toString(firstSequence)));
            Path directory = archiveDirectory(stopped);
            List<String[]> saved = new ArrayList<>();
            List<String> manifest = Files.readAllLines(directory.resolve("manifest.tsv"));
            for (String row : manifest.subList(1, manifest.size())) {
                String[] fields = row.split("\t", -1);
                if (new ClassReader(Files.readAllBytes(directory.resolve(fields[4]))).getClassName().equals("TransientDecoded")) saved.add(fields);
            }
            assertTrue(saved.size() >= 2, stopped);
            assertNotEquals(saved.get(0)[4], saved.get(1)[4]);
            assertArrayEquals(first, Files.readAllBytes(directory.resolve(saved.get(0)[4])));
            assertArrayEquals(second, Files.readAllBytes(directory.resolve(saved.get(1)[4])));
            for (String[] row : saved) assertEquals("TransientDecoded", new ClassReader(Files.readAllBytes(directory.resolve(row[4]))).getClassName());
            assertEquals("pong", session.requestText(Operation.PING, ""));
        }
    }

    @Test void archiveQuotaDoesNotDisableCaptureAndInvalidSettingsPreserveTheCurrentStream() throws Exception {
        try (Fixture fixture = start()) {
            InspectorSession session = fixture.session;
            session.request(Operation.JVMTI_CAPTURE_START, "");
            assertThrows(IOException.class, () -> session.request(Operation.JVMTI_CAPTURE_ARCHIVE_START, archiveParent + "\n0"));
            assertThrows(IOException.class, () -> session.request(Operation.JVMTI_CAPTURE_ARCHIVE_START, archiveParent.resolve("missing") + "\n1024"));
            assertTrue(session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "").startsWith("active=1\n"));
            session.request(Operation.JVMTI_CAPTURE_ARCHIVE_START, archiveParent + "\n1");
            assertEquals("quota", load(session, payload("QuotaDecoded", "quota"), 71));
            String status = session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "");
            assertEquals(1, metric(status, "archive_quota_reached"), status);
            assertTrue(metric(status, "archive_dropped") > 0, status);
            assertEquals(0, metric(status, "archive_written"), status);
            assertEquals("QuotaDecoded", new ClassReader(session.request(Operation.JVMTI_CAPTURE_BYTES,
                    Long.toString(sequenceForBytes(session, status, payload("QuotaDecoded", "quota"))))).getClassName());
            session.request(Operation.JVMTI_CAPTURE_STOP, "");
            String restarted = session.requestText(Operation.JVMTI_CAPTURE_ARCHIVE_START, archiveParent + "\n1048576");
            assertNotEquals(archiveDirectory(status), archiveDirectory(restarted));
            assertEquals("recovered", load(session, payload("RecoveredDecoded", "recovered"), 0));
            String finished = session.requestText(Operation.JVMTI_CAPTURE_STOP, "");
            assertTrue(metric(finished, "archive_written") > 0, finished);
            assertEquals(0, metric(finished, "archive_errors"), finished);
        }
    }

    static byte[] payload(String name, String value) {
        return payload(name, value, false);
    }

    private static byte[] payload(String name, String value, boolean oversized) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()Ljava/lang/String;", null, null);
        method.visitCode(); method.visitLdcInsn(value); method.visitInsn(Opcodes.ARETURN); method.visitMaxs(1, 0); method.visitEnd();
        if (oversized) writer.visitAttribute(new org.objectweb.asm.Attribute("JpiCaptureLimit") {
            @Override protected org.objectweb.asm.ByteVector write(ClassWriter cw, byte[] code, int length, int stack, int locals) {
                byte[] padding = new byte[4 * 1024 * 1024];
                return new org.objectweb.asm.ByteVector().putByteArray(padding, 0, padding.length);
            }
        });
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test void oversizedClassesAreReportedAsArchiveDropsWithoutBreakingClassLoading() throws Exception {
        Path file = archiveParent.resolve("oversized.class");
        Files.write(file, payload("OversizedDecoded", "oversized", true));
        try (Fixture fixture = start()) {
            InspectorSession session = fixture.session;
            session.request(Operation.JVMTI_CAPTURE_ARCHIVE_START, archiveParent + "\n67108864");
            String path = Base64.getEncoder().encodeToString(file.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("oversized", session.requestText(Operation.EXECUTE,
                    "public class LoadOversized { public static void execute(java.io.PrintStream out) throws Exception { "
                    + "out.print(dev.whitedev.jpi.integration.TransientClassFixture.loadFile(\"" + path + "\")); }}"));
            String status = session.requestText(Operation.JVMTI_CAPTURE_STOP, "");
            assertTrue(metric(status, "dropped") > 0, status);
            assertTrue(metric(status, "archive_dropped") > 0, status);
            assertEquals(0, metric(status, "archive_errors"), status);
            assertEquals("pong", session.requestText(Operation.PING, ""));
        }
    }

    @Test void archiveWriteFailureIsReportedWithoutBreakingTheSessionAndCanBeRestarted() throws Exception {
        try (Fixture fixture = start()) {
            InspectorSession session = fixture.session;
            assertEquals("warm", load(session, payload("WarmWriter", "warm"), 0));
            session.request(Operation.JVMTI_CAPTURE_ARCHIVE_START, archiveParent + "\n67108864");
            assertEquals("first", load(session, payload("BeforeWriterFailure", "first"), 0));
            String snapshot = session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "");
            Path directory = archiveDirectory(snapshot);
            long next = snapshot.lines().filter(line -> line.split("\t", -1).length == 4)
                    .mapToLong(line -> Long.parseLong(line.split("\t", -1)[0])).max().orElseThrow() + 1;
            for (int i = 0; i < 128; i++) Files.createDirectory(directory.resolve((next + i) + ".class"));
            assertEquals("still-running", load(session, payload("AfterWriterFailure", "still-running"), 0));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            String failed = session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "");
            while (metric(failed, "archive_errors") == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
                failed = session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "");
            }
            assertTrue(metric(failed, "archive_errors") > 0, failed);
            assertEquals(0, metric(failed, "archive_active"), failed);
            assertTrue(failed.startsWith("active=1\n"), failed);
            assertEquals("pong", session.requestText(Operation.PING, ""));
            session.request(Operation.JVMTI_CAPTURE_STOP, "");
            session.request(Operation.JVMTI_CAPTURE_ARCHIVE_START, archiveParent + "\n67108864");
            assertEquals("restarted", load(session, payload("AfterWriterRestart", "restarted"), 0));
            String finished = session.requestText(Operation.JVMTI_CAPTURE_STOP, "");
            assertEquals(0, metric(finished, "archive_errors"), finished);
            assertTrue(metric(finished, "archive_written") > 0, finished);
        }
    }

    static String load(InspectorSession session, byte[] bytes, int key) throws IOException {
        return session.requestText(Operation.EXECUTE, "public class LoadTransient { public static void execute(java.io.PrintStream out) throws Exception { "
                + "out.print(dev.whitedev.jpi.integration.TransientClassFixture.load(\"" + encrypted(bytes, key) + "\"," + key + ")); }}");
    }

    private static String encrypted(byte[] bytes, int key) {
        byte[] encoded = bytes.clone();
        for (int i = 0; i < encoded.length; i++) encoded[i] ^= (byte) key;
        return Base64.getEncoder().encodeToString(encoded);
    }

    static Path archiveDirectory(String status) {
        String hex = status.lines().filter(line -> line.startsWith("archive_directory_hex=")).findFirst().orElseThrow().split("=", 2)[1];
        return Path.of(new String(HexFormat.of().parseHex(hex), java.nio.charset.StandardCharsets.UTF_8));
    }

    private static long sequenceForBytes(InspectorSession session, String status, byte[] expected) throws IOException {
        List<String> rows = status.lines().filter(line -> line.split("\t", -1).length == 4).toList();
        for (int i = rows.size() - 1; i >= 0; i--) {
            long sequence = Long.parseLong(rows.get(i).split("\t", -1)[0]);
            if (Arrays.equals(expected, session.request(Operation.JVMTI_CAPTURE_BYTES, Long.toString(sequence)))) return sequence;
        }
        throw new AssertionError("Expected captured bytes were not present in the snapshot");
    }

    private Fixture start() throws Exception {
        String os = System.getProperty("os.name", "");
        Assumptions.assumeTrue(os.equals("Linux") || os.startsWith("Windows"));
        boolean bundled = getClass().getResource(NativeAgentArtifact.resourcePath()) != null;
        if (Boolean.getBoolean("jpi.native.required")) assertTrue(bundled); else Assumptions.assumeTrue(bundled);
        NativeAgentArtifact artifact = NativeAgentArtifact.extractBundled();
        try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
            String early = prepared.jvmArgument();
            if (early.startsWith("\"")) early = early.substring(1, early.length() - 1);
            String executable = os.startsWith("Windows") ? "java.exe" : "java";
            Process target = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    artifact.startupArgument(), early, "-cp", System.getProperty("jpi.test.classes", "target/test-classes"), AttachTarget.class.getName())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                InspectorSession session = prepared.await(Long.toString(target.pid()), "capture fixture");
                try { session.request(Operation.JVMTI_BIND, artifact.bindingOptions()); return new Fixture(target, session); }
                catch (Throwable error) { session.close(); throw error; }
            } catch (Throwable error) { target.destroyForcibly(); target.waitFor(5, TimeUnit.SECONDS); throw error; }
        }
    }

    private static void define(InspectorSession session, String name) throws IOException {
        assertEquals("done", session.requestText(Operation.EXECUTE, "public class " + name + " { public static void execute(java.io.PrintStream out) { out.print(\"done\"); } }"));
    }
    private static long sequence(String events, String name) {
        String encoded = Base64.getEncoder().encodeToString(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return events.lines().filter(line -> line.endsWith("\t" + encoded)).map(line -> Long.parseLong(line.split("\t", 2)[0])).findFirst().orElseThrow();
    }
    private static long metric(String events, String key) {
        return events.lines().filter(line -> line.startsWith(key + "=")).mapToLong(line -> Long.parseLong(line.substring(key.length() + 1))).findFirst().orElseThrow();
    }
    private static final class Fixture implements AutoCloseable {
        final Process target; final InspectorSession session;
        Fixture(Process target, InspectorSession session) { this.target = target; this.session = session; }
        public void close() throws Exception {
            session.close(); target.destroy();
            if (!target.waitFor(5, TimeUnit.SECONDS)) { target.destroyForcibly(); assertTrue(target.waitFor(5, TimeUnit.SECONDS)); }
        }
    }
}
