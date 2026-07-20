package dev.whitedev.jpi.agent.trace;

public final class TraceFixture {
    String combine(String value, int count) {
        return value + ":" + count;
    }

    String fail(String message) {
        throw new IllegalStateException(message);
    }

    String analyze(String input) {
        StringBuilder value = new StringBuilder("https://example.test/api/");
        value.append(input);
        return helper(value.toString());
    }

    String helper(String value) {
        return value.trim();
    }
}