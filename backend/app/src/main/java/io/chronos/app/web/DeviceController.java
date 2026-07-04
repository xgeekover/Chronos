package io.chronos.app.web;

import io.chronos.app.persistence.DeviceEntity;
import io.chronos.app.service.DeviceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Device master CRUD + connection test (§10 관리 UI). */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    public record CreateDevice(
            @NotBlank String name,
            @NotBlank String type,
            @NotBlank String adapterType,
            Map<String, Object> params,
            Map<String, String> secrets) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceEntity create(@Valid @RequestBody CreateDevice req) {
        return service.create(req.name(), req.type(), req.adapterType(), req.params(), req.secrets());
    }

    @GetMapping
    public List<DeviceEntity> list() {
        return service.list();
    }

    /** Live connection-pool status (active/idle/total per pooled DB). Literal path beats /{id}. */
    @GetMapping("/pools")
    public List<Map<String, Object>> pools() {
        return service.poolStats();
    }

    @GetMapping("/{id}")
    public DeviceEntity get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public DeviceEntity update(@PathVariable UUID id, @RequestBody CreateDevice req) {
        return service.update(id, req.name(), req.type(), req.adapterType(), req.params(), req.secrets());
    }

    @PostMapping("/{id}/validate")
    public Map<String, Object> validate(@PathVariable UUID id) {
        service.validate(id);
        return Map.of("status", "OK");
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
