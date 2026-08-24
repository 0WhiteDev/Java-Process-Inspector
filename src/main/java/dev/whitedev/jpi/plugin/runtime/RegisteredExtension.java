package dev.whitedev.jpi.plugin.runtime;

public record RegisteredExtension<T>(String pluginId, T extension) {}
