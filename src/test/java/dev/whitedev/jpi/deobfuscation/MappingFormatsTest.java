package dev.whitedev.jpi.deobfuscation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingFormatsTest {
    @TempDir Path directory;

    @Test void roundTripsJsonAndExportsCommonMappingFormats() throws Exception {
        MappingEntry type = mapped(new MappingEntry(MappingKind.CLASS, "", "a.b.c", "", -1, 1),
                "AuthenticationManager");
        type.setComment("Validates \"signed\" licenses\nand sessions");
        type.setTags("auth,license");
        type.setColor("#52C789");
        MappingEntry method = mapped(new MappingEntry(MappingKind.METHOD, "a.b.c", "a",
                "(Ljava/lang/String;)Z", -1, 1), "validateLicense");
        MappingEntry field = mapped(new MappingEntry(MappingKind.FIELD, "a.b.c", "b",
                "Ljava/security/PublicKey;", -1, 1), "serverPublicKey");
        MappingEntry parameter = mapped(new MappingEntry(MappingKind.PARAMETER, "a.b.c", "a",
                "(Ljava/lang/String;)Z", 0, 1), "license");
        List<MappingEntry> entries = Arrays.asList(type, method, field, parameter);

        Path json = directory.resolve("workspace.json");
        MappingFormats.write(json, MappingFormats.Format.JSON, entries);
        List<MappingEntry> loaded = MappingFormats.readJson(json);
        assertEquals(4, loaded.size());
        assertEquals(type.comment(), loaded.get(0).comment());
        assertEquals(type.color(), loaded.get(0).color());

        Path tiny = directory.resolve("workspace.tiny");
        Path tsrg = directory.resolve("workspace.tsrg");
        Path proguard = directory.resolve("workspace.map");
        MappingFormats.write(tiny, MappingFormats.Format.TINY_V2, loaded);
        MappingFormats.write(tsrg, MappingFormats.Format.TSRG2, loaded);
        MappingFormats.write(proguard, MappingFormats.Format.PROGUARD, loaded);
        assertTrue(Files.readString(tiny).contains("validateLicense"));
        assertTrue(Files.readString(tsrg).startsWith("tsrg2 original mapped"));
        assertTrue(Files.readString(proguard).contains("a.b.c -> AuthenticationManager:"));
        assertTrue(Files.readString(proguard).contains("validateLicense"));
    }

    private static MappingEntry mapped(MappingEntry entry, String name) {
        entry.setMappedName(name);
        return entry;
    }
}