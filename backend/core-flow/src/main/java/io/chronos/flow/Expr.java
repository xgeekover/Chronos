package io.chronos.flow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny expression engine — a practical subset of JSONata for the change/switch/template nodes.
 *
 * <p>Supports: number / 'string' / true / false / null literals; property paths
 * ({@code payload}, {@code payload.a.b}, {@code msg.x}, {@code flow.x}, {@code global.x});
 * arithmetic {@code + - * / %}; string concat {@code &}; comparison {@code == != < <= > >=};
 * boolean {@code && || !}; parentheses; and functions
 * {@code length uppercase lowercase trim number string round abs}.
 *
 * <p>Compiled expressions are cached, so evaluating the same source per message is cheap.
 */
public final class Expr {

    private static final Map<String, Expr> CACHE = new ConcurrentHashMap<>();

    private final Node root;

    private Expr(Node root) {
        this.root = root;
    }

    /** Compile (cached) and evaluate {@code src} against the message + flow/global context. */
    public static Object eval(
            String src, Map<String, Object> msg, Map<String, Object> flow, Map<String, Object> global) {
        return eval(src, msg, flow, global, Map.of());
    }

    /** As {@link #eval}, plus an {@code env} map reachable via {@code env.NAME}. */
    public static Object eval(String src, Map<String, Object> msg, Map<String, Object> flow,
            Map<String, Object> global, Map<String, ?> env) {
        return compile(src).evaluate(msg, flow, global, env);
    }

    public static Expr compile(String src) {
        return CACHE.computeIfAbsent(src == null ? "" : src, s -> new Expr(new Parser(s).parseAll()));
    }

    public Object evaluate(
            Map<String, Object> msg, Map<String, Object> flow, Map<String, Object> global) {
        return evaluate(msg, flow, global, Map.of());
    }

    public Object evaluate(Map<String, Object> msg, Map<String, Object> flow,
            Map<String, Object> global, Map<String, ?> env) {
        return root.eval(new Ctx(
                msg == null ? Map.of() : msg,
                flow == null ? Map.of() : flow,
                global == null ? Map.of() : global,
                env == null ? Map.of() : env));
    }

    // ───────── evaluation context + AST ─────────
    private record Ctx(Map<String, Object> msg, Map<String, Object> flow,
            Map<String, Object> global, Map<String, ?> env) {}

    private interface Node {
        Object eval(Ctx c);
    }

    private record Lit(Object v) implements Node {
        @Override
        public Object eval(Ctx c) {
            return v;
        }
    }

    private record Ref(String[] path) implements Node {
        @Override
        public Object eval(Ctx c) {
            Object cur;
            int start;
            switch (path[0]) {
                case "flow" -> {
                    cur = c.flow();
                    start = 1;
                }
                case "global" -> {
                    cur = c.global();
                    start = 1;
                }
                case "env" -> {
                    cur = c.env();
                    start = 1;
                }
                case "msg" -> {
                    cur = c.msg();
                    start = 1;
                }
                default -> {
                    cur = c.msg();
                    start = 0;
                }
            }
            for (int i = start; i < path.length && cur != null; i++) {
                cur = cur instanceof Map<?, ?> m ? m.get(path[i]) : null;
            }
            return cur;
        }
    }

    private record Un(String op, Node e) implements Node {
        @Override
        public Object eval(Ctx c) {
            Object v = e.eval(c);
            return "!".equals(op) ? !truthy(v) : negate(v);
        }
    }

    private record Bin(String op, Node l, Node r) implements Node {
        @Override
        public Object eval(Ctx c) {
            if ("&&".equals(op)) {
                return truthy(l.eval(c)) && truthy(r.eval(c));
            }
            if ("||".equals(op)) {
                return truthy(l.eval(c)) || truthy(r.eval(c));
            }
            Object a = l.eval(c);
            Object b = r.eval(c);
            return switch (op) {
                case "&" -> str(a) + str(b);
                case "+" -> {
                    Double na = num(a);
                    Double nb = num(b);
                    yield na != null && nb != null ? na + nb : str(a) + str(b);
                }
                case "-" -> arith(a, b, (x, y) -> x - y);
                case "*" -> arith(a, b, (x, y) -> x * y);
                case "/" -> arith(a, b, (x, y) -> x / y);
                case "%" -> arith(a, b, (x, y) -> x % y);
                case "==" -> looseEq(a, b);
                case "!=" -> !looseEq(a, b);
                case "<" -> cmp(a, b) < 0;
                case "<=" -> cmp(a, b) <= 0;
                case ">" -> cmp(a, b) > 0;
                case ">=" -> cmp(a, b) >= 0;
                default -> null;
            };
        }
    }

    // ternary: cond ? a : b
    private record Cond(Node cond, Node a, Node b) implements Node {
        @Override
        public Object eval(Ctx c) {
            return truthy(cond.eval(c)) ? a.eval(c) : b.eval(c);
        }
    }

    // index/key access: base[expr]  (List → element, Map → value, String → char)
    private record Index(Node base, Node idx) implements Node {
        @Override
        public Object eval(Ctx c) {
            Object b = base.eval(c);
            Object k = idx.eval(c);
            if (b instanceof List<?> list) {
                Double n = num(k);
                int i = n == null ? -1 : n.intValue();
                return i >= 0 && i < list.size() ? list.get(i) : null;
            }
            if (b instanceof Map<?, ?> m) {
                return m.get(String.valueOf(k));
            }
            if (b instanceof String s) {
                Double n = num(k);
                int i = n == null ? -1 : n.intValue();
                return i >= 0 && i < s.length() ? String.valueOf(s.charAt(i)) : null;
            }
            return null;
        }
    }

    private record Call(String name, List<Node> args) implements Node {
        @Override
        public Object eval(Ctx c) {
            Object a0 = args.isEmpty() ? null : args.get(0).eval(c);
            Object a1 = args.size() > 1 ? args.get(1).eval(c) : null;
            Object a2 = args.size() > 2 ? args.get(2).eval(c) : null;
            return switch (name) {
                case "length", "count" -> (double) lengthOf(a0);
                case "uppercase" -> str(a0).toUpperCase();
                case "lowercase" -> str(a0).toLowerCase();
                case "trim" -> str(a0).trim();
                case "number" -> num(a0);
                case "string" -> str(a0);
                case "boolean" -> truthy(a0);
                case "round" -> a0 == null ? null : (double) Math.round(dbl(num(a0)));
                case "floor" -> a0 == null ? null : Math.floor(dbl(num(a0)));
                case "ceil" -> a0 == null ? null : Math.ceil(dbl(num(a0)));
                case "abs" -> num(a0) == null ? null : Math.abs(num(a0));
                case "sqrt" -> num(a0) == null ? null : Math.sqrt(dbl(num(a0)));
                case "pow" -> arith(a0, a1, Math::pow);
                case "min" -> arith(a0, a1, Math::min);
                case "max" -> arith(a0, a1, Math::max);
                case "now" -> (double) System.currentTimeMillis();
                case "contains" -> str(a0).contains(str(a1));
                case "startsWith" -> str(a0).startsWith(str(a1));
                case "endsWith" -> str(a0).endsWith(str(a1));
                case "replace" -> str(a0).replace(str(a1), str(a2));
                case "substring" -> substring(str(a0), a1, a2);
                case "split" -> new java.util.ArrayList<>(
                        List.of(str(a0).split(java.util.regex.Pattern.quote(str(a1)))));
                case "keys" -> a0 instanceof Map<?, ?> m
                        ? new java.util.ArrayList<>(m.keySet())
                        : List.of();
                case "sum" -> sumOf(a0);
                default -> throw new IllegalArgumentException("unknown function: " + name);
            };
        }
    }

    private static double dbl(Double d) {
        return d == null ? 0 : d;
    }

    private static String substring(String s, Object a, Object b) {
        Double na = num(a);
        int start = na == null ? 0 : Math.max(0, Math.min(s.length(), na.intValue()));
        Double nb = num(b);
        int end = nb == null ? s.length() : Math.max(start, Math.min(s.length(), nb.intValue()));
        return s.substring(start, end);
    }

    private static Object sumOf(Object v) {
        if (!(v instanceof List<?> list)) {
            return num(v);
        }
        double total = 0;
        for (Object o : list) {
            Double n = num(o);
            if (n != null) {
                total += n;
            }
        }
        return total;
    }

    // ───────── shared value helpers ─────────
    private interface DblOp {
        double apply(double a, double b);
    }

    private static Object arith(Object a, Object b, DblOp op) {
        Double na = num(a);
        Double nb = num(b);
        return na == null || nb == null ? null : op.apply(na, nb);
    }

    private static Object negate(Object v) {
        Double n = num(v);
        return n == null ? null : -n;
    }

    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Collection<?> col) {
            return !col.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    private static boolean looseEq(Object a, Object b) {
        Double na = num(a);
        Double nb = num(b);
        if (na != null && nb != null) {
            return na.doubleValue() == nb.doubleValue();
        }
        if (a == null || b == null) {
            return a == b;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int cmp(Object a, Object b) {
        Double na = num(a);
        Double nb = num(b);
        if (na != null && nb != null) {
            return Double.compare(na, nb);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static int lengthOf(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof String s) {
            return s.length();
        }
        if (v instanceof Collection<?> col) {
            return col.size();
        }
        if (v instanceof Map<?, ?> m) {
            return m.size();
        }
        return String.valueOf(v).length();
    }

    private static Double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // ───────── recursive-descent parser ─────────
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String src) {
            this.s = src == null ? "" : src;
        }

        Node parseAll() {
            Node n = ternary();
            skipWs();
            if (i < s.length()) {
                throw new IllegalArgumentException("unexpected '" + s.charAt(i) + "' in expression");
            }
            return n;
        }

        // lowest precedence: cond ? a : b
        private Node ternary() {
            Node n = or();
            skipWs();
            if (peekChar('?')) {
                i++;
                Node a = ternary();
                skipWs();
                expect(':');
                Node b = ternary();
                return new Cond(n, a, b);
            }
            return n;
        }

        private Node or() {
            Node n = and();
            while (eat("||")) {
                n = new Bin("||", n, and());
            }
            return n;
        }

        private Node and() {
            Node n = cmp();
            while (eat("&&")) {
                n = new Bin("&&", n, cmp());
            }
            return n;
        }

        private Node cmp() {
            Node n = add();
            for (String op : new String[] {"==", "!=", "<=", ">=", "<", ">"}) {
                if (peekOp(op)) {
                    i += op.length();
                    return new Bin(op, n, add());
                }
            }
            if (peekEqualsSingle()) {
                i += 1;
                return new Bin("==", n, add());
            }
            return n;
        }

        private Node add() {
            Node n = mul();
            while (true) {
                skipWs();
                // '&' is string-concat, but must not swallow the '&&' logical-and operator
                if ((peekChar('&') && !peekOp("&&")) || peekChar('+') || peekChar('-')) {
                    char op = s.charAt(i);
                    i++;
                    n = new Bin(String.valueOf(op), n, mul());
                } else {
                    return n;
                }
            }
        }

        private Node mul() {
            Node n = unary();
            while (true) {
                skipWs();
                if (peekChar('*') || peekChar('/') || peekChar('%')) {
                    char op = s.charAt(i);
                    i++;
                    n = new Bin(String.valueOf(op), n, unary());
                } else {
                    return n;
                }
            }
        }

        private Node unary() {
            skipWs();
            if (peekChar('!')) {
                i++;
                return new Un("!", unary());
            }
            if (peekChar('-')) {
                i++;
                return new Un("-", unary());
            }
            return postfix();
        }

        // primary followed by any number of [index] accessors
        private Node postfix() {
            Node n = primary();
            while (true) {
                skipWs();
                if (peekChar('[')) {
                    i++;
                    Node idx = ternary();
                    skipWs();
                    expect(']');
                    n = new Index(n, idx);
                } else {
                    return n;
                }
            }
        }

        private Node primary() {
            skipWs();
            if (peekChar('(')) {
                i++;
                Node n = ternary();
                skipWs();
                expect(')');
                return n;
            }
            char ch = s.charAt(i);
            if (ch == '\'' || ch == '"') {
                return new Lit(readString(ch));
            }
            if (Character.isDigit(ch)) {
                return new Lit(readNumber());
            }
            if (Character.isLetter(ch) || ch == '_') {
                String id = readIdent();
                skipWs();
                if (peekChar('(')) {
                    i++;
                    List<Node> args = new ArrayList<>();
                    skipWs();
                    if (!peekChar(')')) {
                        args.add(ternary());
                        skipWs();
                        while (peekChar(',')) {
                            i++;
                            args.add(ternary());
                            skipWs();
                        }
                    }
                    expect(')');
                    return new Call(id, args);
                }
                return switch (id) {
                    case "true" -> new Lit(Boolean.TRUE);
                    case "false" -> new Lit(Boolean.FALSE);
                    case "null" -> new Lit(null);
                    default -> new Ref(id.split("\\."));
                };
            }
            throw new IllegalArgumentException("unexpected '" + ch + "' in expression");
        }

        private String readString(char quote) {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (i < s.length() && s.charAt(i) != quote) {
                sb.append(s.charAt(i++));
            }
            expect(quote);
            return sb.toString();
        }

        private Double readNumber() {
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            return Double.parseDouble(s.substring(start, i));
        }

        private String readIdent() {
            int start = i;
            while (i < s.length()
                    && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) {
                i++;
            }
            return s.substring(start, i);
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private boolean peekChar(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private boolean peekOp(String op) {
            skipWs();
            return s.regionMatches(i, op, 0, op.length());
        }

        // a single '=' that is NOT part of '==' / '<=' / '>=' / '!='
        private boolean peekEqualsSingle() {
            skipWs();
            return i < s.length() && s.charAt(i) == '='
                    && (i + 1 >= s.length() || s.charAt(i + 1) != '=');
        }

        private boolean eat(String op) {
            if (peekOp(op)) {
                i += op.length();
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException("expected '" + c + "' in expression");
            }
            i++;
        }
    }
}
