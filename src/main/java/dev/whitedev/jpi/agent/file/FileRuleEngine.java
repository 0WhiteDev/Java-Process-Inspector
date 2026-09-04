package dev.whitedev.jpi.agent.file;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FileRuleEngine {
    private final CopyOnWriteArrayList<FileRule> rules = new CopyOnWriteArrayList<FileRule>();
    private volatile FileDecision policy = FileDecision.ALLOW;
    private volatile FileRule fallback = fallback(FileDecision.ALLOW);

    public FileRule decision(FileOperation operation, String path, String caller) {
        FileRule selected = null;
        for (FileRule rule : rules) {
            if (!rule.matches(operation, path, caller)) continue;
            if (selected == null || rule.specificity > selected.specificity) selected = rule;
        }
        return selected == null ? fallback : selected;
    }

    public void put(FileRule rule) {
        remove(rule.id);
        rules.add(rule);
    }

    public boolean remove(String id) {
        for (FileRule rule : rules) if (rule.id.equals(id)) return rules.remove(rule);
        return false;
    }

    public List<FileRule> rules() {
        List<FileRule> result = new ArrayList<FileRule>(rules);
        Collections.sort(result, new Comparator<FileRule>() {
            @Override public int compare(FileRule left, FileRule right) {
                return left.id.compareTo(right.id);
            }
        });
        return result;
    }

    public void policy(FileDecision value) {
        if (value == FileDecision.REDIRECT) throw new IllegalArgumentException("Global redirect requires an explicit rule");
        policy = value;
        fallback = fallback(value);
    }

    public FileDecision policy() {
        return policy;
    }

    public void clear() {
        rules.clear();
        policy = FileDecision.ALLOW;
        fallback = fallback(FileDecision.ALLOW);
    }

    private static FileRule fallback(FileDecision decision) {
        return new FileRule("global", Collections.<FileOperation>emptySet(), "*", "*", decision, "");
    }
}
