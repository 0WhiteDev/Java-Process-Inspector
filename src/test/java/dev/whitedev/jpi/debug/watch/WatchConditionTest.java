package dev.whitedev.jpi.debug.watch;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class WatchConditionTest {
    @Test void comparesNumbersWithoutLosingLongPrecision() {
        assertTrue(WatchCondition.parse("< 5").matchesScalar(new BigDecimal("4.9")));
        assertFalse(WatchCondition.parse("< 5").matchesScalar(new BigDecimal("5")));
        assertTrue(WatchCondition.parse("== 9007199254740993").matchesScalar(new BigDecimal("9007199254740993")));
        assertFalse(WatchCondition.parse("== 9007199254740993").matchesScalar(new BigDecimal("9007199254740992")));
        assertTrue(WatchCondition.parse(">= 1.00").matchesScalar(BigDecimal.ONE));
    }

    @Test void supportsBooleanNullAndStringComparisons() {
        assertTrue(WatchCondition.parse("== false").matchesScalar(false));
        assertTrue(WatchCondition.parse("!= null").matchesScalar("ready"));
        assertTrue(WatchCondition.parse("== null").matchesScalar(null));
        assertTrue(WatchCondition.parse("== \"ready\"").matchesScalar("ready"));
        assertFalse(WatchCondition.parse("== \"ready\"").matchesScalar("other"));
    }

    @Test void rejectsUnsupportedExpressionsAndMismatchedTypes() {
        assertThrows(IllegalArgumentException.class, () -> WatchCondition.parse("player.health < 5"));
        assertThrows(IllegalArgumentException.class, () -> WatchCondition.parse("< true"));
        assertThrows(IllegalArgumentException.class, () -> WatchCondition.parse("== call()"));
        assertThrows(IllegalArgumentException.class, () -> WatchCondition.parse("< 5").matchesScalar("text"));
        assertThrows(IllegalArgumentException.class, () -> WatchCondition.parse("== false").matchesScalar("text"));
    }
}
