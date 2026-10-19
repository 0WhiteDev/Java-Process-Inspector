package dev.whitedev.jpi.integration;

import dev.whitedev.jpi.attach.*;
import dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact;
import dev.whitedev.jpi.protocol.Operation;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(300)
class NativeAttachBenchmarkIT {
    private final AttachService service = new AttachService(new File(System.getProperty("jpi.agent.jar", "target/jpi.jar")));
    private record Sample(String route, int trial, boolean warmup, double milliseconds, long rssBefore, long rssAfter, String error) {}

    @Test void measureNativeLoadingAndBindingWithEquivalentPreloadedJavaAgentBaselines() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("jpi.benchmark") && System.getProperty("os.name").equals("Linux"));
        int count = Integer.getInteger("jpi.benchmark.samples", 7);
        assertTrue(count >= 3 && count <= 30, "Use 3 to 30 measured samples per route");
        NativeAgentArtifact artifact = NativeAgentArtifact.extractBundled();
        List<Sample> samples = new ArrayList<>();
        for (int trial = -2; trial < count; trial++) {
            List<String> order = new ArrayList<>(List.of("attach-native", "ptrace-native", "java-session", "native-plus-java-session"));
            if (trial % 2 != 0) Collections.reverse(order);
            for (String route : order) samples.add(route.endsWith("-session") ? measureFull(route, trial, artifact) : measure(route, trial, artifact));
        }
        Path output = Path.of(System.getProperty("jpi.benchmark.output", "target/native-benchmark"));
        Files.createDirectories(output);
        StringBuilder csv = new StringBuilder("route,trial,warmup,load_and_bind_ms,rss_before_kib,rss_after_kib,rss_delta_kib,error\n");
        for (Sample sample : samples) csv.append(sample.route).append(',').append(sample.trial).append(',').append(sample.warmup)
                .append(',').append(String.format(Locale.ROOT, "%.3f", sample.milliseconds)).append(',').append(sample.rssBefore)
                .append(',').append(sample.rssAfter).append(',').append(sample.rssAfter - sample.rssBefore).append(',')
                .append('"').append(sample.error.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ')).append('"').append('\n');
        Files.writeString(output.resolve("samples.csv"), csv, StandardCharsets.UTF_8);
        StringBuilder summary = new StringBuilder("# Native load-and-bind benchmark\n\n")
                .append("JDK: ").append(System.getProperty("java.runtime.version")).append("; OS: ").append(System.getProperty("os.version"))
                .append("; arch: ").append(System.getProperty("os.arch")).append(".\n\n")
                .append("attach-native and ptrace-native start with the same preloaded Java-agent session and measure native loading plus JVMTI binding. java-session starts without agents and measures Java Attach through its handshake. native-plus-java-session also starts without agents and measures direct native loading, ordinary Java Attach and native binding; it provides extra native features and is not functionally identical to java-session. None uses DisableAttachMechanism. All begin after main readiness and 150 ms settling. RSS is /proc/PID/status VmRSS before loading and 100 ms after completion; it is noisy process RSS, not exclusive agent allocation. Each sample uses a fresh child JVM. Two warmups per route are excluded; order reverses on alternating trials. No native fallback is permitted.\n\n")
                .append("| Route | Successful measured samples | Median ms | P95 ms | Median baseline RSS KiB | Median RSS delta KiB |\n|---|---:|---:|---:|---:|---:|\n");
        for (String route : List.of("attach-native", "ptrace-native", "java-session", "native-plus-java-session")) {
            List<Sample> selected = samples.stream().filter(s -> s.route.equals(route) && !s.warmup && s.error.isEmpty()).toList();
            double[] time = selected.stream().mapToDouble(Sample::milliseconds).sorted().toArray();
            double[] baseline = selected.stream().mapToDouble(Sample::rssBefore).sorted().toArray();
            double[] delta = selected.stream().mapToDouble(s -> s.rssAfter - s.rssBefore).sorted().toArray();
            summary.append(String.format(Locale.ROOT, "| %s | %d | %.3f | %.3f | %.0f | %.0f |%n", route, time.length,
                    percentile(time, .5), percentile(time, .95), percentile(baseline, .5), percentile(delta, .5)));
        }
        long failures = samples.stream().filter(s -> !s.error.isEmpty()).count();
        summary.append("\nFailed trials (including warmup): ").append(failures)
                .append(". All raw rows are retained in samples.csv. Percentiles use nearest rank.\n\n")
                .append("Only the two native load-and-bind rows are equivalent paths suitable for a direct route comparison. The full-session rows include communication initialization; native-plus-java-session still uses ordinary Java Attach and is not an agentless connection. A preloaded reconnect would exclude provisioning costs and is not benchmarked. These measurements do not establish a portable speedup or memory-reduction claim.\n");
        Files.writeString(output.resolve("summary.md"), summary, StandardCharsets.UTF_8);
        assertEquals(0, failures, "Benchmark contains failed trials; inspect samples.csv rather than comparing partial results");
    }

    private Sample measure(String route, int trial, NativeAgentArtifact artifact) throws Exception {
        Process target = null;
        double milliseconds = Double.NaN;
        long before = 0, after = 0;
        String error = "";
        try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
            String early = prepared.jvmArgument();
            if (early.startsWith("\"")) early = early.substring(1, early.length() - 1);
            target = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), early,
                    "-cp", System.getProperty("jpi.test.classes", "target/test-classes"), AttachTarget.class.getName())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process running = target;
            String ready = CompletableFuture.supplyAsync(() -> {
                try { return running.inputReader().readLine(); } catch (IOException failure) { throw new CompletionException(failure); }
            }).get(15, TimeUnit.SECONDS);
            assertEquals(Long.toString(target.pid()), ready);
            try (InspectorSession session = prepared.await(ready, "benchmark fixture")) {
                Thread.sleep(150);
                before = rss(target.pid());
                long begin = System.nanoTime();
                if (route.equals("attach-native")) service.loadNativeAgent(ready, artifact);
                else new ProcessInjector().inject(ready, artifact);
                String status = session.requestText(Operation.JVMTI_BIND, artifact.bindingOptions());
                milliseconds = (System.nanoTime() - begin) / 1_000_000.0;
                assertTrue(status.contains("phase=live") && status.contains("can_redefine_classes=1"), status);
                Thread.sleep(100);
                after = rss(target.pid());
                assertEquals("pong", session.requestText(Operation.PING, ""));
            }
        } catch (Throwable failure) { error = failure.toString(); }
        finally {
            if (target != null) {
                target.destroy();
                if (!target.waitFor(5, TimeUnit.SECONDS)) { target.destroyForcibly(); target.waitFor(5, TimeUnit.SECONDS); }
            }
        }
        return new Sample(route, trial, trial < 0, milliseconds, before, after, error);
    }

    private static long rss(long pid) throws IOException {
        return Files.readAllLines(Path.of("/proc", Long.toString(pid), "status")).stream().filter(s -> s.startsWith("VmRSS:"))
                .mapToLong(s -> Long.parseLong(s.trim().split("\\s+")[1])).findFirst().orElseThrow();
    }
    private Sample measureFull(String route, int trial, NativeAgentArtifact artifact) throws Exception {
        Process target = null;
        double milliseconds = Double.NaN; long before = 0, after = 0; String error = "";
        try {
            target = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("jpi.test.classes", "target/test-classes"), AttachTarget.class.getName())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process running = target;
            String ready = CompletableFuture.supplyAsync(() -> {
                try { return running.inputReader().readLine(); } catch (IOException failure) { throw new CompletionException(failure); }
            }).get(15, TimeUnit.SECONDS);
            assertEquals(Long.toString(target.pid()), ready);
            Thread.sleep(150); before = rss(target.pid());
            long begin = System.nanoTime();
            if (route.equals("native-plus-java-session")) new ProcessInjector().inject(ready, artifact);
            try (InspectorSession session = service.attach(new JvmDescriptor(ready, "full-session benchmark"))) {
                if (route.equals("native-plus-java-session")) {
                    String status = session.requestText(Operation.JVMTI_BIND, artifact.bindingOptions());
                    assertTrue(status.contains("phase=live"), status);
                }
                milliseconds = (System.nanoTime() - begin) / 1_000_000.0;
                Thread.sleep(100); after = rss(target.pid());
                assertEquals("pong", session.requestText(Operation.PING, ""));
            }
        } catch (Throwable failure) { error = failure.toString(); }
        finally {
            if (target != null) {
                target.destroy();
                if (!target.waitFor(5, TimeUnit.SECONDS)) { target.destroyForcibly(); target.waitFor(5, TimeUnit.SECONDS); }
            }
        }
        return new Sample(route, trial, trial < 0, milliseconds, before, after, error);
    }
    private static double percentile(double[] sorted, double fraction) {
        return sorted.length == 0 ? Double.NaN : sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
    }
}
