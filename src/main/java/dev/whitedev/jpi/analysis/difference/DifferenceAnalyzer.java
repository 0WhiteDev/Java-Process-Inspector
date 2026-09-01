package dev.whitedev.jpi.analysis.difference;

import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DifferenceAnalyzer {
    private static final int RESYNC_WINDOW = 48;

    public DifferenceReport compare(DifferenceRun runA, DifferenceRun runB) {
        Set<String> methodsA = methods(runA.events());
        Set<String> methodsB = methods(runB.events());
        List<String> onlyA = difference(methodsA, methodsB);
        List<String> onlyB = difference(methodsB, methodsA);
        Set<String> common = new LinkedHashSet<>(methodsA);
        common.retainAll(methodsB);

        List<DifferenceReport.Change> changes = new ArrayList<>();
        DifferenceReport.Divergence branchDivergence = compareTransitions(runA.transitions(), runB.transitions(), changes);
        int branchChanges = count(changes, "Branch");
        compareValues("Return", completions(runA.events()), completions(runB.events()), changes);
        int returnChanges = count(changes, "Return");
        compareSequence("API call", apiCalls(runA.events()), apiCalls(runB.events()), changes);
        int apiChanges = count(changes, "API call");

        DifferenceReport.Divergence first = branchDivergence;
        if (first == null) first = firstExecutionDivergence(runA, runB);
        return new DifferenceReport(common.size(), onlyA, onlyB, branchChanges, returnChanges,
                apiChanges, first, changes);
    }

    private DifferenceReport.Divergence compareTransitions(List<CfgTransition> left,
                                                            List<CfgTransition> right,
                                                            List<DifferenceReport.Change> changes) {
        Map<String, List<String>> a = transitionRoutes(left);
        Map<String, List<String>> b = transitionRoutes(right);
        DifferenceReport.Divergence first = null;
        Set<String> subjects = union(a.keySet(), b.keySet());
        for (String subject : subjects) {
            List<String> valuesA = a.getOrDefault(subject, List.of());
            List<String> valuesB = b.getOrDefault(subject, List.of());
            int mismatch = firstMismatch(valuesA, valuesB);
            if (first == null && mismatch >= 0) {
                first = new DifferenceReport.Divergence("Branch", subject,
                        value(valuesA, mismatch), value(valuesB, mismatch));
            }
            compareOrdered("Branch", subject, valuesA, valuesB, changes);
        }
        return first;
    }

    private void compareValues(String category, Map<String, List<String>> left,
                               Map<String, List<String>> right, List<DifferenceReport.Change> changes) {
        for (String subject : union(left.keySet(), right.keySet())) {
            List<String> valuesA = left.getOrDefault(subject, List.of());
            List<String> valuesB = right.getOrDefault(subject, List.of());
            int size = Math.max(valuesA.size(), valuesB.size());
            for (int index = 0; index < size; index++) {
                String valueA = value(valuesA, index);
                String valueB = value(valuesB, index);
                if (!valueA.equals(valueB)) {
                    changes.add(new DifferenceReport.Change(category, subject, valueA, valueB));
                }
            }
        }
    }

    private void compareSequence(String category, List<String> left, List<String> right,
                                 List<DifferenceReport.Change> changes) {
        compareOrdered(category, "ordered calls", left, right, changes);
    }

    private void compareOrdered(String category, String subject, List<String> left, List<String> right,
                                List<DifferenceReport.Change> changes) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.size() || rightIndex < right.size()) {
            if (leftIndex < left.size() && rightIndex < right.size()
                    && left.get(leftIndex).equals(right.get(rightIndex))) {
                leftIndex++;
                rightIndex++;
                continue;
            }
            Match match = resync(left, right, leftIndex, rightIndex);
            int leftEnd = match == null
                    ? rightIndex >= right.size() ? left.size() : Math.min(left.size(), leftIndex + 1)
                    : match.left;
            int rightEnd = match == null
                    ? leftIndex >= left.size() ? right.size() : Math.min(right.size(), rightIndex + 1)
                    : match.right;
            changes.add(new DifferenceReport.Change(category, subject,
                    range(left, leftIndex, leftEnd), range(right, rightIndex, rightEnd)));
            leftIndex = leftEnd;
            rightIndex = rightEnd;
        }
    }

    private static Match resync(List<String> left, List<String> right, int leftIndex, int rightIndex) {
        Match best = null;
        int leftLimit = Math.min(left.size(), leftIndex + RESYNC_WINDOW + 1);
        int rightLimit = Math.min(right.size(), rightIndex + RESYNC_WINDOW + 1);
        for (int a = leftIndex; a < leftLimit; a++) {
            for (int b = rightIndex; b < rightLimit; b++) {
                if (a == leftIndex && b == rightIndex || !left.get(a).equals(right.get(b))) continue;
                int distance = a - leftIndex + b - rightIndex;
                if (best == null || distance < best.distance) best = new Match(a, b, distance);
            }
        }
        return best;
    }

    private static int firstMismatch(List<String> left, List<String> right) {
        int size = Math.max(left.size(), right.size());
        for (int index = 0; index < size; index++) {
            if (!value(left, index).equals(value(right, index))) return index;
        }
        return -1;
    }

    private static String range(List<String> values, int start, int end) {
        if (start >= end) return "<not observed>";
        StringBuilder output = new StringBuilder();
        int visibleEnd = Math.min(end, start + 6);
        for (int index = start; index < visibleEnd; index++) {
            if (output.length() > 0) output.append(" | ");
            output.append(values.get(index));
        }
        if (visibleEnd < end) output.append(" | ... ").append(end - visibleEnd).append(" more");
        return output.toString();
    }

    private DifferenceReport.Divergence firstExecutionDivergence(DifferenceRun left, DifferenceRun right) {
        List<Token> a = execution(left);
        List<Token> b = execution(right);
        int size = Math.max(a.size(), b.size());
        for (int index = 0; index < size; index++) {
            Token valueA = index < a.size() ? a.get(index) : null;
            Token valueB = index < b.size() ? b.get(index) : null;
            String renderedA = valueA == null ? "<not observed>" : valueA.value;
            String renderedB = valueB == null ? "<not observed>" : valueB.value;
            if (!renderedA.equals(renderedB)) {
                String subject = valueA != null ? valueA.subject : valueB == null ? "" : valueB.subject;
                String category = valueA != null ? valueA.category : valueB == null ? "Execution" : valueB.category;
                return new DifferenceReport.Divergence(category, subject, renderedA, renderedB);
            }
        }
        return null;
    }

    private static Set<String> methods(List<TimelineEvent> events) {
        Set<String> methods = new LinkedHashSet<>();
        for (TimelineEvent event : events) {
            if (event.source() == TimelineSource.TRACE && "ENTER".equals(event.phase())
                    && !event.subject().isEmpty()) methods.add(event.subject());
        }
        return methods;
    }

    private static Map<String, List<String>> completions(List<TimelineEvent> events) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (TimelineEvent event : events) {
            if (event.source() != TimelineSource.TRACE
                    || !("RETURN".equals(event.phase()) || "THROW".equals(event.phase()))) continue;
            values.computeIfAbsent(event.subject(), ignored -> new ArrayList<>())
                    .add(event.phase() + " " + normalized(event.value()));
        }
        return values;
    }

    private static List<String> apiCalls(List<TimelineEvent> events) {
        List<String> values = new ArrayList<>();
        for (TimelineEvent event : events) {
            if (event.source() == TimelineSource.API_HOOK && !event.subject().isEmpty()) {
                values.add(event.subject() + " from " + event.value());
            }
        }
        return values;
    }

    private static List<Token> execution(DifferenceRun run) {
        List<TimedToken> ordered = new ArrayList<>();
        for (TimelineEvent event : run.events()) {
            if (event.subject().isEmpty() || event.phase().isEmpty()) continue;
            if (event.source() == TimelineSource.TRACE || event.source() == TimelineSource.API_HOOK) {
                String value = event.phase() + " " + event.subject();
                if (!event.value().isEmpty() && !"ENTER".equals(event.phase())) {
                    value += " = " + normalized(event.value());
                }
                ordered.add(new TimedToken(event.timestamp(), new Token(
                        event.source() == TimelineSource.TRACE ? "Method" : "API call",
                        event.subject(), value)));
            }
        }
        for (CfgTransition transition : run.transitions()) {
            ordered.add(new TimedToken(transition.timestamp(),
                    new Token("Branch", transition.subject(), transition.route())));
        }
        ordered.sort((left, right) -> Long.compare(left.timestamp, right.timestamp));
        List<Token> values = new ArrayList<>(ordered.size());
        for (TimedToken value : ordered) values.add(value.token);
        return values;
    }

    private static Map<String, List<String>> transitionRoutes(List<CfgTransition> transitions) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (CfgTransition transition : transitions) {
            values.computeIfAbsent(transition.subject(), ignored -> new ArrayList<>()).add(transition.route());
        }
        return values;
    }

    private static List<String> difference(Set<String> left, Set<String> right) {
        List<String> result = new ArrayList<>(left);
        result.removeAll(right);
        return List.copyOf(result);
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.addAll(right);
        return result;
    }

    private static String value(List<String> values, int index) {
        return index < values.size() ? values.get(index) : "<not observed>";
    }

    private static int count(List<DifferenceReport.Change> changes, String category) {
        int count = 0;
        for (DifferenceReport.Change change : changes) if (category.equals(change.category())) count++;
        return count;
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) return "<empty>";
        String compact = value.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 237) + "...";
    }

    private record Token(String category, String subject, String value) {
    }

    private record TimedToken(long timestamp, Token token) {
    }

    private record Match(int left, int right, int distance) {
    }
}
