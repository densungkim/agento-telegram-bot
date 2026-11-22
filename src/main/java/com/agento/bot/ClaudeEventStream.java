package com.agento.bot;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Parses the {@code claude -p --output-format stream-json --verbose} event stream (one JSON object per line).
 */
final class ClaudeEventStream implements Consumer<String> {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ClaudeSessionStore sessions;
    private final String expectedSessionId;
    private final Consumer<String> progress;
    private volatile boolean started;
    private volatile Result result;

    ClaudeEventStream(ClaudeSessionStore sessions, String expectedSessionId, Consumer<String> progress) {
        this.sessions = sessions;
        this.expectedSessionId = ClaudeSessionStore.checkedId(expectedSessionId);
        this.progress = progress;
    }

    @Override
    public void accept(String line) {
        if (line.isBlank()) {
            return;
        }
        JsonNode event;
        try {
            event = mapper.readTree(line);
        } catch (JacksonException e) {
            // Not an event line; stderr carries the diagnostics.
            return;
        }
        switch (event.path("type").asString("")) {
            case "system" -> system(event);
            case "assistant" -> assistant(event.path("message").path("content"));
            case "result" -> result = parseResult(event);
            default -> {
                // user (tool results), rate_limit_event and other events are not needed by the bot.
            }
        }
    }

    boolean started() {
        return started;
    }

    /** The {@code result} event; it is not always the last line, so it is kept until the stream ends. */
    Result result() {
        return result;
    }

    private void system(JsonNode event) {
        switch (event.path("subtype").asString("")) {
            case "init" -> {
                String id = ClaudeSessionStore.checkedId(event.path("session_id").asString(""));
                if (!expectedSessionId.equals(id)) {
                    throw new IllegalStateException("Claude Code открыл другую сессию. Выполнение остановлено.");
                }
                sessions.confirm(id);
                started = true;
                progress.accept("Сессия готова. Claude обрабатывает запрос.");
            }
            case "api_retry" -> progress.accept("Повторяю запрос к API Claude (попытка "
                    + event.path("attempt").asString("?") + ").");
            default -> {
                // task_summary, post_turn_summary and other system events are informational only.
            }
        }
    }

    private void assistant(JsonNode content) {
        String phase = null;
        for (JsonNode block : content) {
            phase = switch (block.path("type").asString("")) {
                case "tool_use" -> toolPhase(block.path("name").asString(""));
                case "text" -> "Готовлю ответ.";
                case "thinking" -> "Claude анализирует запрос.";
                default -> phase;
            };
        }
        if (phase != null) {
            progress.accept(phase);
        }
    }

    private static String toolPhase(String tool) {
        return switch (tool) {
            case "Bash", "BashOutput", "KillShell", "PowerShell" -> "Работаю с командами на VPS.";
            case "Edit", "MultiEdit", "Write", "NotebookEdit" -> "Работаю с файлами.";
            case "Read", "Grep", "Glob", "LS" -> "Изучаю файлы.";
            case "WebSearch", "WebFetch" -> "Ищу информацию.";
            case "Task", "Agent" -> "Работает субагент.";
            case "TodoWrite" -> "Уточняю план выполнения.";
            default -> "Работаю с инструментом " + TelegramTextFormatter.clip(tool, 40) + ".";
        };
    }

    private static Result parseResult(JsonNode event) {
        List<String> errors = new ArrayList<>();
        for (JsonNode error : event.path("errors")) {
            String message = error.isString() ? error.asString("") : error.toString();
            if (!message.isBlank()) {
                errors.add(message);
            }
        }
        List<String> deniedTools = new ArrayList<>();
        for (JsonNode denial : event.path("permission_denials")) {
            String tool = denial.path("tool_name").asString("");
            deniedTools.add(tool.isBlank() ? "?" : tool);
        }
        return new Result(
                event.path("subtype").asString(""),
                event.path("is_error").booleanValue(false),
                event.path("result").asString(""),
                List.copyOf(errors),
                List.copyOf(deniedTools)
        );
    }

    record Result(String subtype, boolean error, String text, List<String> errors, List<String> deniedTools) {
    }
}
