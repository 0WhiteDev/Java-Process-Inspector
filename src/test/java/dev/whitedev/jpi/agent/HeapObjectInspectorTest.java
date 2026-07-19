package dev.whitedev.jpi.agent;

import dev.whitedev.jpi.fixture.HeapObjectFixture;

import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HeapObjectInspectorTest {
    @Test
    void scansKnownStaticRootsAndRetainsInspectableWeakHandles() throws Exception {
        HeapObjectInspector inspector = new HeapObjectInspector(instrumentation());
        String payload = "root=" + HeapObjectFixture.class.getName()
                + "\nclass=" + HeapObjectFixture.Child.class.getName()
                + "\nfield=label\nvalue=needle\nmaxDepth=4\nmaxObjects=100\nmaxResults=20";

        String result = inspector.scan(payload);

        assertTrue(result.startsWith("S\t"));
        assertTrue(result.contains("C\t" + encoded(HeapObjectFixture.Child.class.getName()) + "\t"
                + encoded(ClassRegistry.loaderLabel(HeapObjectFixture.Child.class.getClassLoader())) + "\t1\t24"), result);
        assertTrue(result.contains(encoded(HeapObjectFixture.class.getName() + ".ROOT.child")));
        String objectLine = java.util.Arrays.stream(result.split("\n"))
                .filter(line -> line.startsWith("O\t"))
                .findFirst().orElseThrow();
        String id = objectLine.split("\t", -1)[1];

        String details = inspector.object(id);

        assertTrue(details.startsWith("H\t" + id + "\t"));
        assertTrue(details.contains("F\t" + encoded(HeapObjectFixture.Child.class.getName())
                + "\t" + encoded("label")));
        assertTrue(details.contains(encoded("\"needle-value\"")));
        assertTrue(details.contains(encoded(HeapObjectFixture.class.getName() + ".ROOT.child")));
    }

    private static Instrumentation instrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
                HeapObjectInspectorTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getAllLoadedClasses")) {
                        return new Class<?>[]{HeapObjectFixture.class, HeapObjectFixture.Root.class,
                                HeapObjectFixture.Child.class};
                    }
                    if (method.getName().equals("getObjectSize")) return 24L;
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == void.class) return null;
                    return null;
                });
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
