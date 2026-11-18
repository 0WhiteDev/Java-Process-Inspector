package dev.whitedev.jpi.integration;

import dev.whitedev.jpi.attach.AttachService;
import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.attach.JvmDescriptor;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.search.SearchCategory;
import dev.whitedev.jpi.search.SearchEverythingService;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SearchEverythingIT {
    @Test
    void searchesRealLoadedClassesMembersAndLiteralStrings() throws Exception {
        String javaExe = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process target = new ProcessBuilder(javaExe, "-cp", System.getProperty("jpi.test.classes", "target/test-classes"), AttachTarget.class.getName())
                .redirectErrorStream(true).start();
        try {
            String pid = target.inputReader().readLine();
            assertEquals(Long.toString(target.pid()), pid);
            try (InspectorSession session = new AttachService(new File(System.getProperty("jpi.agent.jar", "target/jpi.jar")))
                    .attach(new JvmDescriptor(pid, "Search Everything fixture"))) {
                var classes = SearchEverythingService.target(session, "AttachTarget");
                var definition = classes.hits().stream().filter(hit -> hit.category() == SearchCategory.CLASSES
                        && hit.className().equals(AttachTarget.class.getName())).findFirst().orElseThrow();
                assertTrue(definition.classId().startsWith("c:"));
                assertEquals(AttachTarget.class.getName().replace('.', '/'), new ClassReader(session.request(Operation.CLASS_BYTES, definition.classId())).getClassName());
                var members = SearchEverythingService.target(session, "runtimeValue");
                assertTrue(members.hits().stream().anyMatch(hit -> hit.category() == SearchCategory.METHODS
                        && hit.classId().equals(definition.classId()) && hit.member().equals("runtimeValue")));
                var fields = SearchEverythingService.target(session, "marker");
                assertTrue(fields.hits().stream().anyMatch(hit -> hit.category() == SearchCategory.FIELDS && hit.classId().equals(definition.classId())));
                var strings = SearchEverythingService.target(session, "jpi-smoke-target");
                assertTrue(strings.hits().stream().anyMatch(hit -> hit.category() == SearchCategory.STRINGS && hit.classId().equals(definition.classId())));
                assertEquals("pong", session.requestText(Operation.PING, ""));
                assertTrue(target.isAlive());
            }
        } finally {
            target.destroy();
            if (!target.waitFor(3, TimeUnit.SECONDS)) target.destroyForcibly();
        }
    }
}
