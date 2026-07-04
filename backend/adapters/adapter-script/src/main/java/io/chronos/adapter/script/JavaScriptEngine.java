package io.chronos.adapter.script;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

/**
 * Compiles and runs <b>pure Java</b> task scripts using the Eclipse Compiler (ECJ), so it works on
 * a plain JRE without a system JDK (ADR-005). The user writes the body of:
 *
 * <pre>{@code public static Map<String,Object> run() throws Exception { <user code> }}</pre>
 *
 * returning a map of tag → value. Compilation goes through a short-lived temp dir (ECJ's JSR-199
 * tool needs file-backed units). A source-level blocklist + execution timeout guard it.
 * ⚠️ In-process Java is hardened but NOT a true sandbox — fit for trusted-admin scripts (Q5).
 */
final class JavaScriptEngine {

    private static final String CLASS_NAME = "ChronosUserScript";
    private static final String PREFIX =
            "import java.util.*;\n"
            + "public class " + CLASS_NAME + " {\n"
            + "  public static java.util.Map<String,Object> run() throws Exception {\n";
    private static final String SUFFIX = "\n  }\n}\n";
    private static final int PREFIX_LINES = 3; // user code starts on source line 4

    // Best-effort defense-in-depth blocklist (trusted-admin posture). Not a substitute for isolation.
    private static final Pattern BANNED = Pattern.compile(
            "\\bRuntime\\b|\\bProcessBuilder\\b|\\bProcessHandle\\b|\\bClassLoader\\b"
            + "|System\\s*\\.\\s*(exit|getenv|getProperty|setProperty|load|loadLibrary)" // exit + env/property/native
            + "|java\\.lang\\.reflect|Class\\s*\\.\\s*forName|\\.exec\\s*\\(|java\\.io\\.File|java\\.nio"
            + "|\\bFiles\\b|\\bPaths\\b|\\bSocket\\b|\\bThread\\b|sun\\.|javax\\.script"
            + "|java\\.net|\\bURL\\b|\\bURLConnection\\b|\\bHttpClient\\b"); // network exfiltration

    /** Thrown for compile errors, security violations, or timeouts; carries an optional user line. */
    static final class ScriptError extends RuntimeException {
        final Integer line;

        ScriptError(String message, Integer line) {
            super(message);
            this.line = line;
        }
    }

    /** Compile-only validation (no execution). Throws {@link ScriptError} on any problem. */
    void validate(String userCode) {
        compile(userCode);
    }

    /** Compile + run the script with a timeout; returns the produced tag map. */
    @SuppressWarnings("unchecked")
    Map<String, Object> run(String userCode, Duration timeout) {
        Class<?> clazz = compile(userCode);
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "chronos-java-script");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<Object> f = exec.submit(() -> clazz.getMethod("run").invoke(null));
            Object result = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw new ScriptError("script must return a Map<String,Object>", null);
        } catch (TimeoutException e) {
            throw new ScriptError("script timed out after " + timeout.toMillis() + "ms", null);
        } catch (ScriptError e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ScriptError("script failed: " + cause.getMessage(), null);
        } finally {
            exec.shutdownNow();
        }
    }

    private Class<?> compile(String userCode) {
        if (userCode == null || userCode.isBlank()) {
            throw new ScriptError("script is empty", null);
        }
        // Reject unicode escapes — `Runtime` compiles to `Runtime` but slips past the blocklist.
        if (userCode.contains("\\u")) {
            throw new ScriptError("unicode escapes are not allowed in scripts", null);
        }
        var banned = BANNED.matcher(userCode);
        if (banned.find()) {
            throw new ScriptError("disallowed reference: '" + banned.group() + "'", null);
        }

        String source = PREFIX + userCode + SUFFIX;
        JavaCompiler compiler = new org.eclipse.jdt.internal.compiler.tool.EclipseCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path dir = null;
        try {
            dir = Files.createTempDirectory("chronos-script");
            Path srcFile = dir.resolve(CLASS_NAME + ".java");
            Files.writeString(srcFile, source);

            try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(dir.toFile()));
                var units = fm.getJavaFileObjectsFromFiles(List.of(srcFile.toFile()));
                boolean ok = compiler.getTask(null, fm, diagnostics,
                        List.of("-source", "17", "-target", "17", "-proc:none"), null, units).call();
                if (!ok) {
                    throw firstError(diagnostics);
                }
            }
            try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
                // class bytecode is fully loaded here; the temp dir can be removed afterwards.
                return loader.loadClass(CLASS_NAME);
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new ScriptError("script compilation failed: " + e.getMessage(), null);
        } finally {
            deleteQuietly(dir);
        }
    }

    private static ScriptError firstError(DiagnosticCollector<JavaFileObject> diagnostics) {
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                int line = Math.max(1, (int) d.getLineNumber() - PREFIX_LINES);
                return new ScriptError(d.getMessage(null), line);
            }
        }
        return new ScriptError("compilation failed", null);
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
