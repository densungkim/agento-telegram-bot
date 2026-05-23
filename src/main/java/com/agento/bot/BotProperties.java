package com.agento.bot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentobot")
public record BotProperties(
        Telegram telegram,
        Codex codex
) {
    public record Telegram(
            String token,
            long allowedChatId,
            int pollingTimeoutSeconds,
            int messageMaxLength
    ) {
    }

    public record Codex(
            String command,
            String workdir,
            long timeoutSeconds,
            int maxOutputChars,
            String model,
            String reasoningEffort,
            String accessMode,
            boolean skipGitRepoCheck,
            String systemPrompt,
            String settingsFile,
            String allowedModels,
            String allowedReasoningEfforts,
            String allowedAccessModes
    ) {
    }
}
