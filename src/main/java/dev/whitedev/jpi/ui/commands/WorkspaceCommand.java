package dev.whitedev.jpi.ui.commands;

import javax.swing.KeyStroke;
import java.util.function.Consumer;
import java.util.function.Function;

public record WorkspaceCommand(String id, String title, String keywords, String shortcutLabel, KeyStroke shortcut,
                                Function<CommandContext, String> unavailable, Consumer<CommandContext> action) {
    public String reason(CommandContext context) { return unavailable.apply(context); }
}
