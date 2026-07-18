package dev.whitedev.jpi.ui;

import java.util.Locale;

final class LoadedClassInfo {
    final String id;
    final String name;
    final String loader;
    final String module;
    final boolean modifiable;
    final boolean captured;
    final String kind;
    final int byteSize;
    final long capturedAt;

    private LoadedClassInfo(String id, String name, String loader, String module,
                            boolean modifiable, boolean captured, String kind,
                            int byteSize, long capturedAt) {
        this.id = id;
        this.name = name;
        this.loader = loader;
        this.module = module;
        this.modifiable = modifiable;
        this.captured = captured;
        this.kind = kind;
        this.byteSize = byteSize;
        this.capturedAt = capturedAt;
    }

    static LoadedClassInfo parse(String line) {
        String[] values = line.split("\t", -1);
        if (values.length != 9) return null;
        try {
            return new LoadedClassInfo(values[0], values[1], values[2], values[3],
                    Boolean.parseBoolean(values[4]), Boolean.parseBoolean(values[5]),
                    values[6], Integer.parseInt(values[7]), Long.parseLong(values[8]));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    boolean matches(String query) {
        String searchable = (name + " " + loader + " " + module + " " + kind).toLowerCase(Locale.ROOT);
        return searchable.contains(query);
    }

    String display(String visibleName) {
        String marker = captured ? "[B] " : "    ";
        String value = name.equals(visibleName) ? name : visibleName + "  [" + name + "]";
        return marker + value + "  -  " + shortLoader();
    }

    @Override public String toString() {
        return display(name);
    }

    private String shortLoader() {
        if ("bootstrap".equals(loader)) return loader;
        int packageSeparator = loader.lastIndexOf('.');
        return packageSeparator < 0 ? loader : loader.substring(packageSeparator + 1);
    }
}
