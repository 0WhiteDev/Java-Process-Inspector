package dev.whitedev.jpi.agent.analysis;

import dev.whitedev.jpi.agent.trace.TraceFixture;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class XrefAnalyzerTest {
    @Test void findsCallsTypesConstantsAndReverseCallers() throws Exception {
        byte[] bytecode = fixtureBytes();
        List<XrefAnalyzer.Reference> references = XrefAnalyzer.references(
                bytecode, "analyze", "(Ljava/lang/String;)Ljava/lang/String;");

        assertTrue(references.stream().anyMatch(value -> "CALLS".equals(value.relation)
                && "helper".equals(value.member)));
        assertTrue(references.stream().anyMatch(value -> "TYPE".equals(value.relation)
                && StringBuilder.class.getName().equals(value.className)));
        assertTrue(references.stream().anyMatch(value -> "CONSTANT".equals(value.relation)
                && value.detail.contains("https://example.test/api/")));

        List<XrefAnalyzer.Reference> callers = XrefAnalyzer.callers(bytecode, "fixture",
                TraceFixture.class.getName(), TraceFixture.class.getName().replace('.', '/'),
                "helper", "(Ljava/lang/String;)Ljava/lang/String;");
        assertTrue(callers.stream().anyMatch(value -> "analyze".equals(value.member)));

        List<XrefAnalyzer.Reference> users = XrefAnalyzer.stringUsers(bytecode, "fixture",
                TraceFixture.class.getName(), "example.test");
        assertTrue(users.stream().anyMatch(value -> "analyze".equals(value.member)));
    }

    private byte[] fixtureBytes() throws Exception {
        String resource = "/" + TraceFixture.class.getName().replace('.', '/') + ".class";
        try (InputStream input = TraceFixture.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Trace fixture bytecode is unavailable");
            return input.readAllBytes();
        }
    }
}