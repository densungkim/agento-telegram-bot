package com.agento.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Keeps the id of the single Claude Code session the bot continues. Claude Code stores the conversation itself in
 * {@code <config dir>/projects/<encoded cwd>/<id>.jsonl}; this store only remembers the id. The bot has one user:
 * a session replaced by {@code /new} is never needed again, so everything Claude Code keeps for it is deleted, and
 * ids whose deletion failed are retried on the next {@code /new} and at startup.
 */
@Component
public class ClaudeSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSessionStore.class);
    private static final String OWNER = "agento-telegram-bot";
    /** Per-session directories {@code <config dir>/<name>/<id>...} (Claude Code docs, ~/.claude layout). */
    private static final List<String> SESSION_DIRS = List.of(
            "file-history", "session-env", "tasks", "uploads", "dev-mods", "image-cache", "debug");

    private final Path file;
    private final String workdir;
    private final Path configDir;
    private String sessionId;
    /** True once Claude Code confirmed the session ({@code system/init}); until then the id is only reserved. */
    private boolean confirmed;
    /** Old session ids whose files could not be deleted yet. */
    private final Set<String> pendingDeletion = new LinkedHashSet<>();

    public ClaudeSessionStore(BotProperties properties) {
        file = Path.of(properties.claude().sessionFile()).toAbsolutePath().normalize();
        if (file.equals(Path.of(properties.claude().settingsFile()).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Claude session and settings files must be different");
        }
        workdir = Path.of(properties.claude().workdir()).toAbsolutePath().normalize().toString();
        configDir = resolveConfigDir(workdir);
        if (Files.notExists(file)) {
            return;
        }
        Properties saved = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            saved.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read session state from " + file, e);
        }
        if (!OWNER.equals(saved.getProperty("owner"))
                || !workdir.equals(saved.getProperty("workdir"))
                || !configDir.toString().equals(saved.getProperty("configDir"))) {
            throw new IllegalStateException("Session state belongs to another workdir or CLAUDE_CONFIG_DIR: " + file
                    + ". Restore the original configuration before resetting the bot session.");
        }
        String id = saved.getProperty("sessionId", "").strip();
        sessionId = id.isEmpty() ? null : checkedId(id);
        String savedConfirmed = saved.getProperty("confirmed", "false");
        if (!savedConfirmed.equals("true") && !savedConfirmed.equals("false")) {
            throw new IllegalStateException("Invalid confirmed value in " + file);
        }
        confirmed = sessionId != null && Boolean.parseBoolean(savedConfirmed);
        Arrays.stream(saved.getProperty("pendingDeletion", "").split(","))
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .map(ClaudeSessionStore::checkedId)
                .forEach(pendingDeletion::add);
        if (!pendingDeletion.isEmpty()) {
            try {
                List<String> failed = deletePending();
                if (!failed.isEmpty()) {
                    log.warn("Old Claude Code session files are still not deleted: {}", failed);
                }
            } catch (IllegalStateException e) {
                log.warn("Could not retry deleting old Claude Code sessions: {}", e.getMessage());
            }
        }
    }

    /**
     * Returns the session for the next run. A new id is written to disk before Claude Code starts, so a crash
     * cannot lose it. A confirmed session is always resumed (Claude Code reports a missing one itself); a reserved
     * id whose transcript already exists is resumed instead of created again.
     */
    public synchronized Session sessionForRun() {
        if (sessionId == null) {
            save(UUID.randomUUID().toString(), false);
        }
        return new Session(sessionId, confirmed || transcriptExists(sessionId));
    }

    /** Called on {@code system/init}: Claude Code really created or resumed this session. */
    public synchronized void confirm(String id) {
        if (!checkedId(id).equals(sessionId)) {
            throw new IllegalStateException("Claude Code вернул другую сессию. Автоматическая замена запрещена.");
        }
        if (confirmed) {
            return;
        }
        try {
            save(sessionId, true);
        } catch (IllegalStateException e) {
            // The transcript file proves the session on the next run, so the run may continue.
            log.warn("Could not mark the session as confirmed: {}", e.getMessage());
        }
    }

    /**
     * {@code /new}: forgets the current session (the next message starts a new one) and deletes everything Claude
     * Code keeps for it and for earlier sessions whose deletion failed, including the project's auto memory.
     *
     * @return text for the user
     */
    public synchronized String reset() {
        if (sessionId != null) {
            pendingDeletion.add(sessionId);
        }
        save(null, false);
        List<String> failed = deletePending();
        if (!failed.isEmpty()) {
            return "Контекст сброшен: следующее сообщение начнёт новую сессию. Настройки сохранены."
                    + "\nЧасть файлов прежних сессий удалить не удалось; бот повторит удаление при следующем /new"
                    + " и при запуске:\n" + String.join("\n", failed);
        }
        return "Предыдущая сессия полностью удалена. Следующее сообщение начнёт новую сессию. Настройки сохранены.";
    }

    public synchronized String description() {
        String current = sessionId == null
                ? "новая; будет создана следующим сообщением"
                : sessionId + (confirmed ? "" : " (ещё не подтверждена Claude Code)");
        return current + (pendingDeletion.isEmpty() ? ""
                : "\nНе удалены файлы прежних сессий: " + pendingDeletion.size() + " (повтор при /new)");
    }

    private boolean transcriptExists(String id) {
        Path projects = configDir.resolve("projects");
        if (!Files.isDirectory(projects)) {
            return false;
        }
        try (Stream<Path> dirs = Files.list(projects)) {
            return dirs.anyMatch(dir -> Files.isRegularFile(dir.resolve(checkedId(id) + ".jsonl")));
        } catch (IOException e) {
            log.warn("Could not list Claude Code projects in {}: {}", projects, e.getMessage());
            return false;
        }
    }

    /** Deletes the files of every pending id; ids without leftovers leave the pending list. */
    private List<String> deletePending() {
        List<String> failed = new ArrayList<>();
        for (String id : List.copyOf(pendingDeletion)) {
            List<String> errors = deleteSessionFiles(id);
            if (errors.isEmpty()) {
                pendingDeletion.remove(id);
            }
            failed.addAll(errors);
        }
        save(sessionId, confirmed);
        return failed;
    }

    /**
     * Deletes everything keyed by the session id: transcripts and their older copies, the {@code <id>/} directory
     * (subagents, tool results), the per-session directories, and the auto memory of the projects it belonged to.
     */
    private List<String> deleteSessionFiles(String id) {
        String checked = checkedId(id);
        List<Path> targets = new ArrayList<>();
        Set<Path> projectDirs = new LinkedHashSet<>();
        projectDirs.add(configDir.resolve("projects").resolve(encodedProjectName(workdir)));
        List<String> errors = new ArrayList<>();
        try {
            for (Path project : list(configDir.resolve("projects"))) {
                for (Path entry : list(project)) {
                    String name = entry.getFileName().toString();
                    if (name.equals(checked) || name.equals(checked + ".jsonl")
                            || name.startsWith(checked + ".orphaned-") || name.startsWith(checked + ".jsonl.superseded-")) {
                        targets.add(entry);
                        projectDirs.add(project);
                    }
                }
            }
            for (String dir : SESSION_DIRS) {
                for (Path entry : list(configDir.resolve(dir))) {
                    if (entry.getFileName().toString().startsWith(checked)) {
                        targets.add(entry);
                    }
                }
            }
        } catch (IOException e) {
            errors.add(configDir + ": " + e.getMessage());
        }
        // Auto memory is per project, not per session; the bot runs with it disabled, so nothing new is lost.
        projectDirs.stream().map(project -> project.resolve("memory")).filter(Files::exists).forEach(targets::add);

        for (Path target : targets) {
            try (Stream<Path> tree = Files.walk(target)) {
                for (Path item : tree.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(item);
                }
            } catch (IOException e) {
                errors.add(target + ": " + e.getMessage());
            }
        }
        return errors;
    }

    private static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.toList();
        }
    }

    /** Claude Code's project directory name: the working directory with non-alphanumerics replaced by "-". */
    private static String encodedProjectName(String path) {
        return path.replaceAll("[^A-Za-z0-9]", "-");
    }

    private void save(String id, boolean isConfirmed) {
        Properties saved = new Properties();
        saved.setProperty("owner", OWNER);
        saved.setProperty("workdir", workdir);
        saved.setProperty("configDir", configDir.toString());
        saved.setProperty("sessionId", id == null ? "" : id);
        saved.setProperty("confirmed", Boolean.toString(isConfirmed));
        saved.setProperty("pendingDeletion", String.join(",", pendingDeletion));
        Path staged = null;
        try {
            Files.createDirectories(file.getParent());
            staged = Files.createTempFile(file.getParent(), "agento-session-", ".new");
            try (OutputStream output = Files.newOutputStream(staged)) {
                saved.store(output, "Agento bot current Claude Code session; no message history");
            }
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            sessionId = id;
            confirmed = isConfirmed;
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить состояние сессии: " + file, e);
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // A cleanup failure must not hide the original state-write error.
                }
            }
        }
    }

    /** Same rule as Claude Code: {@code CLAUDE_CONFIG_DIR}, otherwise {@code ~/.claude}. */
    private static Path resolveConfigDir(String workdir) {
        String configured = System.getenv("CLAUDE_CONFIG_DIR");
        String userHome = System.getenv(java.io.File.separatorChar == '\\' ? "USERPROFILE" : "HOME");
        if (userHome == null || userHome.isBlank()) {
            userHome = System.getProperty("user.home");
        }
        Path dir = configured == null || configured.isBlank() ? Path.of(userHome, ".claude") : Path.of(configured);
        return (dir.isAbsolute() ? dir : Path.of(workdir).resolve(dir)).normalize();
    }

    public static String checkedId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("ID сессии Claude Code должен быть UUID в стандартном формате.");
        }
        return id;
    }

    public record Session(String id, boolean resume) {
    }
}
