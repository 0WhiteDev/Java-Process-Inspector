package dev.whitedev.jpi.analysis.difference;

import java.util.ArrayList;
import java.util.List;

public record CfgTransition(long sequence, long timestamp, long threadId, int from, int to, String subject) {
    public String route() {
        return block(from) + " -> " + block(to);
    }

    public static List<CfgTransition> parse(String snapshot, String subject) {
        List<CfgTransition> transitions = new ArrayList<>();
        for (String line : snapshot.split("\n")) {
            String[] values = line.split("\t", -1);
            if (values.length != 6 || !"T".equals(values[0])) continue;
            try {
                transitions.add(new CfgTransition(Long.parseLong(values[1]), Long.parseLong(values[2]),
                        Long.parseLong(values[3]), Integer.parseInt(values[4]), Integer.parseInt(values[5]),
                        subject == null ? "" : subject));
            } catch (NumberFormatException ignored) {
            }
        }
        transitions.sort((left, right) -> Long.compare(left.sequence, right.sequence));
        return List.copyOf(transitions);
    }

    private static String block(int value) {
        return value < 0 ? "ENTRY" : "B" + value;
    }
}
