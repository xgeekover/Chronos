package io.chronos.flow;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

/**
 * The {@code function} flow node's body compiler (Node-RED's function node, in Java). The user writes
 * the body of:
 *
 * <pre>{@code Object run(Map<String,Object> msg, Map<String,Object> flow, Map<String,Object> global)}</pre>
 *
 * returning the new payload (or a whole msg Map). {@code flow}/{@code global} are mutable context maps
 * (Node-RED's flow/global context). Compiled once via ECJ (works on a plain JRE, ADR-005); a source
 * blocklist applies the same trusted-admin posture as the script adapter.
 * ⚠️ In-process Java is hardened, not a true sandbox — flows are ADMIN-deployed.
 */
final class FlowFunction {

    private static final String CLASS_NAME = "ChronosFlowFn";
    private static final String PREFIX =
            "import java.util.*;\n"
            + "public class " + CLASS_NAME + " {\n"
            + "  public static Object run(java.util.Map<String,Object> msg,"
            + " java.util.Map<String,Object> flow, java.util.Map<String,Object> global)"
            + " throws Exception {\n";
    private static final String SUFFIX = "\n  }\n}\n";

    private static final Pattern BANNED = Pattern.compile(
            "\\bRuntime\\b|\\bProcessBuilder\\b|\\bProcessHandle\\b|\\bClassLoader\\b"
            + "|System\\s*\\.\\s*(exit|getenv|getProperty|setProperty|load|loadLibrary)"
            + "|java\\.lang\\.reflect|Class\\s*\\.\\s*forName|\\.exec\\s*\\(|java\\.io\\.File|java\\.nio"
            + "|\\bFiles\\b|\\bPaths\\b|\\bSocket\\b|\\bThread\\b|sun\\.|javax\\.script"
            + "|java\\.net|\\bURL\\b|\\bURLConnection\\b|\\bHttpClient\\b");

    private final Method method;

    FlowFunction(String userCode) {
        try {
            this.method = compile(userCode).getMethod("run", Map.class, Map.class, Map.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("function compile error: " + e.getMessage(), e);
        }
    }

    Object run(Map<String, Object> msg, Map<String, Object> flow, Map<String, Object> global) {
        try {
            return method.invoke(null, msg, flow, global);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("function failed: " + cause.getMessage(), cause);
        }
    }

    private static Class<?> compile(String userCode) {
        if (userCode == null || userCode.isBlank()) {
            throw new IllegalArgumentException("function is empty");
        }
        if (userCode.contains("\\u")) {
            throw new IllegalArgumentException("unicode escapes are not allowed in functions");
        }
        var banned = BANNED.matcher(userCode);
        if (banned.find()) {
            throw new IllegalArgumentException("disallowed reference: '" + banned.group() + "'");
        }
        String source = PREFIX + userCode + SUFFIX;
        JavaCompiler compiler = new org.eclipse.jdt.internal.compiler.tool.EclipseCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path dir = null;
        try {
            dir = Files.createTempDirectory("chronos-fn");
            Path srcFile = dir.resolve(CLASS_NAME + ".java");
            Files.writeString(srcFile, source);
            try (StandardJavaFileManager fm =
                    compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(dir.toFile()));
                var units = fm.getJavaFileObjectsFromFiles(List.of(srcFile.toFile()));
                boolean ok = compiler.getTask(null, fm, diagnostics,
                        List.of("-source", "17", "-target", "17", "-proc:none"), null, units).call();
                if (!ok) {
                    throw new IllegalArgumentException(firstError(diagnostics));
                }
            }
            try (URLClassLoader loader =
                    new URLClassLoader(new URL[] {dir.toUri().toURL()}, FlowFunction.class.getClassLoader())) {
                return loader.loadClass(CLASS_NAME);
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalArgumentException("function compilation failed: " + e.getMessage(), e);
        } finally {
            deleteQuietly(dir);
        }
    }

    private static String firstError(DiagnosticCollector<JavaFileObject> diagnostics) {
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                return d.getMessage(null);
            }
        }
        return "compilation failed";
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
