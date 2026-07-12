package dev.whitedev.jpi.agent;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstantPoolSearchTest {
    private static final String UNIQUE_CONSTANT = "jpi-constant-pool-search-fixture";

    @Test void findsUtf8ConstantsWithoutLoadingOrInitializingAnotherClass() throws Exception {
        String resource = "/" + ConstantPoolSearchTest.class.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream input = ConstantPoolSearchTest.class.getResourceAsStream(resource)) {
            bytes = input.readAllBytes();
        }
        List<String> matches = ConstantPoolSearch.find(bytes, "POOL-SEARCH", 20);
        assertTrue(matches.stream().anyMatch(value -> value.contains(UNIQUE_CONSTANT)));
    }
}
