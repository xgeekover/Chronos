package io.chronos.app.service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.chronos.app.persistence.FlowDefEntity;
import io.chronos.app.persistence.FlowDefRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Node-RED-style <b>Projects</b>: server-side git versioning of a user's flow library. Each user gets
 * their own git repo (a subdirectory of {@code chronos.projects.dir}) whose {@code flows/} directory
 * mirrors that user's {@link FlowDefEntity} rows, one {@code <flowId>.json} file each. Commit snapshots
 * the current library; history is {@code git log}; revert restores the DB from a chosen commit.
 *
 * <p>Flow graphs are stored exactly as persisted (secret fields already AES-encrypted at rest by
 * {@code FlowDefController}), so plaintext credentials never reach the repo. Writes (commit/revert)
 * are ADMIN-gated by the existing {@code POST/PUT/DELETE /api/** = ROLE_ADMIN} rule in SecurityConfig.
 */
@Service
public class FlowProjectService {

    private static final Logger log = LoggerFactory.getLogger(FlowProjectService.class);
    private static final String FLOWS = "flows";

    private final Path baseDir;
    private final FlowDefRepository repo;
    // deterministic JSON (sorted keys) so identical DB state → identical bytes → no spurious diffs
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    public FlowProjectService(
            @Value("${chronos.projects.dir:data/flow-projects}") String dir, FlowDefRepository repo) {
        this.baseDir = Path.of(dir);
        this.repo = repo;
    }

    /** One entry in the commit history. */
    public record CommitInfo(String id, String shortId, String message, String author, Instant at) {}

    /** Repo status for the Projects panel: last commit + whether the DB differs from it. */
    public record ProjectStatus(boolean initialized, boolean dirty, int commits, CommitInfo head) {}

    /** Result of a commit attempt. */
    public record CommitResult(boolean committed, String message, CommitInfo commit) {}

    /** Result of a revert: how many flows were restored / removed to match the snapshot. */
    public record RevertResult(String commit, int restored, int removed) {}

    /** One changed flow file in a diff. {@code status} = ADDED | MODIFIED | DELETED. */
    public record FileChange(String file, String status, int added, int removed, String patch) {}

    /** A diff between two snapshots ({@code from} → {@code to}), one entry per changed flow file. */
    public record DiffResult(String from, String to, List<FileChange> files) {}

    // ── public API ──────────────────────────────────────────────────────────────

    /** Snapshot the user's current flow library into a git commit. No-op (committed=false) if clean. */
    public synchronized CommitResult commit(String user, String message) {
        try (Git git = openOrInit(user)) {
            writeWorkingTree(user, git.getRepository().getWorkTree().toPath());
            git.add().addFilepattern(".").call();
            // stage deletions of tracked files that no longer exist (like `git commit -a`)
            if (git.status().call().isClean()) {
                return new CommitResult(false, "nothing to commit — library matches last snapshot", head(git));
            }
            String msg = message == null || message.isBlank() ? "Update flows" : message.strip();
            PersonIdent who = new PersonIdent(user, user + "@chronos");
            RevCommit c = git.commit().setAll(true).setAuthor(who).setCommitter(who).setMessage(msg).call();
            log.info("Projects: committed {} flows for {} ({})", countFlows(user), user, c.abbreviate(7).name());
            return new CommitResult(true, "committed", toInfo(c));
        } catch (GitAPIException | IOException e) {
            throw new IllegalStateException("git commit failed: " + e.getMessage(), e);
        }
    }

    /** The commit history (newest first), up to {@code limit}. */
    public synchronized List<CommitInfo> history(String user, int limit) {
        Path dir = repoDir(user);
        if (!Files.exists(dir.resolve(".git"))) {
            return List.of();
        }
        try (Git git = Git.open(dir.toFile())) {
            if (git.getRepository().resolve("HEAD") == null) {
                return List.of(); // repo initialized but no commits yet
            }
            List<CommitInfo> out = new ArrayList<>();
            for (RevCommit c : git.log().setMaxCount(Math.max(1, limit)).call()) {
                out.add(toInfo(c));
            }
            return out;
        } catch (GitAPIException | IOException e) {
            throw new IllegalStateException("git log failed: " + e.getMessage(), e);
        }
    }

    /** Whether the repo exists, how many commits, and whether the DB has uncommitted changes. */
    public synchronized ProjectStatus status(String user) {
        Path dir = repoDir(user);
        if (!Files.exists(dir.resolve(".git"))) {
            return new ProjectStatus(false, hasFlows(user), 0, null);
        }
        try (Git git = Git.open(dir.toFile())) {
            CommitInfo headInfo = head(git);
            int commits = 0;
            if (git.getRepository().resolve("HEAD") != null) {
                for (RevCommit ignored : git.log().call()) {
                    commits++;
                }
            }
            boolean dirty = !committedSnapshot(git).equals(currentSnapshot(user));
            return new ProjectStatus(true, dirty, commits, headInfo);
        } catch (GitAPIException | IOException e) {
            throw new IllegalStateException("git status failed: " + e.getMessage(), e);
        }
    }

    /** Restore the user's flow library to the state captured in {@code commitId}. */
    @Transactional
    public synchronized RevertResult revert(String user, String commitId) {
        Path dir = repoDir(user);
        if (!Files.exists(dir.resolve(".git"))) {
            throw new IllegalArgumentException("no project repository for this user");
        }
        try (Git git = Git.open(dir.toFile())) {
            Repository repository = git.getRepository();
            ObjectId id = repository.resolve(commitId);
            if (id == null) {
                throw new IllegalArgumentException("unknown commit: " + commitId);
            }
            Map<String, Map<String, Object>> snapshot = readSnapshotAt(repository, id); // flowId → {name,graph}
            // upsert every flow from the snapshot (graph is already encrypted-at-rest form)
            int restored = 0;
            for (var entry : snapshot.entrySet()) {
                Map<String, Object> doc = entry.getValue();
                FlowDefEntity e = repo.findById(entry.getKey()).orElseGet(FlowDefEntity::new);
                if (e.getId() != null && !user.equals(e.getOwner())) {
                    continue; // never clobber another user's flow id
                }
                e.setId(entry.getKey());
                e.setOwner(user);
                e.setName(String.valueOf(doc.getOrDefault("name", "Flow")));
                @SuppressWarnings("unchecked")
                Map<String, Object> graph = doc.get("graph") instanceof Map<?, ?> g
                        ? (Map<String, Object>) g
                        : new LinkedHashMap<>();
                e.setGraph(graph);
                repo.save(e);
                restored++;
            }
            // remove the user's current flows that are absent from the snapshot (true "restore to")
            int removed = 0;
            for (FlowDefEntity e : repo.findByOwnerOrderByUpdatedAtDesc(user)) {
                if (!snapshot.containsKey(e.getId())) {
                    repo.delete(e);
                    removed++;
                }
            }
            log.info("Projects: reverted {} to {} (restored {}, removed {})", user, commitId, restored, removed);
            return new RevertResult(commitId, restored, removed);
        } catch (IOException e) {
            throw new IllegalStateException("git revert failed: " + e.getMessage(), e);
        }
    }

    /**
     * Preview what reverting to {@code commitId} would change: diff from the current library (DB) to the
     * chosen commit. ADDED = would be (re)created, DELETED = would be removed, MODIFIED = content differs.
     */
    public synchronized DiffResult diffToCommit(String user, String commitId) {
        Path dir = repoDir(user);
        if (!Files.exists(dir.resolve(".git"))) {
            throw new IllegalArgumentException("no project repository for this user");
        }
        try (Git git = Git.open(dir.toFile())) {
            ObjectId id = git.getRepository().resolve(commitId);
            if (id == null) {
                throw new IllegalArgumentException("unknown commit: " + commitId);
            }
            Map<String, String> from = currentSnapshot(user);
            Map<String, String> to = treeSnapshotAt(git.getRepository(), id);
            return new DiffResult("current", shortId(commitId), computeDiff(from, to));
        } catch (IOException e) {
            throw new IllegalStateException("git diff failed: " + e.getMessage(), e);
        }
    }

    /** The uncommitted changes: diff from HEAD to the current library (what the next commit would record). */
    public synchronized DiffResult diffUncommitted(String user) {
        Path dir = repoDir(user);
        if (!Files.exists(dir.resolve(".git"))) {
            return new DiffResult("empty", "current", computeDiff(Map.of(), currentSnapshot(user)));
        }
        try (Git git = Git.open(dir.toFile())) {
            Map<String, String> from = committedSnapshot(git); // HEAD (empty if no commits)
            Map<String, String> to = currentSnapshot(user);
            ObjectId head = git.getRepository().resolve("HEAD");
            String fromLabel = head == null ? "empty" : head.abbreviate(7).name();
            return new DiffResult(fromLabel, "current", computeDiff(from, to));
        } catch (IOException e) {
            throw new IllegalStateException("git diff failed: " + e.getMessage(), e);
        }
    }

    // ── internals ───────────────────────────────────────────────────────────────

    private Path repoDir(String user) {
        return baseDir.resolve(sanitize(user));
    }

    /** Usernames are app-controlled, but keep the path segment safe regardless. */
    private static String sanitize(String user) {
        String s = user == null ? "" : user.replaceAll("[^a-zA-Z0-9_.-]", "_");
        // "." / ".." would resolve outside baseDir — never allow a traversal segment
        if (s.isBlank() || s.equals(".") || s.equals("..")) {
            return "_";
        }
        return s;
    }

    private Git openOrInit(String user) throws GitAPIException, IOException {
        Path dir = repoDir(user);
        Files.createDirectories(dir);
        if (Files.exists(dir.resolve(".git"))) {
            return Git.open(dir.toFile());
        }
        return Git.init().setDirectory(dir.toFile()).call();
    }

    /** Mirror the user's DB flows onto disk under {@code flows/} (removing files for deleted flows). */
    private void writeWorkingTree(String user, Path workTree) throws IOException {
        Path flowsDir = workTree.resolve(FLOWS);
        Files.createDirectories(flowsDir);
        // clear existing snapshots so removed flows drop out (git detects the deletion)
        try (var s = Files.list(flowsDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
        for (var entry : currentSnapshot(user).entrySet()) {
            // key is already the "<flowId>.json" filename
            Files.writeString(flowsDir.resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** Current DB library as {@code flowId.json → serialized-content}, deterministically ordered. */
    private Map<String, String> currentSnapshot(String user) {
        Map<String, String> out = new TreeMap<>();
        for (FlowDefEntity e : repo.findByOwnerOrderByUpdatedAtDesc(user)) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", e.getId());
            doc.put("name", e.getName());
            doc.put("graph", e.getGraph());
            try {
                out.put(e.getId() + ".json", mapper.writeValueAsString(doc));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return out;
    }

    /** The {@code flows/} tree at HEAD as {@code flowId.json → content} (empty if no commits). */
    private Map<String, String> committedSnapshot(Git git) throws IOException {
        ObjectId head = git.getRepository().resolve("HEAD");
        return head == null ? new TreeMap<>() : treeSnapshotAt(git.getRepository(), head);
    }

    /** The {@code flows/} tree at {@code commit} as {@code flowId.json → raw content}. */
    private Map<String, String> treeSnapshotAt(Repository repository, ObjectId commit) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (RevWalk walk = new RevWalk(repository)) {
            RevTree tree = walk.parseCommit(commit).getTree();
            try (TreeWalk tw = new TreeWalk(repository)) {
                tw.addTree(tree);
                tw.setRecursive(true);
                tw.setFilter(PathFilter.create(FLOWS));
                while (tw.next()) {
                    String path = tw.getPathString();
                    ObjectLoader loader = repository.open(tw.getObjectId(0));
                    out.put(path.substring(FLOWS.length() + 1), new String(loader.getBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    /** Compare two {@code file → content} snapshots into per-file changes with a unified-diff patch. */
    private List<FileChange> computeDiff(Map<String, String> from, Map<String, String> to) {
        List<FileChange> changes = new ArrayList<>();
        var files = new java.util.TreeSet<String>();
        files.addAll(from.keySet());
        files.addAll(to.keySet());
        for (String f : files) {
            String a = from.get(f);
            String b = to.get(f);
            if (java.util.Objects.equals(a, b)) {
                continue;
            }
            String status = a == null ? "ADDED" : b == null ? "DELETED" : "MODIFIED";
            String flowId = f.endsWith(".json") ? f.substring(0, f.length() - 5) : f;
            changes.add(unifiedDiff(flowId, status, a == null ? "" : a, b == null ? "" : b));
        }
        return changes;
    }

    private static final int MAX_PATCH_CHARS = 8000;

    /** Build one {@link FileChange} with added/removed line counts and a capped unified-diff patch. */
    private FileChange unifiedDiff(String flowId, String status, String oldText, String newText) {
        RawText a = new RawText(oldText.getBytes(StandardCharsets.UTF_8));
        RawText b = new RawText(newText.getBytes(StandardCharsets.UTF_8));
        EditList edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.DEFAULT, a, b);
        int added = 0;
        int removed = 0;
        for (Edit e : edits) {
            added += e.getEndB() - e.getBeginB();
            removed += e.getEndA() - e.getBeginA();
        }
        String patch = "";
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                DiffFormatter df = new DiffFormatter(out)) {
            df.format(edits, a, b);
            patch = out.toString(StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // patch is best-effort; the counts above still convey the change
        }
        if (patch.length() > MAX_PATCH_CHARS) {
            patch = patch.substring(0, MAX_PATCH_CHARS) + "\n… (truncated)";
        }
        return new FileChange(flowId, status, added, removed, patch);
    }

    private String shortId(String commitId) {
        return commitId == null ? "" : commitId.length() <= 7 ? commitId : commitId.substring(0, 7);
    }

    /** Parse the {@code flows/} tree at {@code commit} into {@code flowId → {name,graph}}. */
    private Map<String, Map<String, Object>> readSnapshotAt(Repository repository, ObjectId commit)
            throws IOException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        try (RevWalk walk = new RevWalk(repository)) {
            RevTree tree = walk.parseCommit(commit).getTree();
            try (TreeWalk tw = new TreeWalk(repository)) {
                tw.addTree(tree);
                tw.setRecursive(true);
                tw.setFilter(PathFilter.create(FLOWS));
                while (tw.next()) {
                    ObjectLoader loader = repository.open(tw.getObjectId(0));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> doc = mapper.readValue(loader.getBytes(), Map.class);
                    Object fid = doc.get("id");
                    if (fid != null) {
                        out.put(String.valueOf(fid), doc);
                    }
                }
            }
        }
        return out;
    }

    private CommitInfo head(Git git) throws IOException, GitAPIException {
        if (git.getRepository().resolve("HEAD") == null) {
            return null;
        }
        var it = git.log().setMaxCount(1).call().iterator();
        return it.hasNext() ? toInfo(it.next()) : null;
    }

    private static CommitInfo toInfo(RevCommit c) {
        PersonIdent a = c.getAuthorIdent();
        return new CommitInfo(
                c.getName(),
                c.abbreviate(7).name(),
                c.getShortMessage(),
                a == null ? "unknown" : a.getName(),
                Instant.ofEpochSecond(c.getCommitTime()));
    }

    private boolean hasFlows(String user) {
        return !repo.findByOwnerOrderByUpdatedAtDesc(user).isEmpty();
    }

    private int countFlows(String user) {
        return repo.findByOwnerOrderByUpdatedAtDesc(user).size();
    }
}
