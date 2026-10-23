package dev.whitedev.jpi.mcp.dto;

import java.util.List;

public record MethodSummary(String methodId, String classId, String className, String name,
                            String descriptor, List<String> modifiers, int instructionCount,
                            int exceptionHandlers) {}
