package dev.whitedev.jpi.analysis.difference;

import dev.whitedev.jpi.ui.timeline.TimelineEvent;

import java.util.List;

public record DifferenceRun(String name, long startedAt, long stoppedAt,
                            List<TimelineEvent> events, List<CfgTransition> transitions) {
    public DifferenceRun {
        name = name == null || name.isBlank() ? "Run" : name.trim();
        events = events == null ? List.of() : List.copyOf(events);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
    }
}
