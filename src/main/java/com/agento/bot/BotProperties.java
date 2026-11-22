package com.agento.bot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentobot")
public record BotProperties(
        Telegram telegram,
        Claude claude
) {
    public record Telegram(
            String token,
            long allowedChatId,
            int pollingTimeoutSeconds,
            int messageMaxLength
    ) {
    }

    public record Claude(
            String command,
            String workdir,
            long timeoutSeconds,
            int maxOutputChars,
            String model,
            String effort,
            String accessMode,
            String systemPrompt,
            String settingsFile,
            String sessionFile,
            String allowedModels,
            String allowedEfforts,
            String allowedAccessModes
    ) {
    }
}
