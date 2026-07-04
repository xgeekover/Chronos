package io.chronos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.chronos.app.persistence.FlowDefEntity;
import io.chronos.app.persistence.FlowDefRepository;
import io.chronos.app.service.FlowProjectService;
import io.chronos.app.service.FlowProjectService.CommitInfo;
import io.chronos.app.service.FlowProjectService.CommitResult;
import io.chronos.app.service.FlowProjectService.DiffResult;
import io.chronos.app.service.FlowProjectService.FileChange;
import io.chronos.app.service.FlowProjectService.ProjectStatus;
import io.chronos.app.service.FlowProjectService.RevertResult;
import java.util.HashMap;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-tests the Projects git service against an in-memory {@link FlowDefRepository} stub and a
 * throwaway git repo under {@code @TempDir} — commit → history → dirty-detection → revert (restore).
 */
class FlowProjectServiceTest {

    private final Map<String, FlowDefEntity> store = new LinkedHashMap<>();
    private FlowProjectService svc;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        FlowDefRepository repo = mock(FlowDefRepository.class);
        when(repo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        when(repo.save(any(FlowDefEntity.class))).thenAnswer(inv -> {
            FlowDefEntity e = inv.getArgument(0);
            store.put(e.getId(), e);
            return e;
        });
        when(repo.findByOwnerOrderByUpdatedAtDesc(anyString())).thenAnswer(inv -> store.values().stream()
                .filter(e -> inv.getArgument(0).equals(e.getOwner()))
                .toList());
        doAnswer(inv -> {
            store.remove(inv.<FlowDefEntity>getArgument(0).getId());
            return null;
        })
                .when(repo)
                .delete(any(FlowDefEntity.class));

        svc = new FlowProjectService(tmp.toString(), repo);
    }

    private void seed(String id, String owner, String name, Map<String, Object> graph) {
        FlowDefEntity e = new FlowDefEntity();
        e.setId(id);
        e.setOwner(owner);
        e.setName(name);
        e.setGraph(graph);
        store.put(id, e);
    }

    private static Map<String, Object> graph(String node) {
        return new LinkedHashMap<>(Map.of("nodes", List.of(Map.of("id", node, "type", "inject")), "edges", List.of()));
    }

    @Test
    void commitHistoryStatusAndRevertRoundTrip() {
        // fresh repo: not initialized, but DB already has flows → dirty-ish
        ProjectStatus before = svc.status("admin");
        assertThat(before.initialized()).isFalse();

        seed("a", "admin", "Alpha", graph("i1"));
        seed("b", "admin", "Beta", graph("i2"));

        CommitResult first = svc.commit("admin", "first snapshot");
        assertThat(first.committed()).isTrue();
        assertThat(first.commit().message()).isEqualTo("first snapshot");

        // clean now — a second commit with no changes is a no-op
        assertThat(svc.status("admin").dirty()).isFalse();
        assertThat(svc.commit("admin", "noop").committed()).isFalse();

        // add a third flow → dirty → commit
        seed("c", "admin", "Gamma", graph("i3"));
        assertThat(svc.status("admin").dirty()).isTrue();
        CommitResult second = svc.commit("admin", "add gamma");
        assertThat(second.committed()).isTrue();

        List<CommitInfo> log = svc.history("admin", 50);
        assertThat(log).hasSize(2);
        assertThat(log.get(0).message()).isEqualTo("add gamma"); // newest first
        assertThat(log.get(1).message()).isEqualTo("first snapshot");
        assertThat(svc.status("admin").commits()).isEqualTo(2);

        // revert to the first commit → gamma removed, a & b restored
        String firstId = log.get(1).id();
        RevertResult r = svc.revert("admin", firstId);
        assertThat(r.restored()).isEqualTo(2);
        assertThat(r.removed()).isEqualTo(1);
        assertThat(store.keySet()).containsExactlyInAnyOrder("a", "b");
        assertThat(store).doesNotContainKey("c");
    }

    private static HashMap<String, String> byFile(DiffResult d) {
        HashMap<String, String> m = new HashMap<>();
        for (FileChange fc : d.files()) {
            m.put(fc.file(), fc.status());
        }
        return m;
    }

    @Test
    void uncommittedDiffShowsAddModifyDelete() {
        seed("a", "admin", "Alpha", graph("i1"));
        seed("b", "admin", "Beta", graph("i2"));
        svc.commit("admin", "base");

        seed("a", "admin", "Alpha", graph("i1-CHANGED")); // modify a
        store.remove("b"); // delete b
        seed("c", "admin", "Gamma", graph("i3")); // add c

        DiffResult d = svc.diffUncommitted("admin");
        assertThat(byFile(d))
                .containsEntry("a", "MODIFIED")
                .containsEntry("b", "DELETED")
                .containsEntry("c", "ADDED");
        FileChange a = d.files().stream().filter(f -> f.file().equals("a")).findFirst().orElseThrow();
        assertThat(a.added()).isGreaterThan(0);
        assertThat(a.removed()).isGreaterThan(0);
        assertThat(a.patch()).isNotBlank();
    }

    @Test
    void diffToCommitPreviewsWhatRevertWouldDo() {
        seed("a", "admin", "Alpha", graph("i1"));
        seed("b", "admin", "Beta", graph("i2"));
        svc.commit("admin", "base");
        String base = svc.history("admin", 50).get(0).id();

        seed("a", "admin", "Alpha", graph("i1-CHANGED"));
        store.remove("b");
        seed("c", "admin", "Gamma", graph("i3"));

        // current → base: a changes back (MODIFIED), b returns (ADDED), c goes away (DELETED)
        DiffResult d = svc.diffToCommit("admin", base);
        assertThat(byFile(d))
                .containsEntry("a", "MODIFIED")
                .containsEntry("b", "ADDED")
                .containsEntry("c", "DELETED");
    }

    @Test
    void revertRejectsUnknownCommit() {
        seed("a", "admin", "Alpha", graph("i1"));
        svc.commit("admin", "seed");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> svc.revert("admin", "deadbeef"));
    }

    @Test
    void usersAreIsolatedIntoSeparateRepos() {
        seed("a", "admin", "Alpha", graph("i1"));
        seed("z", "operator", "Zed", graph("i9"));

        svc.commit("admin", "admin flows");
        // operator has its own repo with no commits yet
        assertThat(svc.history("operator", 50)).isEmpty();

        svc.commit("operator", "operator flows");
        assertThat(svc.history("admin", 50)).hasSize(1);
        assertThat(svc.history("operator", 50)).hasSize(1);
    }
}
