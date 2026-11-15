package dev.whitedev.jpi.search;

public enum SearchCategory {
    CLASSES("Classes"), METHODS("Methods"), FIELDS("Fields"), STRINGS("Strings"),
    MAPPINGS("Mappings"), TIMELINE("Timeline events"), NOTES("Notes"), NETWORK("Network events"), EXCEPTIONS("Exceptions");

    private final String label;
    SearchCategory(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
