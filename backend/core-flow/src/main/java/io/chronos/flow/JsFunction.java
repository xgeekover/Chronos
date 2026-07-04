package io.chronos.flow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

/**
 * The JavaScript {@code function} node (Node-RED-style), backed by a sandboxed GraalJS context. User
 * code runs as {@code (function(msg, node, flow, global, env){ <code> })} with <b>host access denied</b>
 * — interaction is only through the supplied proxies, so it can't touch the JVM/filesystem/network.
 * Supports {@code return msg} / {@code return [msg1, msg2]} and {@code node.send/status/error/warn/log}.
 * One context per node; the flow worker serializes execution so sequential reuse is safe.
 */
final class JsFunction {

    private final String code;
    private volatile Context ctx; // volatile: recreate() (worker thread) reassigns while run() reads it
    private volatile Value fn;
    private String compileError;

    JsFunction(String code) {
        this.code = code;
        build();
    }

    private void build() {
        Context c = null;
        Value f = null;
        try {
            c = Context.newBuilder("js")
                    .allowHostAccess(HostAccess.NONE)
                    .allowHostClassLookup(name -> false)
                    // stock OpenJDK has no Graal compiler → interpreter mode; silence the startup warning
                    .option("engine.WarnInterpreterOnly", "false")
                    .build();
            f = c.eval("js", "(function(msg, node, flow, global, env){\n" + code + "\n})");
        } catch (PolyglotException e) {
            compileError = e.getMessage();
        }
        this.ctx = c;
        this.fn = f;
    }

    /** Rebuild the context after a timeout so a stuck guest task can't be re-entered concurrently. */
    void recreate() {
        Context old = ctx;
        build();
        if (old != null) {
            try {
                old.close(true); // cancel the abandoned (possibly still-running) context
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    String compileError() {
        return compileError;
    }

    /** Bridges the JS {@code node} API back to the runtime. */
    interface Sink {
        void send(int port, Map<String, Object> msg);

        void error(String message, Map<String, Object> msg);

        void status(String text);

        void log(String text);
    }

    /** Execute for one message; throws {@link PolyglotException} on a JS runtime error. */
    void run(
            Map<String, Object> msgMap,
            Map<String, Object> flow,
            Map<String, Object> global,
            Map<String, String> env,
            Sink sink) {
        Value ret = fn.execute(
                toGuest(msgMap), nodeProxy(sink, msgMap), ctxProxy(flow), ctxProxy(global), envProxy(env));
        if (ret != null && !ret.isNull()) {
            sendValue(sink, fromGuest(ret), msgMap);
        }
    }

    void interrupt() {
        if (ctx != null) {
            try {
                ctx.interrupt(Duration.ofSeconds(2));
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    void close() {
        if (ctx != null) {
            try {
                ctx.close(true);
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    // ── node / flow / global / env proxies ──────────────────────────────────────

    private ProxyObject nodeProxy(Sink sink, Map<String, Object> baseMsg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("send", (ProxyExecutable) args -> {
            if (args.length > 0) {
                sendValue(sink, fromGuest(args[0]), baseMsg);
            }
            return null;
        });
        m.put("error", (ProxyExecutable) args -> {
            String message = args.length > 0 ? String.valueOf(fromGuest(args[0])) : "error";
            Map<String, Object> em = args.length > 1 && fromGuest(args[1]) instanceof Map<?, ?> mm
                    ? castMap(mm)
                    : baseMsg;
            sink.error(message, em);
            return null;
        });
        m.put("warn", (ProxyExecutable) args -> {
            sink.log(firstString(args));
            return null;
        });
        m.put("log", (ProxyExecutable) args -> {
            sink.log(firstString(args));
            return null;
        });
        m.put("status", (ProxyExecutable) args -> {
            Object s = args.length > 0 ? fromGuest(args[0]) : "";
            sink.status(s instanceof Map<?, ?> map ? String.valueOf(map.get("text")) : String.valueOf(s));
            return null;
        });
        m.put("done", (ProxyExecutable) args -> null);
        return ProxyObject.fromMap(m);
    }

    /** flow/global context: Node-RED {@code get(k)} / {@code set(k,v)} / {@code keys()}. */
    private ProxyObject ctxProxy(Map<String, Object> store) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("get", (ProxyExecutable) args ->
                args.length > 0 ? toGuest(store.get(String.valueOf(fromGuest(args[0])))) : null);
        m.put("set", (ProxyExecutable) args -> {
            if (args.length > 1) {
                store.put(String.valueOf(fromGuest(args[0])), fromGuest(args[1]));
            } else if (args.length == 1) {
                store.remove(String.valueOf(fromGuest(args[0])));
            }
            return null;
        });
        m.put("keys", (ProxyExecutable) args -> ProxyArray.fromList(new ArrayList<>(store.keySet())));
        return ProxyObject.fromMap(m);
    }

    private ProxyObject envProxy(Map<String, String> env) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("get", (ProxyExecutable) args ->
                args.length > 0 ? env.get(String.valueOf(fromGuest(args[0]))) : null);
        return ProxyObject.fromMap(m);
    }

    // ── emit helpers ────────────────────────────────────────────────────────────

    /** A List means multi-output (element i → port i, null skips); anything else → port 0. */
    private void sendValue(Sink sink, Object out, Map<String, Object> baseMsg) {
        if (out instanceof List<?> ports) {
            for (int i = 0; i < ports.size(); i++) {
                Object el = ports.get(i);
                if (el != null) {
                    sendOne(sink, i, el, baseMsg);
                }
            }
        } else {
            sendOne(sink, 0, out, baseMsg);
        }
    }

    @SuppressWarnings("unchecked")
    private void sendOne(Sink sink, int port, Object out, Map<String, Object> baseMsg) {
        if (out instanceof Map<?, ?> mm) {
            sink.send(port, (Map<String, Object>) mm);
        } else {
            Map<String, Object> copy = new LinkedHashMap<>(baseMsg);
            copy.put("payload", out);
            sink.send(port, copy);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String firstString(Value[] args) {
        return args.length > 0 ? String.valueOf(fromGuest(args[0])) : "";
    }

    // ── value conversion (Java ⇄ guest JS) ──────────────────────────────────────

    private static Object toGuest(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> g = new LinkedHashMap<>();
            m.forEach((k, v) -> g.put(String.valueOf(k), toGuest(v)));
            return ProxyObject.fromMap(g);
        }
        if (o instanceof List<?> l) {
            List<Object> g = new ArrayList<>(l.size());
            for (Object e : l) {
                g.add(toGuest(e));
            }
            return ProxyArray.fromList(g);
        }
        return o;
    }

    private static Object fromGuest(Value v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.isNumber()) {
            // JS numbers are doubles; surface integral values as Long so downstream Java sees 10 not 10.0
            double d = v.asDouble();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.007199254740992e15) {
                return (long) d;
            }
            return d;
        }
        if (v.hasArrayElements()) {
            List<Object> l = new ArrayList<>((int) v.getArraySize());
            for (long i = 0; i < v.getArraySize(); i++) {
                l.add(fromGuest(v.getArrayElement(i)));
            }
            return l;
        }
        if (v.canExecute()) {
            return null; // functions aren't data
        }
        if (v.hasMembers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : v.getMemberKeys()) {
                m.put(k, fromGuest(v.getMember(k)));
            }
            return m;
        }
        return v.toString();
    }
}
