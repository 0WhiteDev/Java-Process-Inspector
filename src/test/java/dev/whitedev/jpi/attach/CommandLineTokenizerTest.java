package dev.whitedev.jpi.attach;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CommandLineTokenizerTest {
    @Test void preservesQuotedArgumentsAndSpaces() {
        assertEquals(Arrays.asList("-Xmx2g", "-Dname=hello world", "--flag"),
                CommandLineTokenizer.parse("-Xmx2g \"-Dname=hello world\" --flag"));
    }

    @Test void rejectsAnUnclosedQuote() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandLineTokenizer.parse("\"unfinished"));
    }
}
