package io.chronos.adapter.shell;

import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.Capabilities;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceAdapter;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.pf4j.Extension;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;

/**
 * Runs a command locally ({@link ProcessBuilder}) or remotely (Apache MINA SSHD), §2 HOST device.
 *
 * <p>Security (ADR-006, §11): the command binary must be in the device {@code allowlist};
 * arguments are passed as an array (local) so the shell never interpolates them; execution has a
 * timeout and the captured output is size-capped. Task definition: {@code command} (required),
 * {@code args} (array). Device config params: {@code mode} (LOCAL|SSH), {@code allowlist},
 * {@code host}/{@code port} (SSH), {@code maxOutputBytes}; SSH credentials in secrets.
 */
@Extension
public class ShellDeviceAdapter implements DeviceAdapter {

    private static final long DEFAULT_MAX_OUTPUT = 1L << 20; // 1 MiB

    @Override
    public String type() {
        return "SHELL";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(TaskType.SHELL);
    }

    @Override
    public void validate(DeviceConfig config) throws AdapterException {
        if (allowlist(config).isEmpty()) {
            throw new AdapterException("SHELL device requires a non-empty command allowlist (security)");
        }
    }

    @Override
    public RawResult collect(DeviceConfig config, CollectRequest request) throws AdapterException {
        String command = str(request.definition().get("command"));
        if (command == null || command.isBlank()) {
            throw new AdapterException("SHELL task definition is missing 'command'");
        }
        // Deny-by-default: an empty allowlist means nothing is permitted (never fail open).
        Set<String> allow = allowlist(config);
        if (allow.isEmpty()) {
            throw new AdapterException("SHELL device has no command allowlist — refusing to run (security)");
        }
        if (!allow.contains(command)) {
            throw new AdapterException("command not allowlisted: " + command);
        }
        List<String> args = toStringList(request.definition().get("args"));
        long maxBytes = longOr(config.params().get("maxOutputBytes"), DEFAULT_MAX_OUTPUT);

        String mode = String.valueOf(config.params().getOrDefault("mode", "LOCAL")).toUpperCase();
        String output = "SSH".equals(mode)
                ? runSsh(config, command, args, request.timeout(), maxBytes)
                : runLocal(command, args, request.timeout(), maxBytes);
        return RawResult.ofText(output, Instant.now());
    }

    // ---- local --------------------------------------------------------------

    private String runLocal(String command, List<String> args, Duration timeout, long maxBytes)
            throws AdapterException {
        List<String> cmd = new ArrayList<>();
        cmd.add(command); // arg-array form: no shell, no injection
        cmd.addAll(args);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // Drain stdout concurrently so the timeout actually fires and the pipe never deadlocks.
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> copyCapped(p.getInputStream(), sink, maxBytes), "shell-reader");
            reader.setDaemon(true);
            reader.start();

            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                reader.interrupt();
                throw new AdapterException("command timed out after " + timeout.toMillis() + "ms");
            }
            reader.join(1000);
            String out = sink.toString(StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                throw new AdapterException("command exited with " + p.exitValue() + ": " + snippet(out));
            }
            return out;
        } catch (AdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new AdapterException("local command failed: " + e.getMessage(), e);
        }
    }

    // ---- remote (SSH) -------------------------------------------------------

    private String runSsh(DeviceConfig config, String command, List<String> args, Duration timeout, long maxBytes)
            throws AdapterException {
        String host = str(config.params().get("host"));
        int port = (int) longOr(config.params().get("port"), 22);
        String user = config.secrets().get("username");
        String password = config.secrets().get("password");
        if (host == null || user == null) {
            throw new AdapterException("SSH mode requires params.host and secrets.username");
        }
        String remoteCommand = command + " " + args.stream().map(ShellDeviceAdapter::shellQuote)
                .collect(Collectors.joining(" "));
        try (SshClient client = SshClient.setUpDefaultClient()) {
            // NOTE: AcceptAll host-key verification — production should pin known_hosts.
            client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
            client.start();
            try (ClientSession session = client.connect(user, host, port)
                    .verify(timeout.toMillis(), TimeUnit.MILLISECONDS).getSession()) {
                if (password != null) {
                    session.addPasswordIdentity(password);
                }
                session.auth().verify(timeout.toMillis(), TimeUnit.MILLISECONDS);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                session.executeRemoteCommand(remoteCommand.trim(), out, out, StandardCharsets.UTF_8);
                byte[] bytes = out.toByteArray();
                int len = (int) Math.min(bytes.length, maxBytes);
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new AdapterException("SSH command failed: " + e.getMessage(), e);
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static Set<String> allowlist(DeviceConfig config) {
        return Set.copyOf(toStringList(config.params().get("allowlist")));
    }

    /** Copy up to {@code maxBytes} from {@code in} into {@code out}; best-effort (used off-thread). */
    private static void copyCapped(InputStream in, ByteArrayOutputStream out, long maxBytes) {
        byte[] chunk = new byte[8192];
        long total = 0;
        try {
            int read;
            while (total < maxBytes && (read = in.read(chunk)) != -1) {
                int allowed = (int) Math.min(read, maxBytes - total);
                out.write(chunk, 0, allowed);
                total += allowed;
            }
        } catch (Exception ignored) {
            // process killed / stream closed — return what we captured
        }
    }

    /** Single-quote an argument for safe inclusion in a remote shell command. */
    private static String shellQuote(String arg) {
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    private static String snippet(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object o) {
        if (o instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (o instanceof String s && !s.isBlank()) {
            return List.of(s.split("\\s*,\\s*"));
        }
        return List.of();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long longOr(Object o, long def) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? def : Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
