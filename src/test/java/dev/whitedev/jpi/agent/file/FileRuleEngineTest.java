package dev.whitedev.jpi.agent.file;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileRuleEngineTest {
    @Test void exactAndSpecificRulesWinOverGenericRules() {
        FileRuleEngine engine = new FileRuleEngine();
        engine.put(new FileRule("generic", Collections.singleton(FileOperation.WRITE), "**", "*",
                FileDecision.BLOCK, ""));
        engine.put(new FileRule("config", Collections.singleton(FileOperation.WRITE), "**/config.json", "app.*",
                FileDecision.REDIRECT, "sandbox"));

        assertEquals(FileDecision.REDIRECT,
                engine.decision(FileOperation.WRITE, "C:\\app\\config.json", "app.Main.save()V").decision);
        assertEquals(FileDecision.BLOCK,
                engine.decision(FileOperation.WRITE, "C:\\app\\data.bin", "app.Main.save()V").decision);
        assertEquals(FileDecision.ALLOW,
                engine.decision(FileOperation.READ, "C:\\app\\config.json", "app.Main.load()V").decision);
    }
}
