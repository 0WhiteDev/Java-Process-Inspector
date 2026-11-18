package dev.whitedev.jpi.ui.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CommandRegistry {
    private final Map<String, WorkspaceCommand> commands = new LinkedHashMap<>();

    public void add(WorkspaceCommand command) {
        if (commands.putIfAbsent(command.id(), command) != null)
            throw new IllegalArgumentException("Duplicate command " + command.id());
    }

    public List<WorkspaceCommand> all() {
        return List.copyOf(commands.values());
    }

    public void removePrefix(String prefix) {
        commands.keySet().removeIf(id -> id.startsWith(prefix));
    }

    public List<WorkspaceCommand> search(String query, CommandContext context) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.startsWith(">")) needle = needle.substring(1).trim();
        List<WorkspaceCommand> result = new ArrayList<>();
        for (WorkspaceCommand command : commands.values()) {
            String text = (command.title() + " " + command.keywords() + " " + command.shortcutLabel()).toLowerCase(Locale.ROOT);
            boolean matches = true;
            for (String term : needle.split("\\s+"))
                if (!text.contains(term)) {
                    matches = false;
                    break;
                }
            if (matches) result.add(command);
        }
        result.sort(Comparator.comparing((WorkspaceCommand command) -> !command.reason(context).isEmpty()));
        return result;
    }

    public String execute(WorkspaceCommand command, CommandContext context) {
        if (commands.get(command.id()) != command) return "Command is no longer available";
        String reason = command.reason(context);
        if (!reason.isEmpty()) return reason;
        command.action().accept(context);
        return "";
    }
}
