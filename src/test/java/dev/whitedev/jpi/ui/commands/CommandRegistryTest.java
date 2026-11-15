package dev.whitedev.jpi.ui.commands;

import dev.whitedev.jpi.ui.context.AnalysisTarget;
import org.junit.jupiter.api.Test;
import javax.swing.KeyStroke;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CommandRegistryTest {
    @Test void filtersCommandsAndRechecksAvailabilityBeforeExecutingTheCapturedSelection() {
        CommandRegistry registry = new CommandRegistry();
        AtomicBoolean available = new AtomicBoolean(true);
        AtomicReference<AnalysisTarget> received = new AtomicReference<>();
        WorkspaceCommand trace = new WorkspaceCommand("trace", "Trace selected method", "probe live", "Ctrl+T", KeyStroke.getKeyStroke("ctrl T"),
                context -> available.get() ? "" : "Session changed", context -> received.set(context.target()));
        WorkspaceCommand view = new WorkspaceCommand("view", "Open live tracer", "trace view", "", null, context -> "", context -> {});
        registry.add(trace);
        registry.add(view);
        AnalysisTarget target = AnalysisTarget.method("c:77", "com.foo.Auth", "validate", "(Ljava/lang/String;)Z");
        CommandContext context = new CommandContext(null, target, "Loaded classes", null);
        assertEquals(List.of(trace, view), registry.search("> trace", context));
        assertEquals(List.of(trace), registry.search("probe selected", context));
        assertEquals("", registry.execute(trace, context));
        assertSame(target, received.get());
        received.set(null);
        available.set(false);
        assertEquals(List.of(view, trace), registry.search("trace", context));
        assertEquals("Session changed", registry.execute(trace, context));
        assertNull(received.get());
        assertThrows(IllegalArgumentException.class, () -> registry.add(trace));
        registry.removePrefix("view");
        assertEquals("Command is no longer available", registry.execute(view, context));
        assertEquals(1, registry.all().size());
    }

    @Test void distinguishesAllKeyboardModifiersAndLeavesUnregisteredKeysUntouched() {
        CommandRegistry registry = new CommandRegistry();
        List<String> shortcuts = List.of("ctrl P", "ctrl shift F", "ctrl B", "ctrl T", "ctrl G", "ctrl alt X", "F7", "F8", "shift F8", "F9", "ctrl shift C");
        for (String shortcut : shortcuts) registry.add(new WorkspaceCommand(shortcut, shortcut, "", shortcut,
                KeyStroke.getKeyStroke(shortcut), context -> "", context -> {}));
        for (String shortcut : shortcuts) {
            WorkspaceCommand command = CommandShortcuts.matching(registry, KeyStroke.getKeyStroke(shortcut));
            assertNotNull(command, shortcut);
            assertEquals(shortcut, command.id());
        }
        assertNull(CommandShortcuts.matching(registry, KeyStroke.getKeyStroke("alt F8")));
        assertNotEquals(CommandShortcuts.matching(registry, KeyStroke.getKeyStroke("F8")), CommandShortcuts.matching(registry, KeyStroke.getKeyStroke("shift F8")));
    }
}
