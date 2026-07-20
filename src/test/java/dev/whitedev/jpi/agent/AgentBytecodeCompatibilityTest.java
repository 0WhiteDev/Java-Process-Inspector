package dev.whitedev.jpi.agent;

import dev.whitedev.jpi.JpiApplication;
import dev.whitedev.jpi.agent.cfg.CfgRuntime;
import dev.whitedev.jpi.agent.patch.MethodBodyPatcher;
import dev.whitedev.jpi.agent.patch.ModernMethodPatcher;
import dev.whitedev.jpi.agent.patch.RuntimeJavaCompiler;
import dev.whitedev.jpi.protocol.Operation;
import javassist.CtClass;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;

import java.io.DataInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBytecodeCompatibilityTest {
    @Test void packagesAJava8AgentInsideTheJava21Application() throws Exception {
        assertEquals(52, majorVersion(InspectorAgent.class));
        assertEquals(52, majorVersion(AgentServer.class));
        assertEquals(52, majorVersion(CfgRuntime.class));
        assertEquals(52, majorVersion(Operation.class));
        assertEquals(52, majorVersion(MethodBodyPatcher.class));
        assertEquals(52, majorVersion(ModernMethodPatcher.class));
        assertEquals(52, majorVersion(RuntimeJavaCompiler.class));
        assertEquals(52, majorVersion(CtClass.class));
        assertTrue(majorVersion(ClassNode.class) <= 52);
        assertTrue(majorVersion(EclipseCompiler.class) <= 52);
        assertEquals(65, majorVersion(JpiApplication.class));
    }

    private int majorVersion(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream stream = type.getResourceAsStream(resource);
             DataInputStream input = new DataInputStream(stream)) {
            assertEquals(0xCAFEBABE, input.readInt());
            input.readUnsignedShort();
            return input.readUnsignedShort();
        }
    }
}
