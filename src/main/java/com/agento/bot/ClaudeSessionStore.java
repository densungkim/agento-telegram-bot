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
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Keeps the id of the single Claude Code session the bot continues. Claude Code stores the conversation itself in
 * {@code <config dir>/projects/<encoded cwd>/<id>.jsonl}; this store only remembers the id.
 */
@Component
public class ClaudeSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSessionStore.class);
    private static final String OWNER = "agento-telegram-bot";

    private final Path file;
    private final String workdir;
    private final Path configDir;
    private String sessionId;
    /** True once Claude Code confirmed the session ({@code system/init}); until then the id is only reserved. */
    private boolean confirmed;

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
     * Forgets the current session (the next message starts a new one) and deletes its transcript.
     *
     * @return text for the user
     */
    public synchronized String reset() {
        String previous = sessionId;
        save(null, false);
        if (previous == null) {
            return "Контекст уже пуст. Следующее сообщение начнёт новую сессию. Настройки сохранены.";
        }
        List<String> failed = deleteTranscript(previous);
        if (!failed.isEmpty()) {
            return "Контекст сброшен: следующее сообщение начнёт новую сессию. Настройки сохранены."
                    + "\nФайлы прежней сессии удалить не удалось:\n" + String.join("\n", failed);
        }
        return "Предыдущий контекст удалён. Следующее сообщение начнёт новую сессию. Настройки сохранены.";
    }

    public synchronized String description() {
        if (sessionId == null) {
            return "новая; будет создана следующим сообщением";
        }
        return sessionId + (confirmed ? "" : " (ещё не подтверждена Claude Code)");
    }

    private boolean transcriptExists(String id) {
        return transcriptPaths(id).stream().anyMatch(Files::isRegularFile);
    }

    /** {@code <id>.jsonl} and the {@code <id>/} directory (subagent transcripts) in every project directory. */
    private List<Path> transcriptPaths(String id) {
        Path projects = configDir.resolve("projects");
        if (!Files.isDirectory(projects)) {
            return List.of();
        }
        String checked = checkedId(id);
        try (Stream<Path> dirs = Files.list(projects)) {
            return dirs.filter(Files::isDirectory)
                    .flatMap(dir -> Stream.of(dir.resolve(checked + ".jsonl"), dir.resolve(checked)))
                    .filter(Files::exists)
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list Claude Code projects in {}: {}", projects, e.getMessage());
            return List.of();
        }
    }

    private List<String> deleteTranscript(String id) {
        List<String> failed = new ArrayList<>();
        for (Path path : transcriptPaths(id)) {
            try (Stream<Path> tree = Files.walk(path)) {
                for (Path item : tree.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(item);
                }
            } catch (IOException e) {
                failed.add(path + ": " + e.getMessage());
            }
        }
        return failed;
    }

    private void save(String id, boolean isConfirmed) {
        Properties saved = new Properties();
        saved.setProperty("owner", OWNER);
        saved.setProperty("workdir", workdir);
        saved.setProperty("configDir", configDir.toString());
        saved.setProperty("sessionId", id == null ? "" : id);
        saved.setProperty("confirmed", Boolean.toString(isConfirmed));
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
