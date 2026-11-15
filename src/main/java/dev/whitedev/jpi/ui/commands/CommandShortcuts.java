package dev.whitedev.jpi.ui.commands;

import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowFocusListener;
import java.awt.event.WindowEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public final class CommandShortcuts implements AutoCloseable {
    private final Set<Integer> pressed = new HashSet<>();
    private final KeyEventDispatcher dispatcher;
    private final Window window;
    private final WindowFocusListener focusListener;

    public CommandShortcuts(Window window, CommandRegistry registry, Consumer<WorkspaceCommand> execute) {
        this.window = window;
        focusListener = new WindowFocusListener() {
            @Override public void windowGainedFocus(WindowEvent event) {}
            @Override public void windowLostFocus(WindowEvent event) { pressed.clear(); }
        };
        window.addWindowFocusListener(focusListener);
        dispatcher = event -> {
            if (event.getID() == KeyEvent.KEY_RELEASED) { pressed.remove(event.getKeyCode()); return false; }
            if (event.getID() != KeyEvent.KEY_PRESSED || event.isConsumed() || event.getComponent() == null
                    || (event.getComponent() instanceof Window selected ? selected : SwingUtilities.getWindowAncestor(event.getComponent())) != window) return false;
            WorkspaceCommand command = matching(registry, KeyStroke.getKeyStrokeForEvent(event));
            if (command == null) return false;
            if (pressed.add(event.getKeyCode())) execute.accept(command);
            event.consume();
            return true;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
    }

    public static WorkspaceCommand matching(CommandRegistry registry, KeyStroke stroke) {
        return registry.all().stream().filter(command -> stroke.equals(command.shortcut())).findFirst().orElse(null);
    }

    @Override public void close() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
        window.removeWindowFocusListener(focusListener);
        pressed.clear();
    }
}
