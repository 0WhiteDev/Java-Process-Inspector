package dev.whitedev.jpi.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

enum ApiHookProfile {
    NETWORK("Network",
            target("java/net/Socket", "connect"),
            target("java/net/http/HttpClient", "send"),
            target("java/net/URL", "openConnection")),
    CRYPTO("Crypto",
            target("javax/crypto/Cipher", "getInstance"),
            target("javax/crypto/Cipher", "init"),
            target("javax/crypto/Cipher", "doFinal"),
            target("java/security/MessageDigest", "digest")),
    FILES("Files",
            target("java/io/FileInputStream", "<init>"),
            target("java/io/FileOutputStream", "<init>"),
            target("java/nio/file/Files", "readAllBytes"),
            target("java/nio/file/Files", "write")),
    REFLECTION("Reflection",
            target("java/lang/Class", "forName"),
            target("java/lang/reflect/Method", "invoke"),
            target("java/lang/reflect/Constructor", "newInstance")),
    CLASS_LOADING("Class loading",
            target("java/lang/ClassLoader", "defineClass"),
            target("java/lang/invoke/MethodHandles$Lookup", "defineClass"));

    final String displayName;
    private final Target[] targets;

    ApiHookProfile(String displayName, Target... targets) {
        this.displayName = displayName;
        this.targets = targets;
    }

    boolean matches(String owner, String name) {
        for (Target target : targets) {
            if (target.owner.equals(owner) && target.name.equals(name)) return true;
        }
        return false;
    }

    static Set<ApiHookProfile> parse(String value) throws IOException {
        EnumSet<ApiHookProfile> selected = EnumSet.noneOf(ApiHookProfile.class);
        if (value != null) {
            for (String token : value.split(",")) {
                String normalized = token.trim();
                if (normalized.isEmpty()) continue;
                try {
                    selected.add(valueOf(normalized.toUpperCase(Locale.ROOT).replace(' ', '_')));
                } catch (IllegalArgumentException error) {
                    throw new IOException("Unknown API hook profile: " + normalized);
                }
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

    private static Target target(String owner, String name) {
        return new Target(owner, name);
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
