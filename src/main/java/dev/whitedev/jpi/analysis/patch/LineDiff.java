package dev.whitedev.jpi.analysis.patch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LineDiff {
    private static final int WINDOW = 96;

    private LineDiff() {}

    public static List<Line> compare(String before, String after) {
        if (before.length() > 2_000_000 || after.length() > 2_000_000) {
            throw new IllegalArgumentException("Diff input exceeds the 2 million character limit");
        }
        String[] a = lines(before);
        String[] b = lines(after);
        List<Line> result = new ArrayList<>();
        int left = 0;
        int right = 0;
        while (left < a.length || right < b.length) {
            if (left < a.length && right < b.length && a[left].equals(b[right])) {
                result.add(new Line(' ', a[left++]));
                right++;
                continue;
            }
            Map<String, Integer> candidates = new HashMap<>();
            for (int index = left; index < Math.min(a.length, left + WINDOW); index++) candidates.putIfAbsent(a[index], index);
            int matchA = -1;
            int matchB = -1;
            int distance = Integer.MAX_VALUE;
            for (int index = right; index < Math.min(b.length, right + WINDOW); index++) {
                Integer found = candidates.get(b[index]);
                if (found != null && found - left + index - right < distance) {
                    matchA = found;
                    matchB = index;
                    distance = found - left + index - right;
                }
            }
            int endA = matchA >= 0 ? matchA : Math.min(a.length, left + 1);
            int endB = matchB >= 0 ? matchB : Math.min(b.length, right + 1);
            while (left < endA) result.add(new Line('-', a[left++]));
            while (right < endB) result.add(new Line('+', b[right++]));
        }
        return List.copyOf(result);
    }

    public static String unified(String before, String after, String leftName, String rightName, boolean hideCommon) {
        List<Line> lines = compare(before, after);
        boolean[] visible = new boolean[lines.size()];
        boolean changed = false;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).kind() == ' ') continue;
            changed = true;
            if (!hideCommon) continue;
            int start = Math.max(0, index - 3);
            int end = Math.min(lines.size(), index + 4);
            for (int nearby = start; nearby < end; nearby++) visible[nearby] = true;
        }
        if (!changed) return "No differences.\n";
        if (!hideCommon) java.util.Arrays.fill(visible, true);
        StringBuilder out = new StringBuilder("--- " + leftName + "\n+++ " + rightName + "\n");
        int left = 1;
        int right = 1;
        int index = 0;
        while (index < lines.size()) {
            if (!visible[index]) {
                Line line = lines.get(index++);
                if (line.kind() != '+') left++;
                if (line.kind() != '-') right++;
                continue;
            }
            int end = index;
            int countA = 0;
            int countB = 0;
            while (end < lines.size() && visible[end]) {
                Line line = lines.get(end++);
                if (line.kind() != '+') countA++;
                if (line.kind() != '-') countB++;
            }
            out.append("@@ -").append(countA == 0 ? left - 1 : left).append(',').append(countA)
                    .append(" +").append(countB == 0 ? right - 1 : right).append(',').append(countB).append(" @@\n");
            while (index < end) {
                Line line = lines.get(index++);
                out.append(line.kind()).append(line.text()).append('\n');
                if (line.kind() != '+') left++;
                if (line.kind() != '-') right++;
            }
        }
        return out.toString();
    }

    private static String[] lines(String text) {
        if (text.isEmpty()) return new String[0];
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    public record Line(char kind, String text) {
    }
}
