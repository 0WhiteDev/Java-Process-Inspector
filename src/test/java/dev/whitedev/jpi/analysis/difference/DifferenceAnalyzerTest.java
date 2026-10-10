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
        assertEquals("RETURN sample.Validator.check()Z = false", report.firstDivergence().runA());
        assertEquals("RETURN sample.Validator.check()Z = true", report.firstDivergence().runB());
    }

    @Test
    void hidesOnlyUnchangedCommonCallsAndPreservesCountAndReturnDifferences() {
        List<TimelineEvent> baseline = new java.util.ArrayList<>();
        List<TimelineEvent> action = new java.util.ArrayList<>();
        for (int index = 0; index < 100; index++) {
            baseline.add(trace("a" + index, index, "Noise.tick()V", "ENTER", ""));
            action.add(trace("b" + index, index, "Noise.tick()V", "ENTER", ""));
        }
        baseline.add(trace("ac", 101, "Check.test()Z", "ENTER", ""));
        action.add(trace("bc", 101, "Check.test()Z", "ENTER", ""));
        baseline.add(trace("ar", 102, "Check.test()Z", "RETURN", "false"));
        action.add(trace("br", 102, "Check.test()Z", "RETURN", "true"));
        action.add(trace("bo", 103, "Session.open()V", "ENTER", ""));
        DifferenceReport report = new DifferenceAnalyzer().compare(
                new DifferenceRun("Fail", 0, 110, baseline, List.of()),
                new DifferenceRun("Success", 0, 110, action, List.of()));

        assertEquals(100, report.noiseView(0).commonCalls());
        assertEquals(0, report.noiseView(0).hiddenCalls());
        assertEquals(95, report.noiseView(95).hiddenCalls());
        assertEquals(5, report.noiseView(95).methods().stream()
                .filter(method -> method.subject().equals("Noise.tick()V")).findFirst().orElseThrow().baseline());
        assertEquals(List.of("Check.test()Z", "Session.open()V"), report.noiseView(100).methods().stream()
                .map(DifferenceReport.MethodCalls::subject).toList());
        assertEquals(1, report.differentReturns());
        assertEquals(List.of("Session.open()V"), report.onlyB());
    }

    @Test
    void keepsRepeatedCallCountDifferencesVisible() {
        DifferenceReport report = new DifferenceAnalyzer().compare(
                new DifferenceRun("Baseline", 0, 4, List.of(trace("a", 1, "Tick.run()V", "ENTER", "")), List.of()),
                new DifferenceRun("Action", 0, 4, List.of(trace("b", 1, "Tick.run()V", "ENTER", ""),
                        trace("c", 2, "Tick.run()V", "ENTER", "")), List.of()));
        assertEquals(0, report.noiseView(100).hiddenCalls());
        assertEquals(2, report.noiseView(100).methods().get(0).action());
        assertEquals("Calls", report.changes().get(0).category());
    }

    @Test
    void emptyRunsHaveNoNoiseOrDivergence() {
        DifferenceRun empty = new DifferenceRun("Empty", 0, 0, List.of(), List.of());
        DifferenceReport report = new DifferenceAnalyzer().compare(empty, empty);
        assertEquals(0, report.noiseView(100).hiddenCalls());
        assertEquals(List.of(), report.noiseView(95).methods());
        org.junit.jupiter.api.Assertions.assertNull(report.firstDivergence());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> report.noiseView(101));
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
