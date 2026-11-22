package com.agento.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Predicate;

@Service
public class ClaudeSettingsService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSettingsService.class);
    private static final String MODEL = "model";
    private static final String EFFORT = "effort";
    private static final String ACCESS_MODE = "accessMode";
    private static final String MODEL_PATTERN = "[a-z0-9][a-z0-9.\\[\\]_-]*";

    private final Path settingsFile;
    private final List<String> allowedModels;
    private final List<String> allowedEfforts;
    private final List<String> allowedAccessModes;

    private ClaudeSettings current;

    public ClaudeSettingsService(BotProperties properties) {
        BotProperties.Claude claude = properties.claude();
        this.settingsFile = Path.of(valueOrDefault(claude.settingsFile(), "./agento-settings.properties"))
                .toAbsolutePath().normalize();
        this.allowedModels = parseAllowed(claude.allowedModels(), "CLAUDE_ALLOWED_MODELS",
                model -> model.matches(MODEL_PATTERN));
        this.allowedEfforts = parseAllowed(claude.allowedEfforts(), "CLAUDE_ALLOWED_EFFORTS",
                effort -> ClaudeSettings.DEFAULT.equals(effort) || ClaudeSettings.EFFORTS.contains(effort));
        this.allowedAccessModes = parseAllowed(claude.allowedAccessModes(), "CLAUDE_ALLOWED_ACCESS_MODES",
                ClaudeSettings.ACCESS_MODES::contains);
        this.current = loadOrDefault(claude);
        String problem = validationProblem();
        if (!problem.isEmpty()) {
            log.warn("Claude settings require an explicit update: {}", problem);
        }
    }

    public synchronized ClaudeSettings current() {
        return current;
    }

    public synchronized ClaudeSettings setModel(String model) {
        String normalized = normalize(model);
        requireAllowed(normalized, allowedModels, "модели");
        return saveAndApply(new ClaudeSettings(normalized, current.effort(), current.accessMode()));
    }

    public synchronized ClaudeSettings setEffort(String effort) {
        String normalized = normalize(effort);
        requireAllowed(normalized, allowedEfforts, "effort");
        return saveAndApply(new ClaudeSettings(current.model(), normalized, current.accessMode()));
    }

    public synchronized ClaudeSettings setAccessMode(String accessMode) {
        String normalized = normalize(accessMode);
        requireAllowed(normalized, allowedAccessModes, "режима доступа");
        return saveAndApply(new ClaudeSettings(current.model(), current.effort(), normalized));
    }

    public List<String> allowedModels() {
        return allowedModels;
    }

    public List<String> allowedEfforts() {
        return allowedEfforts;
    }

    public List<String> allowedAccessModes() {
        return allowedAccessModes;
    }

    public synchronized ClaudeSettings settingsForRun() {
        validateCurrent();
        return current;
    }

    public synchronized String validationProblem() {
        try {
            validateCurrent();
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    public String effortDescription(ClaudeSettings settings) {
        return settings.usesDefaultEffort() ? "default (настройки Claude Code)" : settings.effort();
    }

    private void validateCurrent() {
        if (!allowedModels.contains(current.model())) {
            throw new IllegalArgumentException("Модель '" + current.model()
                    + "' отсутствует в разрешённом списке. Выберите модель через /model.");
        }
        if (!allowedEfforts.contains(current.effort())) {
            throw new IllegalArgumentException("Effort '" + current.effort()
                    + "' отсутствует в разрешённом списке. Выберите значение через /effort.");
        }
        if (!allowedAccessModes.contains(current.accessMode())) {
            throw new IllegalArgumentException("Режим доступа '" + current.accessMode()
                    + "' отсутствует в разрешённом списке. Выберите режим через /mode.");
        }
    }

    private ClaudeSettings loadOrDefault(BotProperties.Claude claude) {
        ClaudeSettings defaults = new ClaudeSettings(
                normalize(valueOrDefault(claude.model(), ClaudeSettings.DEFAULT)),
                normalize(valueOrDefault(claude.effort(), ClaudeSettings.DEFAULT)),
                firstAllowedOrDefault(claude.accessMode(), allowedAccessModes, ClaudeSettings.FULL_ACCESS)
        );

        if (!Files.isRegularFile(settingsFile)) {
            return defaults;
        }

        Properties loaded = new Properties();
        try (InputStream input = Files.newInputStream(settingsFile)) {
            loaded.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read settings from " + settingsFile, e);
        }

        return new ClaudeSettings(
                normalize(valueOrDefault(loaded.getProperty(MODEL), defaults.model())),
                normalize(valueOrDefault(loaded.getProperty(EFFORT), defaults.effort())),
                firstAllowedOrDefault(loaded.getProperty(ACCESS_MODE), allowedAccessModes, defaults.accessMode())
        );
    }

    private ClaudeSettings saveAndApply(ClaudeSettings next) {
        Properties properties = new Properties();
        properties.setProperty(MODEL, next.model());
        properties.setProperty(EFFORT, next.effort());
        properties.setProperty(ACCESS_MODE, next.accessMode());

        Path staged = null;
        try {
            Path parent = settingsFile.getParent();
            Files.createDirectories(parent);
            staged = Files.createTempFile(parent, "agento-settings-", ".new");
            try (OutputStream output = Files.newOutputStream(staged)) {
                properties.store(output, "Agento Telegram Bot runtime Claude Code settings");
            }
            Files.move(staged, settingsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            current = next;
            return current;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save settings to " + settingsFile, e);
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException e) {
                    log.warn("Failed to remove settings staging file: {}", e.getMessage());
                }
            }
        }
    }

    private static List<String> parseAllowed(String value, String name, Predicate<String> valid) {
        List<String> values = Arrays.stream(valueOrDefault(value, "").split(","))
                .map(ClaudeSettingsService::normalize)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must contain at least one value");
        }
        List<String> invalid = values.stream().filter(valid.negate()).toList();
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException(name + " contains unsupported values: " + String.join(", ", invalid));
        }
        return values;
    }

    private static String firstAllowedOrDefault(String value, List<String> allowed, String defaultValue) {
        String normalized = normalize(value);
        if (allowed.contains(normalized)) {
            return normalized;
        }
        String normalizedDefault = normalize(defaultValue);
        if (allowed.contains(normalizedDefault)) {
            return normalizedDefault;
        }
        return allowed.getFirst();
    }

    private static void requireAllowed(String value, List<String> allowed, String label) {
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("Недопустимое значение для " + label + ": " + value
                    + ". Доступно: " + String.join(", ", allowed));
        }
    }

    private static String normalize(String value) {
        return valueOrDefault(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
