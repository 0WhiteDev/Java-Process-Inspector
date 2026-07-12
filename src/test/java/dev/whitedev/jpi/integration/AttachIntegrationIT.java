package dev.whitedev.jpi.integration;

import dev.whitedev.jpi.attach.AttachService;
import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.attach.JvmDescriptor;
import dev.whitedev.jpi.attach.LaunchResult;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.export.SessionSnapshotExporter;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Arrays;
import java.util.Base64;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class AttachIntegrationIT {
    private static final File AGENT_JAR = new File(System.getProperty("jpi.agent.jar", "target/jpi.jar"));
    private static final String TEST_CLASSES = System.getProperty("jpi.test.classes", "target/test-classes");

    @Test void packagedAgentAttachesAndInspectsARealChildJvm() throws Exception {
        String java = new File(new File(System.getProperty("java.home"), "bin"), isWindows() ? "java.exe" : "java").getAbsolutePath();
        Process target = new ProcessBuilder(java, "-cp", TEST_CLASSES, AttachTarget.class.getName()).redirectErrorStream(true).start();
        try {
            BufferedReader output = new BufferedReader(new InputStreamReader(target.getInputStream(), "UTF-8"));
            String pid = output.readLine();
            assertNotNull(pid, "Target JVM did not start");
            InspectorSession session = new AttachService(AGENT_JAR).attach(new JvmDescriptor(pid, "attach-smoke-target"));
            try {
                assertTrue(session.requestText(Operation.METRICS, "").contains("pid=" + pid));
                String loadedClasses = session.requestText(Operation.CLASSES, "");
                assertTrue(loadedClasses.contains(AttachTarget.class.getName()));
                assertTrue(session.requestText(Operation.FIELDS, "AttachTarget").contains("marker"));
                assertTrue(session.requestText(Operation.CONSTANT_SEARCH, "jpi-smoke-target")
                        .contains(AttachTarget.class.getName()));
                String targetLine = Arrays.stream(loadedClasses.split("\n"))
                        .filter(line -> line.contains("\t" + AttachTarget.class.getName() + "\t"))
                        .findFirst().orElseThrow();
                String classId = targetLine.split("\t", -1)[0];
                String probe = "public class RuntimeProbe { public static void execute(java.io.PrintStream out) { out.print(dev.whitedev.jpi.integration.AttachTarget.runtimeValue()); } }";
                assertEquals("before", session.requestText(Operation.EXECUTE, probe));
                assertTrue(session.requestText(Operation.CLASS_METHODS, classId)
                        .contains("runtimeValue\t()Ljava/lang/String;"));
                String methodPatch = classId + "\nruntimeValue\n()Ljava/lang/String;\n{ return \"method-patched\"; }";
                assertTrue(session.requestText(Operation.PATCH_METHOD, methodPatch).contains("Patched"));
                assertEquals("method-patched", session.requestText(Operation.EXECUTE, probe));
                byte[] patchedBytecode = session.request(Operation.CLASS_BYTES, classId);
                assertTrue(session.requestText(Operation.ROLLBACK_CLASS, classId).contains("Restored"));
                assertEquals("before", session.requestText(Operation.EXECUTE, probe));
                String rawPatch = classId + "\n" + Base64.getEncoder().encodeToString(patchedBytecode);
                assertTrue(session.requestText(Operation.APPLY_CLASS_BYTES, rawPatch).contains("Applied"));
                assertEquals("method-patched", session.requestText(Operation.EXECUTE, probe));
                assertTrue(session.requestText(Operation.ROLLBACK_CLASS, classId).contains("Restored"));
                assertEquals("before", session.requestText(Operation.EXECUTE, probe));
                String lambdaProbe = "public class LambdaProbe { public static void execute(java.io.PrintStream out) { out.print(dev.whitedev.jpi.integration.AttachTarget.lambdaValue(3)); } }";
                assertEquals("4", session.requestText(Operation.EXECUTE, lambdaProbe));
                String lambdaPatch = String.join(String.valueOf((char) 10), classId, "lambdaValue", "(I)I",
                        "{ java.util.function.IntUnaryOperator operation = value -> value + 5; return operation.applyAsInt($1); }");
                assertTrue(session.requestText(Operation.PATCH_METHOD, lambdaPatch).contains("with modern Java compiler"));
                assertEquals("8", session.requestText(Operation.EXECUTE, lambdaProbe));
                assertTrue(session.requestText(Operation.ROLLBACK_CLASS, classId).contains("Restored"));
                assertEquals("4", session.requestText(Operation.EXECUTE, lambdaProbe));
                String replacement = "package dev.whitedev.jpi.integration; import java.lang.management.ManagementFactory; public final class AttachTarget { public static volatile String marker = \"jpi-smoke-target\"; public static String runtimeValue() { return \"after\"; } public static int lambdaValue(int input) { java.util.function.IntUnaryOperator operation = value -> value + 1; return operation.applyAsInt(input); } public static void main(String[] args) throws Exception { System.out.println(ManagementFactory.getRuntimeMXBean().getName().split(\"@\", 2)[0]); System.out.flush(); while (true) Thread.sleep(1000); } }";
                assertTrue(session.requestText(Operation.REDEFINE_SOURCE, classId + "\n" + replacement).contains("Redefined"));
                assertEquals("after", session.requestText(Operation.EXECUTE, probe));
                assertTrue(session.requestText(Operation.ROLLBACK_CLASS, classId).contains("Restored"));
                assertEquals("before", session.requestText(Operation.EXECUTE, probe));
                Path snapshot = Files.createTempFile("jpi-session-", ".zip");
                SessionSnapshotExporter.export(session, snapshot);
                try (ZipFile zip = new ZipFile(snapshot.toFile())) {
                    assertNotNull(zip.getEntry("manifest.txt"));
                    assertNotNull(zip.getEntry("loaded-classes.tsv"));
                    assertNotNull(zip.getEntry("thread-dump.txt"));
                }
            } finally { session.close(); }
        } finally {
            target.destroy();
            if (!target.waitFor(3, TimeUnit.SECONDS)) target.destroyForcibly();
        }
    }

    @Test void earlyAgentCapturesApplicationClassBeforeMain() throws Exception {
        File fixture = executableFixtureJar();
        LaunchResult launch = new AttachService(AGENT_JAR).launch(
                fixture, Collections.emptyList(), Collections.emptyList());
        try {
            String classes = launch.session().requestText(Operation.CLASSES, "");
            String classLine = Arrays.stream(classes.split("\n"))
                    .filter(line -> line.contains("\t" + AttachTarget.class.getName() + "\t"))
                    .findFirst().orElseThrow(() -> new AssertionError("AttachTarget missing from class inventory"));
            String[] columns = classLine.split("\t", -1);
            assertEquals("true", columns[5], "early agent should retain bytecode captured at definition");
            assertTrue(launch.session().requestText(Operation.CLASS_EVENTS, "")
                    .contains(AttachTarget.class.getName()));
            assertTrue(launch.logFile().isFile());
        } finally {
            launch.session().close();
            launch.process().destroy();
            if (!launch.process().waitFor(3, TimeUnit.SECONDS)) launch.process().destroyForcibly();
        }
    }

    @Test void manualEarlyAgentSupportsCustomLaunchCommands() throws Exception {
        File fixture = executableFixtureJar();
        AttachService.PreparedAgentSession prepared = new AttachService(AGENT_JAR).prepareEarlyAgent();
        String java = new File(new File(System.getProperty("java.home"), "bin"), isWindows() ? "java.exe" : "java").getAbsolutePath();
        String agentArgument = prepared.jvmArgument();
        if (agentArgument.startsWith("\"") && agentArgument.endsWith("\"")) {
            agentArgument = agentArgument.substring(1, agentArgument.length() - 1);
        }
        Process target = new ProcessBuilder(java, agentArgument, "-jar", fixture.getAbsolutePath())
                .redirectErrorStream(true).start();
        try {
            InspectorSession session = prepared.await(String.valueOf(target.pid()), "manual-smoke-target");
            try {
                assertTrue(session.requestText(Operation.CLASS_EVENTS, "").contains(AttachTarget.class.getName()));
                assertTrue(session.requestText(Operation.METRICS, "").contains("pid=" + target.pid()));
            } finally { session.close(); }
        } finally {
            prepared.close();
            target.destroy();
            if (!target.waitFor(3, TimeUnit.SECONDS)) target.destroyForcibly();
        }
    }

    private File executableFixtureJar() throws Exception {
        File jar = Files.createTempFile("jpi-early-target-", ".jar").toFile();
        jar.deleteOnExit();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, AttachTarget.class.getName());
        String resource = AttachTarget.class.getName().replace('.', '/') + ".class";
        try (InputStream input = AttachTarget.class.getClassLoader().getResourceAsStream(resource);
             JarOutputStream output = new JarOutputStream(new FileOutputStream(jar), manifest)) {
            assertNotNull(input);
            output.putNextEntry(new JarEntry(resource));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
}
