package dev.whitedev.jpi.ui.timeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTimelineStoreTest {
    @Test void correlatesEventsThroughRootCallThreadAndTime() {
        RuntimeTimelineStore store = new RuntimeTimelineStore();
        store.publish(event("trace:10", 100L, TimelineSource.TRACE, "worker", "10", ""));
        store.publish(event("trace:11", 110L, TimelineSource.TRACE, "worker", "11", "10"));
        store.publish(event("hook:1", 115L, TimelineSource.API_HOOK, "worker", "", ""));
        store.publish(event("network:1", 118L, TimelineSource.NETWORK, "", "", ""));

        assertEquals("#10", find(store.snapshot(), "trace:11").correlation());
        assertEquals("#10", find(store.snapshot(), "hook:1").correlation());
        assertEquals("thread + time", find(store.snapshot(), "hook:1").correlationBasis());
        assertEquals("#10", find(store.snapshot(), "network:1").correlation());
        assertEquals("time", find(store.snapshot(), "network:1").correlationBasis());
    }

    @Test void correlatesMarkerAddedBeforeTheObservedCall() {
        RuntimeTimelineStore store = new RuntimeTimelineStore();
        store.publish(event("marker:1", 1_000L, TimelineSource.MARKER, "", "", ""));
        store.publish(event("trace:20", 1_400L, TimelineSource.TRACE, "target", "20", ""));

        assertEquals("#20", find(store.snapshot(), "marker:1").correlation());
    }

    @Test void boundsEventsAndDeduplicatesStableKeys() {
        RuntimeTimelineStore store = new RuntimeTimelineStore(2);
        store.publish(event("one", 1L, TimelineSource.NETWORK, "", "", ""));
        store.publish(event("two", 2L, TimelineSource.NETWORK, "", "", ""));
        store.publish(event("two", 3L, TimelineSource.NETWORK, "", "", ""));
        store.publish(event("three", 4L, TimelineSource.NETWORK, "", "", ""));

        List<RuntimeTimelineStore.CorrelatedEvent> events = store.snapshot();
        assertEquals(2, events.size());
        assertTrue(events.stream().noneMatch(value -> value.event().key().equals("one")));
        assertEquals(2L, find(events, "two").event().timestamp());
    }

    private static TimelineEvent event(String key, long timestamp, TimelineSource source, String thread,
                                       String callId, String parentCallId) {
        return new TimelineEvent(key, timestamp, source, thread, callId, parentCallId, key, key);
    }

    private static RuntimeTimelineStore.CorrelatedEvent find(
            List<RuntimeTimelineStore.CorrelatedEvent> events, String key) {
        return events.stream().filter(value -> value.event().key().equals(key)).findFirst().orElseThrow();
    }
}
