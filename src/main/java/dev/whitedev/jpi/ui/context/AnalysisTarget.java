package dev.whitedev.jpi.ui.context;

public record AnalysisTarget(Kind kind, String identifier, String owner, String member,
                             String descriptor, String value) {
    public enum Kind { CLASS, METHOD, FIELD, CONSTANT }

    public static AnalysisTarget method(String identifier, String owner, String member, String descriptor) {
        return new AnalysisTarget(Kind.METHOD, identifier, owner, member, descriptor, "");
    }

    public static AnalysisTarget field(String identifier, String owner, String member, String descriptor) {
        return new AnalysisTarget(Kind.FIELD, identifier, owner, member, descriptor, "");
    }

    public static AnalysisTarget type(String identifier, String owner) {
        return new AnalysisTarget(Kind.CLASS, identifier, owner, "", "", "");
    }

    public static AnalysisTarget constant(String value) {
        return new AnalysisTarget(Kind.CONSTANT, "", "", "", "", value);
    }

    public String query() {
        return kind == Kind.CONSTANT ? value : owner + (member.isEmpty() ? "" : "." + member);
    }
}
