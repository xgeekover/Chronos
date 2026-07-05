package io.chronos.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** Built-in flow node types + the factory that instantiates them from a {@link FlowGraph.NodeDef}. */
final class FlowNodes {

    private FlowNodes() {}

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * Shared daemon pool that runs user {@code function}/{@code exec}/{@code device read} code off the
     * single flow worker thread, so a slow call can be timed out without blocking the whole flow.
     */
    static final ExecutorService FN_EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "chronos-flow-fn");
        t.setDaemon(true);
        return t;
    });

    static FlowNode create(
            FlowGraph.NodeDef def,
            ScheduledExecutorService sched,
            Consumer<DebugRecord> debug,
            AtomicLong seq,
            BiConsumer<String, Object> completeHttp,
            Map<String, Object> flowCtx,
            Map<String, Object> globalCtx,
            Map<String, String> env,
            java.util.function.BiFunction<String, Object, Boolean> historianWrite,
            java.util.function.Function<Map<String, Object>, Object> adapterRead) {
        Map<String, Object> c = def.config();
        return switch (def.type()) {
            case "inject" -> new Inject(c, sched);
            case "change" -> new Change(c, flowCtx, globalCtx, env);
            case "switch" -> new Switch(c, flowCtx, globalCtx, env);
            case "template" -> new Template(c, flowCtx, globalCtx, env);
            case "range" -> new Range(c);
            case "delay" -> new Delay(c, sched);
            case "function" -> new JsFunc(def.id(), c, flowCtx, globalCtx, env, debug, seq);
            case "httprequest" -> new HttpReq(c);
            case "soaprequest" -> new Soap(c);
            case "split" -> new Split(c);
            case "join" -> new Join(c);
            case "rbe" -> new Rbe(c);
            case "trigger" -> new Trigger(c, sched);
            case "csv" -> new Csv(c);
            case "json" -> new Json(c);
            case "sort" -> new Sort(c);
            case "batch" -> new Batch(c, sched);
            case "xml" -> new Xml(c);
            case "yaml" -> new Yaml(c);
            case "html" -> new Html(c);
            case "exec" -> new Exec(c);
            case "fileout" -> new FileOut(c);
            case "filein" -> new FileIn(c);
            case "udpout" -> new UdpOut(c);
            case "udpin" -> new UdpIn(c);
            case "catch" -> new Catch();
            case "complete" -> new Complete();
            case "status" -> new Status();
            case "junction" -> new Junction();
            case "linkin" -> new LinkIn();
            case "linkout" -> new LinkOut(c);
            case "linkcall" -> new LinkCall();
            case "tag" -> new HistorianWrite(c, historianWrite);
            case "deviceread" -> new DeviceRead(c, adapterRead);
            case "mqttin" -> new MqttIn(c, sched);
            case "mqttout" -> new MqttOut(c);
            case "tcpin" -> new TcpIn(c);
            case "tcpout" -> new TcpOut(c);
            case "wsin" -> new WsIn(c, sched);
            case "wsout" -> new WsOut(c);
            case "httpin" -> new HttpIn();
            case "httpresponse" -> new HttpResp(completeHttp, c);
            case "debug" -> new Debug(def.id(), c, debug, seq);
            default -> throw new IllegalArgumentException("unknown flow node type: " + def.type());
        };
    }

    /** Fill {@code {{prop}}} placeholders from a message. */
    static String fillTemplate(String tpl, FlowMsg msg) {
        Matcher m = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}").matcher(tpl);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = msg.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ───────── inject: emits a message on a timer (or once) ─────────
    static final class Inject implements FlowNode {
        private final long interval;
        private final boolean once;
        private final Object payload;
        private final Object topic;
        private final ScheduledExecutorService sched;
        private volatile ScheduledFuture<?> task;
        private volatile Emit emit;

        Inject(Map<String, Object> c, ScheduledExecutorService sched) {
            this.interval = longOr(c.get("intervalMs"), 0);
            // once: auto-fire a single message at start (default). Set false for manual-only (button).
            this.once = c.get("once") == null || Boolean.parseBoolean(String.valueOf(c.get("once")));
            this.payload = c.getOrDefault("payload", "$timestamp");
            this.topic = c.get("topic");
            this.sched = sched;
        }

        @Override
        public void start(Emit emit) {
            this.emit = emit;
            Runnable fire = () -> emit.send(0, msg());
            if (interval > 0) {
                task = sched.scheduleAtFixedRate(fire, 0, interval, TimeUnit.MILLISECONDS);
            } else if (once) {
                task = sched.schedule(fire, 50, TimeUnit.MILLISECONDS);
            }
        }

        @Override
        public void trigger() {
            Emit e = emit;
            if (e != null) {
                e.send(0, msg());
            }
        }

        private FlowMsg msg() {
            Object p = "$timestamp".equals(payload) ? System.currentTimeMillis() : payload;
            FlowMsg m = FlowMsg.of(p);
            return topic == null ? m : m.set("topic", topic);
        }

        @Override
        public void stop() {
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    // ───────── change: apply a list of set/change/delete/move rules to the message ─────────
    static final class Change implements FlowNode {
        private final List<Map<String, Object>> rules;
        private final Map<String, Object> flow;
        private final Map<String, Object> global;
        private final Map<String, String> env;

        @SuppressWarnings("unchecked")
        Change(Map<String, Object> c, Map<String, Object> flow, Map<String, Object> global,
                Map<String, String> env) {
            this.flow = flow;
            this.global = global;
            this.env = env;
            Object r = c.get("rules");
            if (r instanceof List) {
                rules = (List<Map<String, Object>>) r;
            } else {
                // legacy single-set config → one "set" rule (backward compatible)
                Map<String, Object> set = new LinkedHashMap<>();
                set.put("t", "set");
                set.put("p", strOr(c.get("property"), "payload"));
                set.put("to", c.get("value") == null ? "" : c.get("value"));
                set.put("tot", strOr(c.get("valueType"), "str"));
                rules = List.of(set);
            }
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            Map<String, Object> m = new LinkedHashMap<>(msg.props());
            for (Map<String, Object> rule : rules) {
                apply(m, rule);
            }
            emit.send(0, new FlowMsg(m));
        }

        private void apply(Map<String, Object> m, Map<String, Object> rule) {
            String t = strOr(rule.get("t"), "set");
            String p = strOr(rule.get("p"), "payload");
            switch (t) {
                case "delete" -> m.remove(p);
                case "move" -> {
                    Object v = m.remove(p);
                    m.put(strOr(rule.get("to"), p), v);
                }
                case "change" -> {
                    Object cur = m.get(p);
                    if (cur != null) {
                        String s = String.valueOf(cur);
                        String from = strOr(rule.get("from"), "");
                        String to = String.valueOf(rule.getOrDefault("to", ""));
                        boolean re = Boolean.parseBoolean(String.valueOf(rule.get("re")));
                        m.put(p, re ? s.replaceAll(from, to) : s.replace(from, to));
                    }
                }
                default -> { // "set"
                    Object raw = rule.get("to");
                    Object v = switch (strOr(rule.get("tot"), "str")) {
                        case "num" -> num(raw);
                        case "bool" -> Boolean.valueOf(String.valueOf(raw));
                        case "msg" -> m.get(String.valueOf(raw)); // copy from another property
                        case "expr" -> Expr.eval(String.valueOf(raw), m, flow, global, env);
                        default -> raw;
                    };
                    m.put(p, v);
                }
            }
        }
    }

    // ───────── switch: route to output i for each matching rule (multi-output) ─────────
    static final class Switch implements FlowNode {
        private final String prop;
        private final List<Map<String, Object>> rules;
        private final Map<String, Object> flow;
        private final Map<String, Object> global;
        private final Map<String, String> env;

        @SuppressWarnings("unchecked")
        Switch(Map<String, Object> c, Map<String, Object> flow, Map<String, Object> global,
                Map<String, String> env) {
            this.flow = flow;
            this.global = global;
            this.env = env;
            prop = strOr(c.get("property"), "payload");
            Object r = c.getOrDefault("rules", List.of());
            rules = r instanceof List ? (List<Map<String, Object>>) r : List.of();
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            Object val = msg.get(prop);
            boolean any = false;
            for (int i = 0; i < rules.size(); i++) {
                Map<String, Object> rule = rules.get(i);
                String op = strOr(rule.get("op"), "eq");
                boolean hit;
                if ("else".equals(op)) {
                    hit = !any;
                } else if ("expr".equals(op)) {
                    // the rule value is a boolean expression evaluated against the whole message
                    hit = Expr.truthy(
                            Expr.eval(strOr(rule.get("value"), ""), msg.props(), flow, global, env));
                    any = any || hit;
                } else {
                    hit = matches(val, op, rule.get("value"));
                    any = any || hit;
                }
                if (hit) {
                    emit.send(i, msg);
                }
            }
        }

        private static boolean matches(Object val, String op, Object target) {
            Double a = num(val);
            Double b = num(target);
            return switch (op) {
                case "gt" -> a != null && b != null && a > b;
                case "gte" -> a != null && b != null && a >= b;
                case "lt" -> a != null && b != null && a < b;
                case "lte" -> a != null && b != null && a <= b;
                case "eq" -> a != null && b != null
                        ? a.doubleValue() == b.doubleValue()
                        : String.valueOf(val).equals(String.valueOf(target));
                case "neq" -> !(a != null && b != null
                        ? a.doubleValue() == b.doubleValue()
                        : String.valueOf(val).equals(String.valueOf(target)));
                case "contains" -> String.valueOf(val).contains(String.valueOf(target));
                case "regex" -> target != null
                        && Pattern.compile(String.valueOf(target)).matcher(String.valueOf(val)).find();
                case "true" -> Boolean.TRUE.equals(val) || "true".equalsIgnoreCase(String.valueOf(val));
                case "false" -> Boolean.FALSE.equals(val) || "false".equalsIgnoreCase(String.valueOf(val));
                case "null" -> val == null;
                case "nnull" -> val != null;
                case "empty" -> isEmpty(val);
                case "nempty" -> !isEmpty(val);
                default -> false;
            };
        }

        private static boolean isEmpty(Object v) {
            if (v == null) {
                return true;
            }
            if (v instanceof String s) {
                return s.isEmpty();
            }
            if (v instanceof java.util.Collection<?> col) {
                return col.isEmpty();
            }
            if (v instanceof Map<?, ?> map) {
                return map.isEmpty();
            }
            return false;
        }
    }

    // ───────── template: fill {{ expr }} placeholders (expression engine) into a string property ────
    static final class Template implements FlowNode {
        // capture anything between {{ }} so it can be a path OR a full expression
        private static final Pattern VAR = Pattern.compile("\\{\\{\\s*(.+?)\\s*}}");
        private final String tpl;
        private final String prop;
        private final Map<String, Object> flow;
        private final Map<String, Object> global;
        private final Map<String, String> env;

        Template(Map<String, Object> c, Map<String, Object> flow, Map<String, Object> global,
                Map<String, String> env) {
            tpl = strOr(c.get("template"), "");
            prop = strOr(c.get("property"), "payload");
            this.flow = flow;
            this.global = global;
            this.env = env;
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            Matcher m = VAR.matcher(tpl);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String content = m.group(1);
                Object v;
                try {
                    v = Expr.eval(content, msg.props(), flow, global, env);
                } catch (RuntimeException e) {
                    v = msg.get(content); // fall back to a plain property lookup
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
            }
            m.appendTail(sb);
            emit.send(0, msg.set(prop, sb.toString()));
        }
    }

    // ───────── range: linearly scale a numeric property ─────────
    static final class Range implements FlowNode {
        private final String prop;
        private final double inMin;
        private final double inMax;
        private final double outMin;
        private final double outMax;

        Range(Map<String, Object> c) {
            prop = strOr(c.get("property"), "payload");
            inMin = dbl(c.get("inMin"), 0);
            inMax = dbl(c.get("inMax"), 100);
            outMin = dbl(c.get("outMin"), 0);
            outMax = dbl(c.get("outMax"), 1);
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            Double v = num(msg.get(prop));
            if (v == null || inMax == inMin) {
                emit.send(0, msg);
                return;
            }
            double scaled = (v - inMin) / (inMax - inMin) * (outMax - outMin) + outMin;
            emit.send(0, msg.set(prop, scaled));
        }
    }

    // ───────── delay: forward each message after a fixed delay ─────────
    static final class Delay implements FlowNode {
        private final long ms;
        private final ScheduledExecutorService sched;

        Delay(Map<String, Object> c, ScheduledExecutorService sched) {
            ms = longOr(c.get("ms"), 1000);
            this.sched = sched;
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            sched.schedule(() -> emit.send(0, msg), ms, TimeUnit.MILLISECONDS);
        }
    }

    // ───────── debug: sink that records into the right-sidebar Debug feed ─────────
    static final class Debug implements FlowNode {
        private final String id;
        private final String name;
        private final String prop;
        private final Consumer<DebugRecord> sink;
        private final AtomicLong seq;

        Debug(String id, Map<String, Object> c, Consumer<DebugRecord> sink, AtomicLong seq) {
            this.id = id;
            this.name = strOr(c.get("name"), "debug");
            this.prop = strOr(c.get("property"), "payload");
            this.sink = sink;
            this.seq = seq;
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            Object out = "$msg".equals(prop) ? msg.props() : msg.get(prop);
            sink.accept(new DebugRecord(
                    seq.incrementAndGet(), System.currentTimeMillis(), id, name, msg.get("topic"), out));
        }
    }

    // ───────── js function: sandboxed GraalJS run(msg,node,flow,global,env) (Node-RED-style) ─────────
    static final class JsFunc implements FlowNode {
        private final String id;
        private final String name;
        private final JsFunction js;
        private final Map<String, Object> flow;
        private final Map<String, Object> global;
        private final Map<String, String> env;
        private final long timeoutMs;
        private final Consumer<DebugRecord> debug;
        private final AtomicLong seq;

        JsFunc(String id, Map<String, Object> c, Map<String, Object> flow, Map<String, Object> global,
                Map<String, String> env, Consumer<DebugRecord> debug, AtomicLong seq) {
            this.id = id;
            this.name = strOr(c.get("name"), "function");
            this.js = new JsFunction(strOr(c.get("code"), "return msg;"));
            this.flow = flow;
            this.global = global;
            this.env = env;
            this.timeoutMs = longOr(c.get("timeoutMs"), 5000);
            this.debug = debug;
            this.seq = seq;
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            if (js.compileError() != null) {
                emit.error(msg, "JS compile error: " + js.compileError());
                return;
            }
            Map<String, Object> mutable = new LinkedHashMap<>(msg.props());
            JsFunction.Sink sink = new JsFunction.Sink() {
                @Override
                public void send(int port, Map<String, Object> m) {
                    emit.send(port, new FlowMsg(m));
                }

                @Override
                public void error(String message, Map<String, Object> m) {
                    emit.error(new FlowMsg(m), message);
                }

                @Override
                public void status(String text) {
                    emit.status(text);
                }

                @Override
                public void log(String text) {
                    debug.accept(new DebugRecord(
                            seq.incrementAndGet(), System.currentTimeMillis(), id, name, "warn", text));
                }
            };
            var future = FN_EXEC.submit(() -> {
                js.run(mutable, flow, global, env, sink);
                return null;
            });
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                // interrupt the guest, cancel the task, and REBUILD the context: if interrupt doesn't stop
                // the stuck task within the budget, the next message must not re-enter the SAME context
                // concurrently (GraalJS forbids it) — a fresh context sidesteps that entirely.
                js.interrupt();
                future.cancel(true);
                js.recreate();
                emit.error(new FlowMsg(mutable), "function timed out after " + timeoutMs + "ms");
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                emit.error(new FlowMsg(mutable),
                        cause.getMessage() == null ? cause.toString() : cause.getMessage());
            }
        }

        @Override
        public void stop() {
            js.close();
        }
    }

    // ───────── http request: outbound REST call (response → payload) ─────────
    static final class HttpReq implements FlowNode {
        private final String method;
        private final String url;
        private final Map<String, Object> headers;

        @SuppressWarnings("unchecked")
        HttpReq(Map<String, Object> c) {
            method = strOr(c.get("method"), "GET").toUpperCase();
            url = strOr(c.get("url"), "");
            headers = c.get("headers") instanceof Map ? (Map<String, Object>) c.get("headers") : Map.of();
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            try {
                String u = strOr(msg.get("url"), url);
                var b = java.net.http.HttpRequest.newBuilder(URI.create(u))
                        .timeout(Duration.ofSeconds(15));
                headers.forEach((k, v) -> b.header(k, String.valueOf(v)));
                String body = msg.payload() == null ? "" : String.valueOf(msg.payload());
                b.method(method, "GET".equals(method) || "DELETE".equals(method)
                        ? BodyPublishers.noBody()
                        : BodyPublishers.ofString(body));
                HTTP.sendAsync(b.build(), BodyHandlers.ofString())
                        .thenAccept(resp -> emit.send(0,
                                msg.set("payload", resp.body()).set("statusCode", resp.statusCode())))
                        .exceptionally(ex -> {
                            emit.error(msg, ex.getMessage());
                            return null;
                        });
            } catch (RuntimeException e) {
                emit.error(msg, e.getMessage());
            }
        }
    }

    // ───────── soap request: POST a SOAP envelope (built from a template) ─────────
    static final class Soap implements FlowNode {
        private final String url;
        private final String action;
        private final String envelope;

        Soap(Map<String, Object> c) {
            url = strOr(c.get("url"), "");
            action = strOr(c.get("soapAction"), "");
            envelope = strOr(c.get("envelope"), "");
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            try {
                String env = fillTemplate(envelope, msg);
                var b = java.net.http.HttpRequest.newBuilder(URI.create(strOr(msg.get("url"), url)))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "text/xml; charset=utf-8");
                if (!action.isBlank()) {
                    b.header("SOAPAction", action);
                }
                b.POST(BodyPublishers.ofString(env));
                HTTP.sendAsync(b.build(), BodyHandlers.ofString())
                        .thenAccept(resp -> emit.send(0,
                                msg.set("payload", resp.body()).set("statusCode", resp.statusCode())))
                        .exceptionally(ex -> {
                            emit.error(msg, ex.getMessage());
                            return null;
                        });
            } catch (RuntimeException e) {
                emit.error(msg, e.getMessage());
            }
        }
    }

    // ───────── split: one message per array element / delimited token ─────────
    static final class Split implements FlowNode {
        private final String delim;

        Split(Map<String, Object> c) {
            delim = strOr(c.get("delimiter"), "\n");
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            Object p = msg.payload();
            if (p instanceof List<?> list) {
                for (Object e : list) {
                    emit.send(0, msg.set("payload", e));
                }
            } else {
                for (String part : String.valueOf(p).split(Pattern.quote(delim))) {
                    emit.send(0, msg.set("payload", part));
                }
            }
        }
    }

    // ───────── join: collect N messages into one array payload ─────────
    static final class Join implements FlowNode {
        private final int count;
        private final List<Object> buf = new ArrayList<>();

        Join(Map<String, Object> c) {
            count = Math.max(2, (int) longOr(c.get("count"), 2));
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            buf.add(msg.payload());
            if (buf.size() >= count) {
                List<Object> out = new ArrayList<>(buf);
                buf.clear();
                emit.send(0, FlowMsg.of(out));
            }
        }
    }

    // ───────── catch: receives error messages routed by the runtime (Node-RED catch) ─────────
    // The runtime enqueues error messages here (msg.error set); this node just forwards them.
    static final class Catch implements FlowNode {
        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            emit.send(0, msg);
        }
    }

    // ───────── complete: receives messages when a watched node finishes (Node-RED complete) ─────────
    static final class Complete implements FlowNode {
        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            emit.send(0, msg);
        }
    }

    // ───────── status: receives status events from watched nodes (Node-RED status); forwards them ─────────
    static final class Status implements FlowNode {
        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            emit.send(0, msg);
        }
    }

    // ───────── junction: a wire pass-through point — forwards its input unchanged (Node-RED junction) ─────────
    static final class Junction implements FlowNode {
        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            emit.send(0, msg);
        }
    }

    // ───────── link in: receives messages from link-out nodes (virtual wire), forwards them ─────────
    static final class LinkIn implements FlowNode {
        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            emit.send(0, msg);
        }
    }

    // ───────── link out: forwards to the linked link-in nodes, OR returns to the caller (return mode) ─
    static final class LinkOut implements FlowNode {
        private final boolean returnMode;

        LinkOut(Map<String, Object> c) {
            returnMode = "return".equals(strOr(c.get("mode"), "link"));
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            if (returnMode) {
                emit.linkReturn(msg); // send back to the link-call that invoked this subroutine
            } else {
                emit.link(msg); // runtime routes to the linked link-in nodes
            }
        }
    }

    // ───────── link call: invoke a link-in subroutine and continue when a return link-out replies ─────
    static final class LinkCall implements FlowNode {
        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            emit.linkCall(msg); // runtime pushes the return address + routes to the target link-in
        }
    }

    // tag (historian write): records msg.payload for an existing historian tag (by canonicalKey) via the
    // same fan-out a scheduled collection uses — current-value cache + Time Machine + gateway.
    // This is the bridge that lets a message-passing flow feed the historian (Pipeline's job).
    static final class HistorianWrite implements FlowNode {
        private final String tag;
        private final java.util.function.BiFunction<String, Object, Boolean> write;

        HistorianWrite(Map<String, Object> c, java.util.function.BiFunction<String, Object, Boolean> write) {
            this.tag = strOr(c.get("tag"), "");
            this.write = write;
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            String key = strOr(msg.get("tag"), tag); // msg.tag overrides the configured tag
            if (key.isEmpty()) {
                emit.error(msg, "tag: no tag configured (set the tag's canonicalKey, e.g. line1.temp)");
                return;
            }
            try {
                if (Boolean.TRUE.equals(write.apply(key, msg.payload()))) {
                    emit.status("wrote " + key);
                    emit.send(0, msg); // pass through so a tag write can be chained
                } else {
                    emit.error(msg, "tag: no historian tag '" + key + "' (create it first)");
                }
            } catch (Exception e) {
                emit.error(msg, "tag: " + e.getMessage());
            }
        }
    }

    // device read: runs a pull adapter (JDBC/Modbus/shell/file/api/script) from inline config on each
    // incoming message (wire an inject timer in) → emits the raw result. Lets a flow pull like Pipeline.
    // Runs off the flow worker (adapters block) with the adapter's own timeout handled app-side.
    static final class DeviceRead implements FlowNode {
        private final Map<String, Object> config;
        private final java.util.function.Function<Map<String, Object>, Object> read;

        DeviceRead(Map<String, Object> config, java.util.function.Function<Map<String, Object>, Object> read) {
            this.config = config;
            this.read = read;
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            FN_EXEC.submit(() -> {
                try {
                    emit.send(0, msg.set("payload", read.apply(config)));
                } catch (Exception e) {
                    emit.error(msg, "device read: " + e.getMessage());
                }
            });
        }
    }

    // ───────── rbe (report-by-exception): pass only when payload changed ─────────
    static final class Rbe implements FlowNode {
        private final boolean blockUnlessChanged; // false → block-if-equal (default)
        private boolean has;
        private Object last;

        Rbe(Map<String, Object> c) {
            // mode: "rbe" (block consecutive equal) — the only mode for now
            blockUnlessChanged = !"narrowband".equalsIgnoreCase(strOr(c.get("mode"), "rbe"));
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            Object p = msg.payload();
            if (!has || !java.util.Objects.equals(p, last)) {
                has = true;
                last = p;
                if (blockUnlessChanged) {
                    emit.send(0, msg);
                }
            }
        }
    }

    // ───────── trigger: emit input now, then a second "then" payload after a delay ─────────
    static final class Trigger implements FlowNode {
        private final long delayMs;
        private final Object then;
        private final ScheduledExecutorService sched;

        Trigger(Map<String, Object> c, ScheduledExecutorService sched) {
            delayMs = longOr(c.get("delayMs"), 1000);
            then = c.getOrDefault("thenPayload", "reset");
            this.sched = sched;
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            emit.send(0, msg);
            sched.schedule(() -> emit.send(0, msg.set("payload", then)), delayMs, TimeUnit.MILLISECONDS);
        }
    }

    // ───────── csv: split a delimited string payload into an array ─────────
    static final class Csv implements FlowNode {
        private final String delim;

        Csv(Map<String, Object> c) {
            delim = strOr(c.get("delimiter"), ",");
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            Object p = msg.payload();
            if (p instanceof List<?>) {
                emit.send(0, msg); // already structured — pass through
                return;
            }
            List<Object> parts = new ArrayList<>();
            for (String x : String.valueOf(p == null ? "" : p).split(Pattern.quote(delim))) {
                parts.add(x.trim());
            }
            emit.send(0, msg.set("payload", parts));
        }
    }

    // ───────── mqtt in: subscribe to a broker topic → emit a message per arrival ─────────
    static final class MqttIn implements FlowNode {
        private final Map<String, Object> c;
        private final String topic;
        private final int qos;
        private final ScheduledExecutorService sched;
        private volatile IMqttClient client;

        MqttIn(Map<String, Object> c, ScheduledExecutorService sched) {
            this.c = c;
            this.topic = strOr(c.get("topic"), "");
            this.qos = (int) longOr(c.get("qos"), 0);
            this.sched = sched;
        }

        @Override
        public void start(Emit emit) {
            // connect on a dedicated thread — NOT the shared 2-thread scheduler, whose slots would be
            // occupied for the full 8s connect timeout (stalling every inject/delay/trigger/http-ack).
            Thread connectThread = new Thread(() -> {
                try {
                    IMqttMessageListener onMsg = (t, m) -> emit.send(0,
                            FlowMsg.of(new String(m.getPayload(), StandardCharsets.UTF_8)).set("topic", t));
                    client = mqttConnect(c, "chronos-in");
                    // Paho auto-reconnects (setAutomaticReconnect), but a clean-session reconnect drops
                    // the subscription — re-subscribe on every (re)connect so the listener survives.
                    client.setCallback(new MqttCallbackExtended() {
                        @Override
                        public void connectComplete(boolean reconnect, String serverUri) {
                            emit.status("connected");
                            if (reconnect) {
                                try {
                                    client.subscribe(topic, qos, onMsg);
                                } catch (MqttException ignored) {
                                    // next reconnect will retry
                                }
                            }
                        }

                        @Override
                        public void connectionLost(Throwable cause) {
                            emit.status("disconnected"); // Paho auto-reconnects; connectComplete resubs
                        }

                        @Override
                        public void messageArrived(String t, MqttMessage m) {
                            // per-topic listener (onMsg) handles delivery
                        }

                        @Override
                        public void deliveryComplete(IMqttDeliveryToken token) {
                            // publish side only
                        }
                    });
                    client.subscribe(topic, qos, onMsg); // initial subscribe
                    // connectComplete only fires on RECONNECT (callback set post-connect), so report
                    // the initial "connected" status explicitly here
                    emit.status("connected");
                } catch (MqttException e) {
                    emit.status("error: " + e.getMessage());
                }
            }, "chronos-flow-mqttin");
            connectThread.setDaemon(true);
            connectThread.start();
        }

        @Override
        public void stop() {
            mqttClose(client);
        }
    }

    // ───────── mqtt out: publish msg.payload to a broker topic (passes the msg through) ─────────
    static final class MqttOut implements FlowNode {
        private final Map<String, Object> c;
        private final String topic;
        private final int qos;
        private final boolean retain;
        private volatile IMqttClient client;

        MqttOut(Map<String, Object> c) {
            this.c = c;
            this.topic = strOr(c.get("topic"), "");
            this.qos = (int) longOr(c.get("qos"), 0);
            this.retain = Boolean.parseBoolean(String.valueOf(c.get("retain")));
        }

        private synchronized IMqttClient client() throws MqttException {
            if (client == null || !client.isConnected()) {
                client = mqttConnect(c, "chronos-out");
            }
            return client;
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            try {
                String t = strOr(msg.get("topic"), topic);
                MqttMessage m = new MqttMessage(
                        String.valueOf(msg.payload() == null ? "" : msg.payload())
                                .getBytes(StandardCharsets.UTF_8));
                m.setQos(qos);
                m.setRetained(retain);
                client().publish(t, m);
                emit.send(0, msg); // passthrough so you can wire mqtt-out → debug
            } catch (MqttException e) {
                emit.error(msg, e.getMessage());
            }
        }

        @Override
        public void stop() {
            mqttClose(client);
        }
    }

    // ───────── tcp in: connect to host:port and emit each received line (client) ─────────
    static final class TcpIn implements FlowNode {
        private final String host;
        private final int port;
        private volatile Socket socket;
        private volatile Thread reader;
        private volatile boolean run;

        TcpIn(Map<String, Object> c) {
            host = strOr(c.get("host"), "localhost");
            port = (int) longOr(c.get("port"), 0);
        }

        @Override
        public void start(Emit emit) {
            run = true;
            reader = new Thread(() -> {
                long backoff = 500;
                while (run) {
                    try (Socket s = new Socket(host, port)) {
                        socket = s;
                        backoff = 500; // reset after a successful connect
                        emit.status("connected");
                        var in = new BufferedReader(
                                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while (run && (line = in.readLine()) != null) {
                            emit.send(0, FlowMsg.of(line));
                        }
                    } catch (IOException ignored) {
                        // connect failed or the stream broke — fall through to reconnect
                    }
                    if (!run) {
                        break;
                    }
                    emit.status("disconnected");
                    try {
                        Thread.sleep(backoff); // exponential backoff, interrupted by stop()
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    backoff = Math.min(backoff * 2, 30_000);
                }
            }, "chronos-tcp-in");
            reader.setDaemon(true);
            reader.start();
        }

        @Override
        public void stop() {
            run = false;
            closeQuietly(socket); // unblocks readLine
            if (reader != null) {
                reader.interrupt(); // unblocks the backoff sleep
            }
        }
    }

    // ───────── tcp out: write msg.payload (+newline) to host:port (client, passthrough) ─────────
    static final class TcpOut implements FlowNode {
        private final String host;
        private final int port;
        private volatile Socket socket;

        TcpOut(Map<String, Object> c) {
            host = strOr(c.get("host"), "localhost");
            port = (int) longOr(c.get("port"), 0);
        }

        private synchronized OutputStream out() throws IOException {
            if (socket == null || socket.isClosed() || !socket.isConnected()) {
                socket = new Socket(host, port);
            }
            return socket.getOutputStream();
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            try {
                OutputStream o = out();
                o.write((String.valueOf(msg.payload() == null ? "" : msg.payload()) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                o.flush();
                emit.send(0, msg);
            } catch (IOException e) {
                socket = null;
                emit.error(msg, e.getMessage());
            }
        }

        @Override
        public void stop() {
            closeQuietly(socket);
        }
    }

    // ───────── ws in: connect to a ws:// URL and emit each text frame (client, auto-reconnects) ────
    static final class WsIn implements FlowNode {
        private final String url;
        private final ScheduledExecutorService sched;
        private volatile WebSocket ws;
        private volatile boolean run;
        private volatile long backoff = 500;

        WsIn(Map<String, Object> c, ScheduledExecutorService sched) {
            url = strOr(c.get("url"), "");
            this.sched = sched;
        }

        @Override
        public void start(Emit emit) {
            run = true;
            connect(emit);
        }

        private void connect(Emit emit) {
            if (!run) {
                return;
            }
            HTTP.newWebSocketBuilder()
                    .buildAsync(URI.create(url), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket w) {
                            backoff = 500; // reset after a successful connect
                            emit.status("connected");
                            w.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                            emit.send(0, FlowMsg.of(data.toString()));
                            w.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                            emit.status("disconnected");
                            reconnect(emit);
                            return null;
                        }

                        @Override
                        public void onError(WebSocket w, Throwable error) {
                            emit.status("disconnected");
                            reconnect(emit);
                        }
                    })
                    .thenAccept(w -> ws = w)
                    .exceptionally(ex -> {
                        reconnect(emit); // initial connect failed — retry with backoff
                        return null;
                    });
        }

        private void reconnect(Emit emit) {
            if (!run) {
                return;
            }
            long delay = backoff;
            backoff = Math.min(backoff * 2, 30_000);
            sched.schedule(() -> connect(emit), delay, TimeUnit.MILLISECONDS);
        }

        @Override
        public void stop() {
            run = false; // prevents any scheduled reconnect from firing
            if (ws != null) {
                ws.abort();
            }
        }
    }

    // ───────── ws out: send msg.payload as a text frame to a ws:// URL (passthrough) ─────────
    static final class WsOut implements FlowNode {
        private final String url;
        private volatile WebSocket ws;
        private volatile CompletableFuture<WebSocket> connecting;

        WsOut(Map<String, Object> c) {
            url = strOr(c.get("url"), "");
        }

        private WebSocket socket() {
            if (ws == null && connecting == null) {
                connecting = HTTP.newWebSocketBuilder()
                        .buildAsync(URI.create(url), new WebSocket.Listener() {});
                connecting.thenAccept(w -> ws = w).exceptionally(ex -> {
                    connecting = null; // reset so the next message retries instead of bricking forever
                    return null;
                });
            }
            return ws;
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            WebSocket w = socket();
            if (w == null) {
                emit.error(msg, "websocket not connected yet");
                return;
            }
            w.sendText(String.valueOf(msg.payload() == null ? "" : msg.payload()), true);
            emit.send(0, msg);
        }

        @Override
        public void stop() {
            if (ws != null) {
                ws.abort();
            }
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    private static IMqttClient mqttConnect(Map<String, Object> c, String idPrefix) throws MqttException {
        String url = strOr(c.get("brokerUrl"), "tcp://localhost:1883");
        String clientId = strOr(c.get("clientId"), idPrefix + "-" + Long.toHexString(System.nanoTime()));
        IMqttClient client = new MqttClient(url, clientId, new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(8);
        opts.setAutomaticReconnect(true);
        String user = strOr(c.get("username"), null);
        String pass = strOr(c.get("password"), null);
        if (user != null && !user.isBlank()) {
            opts.setUserName(user);
        }
        if (pass != null && !pass.isBlank()) {
            opts.setPassword(pass.toCharArray());
        }
        client.connect(opts);
        return client;
    }

    private static void mqttClose(IMqttClient client) {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException ignored) {
            // best-effort on stop/redeploy
        }
    }

    // ───────── http in: inbound REST endpoint — triggered externally by the runtime ─────────
    static final class HttpIn implements FlowNode {
        // no-op: FlowRuntime.injectHttp emits from this node when its endpoint is called
    }

    // ───────── http response: completes the pending inbound request with the payload ─────────
    static final class HttpResp implements FlowNode {
        private final BiConsumer<String, Object> complete;
        private final int cfgStatus;
        private final Map<String, Object> cfgHeaders;

        @SuppressWarnings("unchecked")
        HttpResp(BiConsumer<String, Object> complete, Map<String, Object> c) {
            this.complete = complete;
            this.cfgStatus = (int) longOr(c.get("statusCode"), 200);
            this.cfgHeaders = c.get("headers") instanceof Map
                    ? (Map<String, Object>) c.get("headers") : Map.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onMessage(FlowMsg msg, Emit emit) {
            Object corr = msg.get("_corr");
            if (corr == null) {
                return;
            }
            // config provides defaults; msg.statusCode / msg.headers override per-message
            int status = (int) longOr(msg.get("statusCode"), cfgStatus);
            Map<String, String> headers = new LinkedHashMap<>();
            cfgHeaders.forEach((k, v) -> headers.put(k, String.valueOf(v)));
            if (msg.get("headers") instanceof Map<?, ?> h) {
                ((Map<String, Object>) h).forEach((k, v) -> headers.put(k, String.valueOf(v)));
            }
            complete.accept(String.valueOf(corr), new HttpReply(status, headers, msg.payload()));
        }
    }

    // ───────── shared coercion helpers ─────────
    static long longOr(Object o, long def) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? def : Long.parseLong(o.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static double dbl(Object o, double def) {
        Double d = num(o);
        return d == null ? def : d;
    }

    static Double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        try {
            return o == null ? null : Double.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String strOr(Object o, String def) {
        return o == null ? def : o.toString();
    }

    // ═════════════ Batch-2 core nodes (Node-RED parity): parse / transform / I/O ═════════════

    /** json: parse a JSON string → object, or stringify an object → JSON. "auto" picks by payload type. */
    static final class Json implements FlowNode {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final String action;

        Json(Map<String, Object> c) {
            this.action = strOr(c.get("action"), "auto");
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            Object p = msg.get("payload");
            try {
                Object out;
                if ("str".equals(action)) {
                    out = JSON.writeValueAsString(p);
                } else if ("obj".equals(action)) {
                    out = p instanceof String s ? JSON.readValue(s, Object.class) : p;
                } else {
                    out = p instanceof String s ? JSON.readValue(s, Object.class) : JSON.writeValueAsString(p);
                }
                emit.send(0, msg.set("payload", out));
            } catch (Exception e) {
                emit.error(msg, "json: " + e.getMessage());
            }
        }
    }

    /** sort: reorder a List payload (optionally by a key for lists of objects), asc/desc, numeric or string. */
    static final class Sort implements FlowNode {
        private final boolean desc;
        private final String prop;
        private final boolean numeric;

        Sort(Map<String, Object> c) {
            this.desc = "desc".equalsIgnoreCase(strOr(c.get("order"), "asc"));
            this.prop = strOr(c.get("prop"), "");
            this.numeric = Boolean.parseBoolean(strOr(c.get("numeric"), "false"));
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            if (!(msg.get("payload") instanceof List<?> l)) {
                emit.send(0, msg);
                return;
            }
            List<Object> copy = new ArrayList<>(l);
            Comparator<Object> cmp = (a, b) -> {
                Object av = key(a);
                Object bv = key(b);
                if (numeric) {
                    Double da = num(av);
                    Double db = num(bv);
                    return Double.compare(da == null ? 0 : da, db == null ? 0 : db);
                }
                return String.valueOf(av).compareTo(String.valueOf(bv));
            };
            copy.sort(desc ? cmp.reversed() : cmp);
            emit.send(0, msg.set("payload", copy));
        }

        private Object key(Object o) {
            return !prop.isEmpty() && o instanceof Map<?, ?> m ? m.get(prop) : o;
        }
    }

    /** batch: buffer messages and emit them as one array payload — by count or on a time interval. */
    static final class Batch implements FlowNode {
        private final String mode;
        private final int count;
        private final long intervalMs;
        private final ScheduledExecutorService sched;
        private final List<FlowMsg> buf = new ArrayList<>();
        private volatile Emit emit;
        private volatile ScheduledFuture<?> task;

        Batch(Map<String, Object> c, ScheduledExecutorService sched) {
            this.mode = strOr(c.get("mode"), "count");
            this.count = (int) longOr(c.get("count"), 10);
            this.intervalMs = longOr(c.get("intervalMs"), 1000);
            this.sched = sched;
        }

        @Override
        public void start(Emit emit) {
            this.emit = emit;
            if ("interval".equals(mode)) {
                task = sched.scheduleAtFixedRate(this::flush, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            }
        }

        @Override
        public synchronized void onMessage(FlowMsg msg, Emit emit) {
            this.emit = emit;
            buf.add(msg);
            if ("count".equals(mode) && buf.size() >= count) {
                flush();
            }
        }

        private synchronized void flush() {
            if (buf.isEmpty() || emit == null) {
                return;
            }
            List<Object> payloads = new ArrayList<>(buf.size());
            for (FlowMsg m : buf) {
                payloads.add(m.get("payload"));
            }
            buf.clear();
            emit.send(0, FlowMsg.of(payloads));
        }

        @Override
        public void stop() {
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    /** xml: parse an XML string → nested Map, or serialize a Map → XML. */
    static final class Xml implements FlowNode {
        private final String action;

        Xml(Map<String, Object> c) {
            this.action = strOr(c.get("action"), "auto");
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            Object p = msg.get("payload");
            try {
                boolean toXml = "str".equals(action) || (!"obj".equals(action) && !(p instanceof String));
                emit.send(0, msg.set("payload", toXml ? mapToXml(p) : xmlToMap(String.valueOf(p))));
            } catch (Exception e) {
                emit.error(msg, "xml: " + e.getMessage());
            }
        }

        private static Object xmlToMap(String xml) throws Exception {
            var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // block XXE
            var doc = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            org.w3c.dom.Element root = doc.getDocumentElement();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(root.getNodeName(), elementToObj(root));
            return out;
        }

        @SuppressWarnings("unchecked")
        private static Object elementToObj(org.w3c.dom.Element el) {
            Map<String, Object> m = new LinkedHashMap<>();
            var attrs = el.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                m.put("@" + attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
            }
            StringBuilder text = new StringBuilder();
            var children = el.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                var n = children.item(i);
                if (n instanceof org.w3c.dom.Element ce) {
                    Object child = elementToObj(ce);
                    Object existing = m.get(ce.getNodeName());
                    if (existing == null) {
                        m.put(ce.getNodeName(), child);
                    } else if (existing instanceof List<?> lst) {
                        ((List<Object>) lst).add(child);
                    } else {
                        List<Object> lst = new ArrayList<>();
                        lst.add(existing);
                        lst.add(child);
                        m.put(ce.getNodeName(), lst);
                    }
                } else if (n.getNodeType() == org.w3c.dom.Node.TEXT_NODE) {
                    text.append(n.getNodeValue());
                }
            }
            String t = text.toString().trim();
            if (m.isEmpty()) {
                return t;
            }
            if (!t.isEmpty()) {
                m.put("#text", t);
            }
            return m;
        }

        @SuppressWarnings("unchecked")
        private static String mapToXml(Object o) {
            StringBuilder sb = new StringBuilder();
            if (o instanceof Map<?, ?> m) {
                for (var e : m.entrySet()) {
                    writeEl(sb, String.valueOf(e.getKey()), e.getValue());
                }
            } else {
                sb.append(escape(String.valueOf(o)));
            }
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void writeEl(StringBuilder sb, String name, Object val) {
            if (val instanceof List<?> lst) {
                for (Object item : lst) {
                    writeEl(sb, name, item);
                }
                return;
            }
            sb.append('<').append(name).append('>');
            if (val instanceof Map<?, ?> m) {
                for (var e : m.entrySet()) {
                    writeEl(sb, String.valueOf(e.getKey()), e.getValue());
                }
            } else {
                sb.append(escape(String.valueOf(val)));
            }
            sb.append("</").append(name).append('>');
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    /** yaml: parse a YAML string → object, or serialize an object → YAML (SnakeYAML). */
    static final class Yaml implements FlowNode {
        private final org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        private final String action;

        Yaml(Map<String, Object> c) {
            this.action = strOr(c.get("action"), "auto");
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            Object p = msg.get("payload");
            try {
                boolean toStr = "str".equals(action) || (!"obj".equals(action) && !(p instanceof String));
                emit.send(0, msg.set("payload", toStr ? yaml.dump(p) : yaml.load((String) p)));
            } catch (Exception e) {
                emit.error(msg, "yaml: " + e.getMessage());
            }
        }
    }

    /** html: extract elements matching a CSS selector from an HTML payload (jsoup) → list of text/html. */
    static final class Html implements FlowNode {
        private final String selector;
        private final boolean asHtml;

        Html(Map<String, Object> c) {
            this.selector = strOr(c.get("selector"), "");
            this.asHtml = "html".equalsIgnoreCase(strOr(c.get("output"), "text"));
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            try {
                var doc = org.jsoup.Jsoup.parse(String.valueOf(msg.get("payload")));
                List<Object> out = new ArrayList<>();
                for (var el : doc.select(selector)) {
                    out.add(asHtml ? el.outerHtml() : el.text());
                }
                emit.send(0, msg.set("payload", out));
            } catch (Exception e) {
                emit.error(msg, "html: " + e.getMessage());
            }
        }
    }

    /** exec: run a system command (spawn or shell). Outputs stdout(0), stderr(1), return code(2). */
    static final class Exec implements FlowNode {
        private final String command;
        private final List<String> args;
        private final boolean useShell;
        private final long timeoutMs;

        Exec(Map<String, Object> c) {
            this.command = strOr(c.get("command"), "");
            this.useShell = Boolean.parseBoolean(strOr(c.get("useShell"), "false"));
            this.timeoutMs = longOr(c.get("timeoutMs"), 10000);
            this.args = splitArgs(strOr(c.get("args"), ""));
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            // SECURITY: never run message data as a shell command — sh -c interpolation of msg.payload
            // (which can come from an external http/mqtt/tcp source) would be command injection. In shell
            // mode the command must be a fixed, admin-configured string.
            if (useShell && command.isEmpty()) {
                emit.error(msg, "exec: shell mode requires a fixed 'command' (won't run message payload via sh -c)");
                return;
            }
            // run the whole process off the single flow worker so a slow/blocking child can't freeze the
            // runtime; stdout+stderr are drained on separate threads to avoid a pipe-buffer deadlock.
            FN_EXEC.submit(() -> runProcess(msg, emit));
        }

        private void runProcess(FlowMsg msg, Emit emit) {
            Process proc = null;
            try {
                String base = command.isEmpty() ? String.valueOf(msg.get("payload")) : command;
                List<String> cmd = new ArrayList<>();
                if (useShell) {
                    cmd.add("sh");
                    cmd.add("-c");
                    cmd.add(args.isEmpty() ? base : base + " " + String.join(" ", args));
                } else {
                    cmd.add(base);
                    cmd.addAll(args);
                }
                proc = new ProcessBuilder(cmd).start();
                final Process p = proc;
                var outFut = FN_EXEC.submit(
                        () -> new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                var errFut = FN_EXEC.submit(
                        () -> new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
                if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly();
                    outFut.cancel(true);
                    errFut.cancel(true);
                    emit.error(msg, "exec timed out after " + timeoutMs + "ms");
                    return;
                }
                emit.send(0, msg.set("payload", outFut.get()));
                emit.send(1, msg.set("payload", errFut.get()));
                emit.send(2, FlowMsg.of((long) proc.exitValue()));
            } catch (Exception e) {
                if (proc != null) {
                    proc.destroyForcibly();
                }
                emit.error(msg, "exec: " + e.getMessage());
            }
        }

        private static List<String> splitArgs(String s) {
            List<String> l = new ArrayList<>();
            if (s != null && !s.isBlank()) {
                for (String p : s.trim().split("\\s+")) {
                    l.add(p);
                }
            }
            return l;
        }
    }

    // file nodes are confined to a base dir (safer than Node-RED's arbitrary paths) — set via
    // -Dchronos.flow.files.dir; filenames are resolved relative to it and traversal is rejected.
    private static final Path FILES_BASE = Path.of(System.getProperty(
                    "chronos.flow.files.dir",
                    System.getenv().getOrDefault("CHRONOS_FLOW_FILES_DIR", "data/flow-files")))
            .toAbsolutePath()
            .normalize();

    static Path resolveFile(String name) {
        Path p = FILES_BASE.resolve(name).normalize();
        if (!p.startsWith(FILES_BASE)) {
            throw new IllegalArgumentException("path escapes the flow files directory");
        }
        // lexical normalize() doesn't resolve symlinks — a symlink planted in the base could point out.
        // Resolve the deepest existing ancestor's real path and confirm it's still within the base.
        try {
            if (Files.exists(FILES_BASE)) {
                Path baseReal = FILES_BASE.toRealPath();
                Path probe = p;
                while (probe != null && !Files.exists(probe)) {
                    probe = probe.getParent();
                }
                if (probe != null && !probe.toRealPath().startsWith(baseReal)) {
                    throw new IllegalArgumentException("path escapes the flow files directory (symlink)");
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot resolve path: " + e.getMessage());
        }
        return p;
    }

    /** file (out): write / append / delete a file under the flow files dir. Passes the msg through. */
    static final class FileOut implements FlowNode {
        private final String filename;
        private final String action;

        FileOut(Map<String, Object> c) {
            this.filename = strOr(c.get("filename"), "");
            this.action = strOr(c.get("action"), "append");
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            try {
                Path f = resolveFile(strOr(msg.get("filename"), filename));
                if ("delete".equals(action)) {
                    Files.deleteIfExists(f);
                } else {
                    if (f.getParent() != null) {
                        Files.createDirectories(f.getParent());
                    }
                    String data = String.valueOf(msg.get("payload"));
                    if ("write".equals(action)) {
                        Files.writeString(f, data, StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    } else {
                        Files.writeString(f, data + System.lineSeparator(), StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                    }
                }
                emit.send(0, msg);
            } catch (Exception e) {
                emit.error(msg, "file: " + e.getMessage());
            }
        }
    }

    /** file in: read a file under the flow files dir → payload (string). */
    static final class FileIn implements FlowNode {
        private final String filename;

        FileIn(Map<String, Object> c) {
            this.filename = strOr(c.get("filename"), "");
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            try {
                emit.send(0, msg.set("payload", Files.readString(resolveFile(strOr(msg.get("filename"), filename)))));
            } catch (Exception e) {
                emit.error(msg, "file in: " + e.getMessage());
            }
        }
    }

    /** udp out: send the payload as a UDP datagram to host:port. */
    static final class UdpOut implements FlowNode {
        private final String host;
        private final int port;

        UdpOut(Map<String, Object> c) {
            this.host = strOr(c.get("host"), "127.0.0.1");
            this.port = (int) longOr(c.get("port"), 0);
        }

        @Override
        public void onMessage(FlowMsg msg, Emit emit) {
            try (DatagramSocket s = new DatagramSocket()) {
                byte[] b = String.valueOf(msg.get("payload")).getBytes(StandardCharsets.UTF_8);
                s.send(new DatagramPacket(b, b.length, InetAddress.getByName(host), port));
                emit.send(0, msg);
            } catch (Exception e) {
                emit.error(msg, "udp out: " + e.getMessage());
            }
        }
    }

    /** udp in: bind a port and emit each received datagram's text as payload. */
    static final class UdpIn implements FlowNode {
        private final int port;
        private volatile DatagramSocket socket;
        private volatile boolean run = true;
        private volatile Emit emit;

        UdpIn(Map<String, Object> c) {
            this.port = (int) longOr(c.get("port"), 0);
        }

        @Override
        public void start(Emit emit) {
            this.emit = emit;
            Thread t = new Thread(this::loop, "chronos-flow-udpin");
            t.setDaemon(true);
            t.start();
        }

        private void loop() {
            try {
                socket = new DatagramSocket(port);
                byte[] buf = new byte[65535];
                while (run) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    if (!run) {
                        break;
                    }
                    emit.send(0, FlowMsg.of(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8)));
                }
            } catch (Exception e) {
                if (run && emit != null) {
                    emit.status("error: " + e.getMessage());
                }
            }
        }

        @Override
        public void stop() {
            run = false;
            if (socket != null) {
                socket.close();
            }
        }
    }
}
