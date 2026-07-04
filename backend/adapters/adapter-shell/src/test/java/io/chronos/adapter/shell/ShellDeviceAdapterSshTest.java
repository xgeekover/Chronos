package io.chronos.adapter.shell;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.Secrets;
import io.chronos.api.adapter.TaskType;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.shell.ProcessShellCommandFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real SSH round-trip against an embedded MINA SSHD server (executes exec commands as host processes). */
class ShellDeviceAdapterSshTest {

    private final ShellDeviceAdapter adapter = new ShellDeviceAdapter();
    private SshServer sshd;

    @BeforeEach
    void startServer() throws Exception {
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(KeyPairGenerator.getInstance("RSA").generateKeyPair()));
        sshd.setPasswordAuthenticator((u, p, s) -> "tester".equals(u) && "pw".equals(p));
        sshd.setCommandFactory(ProcessShellCommandFactory.INSTANCE);
        sshd.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (sshd != null) sshd.stop(true);
    }

    @Test
    void runsAllowlistedCommandOverSsh() throws Exception {
        DeviceConfig cfg = new DeviceConfig("SHELL",
                Map.of("mode", "SSH", "host", "127.0.0.1", "port", sshd.getPort(), "allowlist", List.of("echo")),
                new Secrets(Map.of("username", "tester", "password", "pw")));
        CollectRequest req = new CollectRequest("t1", "n1", TaskType.SHELL,
                Map.of("command", "echo", "args", List.of("hello-ssh")), Duration.ofSeconds(10));

        RawResult raw = adapter.collect(cfg, req);
        assertTrue(raw.text().contains("hello-ssh"), () -> "got: " + raw.text());
    }
}
