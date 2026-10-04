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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DebuggerIntegrationIT {
    @Test void attachesToRunningJdwpProcessByPid() throws Exception {
        Process process = new ProcessBuilder(javaExecutable(),
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
                "-jar", executableFixtureJar().getAbsolutePath()).redirectErrorStream(true).start();
        try {
            awaitJdwpListener(process);
            try (DebugSession session = DebugSession.attach(process.pid())) {
                assertEquals(DebugState.RUNNING, session.state());
                assertTrue(process.isAlive());
                assertNotNull(session.capabilities());
            }
        } finally {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    @Test void launchesSuspendedAndStopsAtAPendingMethodBreakpoint() throws Exception {
        DebugSession session = DebugSession.launch(executableFixtureJar(), List.of(), List.of("hello"));
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
            long breakpointId = session.breakpoints().snapshot().getFirst().id();
            session.breakpoints().setSuspendPolicy(breakpointId, BreakpointSpec.SuspendPolicy.ALL);
            assertEquals(BreakpointSpec.SuspendPolicy.ALL,
                    session.breakpoints().snapshot().getFirst().spec().suspendPolicy());
            session.step(session.stoppedThreadId(), StepManager.Depth.OVER, StepManager.Mode.SOURCE);
            assertTrue(stepped.await(15, TimeUnit.SECONDS), () -> "events=" + events);
            List<StackFrameManager.VariableView> variables = session.frames()
                    .variables(session.stoppedThreadId(), 0);
            StackFrameManager.VariableView arguments = variables.stream()
                    .filter(variable -> "args".equals(variable.name())).findFirst().orElseThrow();
            assertEquals("\"hello\"", session.evaluator().evaluate(session.stoppedThreadId(), 0, "args[0]"));
            session.setValue(arguments, "null");
            assertEquals("null", session.evaluator().evaluate(session.stoppedThreadId(), 0, "args"));
            if (session.capabilities().forceEarlyReturn()) {
                session.forceEarlyReturn(session.stoppedThreadId(), 0, "ignored");
                session.resumeAll();
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

    @Test void dataBreakpointsFilterObjectsConditionsAndUnchangedWrites() throws Exception {
        DebugSession session = DebugSession.launch(executableFixtureJar(WatchTarget.class), List.of(), List.of("hello"));
        Process process = session.launchedProcess();
        CountDownLatch ready = new CountDownLatch(1);
        LinkedBlockingQueue<DebugEvent> hits = new LinkedBlockingQueue<>();
        session.addListener(new DebugSession.Listener() {
            @Override public void onEvent(DebugEvent event) {
                if (event.type() == DebugEvent.Type.BREAK) ready.countDown();
                if (event.type() == DebugEvent.Type.DATA_BREAK) hits.add(event);
            }
        });
        try {
            session.breakpoints().add(new BreakpointSpec(WatchTarget.class.getName(), "ready", "()V",
                    null, null, BreakpointSpec.Type.METHOD, BreakpointSpec.SuspendPolicy.THREAD, true));
            session.continueExecution();
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            long thread = session.stoppedThreadId();
            assertEquals("10", session.evaluator().evaluate(thread, 0, "player.health"));
            assertThrows(IllegalArgumentException.class, () -> session.watchField(thread, 0, "1", "", BreakpointSpec.SuspendPolicy.THREAD));
            var conditional = session.watchField(thread, 0, "player.health", "< 5", BreakpointSpec.SuspendPolicy.THREAD);
            var counter = session.watchField(thread, 0, "counter", "", BreakpointSpec.SuspendPolicy.ALL);
            assertTrue(conditional.objectId() > 0);
            assertEquals(-1, counter.objectId());
            session.continueExecution();
            DebugEvent first = hits.poll(15, TimeUnit.SECONDS);
            assertNotNull(first);
            assertTrue(first.details().contains("8 -> 4"), first.toString());
            assertEquals("8", session.evaluator().evaluate(session.stoppedThreadId(), 0, "player.health"));
            session.dataBreakpoints().remove(conditional.id());
            var changed = session.watchField(session.stoppedThreadId(), 0, "player.health", "", BreakpointSpec.SuspendPolicy.THREAD);
            session.continueExecution();
            DebugEvent second = hits.poll(15, TimeUnit.SECONDS);
            assertNotNull(second);
            assertTrue(second.details().contains("4 -> 2"), second.toString());
            assertEquals(1, session.dataBreakpoints().snapshot().stream().filter(view -> view.id() == changed.id()).findFirst().orElseThrow().hits());
            session.dataBreakpoints().remove(changed.id());
            session.continueExecution();
            DebugEvent third = hits.poll(15, TimeUnit.SECONDS);
            assertNotNull(third);
            assertTrue(third.details().contains("0 -> 1"), third.toString());
            session.dataBreakpoints().setEnabled(counter.id(), false);
            session.continueExecution();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertTrue(hits.isEmpty());
        } finally {
            session.close();
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    private void awaitJdwpListener(Process process) throws Exception {
        CompletableFuture<String> ready = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            StringBuilder output = new StringBuilder();
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.startsWith("Listening for transport dt_socket at address:")) {
                        ready.complete(line);
                        return;
                    }
                    if (output.length() < 8192) output.append(line).append('\n');
                }
                ready.completeExceptionally(new IllegalStateException("Target exited before JDWP was ready: " + output));
            } catch (Exception error) {
                ready.completeExceptionally(error);
            }
        }, "jpi-test-jdwp-ready");
        reader.setDaemon(true);
        reader.start();
        assertTrue(ready.get(15, TimeUnit.SECONDS).startsWith("Listening for transport dt_socket"));
    }

    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return new File(new File(System.getProperty("java.home"), "bin"), executable).getAbsolutePath();
    }

    private File executableFixtureJar() throws Exception {
        return executableFixtureJar(AttachTarget.class);
    }

    private File executableFixtureJar(Class<?> target) throws Exception {
        File jar = Files.createTempFile("jpi-debug-target-", ".jar").toFile();
        jar.deleteOnExit();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, target.getName());
        String resource = target.getName().replace('.', '/') + ".class";
        try (InputStream input = target.getClassLoader().getResourceAsStream(resource);
             JarOutputStream output = new JarOutputStream(new FileOutputStream(jar), manifest)) {
            assertNotNull(input);
            output.putNextEntry(new JarEntry(resource));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }
}
