package dev.whitedev.jpi.integration;

import dev.whitedev.jpi.debug.BreakpointSpec;
import dev.whitedev.jpi.debug.DebugEvent;
import dev.whitedev.jpi.debug.DebugSession;
import dev.whitedev.jpi.debug.DebugState;
import dev.whitedev.jpi.debug.StackFrameManager;
import dev.whitedev.jpi.debug.StepManager;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerIntegrationIT {
    @Test void launchesSuspendedAndStopsAtAPendingMethodBreakpoint() throws Exception {
        DebugSession session = DebugSession.launch(executableFixtureJar(), List.of(), List.of());
        Process process = session.launchedProcess();
        CountDownLatch stopped = new CountDownLatch(1);
        CountDownLatch stepped = new CountDownLatch(1);
        List<DebugEvent> events = new CopyOnWriteArrayList<>();
        session.addListener(new DebugSession.Listener() {
            @Override public void onEvent(DebugEvent event) {
                events.add(event);
                if (event.type() == DebugEvent.Type.BREAK) stopped.countDown();
                if (event.type() == DebugEvent.Type.STEP) stepped.countDown();
            }
        });
        try {
            assertEquals(DebugState.SUSPENDED, session.state());
            session.breakpoints().add(new BreakpointSpec(AttachTarget.class.getName(), "main",
                    "([Ljava/lang/String;)V", null, null, BreakpointSpec.Type.METHOD,
                    BreakpointSpec.SuspendPolicy.THREAD, true));
            assertEquals(0, session.breakpoints().snapshot().getFirst().installedLocations());
            session.continueExecution();
            assertTrue(stopped.await(15, TimeUnit.SECONDS), () -> "events=" + events + ", breakpoints="
                    + session.breakpoints().snapshot() + ", state=" + session.state() + ", alive=" + process.isAlive());
            assertEquals(DebugState.SUSPENDED, session.state());
            assertFalse(session.frames().frames(session.stoppedThreadId()).isEmpty());
            assertTrue(session.frames().frames(session.stoppedThreadId()).getFirst().className()
                    .equals(AttachTarget.class.getName()));
            assertTrue(session.breakpoints().snapshot().getFirst().installedLocations() > 0);
            session.step(session.stoppedThreadId(), StepManager.Depth.OVER, StepManager.Mode.SOURCE);
            assertTrue(stepped.await(15, TimeUnit.SECONDS), () -> "events=" + events);
            List<StackFrameManager.VariableView> variables = session.frames()
                    .variables(session.stoppedThreadId(), 0);
            StackFrameManager.VariableView arguments = variables.stream()
                    .filter(variable -> "args".equals(variable.name())).findFirst().orElseThrow();
            session.setValue(arguments, "null");
            assertEquals("null", session.evaluator().evaluate(session.stoppedThreadId(), 0, "args"));
            if (session.capabilities().forceEarlyReturn()) {
                session.forceEarlyReturn(session.stoppedThreadId(), 0, "ignored");
                session.continueExecution();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        } finally {
            session.close();
            if (process != null) {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        }
    }

    private File executableFixtureJar() throws Exception {
        File jar = Files.createTempFile("jpi-debug-target-", ".jar").toFile();
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
}
