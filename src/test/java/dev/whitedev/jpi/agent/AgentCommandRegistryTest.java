package dev.whitedev.jpi.agent;

import dev.whitedev.jpi.protocol.Operation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentCommandRegistryTest {

    @SuppressWarnings("unchecked")
    private Map<Operation, AgentCommand> registry(AgentServer server) throws Exception {
        Field field = AgentServer.class.getDeclaredField("commands");
        field.setAccessible(true);
        return (Map<Operation, AgentCommand>) field.get(server);
    }

    private AgentServer buildServer() throws Exception {
        Method factory = AgentServer.class.getDeclaredMethod("buildCommands");
        factory.setAccessible(true);
        AgentServer server = createMinimalServer();
        return server;
    }

    private AgentServer createMinimalServer() throws Exception {
        java.lang.reflect.Constructor<AgentServer> ctor =
                AgentServer.class.getDeclaredConstructor(
                        dev.whitedev.jpi.agent.AgentOptions.class,
                        java.lang.instrument.Instrumentation.class,
                        dev.whitedev.jpi.agent.ClassRegistry.class);
        ctor.setAccessible(true);
        return ctor.newInstance(null, null, null);
    }

    @Test
    void allOperationsExceptInternalAreRegistered() throws Exception {
        AgentServer server = createMinimalServer();
        Map<Operation, AgentCommand> map = registry(server);

        Set<Operation> all = EnumSet.allOf(Operation.class);
        for (Operation op : all) {
            assertTrue(map.containsKey(op),
                    "Missing command registration for operation: " + op);
        }
    }

    @Test
    void noNullCommandsInRegistry() throws Exception {
        AgentServer server = createMinimalServer();
        Map<Operation, AgentCommand> map = registry(server);

        for (Map.Entry<Operation, AgentCommand> entry : map.entrySet()) {
            assertNotNull(entry.getValue(),
                    "Null command registered for operation: " + entry.getKey());
        }
    }

    @Test
    void fileRuleAddAndUpdateShareSameCommandInstance() throws Exception {
        AgentServer server = createMinimalServer();
        Map<Operation, AgentCommand> map = registry(server);

        AgentCommand add    = map.get(Operation.FILE_RULE_ADD);
        AgentCommand update = map.get(Operation.FILE_RULE_UPDATE);

        assertNotNull(add);
        assertNotNull(update);
        assertSame(add, update,
                "FILE_RULE_ADD and FILE_RULE_UPDATE must share the same AgentCommand instance");
    }

    @Test
    void registrySizeMatchesOperationCount() throws Exception {
        AgentServer server = createMinimalServer();
        Map<Operation, AgentCommand> map = registry(server);

        int expected = Operation.values().length;
        assertEquals(expected, map.size(),
                "Registry size must equal the total number of Operations");
    }

    @Test
    void registryIsEnumMap() throws Exception {
        AgentServer server = createMinimalServer();
        Map<Operation, AgentCommand> map = registry(server);

        assertEquals("EnumMap", map.getClass().getSimpleName(),
                "Registry must be an EnumMap for O(1) dispatch");
    }
}
