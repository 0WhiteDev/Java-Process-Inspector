package dev.whitedev.jpi.fingerprint;

import dev.whitedev.jpi.debug.BreakpointSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public record TargetFingerprint(long capturedAt, String mainClass, String javaVersion, int loadedCount,
                                boolean truncated, List<Definition> definitions, List<BreakpointSpec> breakpoints) {
    public TargetFingerprint {
        if (capturedAt < 0 || loadedCount < 0 || definitions.size() > 50000 || breakpoints.size() > 1000) {
            throw new IllegalArgumentException("Invalid fingerprint counts or timestamp");
        }
        definitions = List.copyOf(definitions);
        breakpoints = List.copyOf(breakpoints);
    }

    public record Member(String name, String descriptor, boolean method, List<Integer> lines) {
        public Member { lines = List.copyOf(lines); }
    }

    public record Definition(String key, String name, String loader, String hash, String status, List<Member> members) {
        public Definition { members = List.copyOf(members); }
        public boolean inspected() { return !hash.isEmpty(); }
    }

    public String id() {
        StringBuilder canonical = new StringBuilder(mainClass).append('\n').append(javaVersion).append('\n')
                .append(loadedCount).append('\n').append(truncated).append('\n');
        definitions.stream().sorted(Comparator.comparing(Definition::key).thenComparing(Definition::hash))
                .forEach(type -> canonical.append(type.key()).append('\t').append(type.hash()).append('\n'));
        try {
            return "JPI-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public static TargetFingerprint parse(String wire) {
        if (wire.length() > 8 * 1024 * 1024) throw new IllegalArgumentException("Fingerprint exceeds 8 MiB");
        String[] rows = wire.split("\n");
        String[] header = rows[0].split("\t", -1);
        if (header.length != 6 || !header[0].equals("H") || !header[1].equals("1")) {
            throw new IllegalArgumentException("Unsupported fingerprint format");
        }
        List<Definition> definitions = new ArrayList<>();
        List<Member> members = new ArrayList<>();
        String[] current = null;
        boolean truncated = false;
        List<BreakpointSpec> breakpoints = new ArrayList<>();
        for (int index = 1; index < rows.length; index++) {
            String[] values = rows[index].split("\t", -1);
            if (values[0].equals("T")) { truncated = true; continue; }
            if (values[0].equals("B") && values.length == 9) {
                if (breakpoints.size() >= 1000) throw new IllegalArgumentException("Too many saved breakpoints");
                breakpoints.add(new BreakpointSpec(decode(values[1]), decode(values[2]), decode(values[3]),
                        values[4].isEmpty() ? null : Integer.valueOf(values[4]), values[5].isEmpty() ? null : Long.valueOf(values[5]),
                        BreakpointSpec.Type.valueOf(values[6]), BreakpointSpec.SuspendPolicy.valueOf(values[7]), Boolean.parseBoolean(values[8])));
                continue;
            }
            if (values[0].equals("C") && values.length == 6) {
                if (current != null) definitions.add(definition(current, members));
                current = values;
                members = new ArrayList<>();
            } else if ((values[0].equals("M") || values[0].equals("F")) && values.length == 5 && current != null) {
                if (!values[1].equals(current[1])) throw new IllegalArgumentException("Member owner does not match definition");
                List<Integer> lines = new ArrayList<>();
                for (String line : values[4].split(",")) if (!line.isEmpty()) lines.add(Integer.parseInt(line));
                members.add(new Member(decode(values[2]), decode(values[3]), values[0].equals("M"), lines));
            } else throw new IllegalArgumentException("Invalid fingerprint row");
        }
        if (current != null) definitions.add(definition(current, members));
        if (definitions.size() > 50000) throw new IllegalArgumentException("Too many fingerprint definitions");
        return new TargetFingerprint(Long.parseLong(header[2]), decode(header[3]), decode(header[4]),
                Integer.parseInt(header[5]), truncated, definitions, breakpoints);
    }

    public static String withBreakpoints(String wire, List<BreakpointSpec> breakpoints) {
        if (!parse(wire).breakpoints().isEmpty()) throw new IllegalArgumentException("Capture already contains saved breakpoints");
        if (breakpoints.size() > 1000) throw new IllegalArgumentException("At most 1000 breakpoints can be saved");
        StringBuilder result = new StringBuilder(wire);
        for (var breakpoint : breakpoints) {
            result.append("B\t").append(encode(breakpoint.className())).append('\t').append(encode(breakpoint.methodName()))
                    .append('\t').append(encode(breakpoint.descriptor())).append('\t')
                    .append(breakpoint.sourceLine() == null ? "" : breakpoint.sourceLine()).append('\t')
                    .append(breakpoint.codeIndex() == null ? "" : breakpoint.codeIndex()).append('\t')
                    .append(breakpoint.type()).append('\t').append(breakpoint.suspendPolicy()).append('\t').append(breakpoint.enabled()).append('\n');
        }
        String value = result.toString();
        if (value.length() > 8 * 1024 * 1024) throw new IllegalArgumentException("Fingerprint exceeds 8 MiB");
        return value;
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Definition definition(String[] values, List<Member> members) {
        if (!values[4].isEmpty() && !values[4].matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid bytecode hash");
        return new Definition(decode(values[1]), decode(values[2]), decode(values[3]), values[4], decode(values[5]), members);
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
