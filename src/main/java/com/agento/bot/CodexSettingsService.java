package com.agento.bot;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

@Service
public class CodexSettingsService {

    private static final String MODEL = "model";
    private static final String REASONING_EFFORT = "reasoningEffort";
    private static final String ACCESS_MODE = "accessMode";
    private static final String APPROVAL_POLICY = "approvalPolicy";

    private final Path settingsFile;
    private final List<String> allowedModels;
    private final List<String> allowedReasoningEfforts;
    private final List<String> allowedAccessModes;
    private final List<String> allowedApprovalPolicies;

    private CodexSettings current;

    public CodexSettingsService(BotProperties properties) {
        BotProperties.Codex codex = properties.codex();
        this.settingsFile = Path.of(valueOrDefault(codex.settingsFile(), "./agento-settings.properties"));
        this.allowedModels = splitCsv(codex.allowedModels());
        this.allowedReasoningEfforts = splitCsv(codex.allowedReasoningEfforts());
        this.allowedAccessModes = splitCsv(codex.allowedAccessModes());
        this.allowedApprovalPolicies = splitCsv(codex.allowedApprovalPolicies());
        this.current = loadOrDefault(codex);
    }

    public synchronized CodexSettings current() {
        return current;
    }

    public synchronized CodexSettings setModel(String model) {
        String normalized = normalize(model);
        requireAllowed(normalized, allowedModels, "model");
        current = new CodexSettings(normalized, current.reasoningEffort(), current.accessMode(), current.approvalPolicy());
        save();
        return current;
    }

    public synchronized CodexSettings setReasoningEffort(String reasoningEffort) {
        String normalized = normalize(reasoningEffort);
        requireAllowed(normalized, allowedReasoningEfforts, "reasoning");
        current = new CodexSettings(current.model(), normalized, current.accessMode(), current.approvalPolicy());
        save();
        return current;
    }

    public synchronized CodexSettings setAccessMode(String accessMode) {
        String normalized = normalize(accessMode);
        requireAllowed(normalized, allowedAccessModes, "access mode");
        current = new CodexSettings(current.model(), current.reasoningEffort(), normalized, current.approvalPolicy());
        save();
        return current;
    }

    public synchronized CodexSettings setApprovalPolicy(String approvalPolicy) {
        String normalized = normalize(approvalPolicy);
        requireAllowed(normalized, allowedApprovalPolicies, "approval policy");
        current = new CodexSettings(current.model(), current.reasoningEffort(), current.accessMode(), normalized);
        save();
        return current;
    }

    public List<String> allowedModels() {
        return allowedModels;
    }

    public List<String> allowedReasoningEfforts() {
        return allowedReasoningEfforts;
    }

    public List<String> allowedAccessModes() {
        return allowedAccessModes;
    }

    public List<String> allowedApprovalPolicies() {
        return allowedApprovalPolicies;
    }

    private CodexSettings loadOrDefault(BotProperties.Codex codex) {
        CodexSettings defaults = new CodexSettings(
                firstAllowedOrDefault(codex.model(), allowedModels, "gpt-5.5"),
                firstAllowedOrDefault(codex.reasoningEffort(), allowedReasoningEfforts, "medium"),
                firstAllowedOrDefault(codex.accessMode(), allowedAccessModes, "full-access"),
                firstAllowedOrDefault(codex.approvalPolicy(), allowedApprovalPolicies, "never")
        );

        if (!Files.isRegularFile(settingsFile)) {
            return defaults;
        }

        Properties loaded = new Properties();
        try (InputStream input = Files.newInputStream(settingsFile)) {
            loaded.load(input);
        } catch (IOException e) {
            return defaults;
        }

        return new CodexSettings(
                firstAllowedOrDefault(loaded.getProperty(MODEL), allowedModels, defaults.model()),
                firstAllowedOrDefault(loaded.getProperty(REASONING_EFFORT), allowedReasoningEfforts, defaults.reasoningEffort()),
                firstAllowedOrDefault(loaded.getProperty(ACCESS_MODE), allowedAccessModes, defaults.accessMode()),
                firstAllowedOrDefault(loaded.getProperty(APPROVAL_POLICY), allowedApprovalPolicies, defaults.approvalPolicy())
        );
    }

    private void save() {
        Properties properties = new Properties();
        properties.setProperty(MODEL, current.model());
        properties.setProperty(REASONING_EFFORT, current.reasoningEffort());
        properties.setProperty(ACCESS_MODE, current.accessMode());
        properties.setProperty(APPROVAL_POLICY, current.approvalPolicy());

        try {
            Path parent = settingsFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream output = Files.newOutputStream(settingsFile)) {
                properties.store(output, "Agento Telegram Bot runtime Codex settings");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save settings to " + settingsFile.toAbsolutePath(), e);
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
            throw new IllegalArgumentException("Unsupported " + label + ": " + value + ". Allowed: " + String.join(", ", allowed));
        }
    }

    private static String normalize(String value) {
        return valueOrDefault(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
