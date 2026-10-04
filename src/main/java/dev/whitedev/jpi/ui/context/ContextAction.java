package dev.whitedev.jpi.ui.context;

public enum ContextAction {
    STATIC_USAGES("Find static usages"),
    RUNTIME_USAGES("Find runtime usages"),
    TRACE_METHOD("Trace this method"),
    TRACE_CALLERS("Trace callers"),
    TRACE_CALLEES("Trace callees"),
    CFG("Open CFG"),
    BREAKPOINT("Add breakpoint"),
    WATCH_WRITES("Watch writes"),
    INVESTIGATE("Investigate");

    private final String label;

    ContextAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean supports(AnalysisTarget target) {
        if (target == null) return false;
        return switch (this) {
            case INVESTIGATE -> !target.query().isBlank();
            case STATIC_USAGES -> target.kind() != AnalysisTarget.Kind.METHOD
                    || target.descriptor().startsWith("(");
            case RUNTIME_USAGES -> target.kind() == AnalysisTarget.Kind.CLASS
                    || target.kind() == AnalysisTarget.Kind.METHOD && target.descriptor().startsWith("(");
            case WATCH_WRITES -> target.kind() == AnalysisTarget.Kind.FIELD;
            default -> target.kind() == AnalysisTarget.Kind.METHOD && !target.owner().isBlank()
                    && !target.member().isBlank() && target.descriptor().startsWith("(");
        };
    }
}
