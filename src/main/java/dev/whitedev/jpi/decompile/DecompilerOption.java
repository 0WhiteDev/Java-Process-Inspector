package dev.whitedev.jpi.decompile;

import dev.whitedev.jpi.plugin.api.decompile.DecompilerProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class DecompilerOption {
    private final String id;
    private final String name;
    private final DecompilerEngine engine;
    private final DecompilerProvider provider;

    private DecompilerOption(String id, String name, DecompilerEngine engine, DecompilerProvider provider) {
        this.id = id;
        this.name = name;
        this.engine = engine;
        this.provider = provider;
    }

    public static List<DecompilerOption> builtIns() {
        return Arrays.stream(DecompilerEngine.values())
                .map(value -> new DecompilerOption("jpi." + value.name().toLowerCase(), value.toString(), value, null))
                .toList();
    }

    public static DecompilerOption plugin(String pluginId, DecompilerProvider provider) {
        if (provider == null) throw new IllegalArgumentException("Decompiler provider is required");
        return new DecompilerOption(pluginId + ":" + provider.id(), provider.name(), null, provider);
    }

    public String id() { return id; }
    DecompilerEngine engine() { return engine; }
    DecompilerProvider provider() { return provider; }
    public boolean plugin() { return provider != null; }

    @Override public String toString() { return name; }
    @Override public boolean equals(Object value) {
        return value instanceof DecompilerOption other && id.equals(other.id);
    }
    @Override public int hashCode() { return Objects.hash(id); }
}
