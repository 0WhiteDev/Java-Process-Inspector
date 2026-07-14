package dev.whitedev.jpi.agent;

final class TraceFixture {
    String combine(String value, int count) {
        return value + ":" + count;
    }

    String fail(String message) {
        throw new IllegalStateException(message);
    }
}