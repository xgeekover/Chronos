package io.chronos.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

/** Smoke test: GraalJS resolves and evaluates JavaScript on the stock OpenJDK 17 toolchain. */
class GraalSmokeTest {

    @Test
    void evaluatesJavaScript() {
        try (Context ctx = Context.newBuilder("js").allowHostAccess(HostAccess.NONE).build()) {
            Value fn = ctx.eval("js", "(function(x){ return x*2 + 1; })");
            assertEquals(21, fn.execute(10).asInt());
        }
    }
}
