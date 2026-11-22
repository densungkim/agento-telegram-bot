package com.agento.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);
    private final RestClient restClient;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final int pollingTimeoutSeconds;
    private final int messageMaxLength;
    private final String token;
    private volatile String username = "";

    public TelegramClient(BotProperties properties, RestClient.Builder builder) {
        token = properties.telegram().token();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN is required");
        }
        pollingTimeoutSeconds = properties.telegram().pollingTimeoutSeconds();
        messageMaxLength = Math.clamp(properties.telegram().messageMaxLength(), 500, 3900);
        restClient = builder.baseUrl("https://api.telegram.org/bot" + token).build();
    }

    public void initialize() {
        username = call("getMe", Map.of(), MediaType.APPLICATION_JSON)
                .path("result").path("username").asString("");
    }

    public String username() {
        return username;
    }

    public JsonNode getUpdates(long offset) {
        return call("getUpdates", Map.of(
                "offset", offset,
                "timeout", pollingTimeoutSeconds,
                "allowed_updates", List.of("message", "callback_query")
        ), MediaType.APPLICATION_JSON);
    }

    public void setCommands(List<BotCommand> commands) {
        call("setMyCommands", Map.of("commands", commands.stream()
                .map(command -> Map.of("command", command.command(), "description", command.description())).toList()),
                MediaType.APPLICATION_JSON);
    }

    public void answerCallback(String queryId, String text) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("callback_query_id", queryId);
            if (text != null && !text.isBlank()) {
                body.put("text", TelegramTextFormatter.clip(text, 180));
            }
            call("answerCallbackQuery", body, MediaType.APPLICATION_JSON);
        } catch (ApiException e) {
            log.debug("Could not acknowledge Telegram callback: {}", e.getMessage());
        }
    }

    public void sendTyping(long chatId) {
        try {
            call("sendChatAction", Map.of("chat_id", chatId, "action", "typing"), MediaType.APPLICATION_JSON);
        } catch (ApiException e) {
            log.debug("Could not send typing action: {}", e.getMessage());
        }
    }

    public void sendMessage(long chatId, String text) {
        sendScreen(chatId, text, List.of(), null);
    }

    public int sendScreen(long chatId, String text, List<List<Button>> buttons, Integer replyTo) {
        Map<String, Object> body = messageBody(chatId, text, buttons);
        if (replyTo != null && replyTo > 0) {
            body.put("reply_parameters", replyParameters(replyTo));
        }
        int id = formattedCall("sendMessage", body, text).path("result").path("message_id").intValue();
        if (id <= 0) {
            throw new ApiException(0, "Telegram не подтвердил отправленное сообщение.", 0);
        }
        return id;
    }

    public void editScreen(long chatId, int messageId, String text, List<List<Button>> buttons) {
        Map<String, Object> body = messageBody(chatId, text, buttons);
        body.put("message_id", messageId);
        if (buttons.isEmpty()) {
            body.put("reply_markup", Map.of("inline_keyboard", List.of()));
        }
        try {
            formattedCall("editMessageText", body, text);
        } catch (ApiException e) {
            if (!e.notModified()) {
                throw e;
            }
        }
    }

    public void removeLegacyKeyboard(long chatId) {
        call("sendMessage", Map.of(
                "chat_id", chatId,
                "text", "Управление ботом — кнопками под сообщениями или командами.",
                "reply_markup", Map.of("remove_keyboard", true)
        ), MediaType.APPLICATION_JSON);
    }

    public void sendResult(long chatId, String result, int replyTo, String taskId) {
        String text = result == null || result.isBlank() ? "Claude Code не вернул текст ответа." : result;
        if (text.length() <= messageMaxLength) {
            sendScreen(chatId, text, List.of(), replyTo);
            return;
        }

        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 49_000_000) {
            throw new ApiException(0, "Ответ превышает лимит файла Telegram. Сессия сохранена.", 0);
        }
        ByteArrayResource document = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "agento-" + taskId + ".md";
            }
        };
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("chat_id", Long.toString(chatId));
        body.add("document", document);
        body.add("caption", "Ответ Claude · задача #" + taskId + "\nПолный текст в UTF-8, без обрезки.");
        if (replyTo > 0) {
            body.add("reply_parameters", mapper.writeValueAsString(replyParameters(replyTo)));
        }
        call("sendDocument", body, MediaType.MULTIPART_FORM_DATA);
    }

    private Map<String, Object> messageBody(long chatId, String text, List<List<Button>> buttons) {
        String safe = text == null || text.isBlank() ? "Нет данных." : TelegramTextFormatter.clip(text, 3900);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", TelegramTextFormatter.html(safe));
        body.put("parse_mode", "HTML");
        body.put("link_preview_options", Map.of("is_disabled", true));
        if (!buttons.isEmpty()) {
            body.put("reply_markup", Map.of("inline_keyboard", buttons.stream()
                    .map(row -> row.stream().map(button -> Map.of(
                            "text", button.text(), "callback_data", button.data())).toList()).toList()));
        }
        return body;
    }

    private JsonNode formattedCall(String method, Map<String, Object> body, String original) {
        try {
            return call(method, body, MediaType.APPLICATION_JSON);
        } catch (ApiException e) {
            if (!e.formattingError()) {
                throw e;
            }
            body.remove("parse_mode");
            body.put("text", TelegramTextFormatter.clip(original == null || original.isBlank() ? "Нет данных." : original, 3900));
            return call(method, body, MediaType.APPLICATION_JSON);
        }
    }

    private Map<String, Object> replyParameters(int messageId) {
        return Map.of("message_id", messageId, "allow_sending_without_reply", true);
    }

    private JsonNode call(String method, Object body, MediaType contentType) {
        try {
            JsonNode response = restClient.post().uri("/" + method).contentType(contentType)
                    .body(body).retrieve().body(JsonNode.class);
            if (response == null) {
                throw new ApiException(0, "Telegram вернул пустой ответ.", 0);
            }
            if (!response.path("ok").booleanValue(false)) {
                throw apiError(response, 0);
            }
            return response;
        } catch (RestClientResponseException e) {
            JsonNode error;
            try {
                error = mapper.readTree(e.getResponseBodyAsString());
            } catch (RuntimeException ignored) {
                throw new ApiException(e.getStatusCode().value(), "Ошибка HTTP " + e.getStatusCode().value(), 0);
            }
            throw apiError(error, e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new ApiException(0, "Не удалось связаться с Telegram.", 0);
        }
    }

    private ApiException apiError(JsonNode error, int fallbackCode) {
        if (error == null) {
            return new ApiException(fallbackCode, "Некорректный ответ Telegram.", 0);
        }
        return new ApiException(error.path("error_code").intValue(fallbackCode),
                error.path("description").asString("Ошибка Telegram").replace(token, "[token]"),
                error.path("parameters").path("retry_after").intValue(0));
    }

    public record BotCommand(String command, String description) {
    }

    public record Button(String text, String data) {
        public Button {
            if (text == null || text.isBlank() || data == null || data.isEmpty()
                    || data.getBytes(StandardCharsets.UTF_8).length > 64) {
                throw new IllegalArgumentException("Invalid Telegram callback button");
            }
        }
    }

    public static final class ApiException extends RuntimeException {
        private final int code;
        private final int retryAfterSeconds;

        ApiException(int code, String message, int retryAfterSeconds) {
            super(message);
            this.code = code;
            this.retryAfterSeconds = Math.clamp(retryAfterSeconds, 0, 86_400);
        }

        public int retryAfterSeconds() {
            return retryAfterSeconds;
        }

        public boolean cannotEdit() {
            String message = getMessage().toLowerCase(Locale.ROOT);
            return code == 400 && (message.contains("message to edit not found") || message.contains("can't be edited"));
        }

        private boolean formattingError() {
            return code == 400 && getMessage().toLowerCase(Locale.ROOT).contains("can't parse entities");
        }

        private boolean notModified() {
            return code == 400 && getMessage().toLowerCase(Locale.ROOT).contains("message is not modified");
        }
    }
}
