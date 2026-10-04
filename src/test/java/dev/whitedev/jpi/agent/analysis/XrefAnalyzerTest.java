package dev.whitedev.jpi.agent.analysis;

import dev.whitedev.jpi.agent.trace.TraceFixture;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class XrefAnalyzerTest {
    @Test void findsClassAndFieldInstructionUsagesWithDefinitionIdentifiers() throws Exception {
        String resource = "/" + SymbolFixture.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream input = SymbolFixture.class.getResourceAsStream(resource)) {
            bytecode = input.readAllBytes();
        }
        String owner = SymbolFixture.class.getName().replace('.', '/');
        var fields = XrefAnalyzer.symbolUsers(bytecode, "c:21", SymbolFixture.class.getName(), owner, "value", "I");
        assertTrue(fields.stream().anyMatch(row -> row.member.equals("read") && row.count == 1));
        assertTrue(fields.stream().anyMatch(row -> row.member.equals("write") && row.count == 1));
        assertTrue(fields.stream().allMatch(row -> row.targetIdentifier.equals("c:21")
                && row.relation.equals("CALLED_BY") && row.descriptor.startsWith("(")));
        assertTrue(XrefAnalyzer.symbolUsers(bytecode, "c:21", SymbolFixture.class.getName(), owner, "value", "J").isEmpty());
        var types = XrefAnalyzer.symbolUsers(bytecode, "c:21", SymbolFixture.class.getName(), "java/lang/StringBuilder", "", "");
        assertTrue(types.stream().anyMatch(row -> row.member.equals("build")));
    }

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

    static final class SymbolFixture {
        int value;

        int read() { return value; }

        void write(int next) { value = next; }

        String build() { return new StringBuilder().append(value).toString(); }
    }
}
