package dev.whitedev.jpi.evidence;

import dev.whitedev.jpi.evidence.EvidenceStore.Graph;
import dev.whitedev.jpi.evidence.EvidenceStore.Kind;
import dev.whitedev.jpi.evidence.EvidenceStore.Symbol;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceGraphTest {
    @Test
    void keepsRecordedRelationshipsSeparateFromTimingAndAvoidsAmbiguousMatches() {
        var parent = trace("1", "", 100, "worker", "a");
        var child = trace("2", "1", 200, "worker", "b");
        var sameTime = trace("3", "", 200, "other", "c");
        var write = new TimelineEvent("write", 205, TimelineSource.FIELD_WRITE, "worker", "2", "", "Write", "");
        var time = new TimelineEvent("network-1", 215, TimelineSource.NETWORK, "worker", "", "", "Socket", "");
        var ambiguous = new TimelineEvent("network-2", 220, TimelineSource.NETWORK, "", "", "", "Ambiguous", "");
        var mismatched = new TimelineEvent("network-3", 205, TimelineSource.NETWORK, "other", "2", "", "Different thread", "");
        var events = List.of(parent, child, sameTime, write, time, ambiguous, mismatched);
        Graph graph = EvidenceGraphBuilder.live(events);
        assertEquals(graph, EvidenceGraphBuilder.live(events));
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.kind() == Kind.OBSERVED
                && edge.basis().equals("Recorded parent call #1")));
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.kind() == Kind.OBSERVED && edge.to().equals(timelineId("write"))));
        assertEquals(List.of(timelineId("network-1")), graph.edges().stream()
                .filter(edge -> edge.kind() == Kind.TIME_CORRELATED).map(EvidenceStore.Edge::to).toList());
        assertTrue(graph.edges().stream().noneMatch(edge -> edge.to().equals(timelineId("network-3"))));
        assertNotEquals(new Symbol("a", "Same", "run", "()V").key(), new Symbol("b", "Same", "run", "()V").key());
    }

    @Test
    void reportsTruncationAndRejectsDanglingLinks() {
        EvidenceStore store = new EvidenceStore();
        for (int i = 0; i < 6000; i++) store.addNode("Test", Integer.toString(i), "event", "", null);
        assertTrue(store.snapshot().partial());
        assertThrows(IllegalArgumentException.class, () -> new Graph(List.of(),
                List.of(new EvidenceStore.Edge("edge", "missing", "missing", Kind.STATIC, "Test", "missing node")), false));
    }

    private static TimelineEvent trace(String id, String parent, int timestamp, String thread, String definition) {
        return new TimelineEvent("start:" + id, timestamp, TimelineSource.TRACE, thread, id, parent, "Call", "",
                "Same.run()V", "ENTER", "", new Symbol(definition, "Same", "run", "()V"));
    }

    private static String timelineId(String key) {
        return EvidenceStore.id("Timeline", key);
    }
}
