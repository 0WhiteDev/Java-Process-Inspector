package dev.whitedev.jpi.mcp.dto;

public record ClassSummary(String classId, String name, String loaderId, String loaderName,
                           String module, boolean modifiable, boolean captured, String kind) {}
