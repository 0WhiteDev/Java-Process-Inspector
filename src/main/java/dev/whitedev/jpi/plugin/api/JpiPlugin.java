package dev.whitedev.jpi.plugin.api;

public interface JpiPlugin {
    String name();

    default String id() {
        return getClass().getName();
    }

    default String version() {
        return "unspecified";
    }

    default int apiVersion() {
        return 1;
    }

    void initialize(JpiContext context) throws Exception;

    default void shutdown() throws Exception {}
}
