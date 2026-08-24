package dev.whitedev.jpi.plugin.api.analysis;

public record AnalysisResult(String title, String content, String contentType) {
    public AnalysisResult {
        title = title == null || title.isBlank() ? "Plugin analysis" : title;
        content = content == null ? "" : content;
        contentType = contentType == null || contentType.isBlank() ? "text/plain" : contentType;
    }

    public AnalysisResult(String title, String content) {
        this(title, content, "text/plain");
    }
}
