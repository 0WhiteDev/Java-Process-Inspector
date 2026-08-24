package dev.whitedev.jpi.agent.hook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ApiHookProfile {
    static final ApiHookProfile NETWORK = profile("NETWORK", "Network",
            target("java/net/Socket", "connect"),
            target("java/net/http/HttpClient", "send"),
            target("java/net/URL", "openConnection"));
    static final ApiHookProfile CRYPTO = profile("CRYPTO", "Crypto",
            target("javax/crypto/Cipher", "getInstance"),
            target("javax/crypto/Cipher", "init"),
            target("javax/crypto/Cipher", "doFinal"),
            target("java/security/MessageDigest", "digest"));
    static final ApiHookProfile FILES = profile("FILES", "Files",
            target("java/io/FileInputStream", "<init>"),
            target("java/io/FileOutputStream", "<init>"),
            target("java/nio/file/Files", "readAllBytes"),
            target("java/nio/file/Files", "write"));
    static final ApiHookProfile REFLECTION = profile("REFLECTION", "Reflection",
            target("java/lang/Class", "forName"),
            target("java/lang/reflect/Method", "invoke"),
            target("java/lang/reflect/Constructor", "newInstance"));
    static final ApiHookProfile CLASS_LOADING = profile("CLASS_LOADING", "Class loading",
            target("java/lang/ClassLoader", "defineClass"),
            target("java/lang/invoke/MethodHandles$Lookup", "defineClass"));
    private static final ApiHookProfile[] BUILT_INS = {NETWORK, CRYPTO, FILES, REFLECTION, CLASS_LOADING};
    private static final int MAX_CUSTOM_PROFILES = 32;
    private static final int MAX_TARGETS_PER_PROFILE = 256;

    final String displayName;
    private final String id;
    private final List<Target> targets;

    private ApiHookProfile(String id, String displayName, List<Target> targets) {
        this.id = id;
        this.displayName = displayName;
        this.targets = targets;
    }

    String name() {
        return id;
    }

    boolean matches(String owner, String name) {
        for (Target target : targets) {
            if (target.owner.equals(owner) && target.name.equals(name)) return true;
        }
        return false;
    }

    static Set<ApiHookProfile> parse(String value) throws IOException {
        return parse(value, "");
    }

    static Set<ApiHookProfile> parse(String value, String definitions) throws IOException {
        Map<String, ApiHookProfile> available = new LinkedHashMap<String, ApiHookProfile>();
        for (ApiHookProfile profile : BUILT_INS) available.put(profile.id, profile);
        parseCustom(definitions, available);
        Set<ApiHookProfile> selected = new LinkedHashSet<ApiHookProfile>();
        if (value != null) {
            for (String token : value.split(",")) {
                String normalized = normalize(token);
                if (normalized.isEmpty()) continue;
                ApiHookProfile profile = available.get(normalized);
                if (profile == null) throw new IOException("Unknown API hook profile: " + token.trim());
                selected.add(profile);
            }
        }
        if (selected.isEmpty()) throw new IOException("Select at least one API hook profile");
        return selected;
    }

    static List<ApiHookProfile> matching(Set<ApiHookProfile> profiles, String owner, String name) {
        List<ApiHookProfile> matches = new ArrayList<ApiHookProfile>();
        for (ApiHookProfile profile : profiles) {
            if (profile.matches(owner, name)) matches.add(profile);
        }
        return matches;
    }

    private static void parseCustom(String definitions, Map<String, ApiHookProfile> available) throws IOException {
        Map<String, CustomProfile> custom = new LinkedHashMap<String, CustomProfile>();
        if (definitions != null && !definitions.isEmpty()) {
            for (String line : definitions.split("\n")) {
                if (line.trim().isEmpty()) continue;
                String[] values = line.split("\t", -1);
                if (values.length != 5 || !"P".equals(values[0])) throw new IOException("Invalid plugin hook profile definition");
                String id = normalize(decoded(values[1]));
                String name = decoded(values[2]).trim();
                String owner = decoded(values[3]).trim().replace('.', '/');
                String method = decoded(values[4]).trim();
                if (!id.matches("[A-Z][A-Z0-9_]{1,63}") || name.isEmpty() || owner.isEmpty() || method.isEmpty()) {
                    throw new IOException("Invalid plugin hook profile values");
                }
                if (available.containsKey(id)) throw new IOException("Plugin hook profile conflicts with built-in profile: " + id);
                CustomProfile profile = custom.get(id);
                if (profile == null) {
                    if (custom.size() >= MAX_CUSTOM_PROFILES) throw new IOException("Too many plugin hook profiles");
                    profile = new CustomProfile(name);
                    custom.put(id, profile);
                } else if (!profile.name.equals(name)) {
                    throw new IOException("Conflicting plugin hook profile name: " + id);
                }
                if (profile.targets.size() >= MAX_TARGETS_PER_PROFILE) throw new IOException("Too many targets for plugin hook profile: " + id);
                profile.targets.add(target(owner, method));
            }
        }
        for (Map.Entry<String, CustomProfile> entry : custom.entrySet()) {
            available.put(entry.getKey(), new ApiHookProfile(entry.getKey(), entry.getValue().name,
                    new ArrayList<Target>(entry.getValue().targets)));
        }
    }

    private static String decoded(String value) throws IOException {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length > 8192) throw new IOException("Plugin hook profile value is too large");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid plugin hook profile encoding", error);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static ApiHookProfile profile(String id, String name, Target... targets) {
        List<Target> values = new ArrayList<Target>();
        for (Target target : targets) values.add(target);
        return new ApiHookProfile(id, name, values);
    }

    private static Target target(String owner, String name) {
        return new Target(owner, name);
    }

    private static final class CustomProfile {
        final String name;
        final List<Target> targets = new ArrayList<Target>();

        CustomProfile(String name) {
            this.name = name;
        }
    }

    private static final class Target {
        final String owner;
        final String name;

        Target(String owner, String name) {
            this.owner = owner;
            this.name = name;
        }
    }
}
