package io.chronos.app.web;

import io.chronos.app.service.FlowProjectService;
import io.chronos.app.service.FlowProjectService.CommitInfo;
import io.chronos.app.service.FlowProjectService.CommitResult;
import io.chronos.app.service.FlowProjectService.DiffResult;
import io.chronos.app.service.FlowProjectService.ProjectStatus;
import io.chronos.app.service.FlowProjectService.RevertResult;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Node-RED-style <b>Projects</b> REST surface — git versioning of the caller's flow library.
 * Reads (status/history) are open to any authenticated user (owner-scoped); writes (commit/revert)
 * are ADMIN-only via SecurityConfig's {@code POST /api/** = ROLE_ADMIN} catch-all.
 */
@RestController
@RequestMapping("/api/projects")
public class FlowProjectController {

    private final FlowProjectService projects;

    public FlowProjectController(FlowProjectService projects) {
        this.projects = projects;
    }

    public record CommitRequest(String message) {}

    public record RevertRequest(String commit) {}

    /** Repo status: initialized? dirty (uncommitted DB changes)? commit count + HEAD. */
    @GetMapping("/status")
    public ProjectStatus status(@AuthenticationPrincipal Jwt jwt) {
        return projects.status(jwt.getSubject());
    }

    /** Commit history (newest first). */
    @GetMapping("/history")
    public List<CommitInfo> history(
            @RequestParam(defaultValue = "50") int limit, @AuthenticationPrincipal Jwt jwt) {
        return projects.history(jwt.getSubject(), limit);
    }

    /**
     * Diff: with {@code ?commit=<id>}, previews what reverting to that commit would change (current →
     * commit); without a commit, shows the uncommitted changes (HEAD → current library).
     */
    @GetMapping("/diff")
    public ResponseEntity<?> diff(
            @RequestParam(required = false) String commit, @AuthenticationPrincipal Jwt jwt) {
        try {
            DiffResult d = commit == null || commit.isBlank()
                    ? projects.diffUncommitted(jwt.getSubject())
                    : projects.diffToCommit(jwt.getSubject(), commit);
            return ResponseEntity.ok(d);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Snapshot the current flow library into a new commit. */
    @PostMapping("/commit")
    public CommitResult commit(@RequestBody(required = false) CommitRequest req, @AuthenticationPrincipal Jwt jwt) {
        return projects.commit(jwt.getSubject(), req == null ? null : req.message());
    }

    /** Restore the flow library to a chosen commit. */
    @PostMapping("/revert")
    public ResponseEntity<?> revert(@RequestBody RevertRequest req, @AuthenticationPrincipal Jwt jwt) {
        if (req == null || req.commit() == null || req.commit().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "commit is required"));
        }
        try {
            RevertResult r = projects.revert(jwt.getSubject(), req.commit());
            return ResponseEntity.ok(r);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
