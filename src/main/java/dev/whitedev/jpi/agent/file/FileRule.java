package dev.whitedev.jpi.agent.file;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class FileRule {
    final String id;
    final Set<FileOperation> operations;
    final String pathPattern;
    final String callerPattern;
    final FileDecision decision;
    final String redirectRoot;
    final Pattern pathMatcher;
    final Pattern callerMatcher;
    final int specificity;

    public FileRule(String id, Set<FileOperation> operations, String pathPattern, String callerPattern,
                    FileDecision decision, String redirectRoot) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Rule ID is required");
        if (decision == null) throw new IllegalArgumentException("Rule decision is required");
        this.id = id.trim();
        this.operations = operations == null || operations.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.allOf(FileOperation.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(operations));
        this.pathPattern = normalizePattern(pathPattern);
        this.callerPattern = normalizePattern(callerPattern);
        this.decision = decision;
        this.redirectRoot = redirectRoot == null ? "" : redirectRoot.trim();
        if (decision == FileDecision.REDIRECT && this.redirectRoot.isEmpty()) {
            throw new IllegalArgumentException("Redirect rules require a destination directory");
        }
        pathMatcher = glob(this.pathPattern);
        callerMatcher = glob(this.callerPattern);
        specificity = specificity(this.pathPattern) * 2 + specificity(this.callerPattern);
    }

    boolean matches(FileOperation operation, String path, String caller) {
        return operations.contains(operation) && pathMatcher.matcher(path).matches()
                && callerMatcher.matcher(caller).matches();
    }

    private static String normalizePattern(String value) {
        return value == null || value.trim().isEmpty() ? "*" : value.trim();
    }

    private static int specificity(String value) {
        int score = value.indexOf('*') < 0 && value.indexOf('?') < 0 ? 100000 : 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '*' && character != '?') score++;
        }
        return score;
    }

    private static Pattern glob(String value) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '*') {
                if (index + 1 < value.length() && value.charAt(index + 1) == '*') index++;
                regex.append(".*");
            } else if (character == '?') {
                regex.append('.');
            } else {
                if (character == '\\' || character == '/') regex.append("[\\\\/]");
                else {
                    if (".^$|()[]{}+".indexOf(character) >= 0) regex.append('\\');
                    regex.append(character);
                }
            }
        }
        return Pattern.compile(regex.append('$').toString(), Pattern.CASE_INSENSITIVE);
    }
}
