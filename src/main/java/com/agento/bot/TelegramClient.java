package com.agento.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    private final RestClient restClient;
    private final int pollingTimeoutSeconds;
    private final int messageMaxLength;

    public TelegramClient(BotProperties properties, RestClient.Builder builder) {
        String token = properties.telegram().token();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN is required");
        }

        this.pollingTimeoutSeconds = properties.telegram().pollingTimeoutSeconds();
        this.messageMaxLength = Math.clamp(properties.telegram().messageMaxLength(), 500, 3900);
        this.restClient = builder
                .baseUrl("https://api.telegram.org/bot" + token)
                .build();
    }

    public JsonNode getUpdates(long offset) {
        return restClient.post()
                .uri("/getUpdates")
                .body(Map.of(
                        "offset", offset,
                        "timeout", pollingTimeoutSeconds,
                        "allowed_updates", List.of("message")
                ))
                .retrieve()
                .body(JsonNode.class);
    }

    public void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    public void sendMainMenu(long chatId, String text) {
        sendKeyboard(chatId, text, List.of(
                List.of("/status", "/cancel"),
                List.of("/model", "/reasoning"),
                List.of("/mode", "/help")
        ), false);
    }

    public void sendKeyboard(long chatId, String text, List<List<String>> keyboardRows) {
        sendKeyboard(chatId, text, keyboardRows, true);
    }

    public void sendKeyboard(long chatId, String text, List<List<String>> keyboardRows, boolean oneTimeKeyboard) {
        sendMessage(chatId, text, Map.of(
                "keyboard", keyboardRows,
                "resize_keyboard", true,
                "one_time_keyboard", oneTimeKeyboard
        ));
    }

    private void sendMessage(long chatId, String text, Object replyMarkup) {
        for (String part : splitMessage(text)) {
            try {
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("chat_id", chatId);
                body.put("text", part);
                body.put("disable_web_page_preview", true);
                if (replyMarkup != null) {
                    body.put("reply_markup", replyMarkup);
                }

                restClient.post()
                        .uri("/sendMessage")
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("Failed to send Telegram message: {}", e.getMessage());
            }
        }
    }

    private List<String> splitMessage(String text) {
        String safeText = (text == null || text.isBlank()) ? "(empty response)" : text.strip();

        if (safeText.length() <= messageMaxLength) {
            return List.of(safeText);
        }

        List<String> parts = new ArrayList<>();
        int index = 0;
        while (index < safeText.length()) {
            int end = Math.min(index + messageMaxLength, safeText.length());
            int newline = safeText.lastIndexOf('\n', end);
            if (newline > index + 500) {
                end = newline;
            }
            parts.add(safeText.substring(index, end).strip());
            index = end;
        }
        return parts;
    }
}
