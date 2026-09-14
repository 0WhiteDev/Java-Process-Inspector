package dev.whitedev.jpi.profiler;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProfilerReportTest {
    @Test void parsesSummaryFlamesAndItems() {
        String path = encoded("main\nexample.Service.run");
        String item = encoded("byte[]");
        ProfilerReport report = ProfilerReport.parse("R\t10\t2010\t2000\t42\tfalse\n"
                + "C\tALLOCATIONS\t7\t4096\nF\tALLOCATIONS\t3072\t5\t" + path
                + "\nI\tALLOCATIONS\t4096\t7\t" + item + "\n");

        assertEquals(42, report.processedEvents());
        assertFalse(report.truncated());
        assertEquals(4096L, report.category(ProfilerCategory.ALLOCATIONS).total());
        assertEquals("example.Service.run", report.category(ProfilerCategory.ALLOCATIONS)
                .flames().getFirst().path().get(1));
        assertEquals("byte[]", report.category(ProfilerCategory.ALLOCATIONS).items().getFirst().label());
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
