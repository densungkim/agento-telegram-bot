package com.agento.bot;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@Component
public class CodexSessionStore {

    private static final String OWNER = "agento-telegram-bot";

    private final Path file;
    private final String workdir;
    private final String codexHome;
    private String sessionId;
    private boolean resetPending;

    public CodexSessionStore(BotProperties properties) {
        file = Path.of(properties.codex().sessionFile()).toAbsolutePath().normalize();
        if (file.equals(Path.of(properties.codex().settingsFile()).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Codex session and settings files must be different");
        }
        workdir = Path.of(properties.codex().workdir()).toAbsolutePath().normalize().toString();
        String configuredHome = System.getenv("CODEX_HOME");
        String userHome = System.getenv(java.io.File.separatorChar == '\\' ? "USERPROFILE" : "HOME");
        if (userHome == null || userHome.isBlank()) {
            userHome = System.getProperty("user.home");
        }
        Path home = configuredHome == null || configuredHome.isBlank()
                ? Path.of(userHome, ".codex") : Path.of(configuredHome);
        codexHome = (home.isAbsolute() ? home : Path.of(workdir).resolve(home)).normalize().toString();
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
                || !codexHome.equals(saved.getProperty("codexHome"))) {
            throw new IllegalStateException("Session state belongs to another workdir or CODEX_HOME: " + file
                    + ". Restore the original configuration before resetting the bot session.");
        }
        String id = saved.getProperty("sessionId", "").strip();
        sessionId = id.isEmpty() ? null : checkedId(id);
        String pending = saved.getProperty("resetPending", "false");
        if (!pending.equals("true") && !pending.equals("false")) {
            throw new IllegalStateException("Invalid resetPending value in " + file);
        }
        resetPending = Boolean.parseBoolean(pending);
    }

    public synchronized String resumeId() {
        if (resetPending) {
            throw new IllegalStateException("Очистка предыдущей сессии не завершена. Повторите /new.");
        }
        return sessionId;
    }

    public synchronized void remember(String id) {
        id = checkedId(id);
        if (resetPending || (sessionId != null && !sessionId.equals(id))) {
            throw new IllegalStateException("Codex вернул другую сессию. Автоматическая замена запрещена.");
        }
        if (sessionId == null) {
            try {
                save(id, false);
            } catch (IllegalStateException e) {
                sessionId = id;
                resetPending = true;
                throw new IllegalStateException("ID новой сессии не записан на диск. Выполнение остановлено; используйте /new.", e);
            }
        }
    }

    public synchronized String beginReset() {
        if (sessionId != null && !resetPending) {
            save(sessionId, true);
        }
        return sessionId;
    }

    public synchronized void finishReset(String expectedId) {
        if (!java.util.Objects.equals(sessionId, expectedId)) {
            throw new IllegalStateException("Сессия изменилась во время очистки.");
        }
        save(null, false);
    }

    public synchronized String description() {
        if (sessionId == null) {
            return "новая; будет создана следующим сообщением";
        }
        return sessionId + (resetPending ? " (очистка не завершена; повторите /new)" : "");
    }

    public Map<String, String> environment() {
        return Map.of("CODEX_HOME", codexHome);
    }

    private void save(String id, boolean pending) {
        Properties saved = new Properties();
        saved.setProperty("owner", OWNER);
        saved.setProperty("workdir", workdir);
        saved.setProperty("codexHome", codexHome);
        saved.setProperty("sessionId", id == null ? "" : id);
        saved.setProperty("resetPending", Boolean.toString(pending));
        Path staged = null;
        try {
            Files.createDirectories(file.getParent());
            staged = Files.createTempFile(file.getParent(), "agento-session-", ".new");
            try (OutputStream output = Files.newOutputStream(staged)) {
                saved.store(output, "Agento bot current Codex session; no message history");
            }
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            sessionId = id;
            resetPending = pending;
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

    public static String checkedId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("ID сессии Codex должен быть UUID в стандартном формате.");
        }
        return id;
    }
}
