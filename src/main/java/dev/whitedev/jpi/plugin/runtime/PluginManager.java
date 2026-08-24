package dev.whitedev.jpi.plugin.runtime;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.plugin.api.JpiPlugin;
import dev.whitedev.jpi.plugin.api.JpiContext;
import dev.whitedev.jpi.plugin.api.JpiSession;
import dev.whitedev.jpi.plugin.api.Registration;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public final class PluginManager implements AutoCloseable {
    public static final int API_VERSION = 1;

    private final Path pluginDirectory;
    private final Path dataDirectory;
    private final ExtensionRegistry registry = new ExtensionRegistry();
    private final List<LoadedPlugin> loaded = new ArrayList<>();
    private final List<PluginDescriptor> descriptors = new ArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, PluginContext> contexts = new ConcurrentHashMap<>();
    private JpiSession session;

    public PluginManager() {
        this(defaultDirectory());
    }

    public PluginManager(Path pluginDirectory) {
        this.pluginDirectory = pluginDirectory.toAbsolutePath().normalize();
        Path parent = this.pluginDirectory.getParent();
        this.dataDirectory = (parent == null ? this.pluginDirectory : parent).resolve("plugin-data").normalize();
    }

    public ExtensionRegistry extensions() { return registry; }
    public Path pluginDirectory() { return pluginDirectory; }
    public synchronized List<PluginDescriptor> descriptors() {
        List<PluginDescriptor> values = new ArrayList<>();
        for (PluginDescriptor descriptor : descriptors) {
            int count = descriptor.status() == PluginDescriptor.Status.LOADED
                    ? extensionCount(descriptor.id()) : descriptor.extensions();
            values.add(new PluginDescriptor(descriptor.id(), descriptor.name(), descriptor.version(),
                    descriptor.source(), descriptor.status(), descriptor.message(), count));
        }
        return List.copyOf(values);
    }

    public Optional<JpiContext> context(String pluginId) {
        return Optional.ofNullable(contexts.get(pluginId));
    }

    public Registration addListener(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public synchronized void reload() throws IOException {
        unload();
        Files.createDirectories(pluginDirectory);
        Files.createDirectories(dataDirectory);
        List<Path> jars;
        try (var files = Files.list(pluginDirectory)) {
            jars = files.filter(Files::isRegularFile)
                    .filter(value -> value.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(value -> value.getFileName().toString().toLowerCase()))
                    .toList();
        }
        Set<String> pluginIds = new HashSet<>();
        for (Path jar : jars) load(jar, pluginIds);
        changed();
    }

    public synchronized Path install(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source) || !source.getFileName().toString().toLowerCase().endsWith(".jar")) {
            throw new IOException("Choose an existing plugin JAR");
        }
        Files.createDirectories(pluginDirectory);
        Path destination = pluginDirectory.resolve(source.getFileName()).normalize();
        if (!destination.startsWith(pluginDirectory)) throw new IOException("Invalid plugin file name");
        if (Files.exists(destination)) throw new IOException("A plugin JAR with this name is already installed: " + destination.getFileName());
        return Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
    }

    public synchronized void setSession(InspectorSession value) {
        session = value == null ? null : new SessionFacade(value);
        for (LoadedPlugin plugin : loaded) plugin.context.setSession(session);
    }

    private void load(Path jar, Set<String> pluginIds) {
        URLClassLoader loader = null;
        boolean retained = false;
        int discovered = 0;
        try {
            loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, JpiPlugin.class.getClassLoader());
            ServiceLoader<JpiPlugin> services = ServiceLoader.load(JpiPlugin.class, loader);
            Iterator<JpiPlugin> iterator = services.iterator();
            while (iterator.hasNext()) {
                JpiPlugin plugin = iterator.next();
                discovered++;
                String id = plugin.id() == null ? "" : plugin.id().trim();
                if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,127}")) throw new IOException("Invalid plugin id: " + id);
                if (!pluginIds.add(id.toLowerCase())) throw new IOException("Duplicate plugin id: " + id);
                if (plugin.apiVersion() != API_VERSION) {
                    throw new IOException("Plugin " + id + " requires API v" + plugin.apiVersion()
                            + " but JPI provides v" + API_VERSION);
                }
                PluginContext context = new PluginContext(id, dataDirectory, registry);
                context.setSession(session);
                try {
                    plugin.initialize(context);
                } catch (Throwable error) {
                    context.close();
                    try { plugin.shutdown(); } catch (Throwable ignored) {}
                    throw error;
                }
                loaded.add(new LoadedPlugin(plugin, context, loader, jar));
                contexts.put(id, context);
                retained = true;
                descriptors.add(new PluginDescriptor(id, value(plugin.name(), id), value(plugin.version(), "unspecified"),
                        jar, PluginDescriptor.Status.LOADED, "Loaded", context.extensionCount()));
            }
            if (discovered == 0) {
                descriptors.add(new PluginDescriptor("", jar.getFileName().toString(), "", jar,
                        PluginDescriptor.Status.EMPTY, "No JpiPlugin service provider was found", 0));
            }
        } catch (Throwable error) {
            descriptors.add(new PluginDescriptor("", jar.getFileName().toString(), "", jar,
                    PluginDescriptor.Status.FAILED, message(error), 0));
        } finally {
            if (loader != null && !retained) try { loader.close(); } catch (IOException ignored) {}
        }
    }

    private synchronized void unload() {
        for (int index = loaded.size() - 1; index >= 0; index--) {
            LoadedPlugin value = loaded.get(index);
            try { value.plugin.shutdown(); } catch (Throwable ignored) {}
            value.context.close();
            try { value.loader.close(); } catch (IOException ignored) {}
        }
        loaded.clear();
        contexts.clear();
        descriptors.clear();
    }

    @Override public synchronized void close() {
        unload();
        session = null;
        changed();
    }

    private void changed() {
        for (Runnable listener : listeners) listener.run();
    }

    private static Path defaultDirectory() {
        String configured = System.getProperty("jpi.plugins.dir");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        return Path.of(System.getProperty("user.home"), ".jpi", "plugins");
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int extensionCount(String pluginId) {
        int count = 0;
        for (RegisteredExtension<?> value : registry.tabs()) if (pluginId.equals(value.pluginId())) count++;
        for (RegisteredExtension<?> value : registry.analyzers()) if (pluginId.equals(value.pluginId())) count++;
        for (RegisteredExtension<?> value : registry.decompilers()) if (pluginId.equals(value.pluginId())) count++;
        for (RegisteredExtension<?> value : registry.deobfuscators()) if (pluginId.equals(value.pluginId())) count++;
        for (RegisteredExtension<?> value : registry.exporters()) if (pluginId.equals(value.pluginId())) count++;
        for (RegisteredExtension<?> value : registry.hookProfiles()) if (pluginId.equals(value.pluginId())) count++;
        return count;
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private record LoadedPlugin(JpiPlugin plugin, PluginContext context, URLClassLoader loader, Path source) {}
}
