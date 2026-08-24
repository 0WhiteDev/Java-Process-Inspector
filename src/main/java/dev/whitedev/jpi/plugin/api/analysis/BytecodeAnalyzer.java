package dev.whitedev.jpi.plugin.api.analysis;

public interface BytecodeAnalyzer {
    String id();
    String name();
    AnalysisResult analyze(BytecodeTarget target) throws Exception;
}
