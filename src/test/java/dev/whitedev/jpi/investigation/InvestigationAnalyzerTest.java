package dev.whitedev.jpi.investigation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigationAnalyzerTest {
    @Test
    void correlatesConstantsMethodsAndRuntimeEvidence() {
        String constantRaw = "com.foo.LicenseService\tapp\thttps://api.example.com/license\n"
                + "com.foo.LicenseService\tapp\tInvalid license\n";
        String xrefsRaw = row("STATIC", "STRING_USER", 1, "c:1", "com.foo.LicenseService",
                "validateLicense", "(Ljava/lang/String;)Z", "https://api.example.com/license")
                + row("STATIC", "STRING_USER", 1, "c:2", "com.foo.Screen",
                "a", "()V", "https://api.example.com/license");
        String tracesRaw = String.join("\t", "S", "trace-1", "13", "5", "0", "0", "true",
                encoded("com.foo.LicenseService"), encoded("validateLicense"), encoded("(Ljava/lang/String;)Z")) + "\n"
                + String.join("\t", "E", "1", "0", "1", "trace-1", "100", "return",
                encoded("c:1"), encoded("com.foo.LicenseService"), encoded("validateLicense"),
                encoded("(Ljava/lang/String;)Z"), encoded("main"), "", "", encoded("false"), "",
                encoded("com.foo.LoginScreen.login(LoginScreen.java:20)")) + "\n"
                + String.join("\t", "E", "2", "0", "1", "trace-2", "100", "return",
                encoded("c:9"), encoded("com.foo.Noise"), encoded("run"), encoded("()V"), encoded("worker"),
                "", "", "", "", encoded("com.foo.Noise.main(Noise.java:1)")) + "\n";

        InvestigationReport report = InvestigationAnalyzer.analyze("license", constantRaw, xrefsRaw, tracesRaw);

        assertEquals(1, report.matchingClasses());
        assertEquals(2, report.constants().size());
        assertEquals("validateLicense", report.entryPoints().get(0).methodName());
        assertEquals(13, report.entryPoints().get(0).runtimeHits());
        assertTrue(report.entryPoints().get(0).confidence() > report.entryPoints().get(1).confidence());
        assertTrue(report.runtimePaths().get(0).contains("LoginScreen.login"));
        assertTrue(report.runtimePaths().stream().noneMatch(path -> path.contains("Noise")));
    }

    @Test
    void toleratesTruncatedAndInvalidProtocolRows() {
        InvestigationReport report = InvestigationAnalyzer.analyze("license",
                "<limit>\t<limit>\tResult limited\n", "invalid\nR\tbroken", "E\tbroken");
        assertTrue(report.entryPoints().isEmpty());
        assertTrue(report.constants().isEmpty());
        assertTrue(report.runtimePaths().isEmpty());
    }

    private static String row(String layer, String relation, long count, String identifier,
                              String owner, String method, String descriptor, String detail) {
        return String.join("\t", "R", layer, relation, String.valueOf(count), encoded(identifier), encoded(owner),
                encoded(method), encoded(descriptor), encoded(detail)) + "\n";
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
