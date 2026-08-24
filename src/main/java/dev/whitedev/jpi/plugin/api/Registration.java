package dev.whitedev.jpi.plugin.api;

@FunctionalInterface
public interface Registration extends AutoCloseable {
    @Override void close();
}
