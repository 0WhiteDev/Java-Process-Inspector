package dev.whitedev.jpi.analysis.difference;

import java.util.List;
import java.util.ArrayList;

public record DifferenceReport(int commonMethods, List<String> onlyA, List<String> onlyB,
                               int differentBranches, int differentReturns, int differentApiCalls,
                               Divergence firstDivergence, List<Change> changes, List<MethodCalls> methods) {
    public DifferenceReport {
        onlyA = List.copyOf(onlyA);
        onlyB = List.copyOf(onlyB);
        changes = List.copyOf(changes);
        methods = List.copyOf(methods);
    }

    public NoiseView noiseView(int percent) {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("Percentage must be between 0 and 100");
        long total = methods.stream().filter(MethodCalls::unchanged).mapToLong(MethodCalls::baseline).sum();
        long hidden = total * percent / 100;
        long remaining = hidden;
        List<MethodCalls> visible = new ArrayList<>();
        for (MethodCalls method : methods) {
            long removed = method.unchanged() ? Math.min(remaining, method.baseline()) : 0;
            remaining -= removed;
            long a = method.baseline() - removed;
            long b = method.action() - removed;
            if (a != 0 || b != 0) visible.add(new MethodCalls(method.subject(), a, b, method.unchanged()));
        }
        return new NoiseView(hidden, total, List.copyOf(visible));
    }

    public record MethodCalls(String subject, long baseline, long action, boolean unchanged) {
    }

    public record NoiseView(long hiddenCalls, long commonCalls, List<MethodCalls> methods) {
    }

    public record Divergence(String category, String subject, String runA, String runB) {
    }

    public record Change(String category, String subject, String runA, String runB) {
    }
}
