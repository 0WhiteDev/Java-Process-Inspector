package dev.whitedev.jpi.attach;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum AttachRestriction {
    DISABLED, EXPLICITLY_ENABLED, UNKNOWN;

    public static AttachRestriction inspect(String pid) {
        List<String> arguments = observedArguments(pid);
        return arguments == null ? UNKNOWN : fromCommandArguments(arguments);
    }

    static List<String> observedArguments(String pid) {
        try {
            Optional<ProcessHandle> process = ProcessHandle.of(Long.parseLong(pid));
            if (process.isEmpty() || !process.get().isAlive()) return null;
            ProcessHandle.Info info = process.get().info();
            String command = info.command().orElse("");
            String name = command.isEmpty() ? "" : Path.of(command).getFileName().toString();
            if (!List.of("java", "java.exe", "javaw.exe").contains(name.toLowerCase(java.util.Locale.ROOT))) return null;
            String[] args = info.arguments().orElse(null);
            if (args == null && System.getProperty("os.name", "").startsWith("Windows")) {
                List<String> observed = WindowsProcessArguments.read(process.get().pid());
                if (!process.get().isAlive() || !info.startInstant().equals(process.get().info().startInstant())) return null;
                return observed;
            }
            return args == null ? null : Arrays.asList(args);
        } catch (IllegalArgumentException | SecurityException | LinkageError error) { return null; }
    }

    static AttachRestriction fromCommandArguments(List<String> arguments) {
        List<String> flags = vmArguments(arguments);
        if (flags == null) return UNKNOWN;
        AttachRestriction result = UNKNOWN;
        for (String value : flags) {
            if (value.equals("-XX:+DisableAttachMechanism")) result = DISABLED;
            if (value.equals("-XX:-DisableAttachMechanism")) result = EXPLICITLY_ENABLED;
        }
        return result;
    }

    static List<String> vmArguments(List<String> arguments) {
        List<String> flags = new java.util.ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            String value = arguments.get(index);
            if (value.startsWith("@") || value.startsWith("-XX:Flags=") || value.startsWith("-XX:VMOptionsFile=")) return null;
            if (value.equals("-jar") || value.equals("-m") || value.equals("--module") || value.startsWith("--module=")
                    || value.equals("--") || !value.startsWith("-")) break;
            if (List.of("-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path",
                    "--add-modules", "--limit-modules", "--add-exports", "--add-opens", "--add-reads", "--patch-module").contains(value)) {
                if (++index >= arguments.size()) return null;
            } else flags.add(value);
        }
        return flags;
    }
}
