package dev.whitedev.jpi.deobfuscation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeobfuscationWorkspaceTest {
    @Test void autoMapsInventoryAndResolvesAliasesWithoutChangingTextualContent() {
        MappingInventory inventory = MappingInventory.parse(
                "C\t" + encoded("a.b.c") + "\t1\n"
                        + "F\t" + encoded("a.b.c") + "\t" + encoded("b") + "\t" + encoded("Ljava/lang/String;") + "\t1\n"
                        + "M\t" + encoded("a.b.c") + "\t" + encoded("a") + "\t"
                        + encoded("(Ljava/lang/String;)Z") + "\t1\t1\n");
        DeobfuscationWorkspace workspace = new DeobfuscationWorkspace();
        workspace.autoMap(inventory, true, true, true, true, false);

        assertEquals("class_1", workspace.classAlias("a.b.c"));
        assertEquals("method_1", workspace.methodAlias("a.b.c", "a", "(Ljava/lang/String;)Z"));
        assertEquals("field_1", workspace.fieldAlias("a.b.c", "b", "Ljava/lang/String;"));

        String source = "class_1.method_1(field_1); String value = \"class_1.method_1\"; "
                + "// class_1.method_1\nchar marker = 'x';";
        DeobfuscationWorkspace.TranslationResult translated = workspace.translateSource(source);
        assertTrue(translated.source().startsWith("a.b.c.a(b);"));
        assertTrue(translated.source().contains("\"class_1.method_1\""));
        assertTrue(translated.source().contains("// class_1.method_1"));
        assertEquals(3, translated.replacements());
    }

    @Test void autoMapOnlyChangesTheRequestedInventoryScope() {
        MappingInventory first = MappingInventory.parse("C\t" + encoded("first.Scope") + "\t1\n");
        MappingInventory second = MappingInventory.parse("C\t" + encoded("second.Scope") + "\t1\n");
        DeobfuscationWorkspace workspace = new DeobfuscationWorkspace();
        workspace.mergeInventory(first);
        workspace.autoMap(second, true, false, false, false, false);

        assertEquals("first.Scope", workspace.classAlias("first.Scope"));
        assertEquals("class_1", workspace.classAlias("second.Scope"));
    }
    @Test void packageMappingIsOptionalAndAmbiguousAliasesAreNotTranslated() {
        DeobfuscationWorkspace workspace = new DeobfuscationWorkspace();
        MappingEntry first = new MappingEntry(MappingKind.CLASS, "", "a.First", "", -1, 0);
        MappingEntry second = new MappingEntry(MappingKind.CLASS, "", "b.Second", "", -1, 0);
        first.setMappedName("same");
        second.setMappedName("same");
        workspace.update(first);
        workspace.update(second);

        DeobfuscationWorkspace.TranslationResult result = workspace.translateSource("same.call();");
        assertEquals("same.call();", result.source());
        assertTrue(result.ambiguousAliases().contains("same"));
        assertFalse(workspace.classAlias("a.First").isEmpty());
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}