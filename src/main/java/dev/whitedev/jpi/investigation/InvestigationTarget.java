package dev.whitedev.jpi.investigation;

public record InvestigationTarget(String classIdentifier, String className, String methodName,
                                  String descriptor, String matchedConstant, int confidence,
                                  long runtimeHits) {
    public String methodKey() {
        return className + "\u0000" + methodName + "\u0000" + descriptor;
    }

    public String displayName() {
        return className + "." + methodName + descriptor;
    }
}
