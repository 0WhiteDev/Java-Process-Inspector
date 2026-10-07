package dev.whitedev.jpi.investigation.assistant;

import dev.whitedev.jpi.protocol.Operation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class InvestigationAssistantTest {
    private static final String QUERY = "api.example.com/v2/login";

    @Test void buildsDeterministicGraphFindingsAndSafeTraceSuggestions() throws Exception {
        Fixture fixture = new Fixture();
        var updates = new ArrayList<InvestigationAssistant.Progress>();
        var first = new InvestigationAssistant(fixture, InvestigationAssistant.Limits.defaults())
                .run(QUERY, updates::add, () -> false);
        var second = new InvestigationAssistant(new Fixture(), InvestigationAssistant.Limits.defaults())
                .run(QUERY, ignored -> { }, () -> false);
        assertEquals(first.markdown(), second.markdown());
        assertTrue(updates.stream().map(InvestigationAssistant.Progress::step).toList().containsAll(List.of(1, 2, 3, 4, 5, 6, 7)));
        assertTrue(first.findings().stream().anyMatch(finding -> finding.category().equals("Crypto")));
        assertTrue(first.findings().stream().anyMatch(finding -> finding.category().equals("Network") && finding.observed()));
        assertTrue(first.probes().stream().allMatch(probe -> probe.classIdentifier().startsWith("c:")));
        assertTrue(first.chains().stream().allMatch(chain -> chain.confidence() <= 95 && chain.edgeCount() > 0));
        assertTrue(first.edges().stream().anyMatch(edge -> edge.callee().owner().equals("java.net.http.HttpClient")
                && edge.staticReference() && edge.observedHits() == 4));
        assertTrue(fixture.calls.stream().anyMatch(call -> call.endsWith("\noutgoing")));
        assertTrue(fixture.operations.stream().allMatch(operation -> List.of(Operation.CONSTANT_SEARCH,
                Operation.XREF_SEARCH, Operation.TRACE_EVENTS, Operation.CLASSLOADER_SNAPSHOT, Operation.METHOD_XREFS).contains(operation)));
        assertTrue(first.markdown().contains("not proof of one complete execution"));
    }

    @Test void doesNotInventAnOrderedChainBetweenSiblingCryptoAndNetworkCalls() throws Exception {
        var report = new InvestigationAssistant(new Fixture(), InvestigationAssistant.Limits.defaults())
                .run(QUERY, ignored -> { }, () -> false);
        assertFalse(report.chains().isEmpty());
        for (var chain : report.chains()) {
            boolean crypto = chain.methods().stream().anyMatch(method -> method.owner().equals("javax.crypto.Cipher"));
            boolean network = chain.methods().stream().anyMatch(method -> method.owner().equals("java.net.http.HttpClient"));
            assertFalse(crypto && network);
        }
    }

    @Test void respectsRequestDepthAndGraphBoundsAndReportsPartialEvidence() throws Exception {
        Fixture fixture = new Fixture();
        var limits = new InvestigationAssistant.Limits(1, 1, 0, 3, 2, Duration.ofSeconds(30));
        var report = new InvestigationAssistant(fixture, limits).run(QUERY, ignored -> { }, () -> false);
        assertTrue(report.partial());
        assertTrue(report.edges().size() <= 2);
        assertEquals(1, fixture.operations.stream().filter(operation -> operation == Operation.METHOD_XREFS).count());
        assertFalse(report.notices().isEmpty());
    }

    @Test void stopsAtTheSoftDeadlineWithoutIssuingAnExpansionRequest() throws Exception {
        Fixture fixture = new Fixture();
        var limits = new InvestigationAssistant.Limits(1, 16, 3, 80, 200, Duration.ofNanos(1));
        var report = new InvestigationAssistant(fixture, limits).run(QUERY, ignored -> { }, () -> false);
        assertTrue(report.partial());
        assertEquals(0, report.analyzedMethods());
        assertTrue(fixture.operations.stream().noneMatch(operation -> operation == Operation.METHOD_XREFS));
        assertFalse(report.text().contains("```"));
    }

    @Test void neverExpandsAnAmbiguousClassloaderOrSuggestsItsUnresolvedMethod() throws Exception {
        Fixture fixture = new Fixture();
        fixture.ambiguous = true;
        var report = new InvestigationAssistant(fixture, InvestigationAssistant.Limits.defaults())
                .run(QUERY, ignored -> { }, () -> false);
        assertTrue(report.partial());
        assertTrue(report.notices().stream().anyMatch(notice -> notice.contains("Ambiguous classloader for app.Crypto")));
        assertTrue(report.probes().stream().noneMatch(probe -> probe.className().equals("app.Crypto")));
        assertTrue(fixture.calls.stream().noneMatch(call -> call.startsWith("c:2\n")));
    }

    @Test void preservesSuccessfulEvidenceWhenOneExpansionFails() throws Exception {
        Fixture fixture = new Fixture();
        fixture.failCrypto = true;
        var report = new InvestigationAssistant(fixture, InvestigationAssistant.Limits.defaults())
                .run(QUERY, ignored -> { }, () -> false);
        assertTrue(report.partial());
        assertTrue(report.notices().stream().anyMatch(notice -> notice.contains("bytecode unavailable")));
        assertTrue(report.findings().stream().anyMatch(finding -> finding.category().equals("Network")));
    }

    @Test void cancelsBetweenRequestsWithoutInstallingAnything() {
        AtomicBoolean cancelled = new AtomicBoolean();
        List<Operation> calls = new ArrayList<>();
        var assistant = new InvestigationAssistant((operation, payload) -> {
            calls.add(operation);
            cancelled.set(true);
            return "";
        }, InvestigationAssistant.Limits.defaults());
        assertThrows(CancellationException.class, () -> assistant.run(QUERY, ignored -> { }, cancelled::get));
        assertEquals(List.of(Operation.CONSTANT_SEARCH), calls);
    }

    @Test void emptyAndMalformedResultsDoNotGenerateFalseApiFindings() throws Exception {
        var report = new InvestigationAssistant((operation, payload) -> "broken\nR\tinvalid",
                InvestigationAssistant.Limits.defaults()).run(QUERY, ignored -> { }, () -> false);
        assertTrue(report.edges().isEmpty());
        assertTrue(report.findings().isEmpty());
        assertTrue(report.probes().isEmpty());
        assertTrue(report.chains().isEmpty());
        assertTrue(report.notices().stream().anyMatch(notice -> notice.contains("No method-level string users")));
        assertEquals("", InvestigationAssistant.category("app.HttpClient"));
        assertEquals("", InvestigationAssistant.category("app.Cipher"));
    }

    private static final class Fixture implements InvestigationAssistant.Requests {
        final List<Operation> operations = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        boolean ambiguous;
        boolean failCrypto;

        @Override public String request(Operation operation, String payload) throws Exception {
            operations.add(operation);
            calls.add(payload);
            return switch (operation) {
                case CONSTANT_SEARCH -> "app.Auth\tapp\t" + QUERY;
                case XREF_SEARCH -> row("STATIC", "STRING_USER", "c:1", "app.Auth", "login", "()V", QUERY, 1);
                case TRACE_EVENTS -> "";
                case CLASSLOADER_SNAPSHOT -> definition("c:1", "app.Auth") + definition("c:2", "app.Crypto")
                        + definition("c:3", "app.Request") + definition("c:4", "app.Screen")
                        + (ambiguous ? definition("c:22", "app.Crypto") : "");
                case METHOD_XREFS -> {
                    if (payload.startsWith("c:1\n")) yield row("STATIC", "CALLED_BY", "c:4", "app.Screen", "click", "()V", "invoke", 1)
                            + row("STATIC", "CALLS", "app.Crypto", "app.Crypto", "sign", "()V", "invoke", 1)
                            + row("STATIC", "CALLS", "app.Request", "app.Request", "send", "()V", "invoke", 1)
                            + "R\tbroken\n";
                    if (payload.startsWith("c:2\n")) {
                        if (failCrypto) throw new java.io.IOException("bytecode unavailable");
                        yield row("STATIC", "CALLS", "javax.crypto.Cipher", "javax.crypto.Cipher", "doFinal", "()[B", "invoke", 1);
                    }
                    if (payload.startsWith("c:3\n")) yield row("STATIC", "CALLS", "java.net.http.HttpClient", "java.net.http.HttpClient", "send", "()V", "invoke", 1)
                            + row("DYNAMIC", "CALLS", "java.net.http.HttpClient", "java.net.http.HttpClient", "send", "()V", "observed", 4);
                    if (payload.startsWith("c:4\n")) yield row("STATIC", "CALLS", "app.Auth", "app.Auth", "login", "()V", "invoke", 1);
                    throw new AssertionError("Unexpected Xrefs query: " + payload);
                }
                default -> throw new AssertionError("Unexpected operation: " + operation);
            };
        }
    }

    private static String definition(String id, String owner) {
        return String.join("\t", "C", encoded(id), encoded(owner), encoded("loader"), "", "definition") + "\n";
    }

    private static String row(String layer, String relation, String id, String owner, String member,
                              String descriptor, String detail, long count) {
        return String.join("\t", "R", layer, relation, Long.toString(count), encoded(id), encoded(owner),
                encoded(member), encoded(descriptor), encoded(detail)) + "\n";
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
