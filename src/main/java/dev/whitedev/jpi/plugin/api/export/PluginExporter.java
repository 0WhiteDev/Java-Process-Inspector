package dev.whitedev.jpi.plugin.api.export;

public interface PluginExporter {
    String id();
    String name();
    String fileExtension();
    void export(ExportRequest request) throws Exception;
}
