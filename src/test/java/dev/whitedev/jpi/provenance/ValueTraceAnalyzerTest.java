package dev.whitedev.jpi.provenance;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueTraceAnalyzerTest {
    @Test
    void correlatesFieldWriterNestedTraceAndStaticReference() {
        String field = "E\t1\t1000\tfield-1\t" + encoded("worker") + "\t"
                + encoded("Session") + "\t" + encoded("token") + "\t" + encoded("Ljava/lang/String;") + "\t"
                + encoded("SessionManager") + "\t" + encoded("setToken") + "\t" + encoded("(Ljava/lang/String;)V")
                + "\t42\t" + encoded("null") + "\t" + encoded("\"abc123\"") + "\t" + encoded("static")
                + "\t" + encoded("SessionManager.setToken(SessionManager.java:42)") + "\t9\tPUTSTATIC\n";
        String trace = "E\t9\t0\t990\ttrace-1\t100\treturn\t" + encoded("target") + "\t"
                + encoded("LoginResponse") + "\t" + encoded("login") + "\t" + encoded("()V") + "\t"
                + encoded("worker") + "\t" + encoded("") + "\t" + encoded("$1 = \"abc123\"") + "\t"
                + encoded("") + "\t" + encoded("") + "\t" + encoded("") + "\n";
        String xrefs = "R\tSTATIC\tSTRING_USER\t2\t" + encoded("id") + "\t"
                + encoded("RequestBuilder") + "\t" + encoded("header") + "\t" + encoded("(Ljava/lang/String;)V")
                + "\t" + encoded("constant") + "\n";

        ValueTraceAnalyzer.Report report = ValueTraceAnalyzer.analyze("abc123", field, trace, xrefs);

        assertEquals(1, report.fieldMatches());
        assertEquals(1, report.traceEvents());
        assertEquals(1, report.references().size());
        assertTrue(report.links().stream().anyMatch(link -> link.kind().equals("writes value into")));
        assertTrue(report.links().stream().anyMatch(link -> link.kind().equals("observed in")));
        assertTrue(report.nodes().stream().anyMatch(node -> node.label().contains("SessionManager.setToken")));
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
