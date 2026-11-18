package dev.whitedev.jpi.search;

public record SearchHit(SearchCategory category, String key, String title, String location, String details,
                        String classId, String className, String member, String descriptor, String value) {
    public SearchHit {
        title = bounded(title, 4096);
        location = bounded(location, 4096);
        details = bounded(details, 65536);
    }

    private static String bounded(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit - 3) + "..." : text;
    }
}
