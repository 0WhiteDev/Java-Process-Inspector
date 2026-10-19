package dev.whitedev.jpi.agent;

import dev.whitedev.jpi.agent.jvmti.NativeJvmtiBridge;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;

public final class RuntimeAccess {
    private final Instrumentation instrumentation;
    private final boolean nativeOnly;
    private final boolean nativeRedefinition;

    private RuntimeAccess(Instrumentation instrumentation, boolean nativeOnly, boolean nativeRedefinition) {
        this.instrumentation = instrumentation;
        this.nativeOnly = nativeOnly;
        this.nativeRedefinition = nativeRedefinition;
    }

    public static RuntimeAccess instrumentation(Instrumentation instrumentation) {
        return new RuntimeAccess(instrumentation, false, false);
    }

    public static RuntimeAccess jvmti() throws IOException {
        String status = NativeJvmtiBridge.status();
        if (!status.startsWith("backend=JVMTI\n")) throw new IOException("Native communication requires initialized JVMTI");
        return new RuntimeAccess(null, true, status.contains("\ncan_redefine_classes=1\n"));
    }

    public boolean isNativeOnly() { return nativeOnly; }

    public Class<?>[] getAllLoadedClasses() {
        if (!nativeOnly) return instrumentation.getAllLoadedClasses();
        try { return NativeJvmtiBridge.loadedClasses(); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }

    public boolean isModifiableClass(Class<?> type) {
        if (!nativeOnly) return instrumentation.isModifiableClass(type);
        try { return NativeJvmtiBridge.isModifiableClass(type); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }

    public boolean isRedefineClassesSupported() {
        return nativeOnly ? nativeRedefinition : instrumentation.isRedefineClassesSupported();
    }

    public boolean isRetransformClassesSupported() {
        return !nativeOnly && instrumentation.isRetransformClassesSupported();
    }

    public void retransformClasses(Class<?>... types) throws Exception {
        if (nativeOnly) throw new IOException("Java transformers require a Java Instrumentation agent; use JVMTI class capture");
        instrumentation.retransformClasses(types);
    }

    public void redefineClasses(ClassDefinition... definitions) throws Exception {
        if (!nativeOnly) { instrumentation.redefineClasses(definitions); return; }
        if (definitions.length != 1) throw new IOException("Native runtime requires a single class definition per request");
        NativeJvmtiBridge.redefineClass(definitions[0].getDefinitionClass(), definitions[0].getDefinitionClassFile());
    }

    public long getObjectSize(Object value) {
        if (!nativeOnly) return instrumentation.getObjectSize(value);
        try { return NativeJvmtiBridge.objectSize(value); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }
}
