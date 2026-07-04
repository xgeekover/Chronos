package io.chronos.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ExprTest {

    private static Object ev(String src, Map<String, Object> msg) {
        return Expr.eval(src, msg, Map.of(), Map.of());
    }

    @Test
    void arithmeticOverProperties() {
        assertEquals(212.0, ev("payload * 1.8 + 32", Map.of("payload", 100)));
        assertEquals(6.0, ev("(1 + 2) * 2", Map.of()));
        assertEquals(1.0, ev("10 % 3", Map.of()));
    }

    @Test
    void nestedPathAndMsgPrefix() {
        var msg = Map.<String, Object>of("payload", Map.of("temp", 21, "unit", "C"), "topic", "t");
        assertEquals(21, ev("payload.temp", msg)); // bare path returns the stored value as-is
        assertEquals("t", ev("msg.topic", msg));
        assertEquals(42.0, ev("payload.temp * 2", msg));
    }

    @Test
    void comparisonAndBoolean() {
        assertEquals(true, ev("payload > 10 && payload < 100", Map.of("payload", 50)));
        assertEquals(false, ev("payload > 10 && payload < 100", Map.of("payload", 5)));
        assertEquals(true, ev("topic == 'sensors'", Map.of("topic", "sensors")));
        assertEquals(true, ev("payload != 3", Map.of("payload", 4)));
    }

    @Test
    void stringConcatAndFunctions() {
        assertEquals("21 C", ev("payload & ' ' & unit", Map.of("payload", 21, "unit", "C")));
        assertEquals("HELLO", ev("uppercase(payload)", Map.of("payload", "hello")));
        assertEquals(5.0, ev("length(payload)", Map.of("payload", "abcde")));
        assertEquals(3.0, ev("round(2.6)", Map.of()));
        assertEquals(4.0, ev("abs(0 - 4)", Map.of()));
    }

    @Test
    void truthyResultsForSwitchExpressions() {
        assertTrue(Expr.truthy(ev("payload > 0", Map.of("payload", 5))));
        assertFalse(Expr.truthy(ev("payload > 0", Map.of("payload", -1))));
    }

    @Test
    void ternaryConditional() {
        assertEquals("hot", ev("payload > 30 ? 'hot' : 'cold'", Map.of("payload", 40)));
        assertEquals("cold", ev("payload > 30 ? 'hot' : 'cold'", Map.of("payload", 10)));
    }

    @Test
    void arrayAndObjectIndexing() {
        var msg = Map.<String, Object>of(
                "payload", java.util.List.of(10, 20, 30),
                "obj", Map.of("k", "v"));
        assertEquals(20, ev("payload[1]", msg));
        assertEquals("v", ev("obj['k']", msg));
        assertEquals(30.0, ev("payload[0] + payload[2] - 10", msg));
    }

    @Test
    void moreFunctions() {
        assertEquals(5.0, ev("max(2, 5)", Map.of()));
        assertEquals(2.0, ev("min(2, 5)", Map.of()));
        assertEquals(3.0, ev("floor(3.9)", Map.of()));
        assertEquals(4.0, ev("ceil(3.1)", Map.of()));
        assertEquals(8.0, ev("pow(2, 3)", Map.of()));
        assertEquals(true, ev("contains(payload, 'ell')", Map.of("payload", "hello")));
        assertEquals("ell", ev("substring(payload, 1, 4)", Map.of("payload", "hello")));
        assertEquals(6.0, ev("sum(payload)", Map.of("payload", java.util.List.of(1, 2, 3))));
    }

    @Test
    void envVariableAccess() {
        Object r = Expr.eval(
                "'unit=' & env.UNIT", Map.of(), Map.of(), Map.of(), Map.of("UNIT", "C"));
        assertEquals("unit=C", r);
    }
}
