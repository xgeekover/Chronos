package io.chronos.app.service;

import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.Secrets;
import io.chronos.app.persistence.DeviceEntity;
import io.chronos.app.persistence.DeviceRepository;
import io.chronos.app.persistence.TaskEntity;
import io.chronos.app.persistence.TaskRepository;
import io.chronos.app.security.SecretCipher;
import io.chronos.engine.plugin.PluginRuntime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD + connection test for Devices. Encrypts credentials before persisting (ADR-008). */
@Service
public class DeviceService {

    private final DeviceRepository repo;
    private final SecretCipher cipher;
    private final PluginRuntime plugins;
    private final TaskRepository taskRepo;
    private final TaskService taskService;

    // @Lazy TaskService breaks the construction cycle (TaskService depends on DeviceService).
    public DeviceService(DeviceRepository repo, SecretCipher cipher, PluginRuntime plugins,
            TaskRepository taskRepo, @Lazy TaskService taskService) {
        this.repo = repo;
        this.cipher = cipher;
        this.plugins = plugins;
        this.taskRepo = taskRepo;
        this.taskService = taskService;
    }

    @Transactional
    public DeviceEntity create(String name, String type, String adapterType,
            Map<String, Object> params, Map<String, String> secrets) {
        if (repo.existsByName(name)) {
            throw new IllegalArgumentException("device name already exists: " + name);
        }
        DeviceEntity d = new DeviceEntity();
        d.setName(name);
        d.setType(type);
        d.setAdapterType(adapterType);
        d.setConnectionConfigMeta(params == null ? Map.of() : params);
        d.setConnectionConfigEnc(cipher.encrypt(secrets));
        d.setStatus("CONFIGURED");
        return repo.save(d);
    }

    public List<DeviceEntity> list() {
        return repo.findAll();
    }

    public DeviceEntity get(UUID id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("device not found: " + id));
    }

    @Transactional
    public DeviceEntity update(UUID id, String name, String type, String adapterType,
            Map<String, Object> params, Map<String, String> secrets) {
        DeviceEntity d = get(id);
        if (name != null && !name.equals(d.getName())) {
            if (repo.existsByName(name)) {
                throw new IllegalArgumentException("device name already exists: " + name);
            }
            d.setName(name);
        }
        if (type != null) {
            d.setType(type);
        }
        if (adapterType != null) {
            d.setAdapterType(adapterType);
        }
        if (params != null) {
            d.setConnectionConfigMeta(params);
        }
        if (secrets != null) {
            d.setConnectionConfigEnc(cipher.encrypt(secrets)); // re-encrypt updated credentials
        }
        return repo.save(d);
    }

    @Transactional
    public void delete(UUID id) {
        // cascade: drop tasks bound to this device (TaskService.delete unschedules + cleans mappings)
        for (TaskEntity t : taskRepo.findByDeviceId(id)) {
            taskService.delete(t.getId());
        }
        repo.deleteById(id);
    }

    /** Build the adapter-facing config (decrypts secrets). Used by the collection pipeline. */
    public DeviceConfig toDeviceConfig(DeviceEntity d) {
        return new DeviceConfig(d.getAdapterType(), d.getConnectionConfigMeta(),
                new Secrets(cipher.decrypt(d.getConnectionConfigEnc())));
    }

    /** Run the adapter's connection test (§10 "접속테스트"). Not @Transactional: the OK/ERROR status
     *  must be committed even when the test fails (a rollback would discard the ERROR write). */
    public void validate(UUID id) {
        DeviceEntity d = get(id);
        var adapter = plugins.find(d.getAdapterType())
                .orElseThrow(() -> new IllegalStateException("no adapter for type " + d.getAdapterType()));
        try {
            adapter.validate(toDeviceConfig(d));
            d.setStatus("OK");
            repo.save(d);
        } catch (Exception e) {
            d.setStatus("ERROR");
            repo.save(d); // committed (no surrounding tx) before the error propagates
            throw new IllegalStateException("connection test failed: " + e.getMessage(), e);
        }
    }
}
