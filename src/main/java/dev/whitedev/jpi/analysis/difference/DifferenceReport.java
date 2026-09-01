package dev.whitedev.jpi.analysis.difference;

import java.util.List;

public record DifferenceReport(int commonMethods, List<String> onlyA, List<String> onlyB,
                               int differentBranches, int differentReturns, int differentApiCalls,
                               Divergence firstDivergence, List<Change> changes) {
    public DifferenceReport {
        onlyA = List.copyOf(onlyA);
        onlyB = List.copyOf(onlyB);
        changes = List.copyOf(changes);
    }

    public record Divergence(String category, String subject, String runA, String runB) {
    }

    public record Change(String category, String subject, String runA, String runB) {
    }
}
