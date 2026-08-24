package dev.whitedev.jpi.plugin.api.hook;

import java.util.List;

public record HookProfile(String id, String name, String description, List<HookTarget> targets) {
    public HookProfile {
        id = id == null ? "" : id.trim().toUpperCase().replace(' ', '_');
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        targets = targets == null ? List.of() : List.copyOf(targets);
        if (!id.matches("[A-Z][A-Z0-9_]{1,63}")) throw new IllegalArgumentException("Invalid hook profile id: " + id);
        if (name.isEmpty()) throw new IllegalArgumentException("Hook profile name is required");
        if (targets.isEmpty() || targets.size() > 256) throw new IllegalArgumentException("Hook profile needs 1 to 256 targets");
    }
}
