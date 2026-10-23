package dev.whitedev.jpi.mcp;

public final class McpException extends RuntimeException {
    private final McpErrorCode code;

    public McpException(McpErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public McpErrorCode code() { return code; }
    public boolean retryable() { return code == McpErrorCode.SESSION_CHANGED || code == McpErrorCode.OPERATION_TIMEOUT; }
}
