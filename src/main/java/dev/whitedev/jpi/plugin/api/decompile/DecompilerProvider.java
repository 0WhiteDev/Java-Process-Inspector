package dev.whitedev.jpi.plugin.api.decompile;

public interface DecompilerProvider {
    String id();
    String name();
    String decompile(DecompilationRequest request) throws Exception;
}
