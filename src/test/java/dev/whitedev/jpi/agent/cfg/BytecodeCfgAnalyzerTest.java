package dev.whitedev.jpi.agent.cfg;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BytecodeCfgAnalyzerTest {
    @AfterEach void clearRuntime() {
        CfgRuntime.clear();
    }

    @Test void buildsBasicBlocksEdgesAndDominatorsFromBytecode() throws Exception {
        String graph = BytecodeCfgAnalyzer.analyze(
                fixtureBytes(), "classify", "(I)I");

        String[] lines = graph.split("\n");
        String[] header = lines[0].split("\t", -1);
        assertEquals("G", header[0]);
        assertEquals(CfgFixture.class.getName(), decoded(header[1]));
        assertTrue(Integer.parseInt(header[4]) >= 6);
        assertTrue(Integer.parseInt(header[6]) >= 3);
        assertTrue(graph.contains("\tBRANCH\t" + encoded("true")));
        assertTrue(graph.contains("\tBRANCH\t" + encoded("false")));

        boolean foundImmediateDominator = false;
        boolean foundConditionalOpcode = false;
        for (String line : lines) {
            String[] values = line.split("\t", -1);
            if (!"B".equals(values[0])) continue;
            if (!"B0".equals(values[1]) && !values[7].isEmpty()) foundImmediateDominator = true;
            String instructions = decoded(values[9]);
            if (instructions.contains("IF")) foundConditionalOpcode = true;
        }
        assertTrue(foundImmediateDominator);
        assertTrue(foundConditionalOpcode);
    }

    @Test void instrumentsBlocksAndCollectsBranchSpecificCounts() throws Exception {
        String probeId = "cfg-test";
        BytecodeCfgAnalyzer.Instrumented instrumented = BytecodeCfgAnalyzer.instrument(
                fixtureBytes(), "classify", "(I)I", probeId);
        CfgRuntime.register(probeId, instrumented.blockCount);

        FixtureLoader loader = new FixtureLoader(getClass().getClassLoader());
        Class<?> type = loader.define(CfgFixture.class.getName(), instrumented.bytecode);
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object fixture = constructor.newInstance();
        Method classify = type.getDeclaredMethod("classify", int.class);
        classify.setAccessible(true);

        assertEquals(6, classify.invoke(fixture, 4));

        String snapshot = CfgRuntime.snapshot(probeId);
        assertTrue(snapshot.startsWith("S\t" + probeId + "\ttrue\t"));
        boolean executed = false;
        boolean notExecuted = false;
        for (String line : snapshot.split("\n")) {
            String[] values = line.split("\t", -1);
            if (!"H".equals(values[0])) continue;
            long count = Long.parseLong(values[2]);
            if (count > 0L) executed = true;
            if (count == 0L) notExecuted = true;
        }
        assertTrue(executed);
        assertTrue(notExecuted);
    }

    private static byte[] fixtureBytes() throws Exception {
        String resource = "/" + CfgFixture.class.getName().replace('.', '/') + ".class";
        try (InputStream input = CfgFixture.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("CFG fixture bytecode is unavailable");
            return input.readAllBytes();
        }
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}

