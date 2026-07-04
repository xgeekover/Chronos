package io.chronos.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-GCM (256) encryption for device connection secrets (ADR-008, 불변 규칙 4). The key comes
 * from {@code chronos.secret-key} (base64, 32 bytes) — externalize via env/Vault in production.
 * Output layout: {@code [12-byte IV][ciphertext+tag]}.
 *
 * <p>If no key is configured an ephemeral one is generated with a warning: encryption works
 * within the running process but stored ciphertext cannot be decrypted after a restart.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    // Own instance — only used for the tiny secrets Map<->JSON round-trip, so it does not
    // depend on the application's (optional) ObjectMapper bean.
    private final ObjectMapper mapper = new ObjectMapper();
    private final String configuredKey;
    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec key;

    public SecretCipher(@Value("${chronos.secret-key:}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    @PostConstruct
    void init() throws Exception {
        if (configuredKey != null && !configuredKey.isBlank()) {
            byte[] raw = Base64.getDecoder().decode(configuredKey.trim());
            if (raw.length != 32) {
                throw new IllegalStateException("chronos.secret-key must be base64 of 32 bytes (AES-256)");
            }
            this.key = new SecretKeySpec(raw, "AES");
        } else {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            this.key = new SecretKeySpec(kg.generateKey().getEncoded(), "AES");
            log.warn("No 'chronos.secret-key' configured — using an EPHEMERAL key. Stored "
                    + "credentials will not be decryptable after a restart. Set it in production.");
        }
    }

    /** Marker prefix for an encrypted single-value token (used by flow graph secrets). */
    public static final String ENC_PREFIX = "enc:";

    public boolean isEncrypted(String s) {
        return s != null && s.startsWith(ENC_PREFIX);
    }

    /** Encrypt one string into a self-describing {@code enc:<base64>} token (idempotent). */
    public String encryptString(String plain) {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) {
            return plain;
        }
        return ENC_PREFIX + Base64.getEncoder().encodeToString(encrypt(Map.of("v", plain)));
    }

    /** Decrypt an {@code enc:<base64>} token; returns the input unchanged if not encrypted. */
    public String decryptString(String token) {
        if (!isEncrypted(token)) {
            return token;
        }
        byte[] data = Base64.getDecoder().decode(token.substring(ENC_PREFIX.length()));
        return decrypt(data).getOrDefault("v", "");
    }

    /** Encrypt a secrets map (e.g. username/password) to {@code [IV][ciphertext]}. */
    public byte[] encrypt(Map<String, String> secrets) {
        try {
            byte[] plaintext = mapper.writeValueAsBytes(secrets == null ? Map.of() : secrets);
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("secret encryption failed", e);
        }
    }

    /** Decrypt {@code [IV][ciphertext]} back to the secrets map. */
    @SuppressWarnings("unchecked")
    public Map<String, String> decrypt(byte[] data) {
        if (data == null || data.length <= IV_BYTES) {
            return Map.of();
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(data, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
            return mapper.readValue(new String(plaintext, StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("secret decryption failed", e);
        }
    }
}
