package dev.whitedev.jpi.analysis.difference;

import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DifferenceAnalyzerTest {
    @Test
    void resynchronizesAfterAnInsertedEvent() {
        DifferenceRun runA = new DifferenceRun("A", 1L, 5L, List.of(
                api("a1", 1L, "api.first()V"),
                api("a2", 2L, "api.second()V"),
                api("a3", 3L, "api.third()V")), List.of());
        DifferenceRun runB = new DifferenceRun("B", 6L, 12L, List.of(
                api("b1", 6L, "api.first()V"),
                api("b2", 7L, "api.inserted()V"),
                api("b3", 8L, "api.second()V"),
                api("b4", 9L, "api.third()V")), List.of());

        DifferenceReport report = new DifferenceAnalyzer().compare(runA, runB);

        assertEquals(1, report.differentApiCalls());
        assertEquals("<not observed>", report.changes().get(0).runA());
        assertEquals("api.inserted()V from sample.Validator.check()Z", report.changes().get(0).runB());
    }

    @Test
    void findsFirstBranchDivergenceAndBehavioralDifferences() {
        DifferenceRun runA = new DifferenceRun("A", 1L, 10L, List.of(
                trace("a1", 1L, "sample.Validator.check()Z", "ENTER", ""),
                trace("a2", 2L, "sample.Validator.check()Z", "RETURN", "false"),
                api("a3", 3L, "java.security.MessageDigest.digest()[B")), List.of(
                new CfgTransition(1L, 1L, 1L, -1, 0, "sample.Validator.check()Z"),
                new CfgTransition(2L, 2L, 1L, 0, 4, "sample.Validator.check()Z"),
                new CfgTransition(3L, 3L, 1L, 4, 7, "sample.Validator.check()Z")));
        DifferenceRun runB = new DifferenceRun("B", 11L, 20L, List.of(
                trace("b1", 11L, "sample.Validator.check()Z", "ENTER", ""),
                trace("b2", 12L, "sample.Validator.check()Z", "RETURN", "true"),
                trace("b3", 13L, "sample.Session.open()V", "ENTER", ""),
                api("b4", 14L, "java.net.http.HttpClient.send()V")), List.of(
                new CfgTransition(1L, 11L, 2L, -1, 0, "sample.Validator.check()Z"),
                new CfgTransition(2L, 12L, 2L, 0, 4, "sample.Validator.check()Z"),
                new CfgTransition(3L, 13L, 2L, 4, 5, "sample.Validator.check()Z")));

        DifferenceReport report = new DifferenceAnalyzer().compare(runA, runB);

        assertEquals(1, report.commonMethods());
        assertEquals(List.of(), report.onlyA());
        assertEquals(List.of("sample.Session.open()V"), report.onlyB());
        assertEquals(1, report.differentBranches());
        assertEquals(1, report.differentReturns());
        assertEquals(1, report.differentApiCalls());
        assertEquals("sample.Validator.check()Z", report.firstDivergence().subject());
        assertEquals("B4 -> B7", report.firstDivergence().runA());
        assertEquals("B4 -> B5", report.firstDivergence().runB());
    }

    private static TimelineEvent trace(String key, long timestamp, String subject, String phase, String value) {
        return new TimelineEvent(key, timestamp, TimelineSource.TRACE, "main", key, "", subject, "",
                subject, phase, value);
    }

    private static TimelineEvent api(String key, long timestamp, String subject) {
        return new TimelineEvent(key, timestamp, TimelineSource.API_HOOK, "main", "", "", subject, "",
                subject, "CALL", "sample.Validator.check()Z");
    }
}
