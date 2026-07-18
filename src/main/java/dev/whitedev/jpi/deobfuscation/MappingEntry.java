package dev.whitedev.jpi.deobfuscation;

public final class MappingEntry {
    private final MappingKind kind;
    private final String owner;
    private final String originalName;
    private final String descriptor;
    private final int parameterIndex;
    private final int access;
    private String mappedName;
    private String comment;
    private String tags;
    private String color;
    private boolean enabled;

    public MappingEntry(MappingKind kind, String owner, String originalName, String descriptor,
                        int parameterIndex, int access) {
        this(kind, owner, originalName, descriptor, parameterIndex, access, "", "", "", "", true);
    }

    public MappingEntry(MappingKind kind, String owner, String originalName, String descriptor,
                        int parameterIndex, int access, String mappedName, String comment,
                        String tags, String color, boolean enabled) {
        this.kind = kind;
        this.owner = value(owner);
        this.originalName = value(originalName);
        this.descriptor = value(descriptor);
        this.parameterIndex = parameterIndex;
        this.access = access;
        this.mappedName = value(mappedName);
        this.comment = value(comment);
        this.tags = value(tags);
        this.color = value(color);
        this.enabled = enabled;
    }

    public MappingKind kind() { return kind; }
    public String owner() { return owner; }
    public String originalName() { return originalName; }
    public String descriptor() { return descriptor; }
    public int parameterIndex() { return parameterIndex; }
    public int access() { return access; }
    public String mappedName() { return mappedName; }
    public String comment() { return comment; }
    public String tags() { return tags; }
    public String color() { return color; }
    public boolean enabled() { return enabled; }

    public void setMappedName(String value) { mappedName = value(value).trim(); }
    public void setComment(String value) { comment = value(value); }
    public void setTags(String value) { tags = value(value); }
    public void setColor(String value) { color = value(value).trim(); }
    public void setEnabled(boolean value) { enabled = value; }

    public String key() {
        return kind.name() + '\u0000' + owner + '\u0000' + originalName + '\u0000'
                + descriptor + '\u0000' + parameterIndex;
    }

    public MappingEntry copy() {
        return new MappingEntry(kind, owner, originalName, descriptor, parameterIndex, access,
                mappedName, comment, tags, color, enabled);
    }

    public String location() {
        if (kind == MappingKind.PACKAGE || kind == MappingKind.CLASS) return originalName;
        String member = owner + "." + originalName;
        if (kind == MappingKind.PARAMETER) return member + descriptor + " arg" + parameterIndex;
        return member + descriptor;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}