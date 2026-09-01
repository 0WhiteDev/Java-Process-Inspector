package dev.whitedev.jpi.ui.callgraph;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallGraphModelTest {
    @Test
    void parsesMetricsAndFiltersNoiseByCallsAndHeat() {
        String raw = "G\t1000\t3\t2\n"
                + node("sample.Main", "run", "()V", 1, 1, 1_000_000, 0, 0, true)
                + node("sample.Renderer", "tick", "()V", 54_231, 54_231, 90_000_000, 2, 1, true)
                + node("sample.License", "check", "()Z", 1, 1, 5_000_000, 0, 1, false)
                + edge("sample.Main", "run", "()V", "sample.Renderer", "tick", "()V", 54_231, 2, false)
                + edge("sample.Main", "run", "()V", "sample.License", "check", "()Z", 1, 0, false);

        CallGraphModel model = CallGraphModel.parse(raw);
        CallGraphModel.View hot = model.view("", 10L, 100, CallGraphModel.HeatMetric.CALLS);

        assertEquals(3, model.nodes.size());
        assertEquals(2, model.edges.size());
        assertEquals(54_233L, model.totalCalls());
        assertEquals(1, hot.nodes().size());
        assertEquals("sample.Renderer.tick()V", hot.nodes().get(0).id());
        assertEquals(2L, hot.nodes().get(0).exceptions);
        assertTrue(model.view("license", 0L, 100, CallGraphModel.HeatMetric.TOTAL_TIME)
                .nodes().get(0).id().contains("License"));
    }

    private static String node(String owner, String method, String descriptor, long calls, long completed,
                               long nanos, long failures, int callers, boolean active) {
        return "N\t" + encoded(owner) + "\t" + encoded(method) + "\t" + encoded(descriptor) + "\t"
                + calls + "\t" + completed + "\t" + nanos + "\t" + failures + "\t" + callers
                + "\t" + active + "\n";
    }

    private static String edge(String callerOwner, String callerMethod, String callerDescriptor,
                               String calleeOwner, String calleeMethod, String calleeDescriptor,
                               long calls, long failures, boolean reflective) {
        return "E\t" + encoded(callerOwner) + "\t" + encoded(callerMethod) + "\t"
                + encoded(callerDescriptor) + "\t" + encoded(calleeOwner) + "\t" + encoded(calleeMethod)
                + "\t" + encoded(calleeDescriptor) + "\t" + calls + "\t" + failures + "\t"
                + reflective + "\n";
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
