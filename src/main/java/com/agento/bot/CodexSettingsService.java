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

@Service
public class CodexSettingsService {

    private static final Logger log = LoggerFactory.getLogger(CodexSettingsService.class);
    private static final String MODEL = "model";
    private static final String REASONING_EFFORT = "reasoningEffort";
    private static final String ACCESS_MODE = "accessMode";

    private final Path settingsFile;
    private final CodexModelCatalog catalog;
    private final List<String> allowedModels;
    private final List<String> reasoningFilter;
    private final List<String> allowedAccessModes;

    private CodexSettings current;

    public CodexSettingsService(BotProperties properties, CodexModelCatalog catalog) {
        BotProperties.Codex codex = properties.codex();
        this.settingsFile = Path.of(valueOrDefault(codex.settingsFile(), "./agento-settings.properties"))
                .toAbsolutePath().normalize();
        this.catalog = catalog;
        List<String> modelFilter = splitCsv(codex.allowedModels());
        this.allowedModels = catalog.modelIds().stream()
                .filter(model -> modelFilter.isEmpty() || modelFilter.contains(model)).toList();
        if (allowedModels.isEmpty()) {
            throw new IllegalArgumentException("CODEX_ALLOWED_MODELS does not contain any model from the current catalog");
        }
        this.reasoningFilter = splitCsv(codex.allowedReasoningEfforts());
        this.allowedAccessModes = splitCsv(codex.allowedAccessModes());
        this.current = loadOrDefault(codex);
        String problem = validationProblem();
        if (!problem.isEmpty()) {
            log.warn("Codex settings require an explicit update: {}", problem);
        }
    }

    public synchronized CodexSettings current() {
        return current;
    }

    public synchronized CodexSettings setModel(String model) {
        String normalized = normalize(model);
        requireAllowed(normalized, allowedModels, "модели");
        String reasoning = reasoningOptions(normalized).contains(current.reasoningEffort())
                ? current.reasoningEffort() : CodexSettings.DEFAULT;
        return saveAndApply(new CodexSettings(normalized, reasoning, current.accessMode()));
    }

    public synchronized CodexSettings setReasoningEffort(String reasoningEffort) {
        String normalized = normalize(reasoningEffort);
        requireAllowed(normalized, reasoningOptions(current.model()), "reasoning для " + current.model());
        return saveAndApply(new CodexSettings(current.model(), normalized, current.accessMode()));
    }

    public synchronized CodexSettings setAccessMode(String accessMode) {
        String normalized = normalize(accessMode);
        requireAllowed(normalized, allowedAccessModes, "режима доступа");
        return saveAndApply(new CodexSettings(current.model(), current.reasoningEffort(), normalized));
    }

    public List<String> allowedModels() {
        return allowedModels;
    }

    public synchronized List<String> allowedReasoningEfforts() {
        if (!allowedModels.contains(current.model())) {
            return List.of(CodexSettings.DEFAULT);
        }
        return reasoningOptions(current.model());
    }

    public synchronized CodexSettings settingsForRun() {
        validateCurrent();
        if (current.usesDefaultModel() || !current.usesDefaultReasoning()) {
            return current;
        }
        return new CodexSettings(current.model(), catalog.model(current.model()).defaultReasoningEffort(),
                current.accessMode());
    }

    public synchronized String validationProblem() {
        try {
            validateCurrent();
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    public String reasoningDescription(CodexSettings settings) {
        if (!settings.usesDefaultReasoning()) {
            return settings.reasoningEffort();
        }
        if (settings.usesDefaultModel() || !allowedModels.contains(settings.model())) {
            return "default (настройки Codex CLI)";
        }
        return "default (" + catalog.model(settings.model()).defaultReasoningEffort() + ")";
    }

    private void validateCurrent() {
        if (!allowedModels.contains(current.model())) {
            throw new IllegalArgumentException("Модель '" + current.model()
                    + "' отсутствует в разрешённом каталоге. Выберите модель через /model.");
        }
        if (!reasoningOptions(current.model()).contains(current.reasoningEffort())) {
            throw new IllegalArgumentException("Reasoning '" + current.reasoningEffort()
                    + "' не поддерживается для " + current.model() + ". Выберите значение через /reasoning.");
        }
    }

    private List<String> reasoningOptions(String model) {
        return catalog.reasoningEfforts(model).stream()
                .filter(effort -> CodexSettings.DEFAULT.equals(effort)
                        || reasoningFilter.isEmpty() || reasoningFilter.contains(effort)).toList();
    }

    public List<String> allowedAccessModes() {
        return allowedAccessModes;
    }

    private CodexSettings loadOrDefault(BotProperties.Codex codex) {
        CodexSettings defaults = new CodexSettings(
                normalize(valueOrDefault(codex.model(), CodexSettings.DEFAULT)),
                normalize(valueOrDefault(codex.reasoningEffort(), CodexSettings.DEFAULT)),
                firstAllowedOrDefault(codex.accessMode(), allowedAccessModes, "full-access")
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

        return new CodexSettings(
                normalize(valueOrDefault(loaded.getProperty(MODEL), defaults.model())),
                normalize(valueOrDefault(loaded.getProperty(REASONING_EFFORT), defaults.reasoningEffort())),
                firstAllowedOrDefault(loaded.getProperty(ACCESS_MODE), allowedAccessModes, defaults.accessMode())
        );
    }

    private CodexSettings saveAndApply(CodexSettings next) {
        Properties properties = new Properties();
        properties.setProperty(MODEL, next.model());
        properties.setProperty(REASONING_EFFORT, next.reasoningEffort());
        properties.setProperty(ACCESS_MODE, next.accessMode());

        Path staged = null;
        try {
            Path parent = settingsFile.getParent();
            Files.createDirectories(parent);
            staged = Files.createTempFile(parent, "agento-settings-", ".new");
            try (OutputStream output = Files.newOutputStream(staged)) {
                properties.store(output, "Agento Telegram Bot runtime Codex settings");
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

    private static List<String> splitCsv(String value) {
        return Arrays.stream(valueOrDefault(value, "").split(","))
                .map(CodexSettingsService::normalize)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
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
        if (!allowed.isEmpty()) {
            return allowed.getFirst();
        }
        return normalizedDefault;
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
