package dev.whitedev.jpi.symbols;

import dev.whitedev.jpi.symbols.model.SymbolKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CppDemanglerTest {
    @Test void demanglesItaniumAndMsvcNamesAndClassifiesRuntimeTypes() {
        assertEquals("demo::Widget::run(int)", CppDemangler.demangle("_ZN4demo6Widget3runEi"));
        assertEquals("Demo::run", CppDemangler.demangle("?run@Demo@@YAXH@Z"));
        assertEquals("vtable for demo::Widget", CppDemangler.demangle("_ZTVN4demo6WidgetE"));
        assertEquals(SymbolKind.VTABLE, CppDemangler.kind("??_7Demo@@6B@", "", SymbolKind.DATA));
        assertEquals(SymbolKind.RTTI, CppDemangler.kind("_ZTIN4demo6WidgetE", "", SymbolKind.DATA));
    }
}
