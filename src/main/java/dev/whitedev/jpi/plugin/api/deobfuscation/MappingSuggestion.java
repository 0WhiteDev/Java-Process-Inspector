package dev.whitedev.jpi.plugin.api.deobfuscation;

public record MappingSuggestion(MappingType type, String owner, String originalName, String descriptor,
                                int parameterIndex, String suggestedName, String reason, double confidence) {
    public MappingSuggestion {
        if (type == null) throw new IllegalArgumentException("Mapping type is required");
        owner = value(owner);
        originalName = value(originalName);
        descriptor = value(descriptor);
        suggestedName = value(suggestedName).trim();
        reason = value(reason);
        if (originalName.isEmpty()) throw new IllegalArgumentException("Original name is required");
        if (suggestedName.isEmpty()) throw new IllegalArgumentException("Suggested name is required");
        if (!validName(suggestedName)) throw new IllegalArgumentException("Suggested name is not a valid Java name: " + suggestedName);
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Confidence must be between 0 and 1");
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static boolean validName(String value) {
        for (String part : value.split("\\.", -1)) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) return false;
            for (int index = 1; index < part.length(); index++) {
                if (!Character.isJavaIdentifierPart(part.charAt(index))) return false;
            }
        }
        return true;
    }
}
